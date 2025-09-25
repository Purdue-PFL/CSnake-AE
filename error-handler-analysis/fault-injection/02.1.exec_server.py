from typing import *
import json
import os
from pathlib import Path
import grpc
import threading
import queue
from dataclasses import dataclass, field
import shutil  
import tempfile 
import subprocess 
import shlex 
from concurrent.futures import ThreadPoolExecutor
import time 
import configparser 
import argparse 
import random 

import zstd_helper
import exec_config
import TestExecutionService_pb2_grpc
import TestExecutionService_pb2 as pb
from google.protobuf import wrappers_pb2 as wrappers

parser = argparse.ArgumentParser()
parser.add_argument("--target", type=str, default='HDFS292')
parser.add_argument("--run_type", type=str, default='Profile') # Profile | Error_Injection | Delay_Injection
args = parser.parse_args()
run_config = configparser.ConfigParser(interpolation=configparser.ExtendedInterpolation())
run_config.read(Path('./run_config', args.target + '.ini'))
run_config_section = args.run_type + "_Run"

# TESTPLAN_PATH = "/home/qian151/research/vc-detect/error-handler-analysis/result/loop_interference/hdfs292_injection_run_manual.json"
# BTM_PATH = "/local2/qian151/vc-detect-workspace/btm_manual"
# TEST_OUTPUT_PATH = "/local1/qian151/vc-detect-workspace/loop-interference-result/injection_manual"
TESTPLAN_PATH = run_config[run_config_section]['TESTPLAN_PATH']
BTM_PATH = run_config[run_config_section]['BTM_OUTPUT_PATH']
TEST_OUTPUT_PATH = run_config[run_config_section]['TEST_OUTPUT_PATH']
TEMP_DIR = None # None for default temp dir, useful for ramdisk mapping 

Path(TEST_OUTPUT_PATH).mkdir(parents=True, exist_ok=True)
test_progress: List[str] = []
test_progress_path = Path(TEST_OUTPUT_PATH, 'progress.log')
progress_mutex = threading.Lock()
exit_flag = threading.Event()

@dataclass(order=True)
class TaskItem:
    priority_1: int
    priority_2: int
    task_obj: pb.Task = field(compare=False)

task_queue: queue.PriorityQueue[TaskItem] = queue.PriorityQueue()
running_task: Dict[Tuple[str, str], pb.Task] = {} # {(client_uuid, task_key): task_obj}
retry_counter: Dict[str, int] = {}

class Server(TestExecutionService_pb2_grpc.TestExecutionServicer):
    global test_progress 
    global retry_counter 
    global running_task 
    global progress_mutex 
    global task_queue   

    def task_retry(client_uuid: str, task_key: str, task_obj: pb.Task):
        # print("Request retry 1:", task_key)
        with progress_mutex:
            if task_key in test_progress:
                # task has finished 
                return 
            elif (client_uuid, task_key) in running_task:
                del running_task[(client_uuid, task_key)]
        # print("Request retry 2:", task_key)
        retry_count = retry_counter.get(task_key, 0) + 1
        retry_counter[task_key] = retry_count 
        if retry_count > exec_config.RETRY_LIMIT:
            return 
        task_queue.put(TaskItem(1, retry_count, task_obj)) 

    def getTask(self, request: pb.ClientID, context):
        client_uuid: str = request.uuid 
        task_obj = task_queue.get().task_obj 
        task_key = task_obj.expKey 
        task_timeout_ms = task_obj.unittestTimeoutMs

        # print("Got call:", client_uuid)

        btm_str_path = Path(BTM_PATH, task_key + ".btm")
        if not btm_str_path.exists():
            btm_str_path = Path(BTM_PATH, task_key + ".btm.zst")
        with zstd_helper.openZstd(btm_str_path, 'rb') if str(btm_str_path).endswith('.zst') else open(btm_str_path, 'r') as f:
            btm_str = f.read() 
        task_obj.btmStr = btm_str

        with progress_mutex:
            running_task[(client_uuid, task_key)] = task_obj 
        if task_timeout_ms > 0:
            task_timer = threading.Timer(interval=(task_timeout_ms / 1000.0 + 30), function=Server.task_retry, kwargs={'client_uuid': client_uuid, 'task_key': task_key, 'task_obj': task_obj})
            task_timer.start()
        else:
            # Profile Run
            task_timer = threading.Timer(interval=exec_config.DEFAULT_TIMEOUT, function=Server.task_retry, kwargs={'client_uuid': client_uuid, 'task_key': task_key, 'task_obj': task_obj})
            task_timer.start()

        # print("Return task:", task_obj)

        return task_obj 

    def taskFinish(self, request: pb.TaskResult, context) -> wrappers.UInt64Value:
        client_uuid = request.clientID.uuid 
        task_key = request.expKey 
        task_obj = None 

        # Remove from running task
        with progress_mutex:
            if not (client_uuid, task_key) in running_task:
                return wrappers.UInt64Value(value=9999999) 
            else:
                task_obj = running_task[(client_uuid, task_key)]
                del running_task[(client_uuid, task_key)]

        # Receive the result 
        report_output_path = Path(TEST_OUTPUT_PATH, task_key) 
        if report_output_path.exists():
            shutil.rmtree(report_output_path) 
        report_output_path.mkdir(parents=True, exist_ok=True)
        with tempfile.NamedTemporaryFile(dir=TEMP_DIR) as tmp:
            with tmp.file as f:
                f.write(request.testOutput)
            subprocess.call(
                shlex.split("tar --zstd -xf {fn} -C {result_path} --strip-components=2".format(
                    **{
                        'fn': tmp.name, 'result_path': str(report_output_path)
                    })))
        
        # print("Task Finish 1:", task_key)
        # Sanity check
        iter_event_path = Path(report_output_path, 'IterEvents.bin')
        if iter_event_path.exists() and iter_event_path.stat().st_size > 0:
            # Task finished, no problem 
            if not exit_flag.is_set():
                with progress_mutex:
                    test_progress.append(task_key) 
            # print("Task Finish 2:", task_key)
        else:
            # Retry 
            # print("Task Finish 3:", task_key)
            Server.task_retry(client_uuid, task_key, task_obj)

        # print("Task Finish 4:", task_key)
        return wrappers.UInt64Value(value=0) 

def dump_testprogess():
    global test_progress

    with progress_mutex:
        with open(test_progress_path, 'w') as f:
            for line in test_progress:
                f.write(f"{line}\n")

def result_dump_thread():
    global task_queue
    global running_task 
    global exit_flag
    global test_progress 

    while not exit_flag.is_set():
        with progress_mutex:
            if task_queue.empty() and (len(running_task) == 0):
                break 
        exit_flag.wait(60)
        dump_testprogess()

def main():
    global test_progress
    global exit_flag

    with zstd_helper.openZstd(TESTPLAN_PATH, 'rb') if TESTPLAN_PATH.endswith('.json.zst') else open(TESTPLAN_PATH, 'r') as f:
        testplan: Dict[str, Dict[str, str | int]] = json.load(f)
    
    if test_progress_path.exists():
        with open(test_progress_path, 'r') as f:
            test_progress = [line.rstrip() for line in f]

    grpc_server = grpc.server(thread_pool=ThreadPoolExecutor(max_workers=32),
                              options=[('grpc.max_send_message_length', -1), ('grpc.max_receive_message_length', -1)])
    TestExecutionService_pb2_grpc.add_TestExecutionServicer_to_server(servicer=Server(), server=grpc_server)
    grpc_server.add_insecure_port("[::]:" + exec_config.GRPC_PORT)
    grpc_server.start()
    print("RPC Server Started")

    dump_thread = threading.Thread(target=result_dump_thread)
    print("Tests:", len(testplan))
    # print("Test progress size:", len(test_progress))
    try:
        testplan_items = list(testplan.items())
        random.shuffle(testplan_items)
        skip_count = 0
        for exp_idx, (task_key, task_dict) in enumerate(testplan_items):
            if task_key in test_progress:
                # print(exp_idx, task_key, task_dict['UnitTest'], 'skipped')
                skip_count += 1
                continue 
            
            task_obj = pb.Task() 
            task_obj.expKey = task_key 
            # task_obj.btmStr = btm_str 
            task_obj.unittestFunc = task_dict['UnitTest']
            task_obj.injectionTimeMs = int(task_dict['InjectionTimeMs'])
            task_obj.unittestTimeoutMs = int(task_dict['UnitTestTimeoutMs'])

            task_queue.put(TaskItem(5, exp_idx, task_obj)) 
            # print(task_queue.qsize())

            if not dump_thread.is_alive():
                dump_thread.start()
                while not dump_thread.is_alive():
                    time.sleep(1)
                print("Dump Thread Started")

        print("Task Loaded, skipping:", skip_count)
        dump_thread.join()
            
    except KeyboardInterrupt:
        with progress_mutex:
            exit_flag.set()
        print("Terminating RPC Server...")
        grpc_server.stop(grace=None)
        dump_testprogess()
        print("Result Dumped")
        dump_thread.join()

if __name__ == "__main__":
    main()
