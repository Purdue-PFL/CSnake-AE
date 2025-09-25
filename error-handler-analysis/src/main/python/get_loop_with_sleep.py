import json 
from pathlib import Path 
import argparse
import linecache 
from multiprocessing import Pool 

parser = argparse.ArgumentParser()
parser.add_argument("--loop_json", type=str, default='/home/qian151/research/vc-detect/error-handler-analysis/result/loop_interference/hdfs292_loops.json')
parser.add_argument("--result_path", type=str, default='/home/qian151/research/vc-detect/error-handler-analysis/result/loop_interference/hdfs292_loops_w_sleep.json')
args = parser.parse_args()

CODE_PATHS = [Path("/local2/qian151/vc-detect-workspace/hadoop292-vctest/")]

with open(args.loop_json, 'r') as f:
    loops = json.load(f)

def get_code(loop_dict):
    classname = loop_dict['Class'].split('.')[-1]
    if '$' in classname:
        classname = classname.split('$')[0]
    filenames = []
    for CODE_PATH in CODE_PATHS:
        filenames = filenames + list(CODE_PATH.rglob(classname + '.java'))
    if len(filenames) == 1:
        filename = str(filenames[0])
        src_lines = []
        for lineno in range(loop_dict['StartLine'], loop_dict['EndLine']):
            line = linecache.getline(filename, lineno=lineno)
            src_lines.append(line.rstrip())
        return src_lines

    return []

def loop_filter(loop_key, loop_dict):
    src_lines = get_code(loop_dict) 
    if any([("Thread.sleep" in line) for line in src_lines]):
        loop_dict['Src'] = src_lines
        return True, loop_key, loop_dict
    else:
        return False, None, None 

loop_with_sleep = {}
with Pool(processes=24) as pool:
    results = pool.starmap(loop_filter, list(loops.items()))
    for state, loop_key, loop_dict in results:
        if state:
            loop_with_sleep[loop_key] = loop_dict 

with open(args.result_path, 'w') as f:
    json.dump(loop_with_sleep, f, indent=4) 
