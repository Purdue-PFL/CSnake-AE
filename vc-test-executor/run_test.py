import os
from pathlib import Path
import subprocess
import shlex
import signal
import time
import shutil
import threading 
import sys 

EXPIRE_THRESHOLD = 17 * 60

checkpoint_finished = False

def checkpoint_run():
    global checkpoint_finished 
    global EXPIRE_THRESHOLD

    cmd_str = 'bash run_checkpoint.sh {test_sys} {test_mode}'.format(test_sys=sys.argv[1], test_mode=sys.argv[2])
    p = subprocess.Popen(shlex.split(cmd_str), start_new_session=True, universal_newlines=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    def output_redirect():
        for line in p.stdout:
            sys.stdout.write(line)
    stdout_thread = threading.Thread(target=output_redirect)
    stdout_thread.start()
    try:
        p.wait(timeout=EXPIRE_THRESHOLD)
    except subprocess.TimeoutExpired:
        pgrp = os.getpgid(p.pid)
        os.killpg(pgrp, signal.SIGKILL)
        time.sleep(1)
    stdout_thread.join()

    # Check CRIU Dump
    criu_log = Path('/criu/javasave.log')
    if criu_log.exists():
        p = subprocess.Popen(['tail', '-1', str(criu_log.absolute())],
                             shell=False,
                             stderr=subprocess.PIPE,
                             stdout=subprocess.PIPE)
        res, err = p.communicate()
        if 'successfully' in res.decode():
            print("Checkpoint finished")
            checkpoint_finished = True

# Main Loop
while True:
    if not checkpoint_finished:
        criu_path = Path("/criu")
        if criu_path.exists():
            shutil.rmtree(criu_path)
        checkpoint_run()
    else:
        break 
