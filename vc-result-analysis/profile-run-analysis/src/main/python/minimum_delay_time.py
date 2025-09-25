import json 
import argparse 
from typing import *
from pathlib import Path 
import sys 

parser = argparse.ArgumentParser()
parser.add_argument('--loops', type=str)
parser.add_argument('--injection_plan', type=str)
parser.add_argument('--result_dir', type=str)
parser.add_argument('--output_dir', type=str)
parser.add_argument('--output_prefix', type=str)
args = parser.parse_args()

with open(args.loops) as f:
    loops: Dict[str, Dict[str, str | int]] = json.load(f)
with open(args.injection_plan) as f:
    injection_plan: Dict[str, Dict[str, str | int]] = json.load(f) 

minimum_delay_inj_time: Dict[str, int] = {}
for run_id, run_prop in injection_plan.items():
    if run_prop['AggregateExpKey'] != run_id: 
        continue 
    if run_prop['InjectionType'] != "DELAY":
        continue 

    inj_loop_id = run_prop['Injection Loop']
    inj_time_ms = run_prop['InjectionTimeMs']

    e_result_path = Path(args.result_dir, run_id + '.json')
    if not e_result_path.exists():
        continue 
    with open(e_result_path) as f:
        e_interference_result: Dict[str, List[str]] = json.load(f)

    # Here, we force delay injection to have E interferences
    # That is, no L1 -- S+ --> L2 -- S+ --> L3 chains
    has_interference = False 
    for interfered_loop_keys in e_interference_result.values():
        if len(interfered_loop_keys) > 0:
            has_interference = True 
            break 
    
    if not has_interference:
        continue    

    cur_min_delay_inj_time = minimum_delay_inj_time.get(inj_loop_id, sys.maxsize)
    minimum_delay_inj_time[inj_loop_id] = min(inj_time_ms, cur_min_delay_inj_time)

with open(Path(args.output_dir, args.output_prefix + "_MinDelayInjTime.json"), 'w') as f:
    json.dump(minimum_delay_inj_time, f, indent=4)


    