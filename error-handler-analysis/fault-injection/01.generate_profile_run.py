from typing import *
import json
import os
from pathlib import Path
import mmh3 
from concurrent.futures import ProcessPoolExecutor 
from multiprocessing import Pool 
import concurrent.futures
import argparse
import configparser 
import functools 

import btm_gen
import zstd_helper
import exec_config
import utils 

parser = argparse.ArgumentParser()
parser.add_argument("--target", type=str)
args = parser.parse_args()
run_config = configparser.ConfigParser(interpolation=configparser.ExtendedInterpolation())
run_config.read(Path('./run_config', args.target + '.ini'))

UNITTEST_LIST_PATH = run_config['Static']['UNITTEST_LIST_PATH']
THROW_EVENT_PATH = run_config['Static']['THROW_EVENT_PATH']
NEGATE_EVENT_PATH = run_config['Static']['NEGATE_EVENT_PATH']
BRANCH_EVENT_PATH = run_config['Static']['BRANCH_EVENT_PATH']
LOOP_EVENT_PATH = run_config['Static']['LOOP_EVENT_PATH']
LOOP_SIZE_THRESHOLD = -1
if run_config.has_option('Static', 'LOOP_SIZE_THRESHOLD'):
    LOOP_SIZE_THRESHOLD = int(run_config['Static']['LOOP_SIZE_THRESHOLD'])
else:
    print("WARNING: No LOOP_SIZE_THRESHOLD used, monitoring all loops")

OUTPUT_PATH = run_config['Profile_Run']['OUTPUT_PATH']
BTM_OUTPUT_PATH = run_config['Profile_Run']['BTM_OUTPUT_PATH']
REPEAT_COUNT = int(run_config['Profile_Run']['REPEAT_COUNT'])
Path(BTM_OUTPUT_PATH).mkdir(parents=True, exist_ok=True)
BTM_WITH_ZSTD = OUTPUT_PATH.endswith('.zst')

exec_point_id_mapper = None
if run_config.has_option('Static', 'EXECPOINT_ID_MAPPER_PATH'):
    EXECPOINT_ID_MAPPER_PATH = run_config['Static']['EXECPOINT_ID_MAPPER_PATH']
    with open(EXECPOINT_ID_MAPPER_PATH, 'r') as f:
        exec_point_id_mapper: Dict[str, int] = json.load(f) 
else:
    print("WARNING: No Option for ['Static']['EXECPOINT_ID_MAPPER_PATH']")

with open(THROW_EVENT_PATH, 'r') as f:
    throw_events: Dict[str, Dict[str, str | int | List[Dict[str, str | List[int]]]]] = json.load(f)
with open(NEGATE_EVENT_PATH, 'r') as f:
    negate_events: Dict[str, Dict[str, Dict[str, str | int]]] = json.load(f)
with open(BRANCH_EVENT_PATH, 'r') as f:
    branch_events: Dict[str, Dict[str, str | int]] = json.load(f)
with open(LOOP_EVENT_PATH, 'r') as f:
    loop_events: Dict[str, Dict[str, str | int]] = json.load(f)
with open(UNITTEST_LIST_PATH, 'r') as f:
    unittests: List[str] = json.load(f)

junit_to_byteman_class_mapper: Dict[str, str] = {}
if run_config.has_option("Static", 'JUNIT_TO_BYTEMAN_TEST_CLASS_MAPPER'):
    with open(run_config['Static']['JUNIT_TO_BYTEMAN_TEST_CLASS_MAPPER']) as f:
        junit_to_byteman_class_mapper = json.load(f)
else:
    print("WARNING: No Option for ['Static']['JUNIT_TO_BYTEMAN_TEST_CLASS_MAPPER']")

throw_btm: str = ""
for throw_event in throw_events.values():
    if throw_event["Method"] == '<clinit>':
        continue
    throw_btm = throw_btm + btm_gen.throw_event_ref(throw_event, exec_point_id_mapper=exec_point_id_mapper) + os.linesep

negate_btm: str = ""
for negate_event in negate_events.values():
    if negate_event["Method"] == '<clinit>':
        continue
    if negate_event.get('ShouldRemove?', False):
        continue 
    negate_btm = negate_btm + btm_gen.negate_event_ref(negate_event, exec_point_id_mapper=exec_point_id_mapper) + os.linesep

branch_btm: str = ""
with Pool(32) as p:
    gen_branch_event = functools.partial(btm_gen.branch_event, exec_point_id_mapper=exec_point_id_mapper)
    branch_btm = os.linesep.join(p.map(gen_branch_event, [e for e in branch_events.values() if e["Method"] != '<clinit>'], 1024))
# for branch_event in branch_events.values():
#     if branch_event["Method"] == '<clinit>':
#         continue
#     branch_btm = branch_btm + btm_gen.branch_event(branch_event, exec_point_id_mapper=exec_point_id_mapper) + os.linesep

loop_btm: str = ""
for loop_event in loop_events.values():
    if loop_event["Method"] == '<clinit>':
        continue
    if loop_event.get('AllInvokedMethodSSASize', 0) <= LOOP_SIZE_THRESHOLD:
        continue 
    loop_btm = loop_btm + btm_gen.loop_event_ref(loop_event, exec_point_id_mapper=exec_point_id_mapper) + os.linesep

loop_end_by_return_btm = btm_gen.loop_end_by_return_event(loop_events, exec_point_id_mapper=exec_point_id_mapper)

cluster_ready_btm = Path(run_config['Profile_Run']['CLUSTER_READY_BTM']).read_text()
thread_btm = Path('./btm_templates/threads.btm').read_text()
base_btm_str = """
HELPER pfl.bm.VCLoopInterferenceHelper
COMPILE

{throw_btm}

{negate_btm}

{branch_btm}

{loop_btm}

{loop_end_by_return_btm}

{thread_btm}

{cluster_ready_btm}
""".format(throw_btm=throw_btm,
           negate_btm=negate_btm,
           branch_btm=branch_btm,
           loop_btm=loop_btm,
           loop_end_by_return_btm=loop_end_by_return_btm,
           thread_btm=thread_btm,
           cluster_ready_btm=cluster_ready_btm)

base_btm_dump_path = Path(BTM_OUTPUT_PATH, "base.btm" + (".zst" if BTM_WITH_ZSTD else ""))
with zstd_helper.openZstd(base_btm_dump_path, 'wb') if BTM_WITH_ZSTD else open(base_btm_dump_path, 'w') as f:
    f.write(base_btm_str)

def generate_task(unittest: str) -> List[Tuple[str, Dict]]:
    thread_btm = Path('./btm_templates/threads.btm').read_text()
    btm_template = """
HELPER pfl.bm.VCLoopInterferenceHelper
COMPILE

RULE DumpResult1
CLASS {unittest_class}
METHOD {unittest_method}
AT EXIT 
IF true 
DO dumpResult();
ENDRULE 

RULE DumpResult2
CLASS {unittest_class}
METHOD {unittest_method}
AT EXCEPTION EXIT 
IF true 
DO dumpResult();
ENDRULE 
"""
    repeated_run_aggregate_to_hash = None 
    r = []
    for idx in range(REPEAT_COUNT):
        test_type, unittest_class, unittest_method, disp_unittest_class, disp_unittest_method = utils.demangle_junit_testname(unittest, junit_byteman_map=junit_to_byteman_class_mapper)

        btm_str = btm_template.format(unittest_class=unittest_class,
                                    unittest_method=unittest_method)
        
        hasher = mmh3.mmh3_x64_128(seed=0)
        hasher.update(btm_str.encode())
        hasher.update(unittest.encode())
        if idx > 0:
            hasher.update(repr(idx).encode())
        exp_key = format(hasher.uintdigest(), 'x').zfill(32)

        test_dict = {}
        if "JUnit5" in test_type:
            test_dict['UnitTest'] = unittest + '|' + unittest_class + '#' + unittest_method
        else:
            test_dict['UnitTest'] = unittest
        test_dict['ExpKey'] = exp_key
        test_dict['InjectionTimeMs'] = 0
        test_dict['UnitTestTimeoutMs'] = 0
        test_dict['TestDisplayName'] = disp_unittest_class + '#' + disp_unittest_method 
        if idx == 0:
            repeated_run_aggregate_to_hash = exp_key 
        test_dict['AggregateExpKey'] = repeated_run_aggregate_to_hash 
        # test_dict['btm_str'] = btm_str
        # testplan[exp_key] = test_dict 

        btm_dump_path = Path(BTM_OUTPUT_PATH, exp_key + ".btm" + (".zst" if BTM_WITH_ZSTD else ""))
        with zstd_helper.openZstd(btm_dump_path, 'wb') if BTM_WITH_ZSTD else open(btm_dump_path, 'w') as f:
            f.write(btm_str)

        r.append((exp_key, test_dict))

    return r 

testplan: Dict[str, Dict[str, str | int]] = {}
with ProcessPoolExecutor(max_workers=32) as executor:
    futures = []
    for unittest in unittests:
        future = executor.submit(generate_task, unittest)
        futures.append(future)

    for future in concurrent.futures.as_completed(futures):
        r = future.result()
        for exp_key, test_dict in r:
            testplan[exp_key] = test_dict


with zstd_helper.openZstd(OUTPUT_PATH, 'wb') if OUTPUT_PATH.endswith('.json.zst') else open(OUTPUT_PATH, 'w') as f:
    json.dump(testplan, f, indent=4)

