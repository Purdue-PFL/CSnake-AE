import os 
import sys 
from pathlib import Path 
import signal 
import time 

EXPIRE_THRESHOLD = 17 

def check_pid(pid):        
    """ Check For the existence of a unix pid. """
    try:
        os.kill(pid, 0)
    except OSError:
        return False
    else:
        return True

pid = int(sys.argv[1])
tmp_path = Path("/tmp")
last_glob = set()
expire_counter = 0 
while True:
    current_glob = set(tmp_path.glob('vc_*'))
    if current_glob == last_glob:
        expire_counter = expire_counter + 1 
    else:
        last_glob = current_glob 
        expire_counter = 0 
    
    if expire_counter > EXPIRE_THRESHOLD:
        os.kill(pid, signal.SIGKILL)
        break 

    if not check_pid(pid):
        break 

    time.sleep(60) 

