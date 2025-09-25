import json 
import argparse 
from pathlib import Path 
from typing import * 

parser = argparse.ArgumentParser()
parser.add_argument('--injection_plan', type=str)
parser.add_argument('--result_dir', type=str)
parser.add_argument('--output_dir', type=str)
parser.add_argument('--output_prefix', type=str)
args = parser.parse_args()

with open(args.injection_plan) as f:
    injection_plan: Dict[str, Dict[str, str | int]] = json.load(f) 

delay_inj_targets = set()
for run_id in injection_plan.keys():
    s_result_path = Path(args.result_dir, run_id + '_itercount.json')
    if not s_result_path.exists():
        continue 
    parent_delay_path = Path(args.result_dir, run_id + '_parent_delay.json')
    if not parent_delay_path.exists():
        continue 
    with open(s_result_path, 'r') as f:
        s_interference_result: Dict[str, Dict[str, List[List[str]]]] = json.load(f) 
    with open(parent_delay_path, 'r') as f:
        parent_delay: Dict[str, List[str]] = json.load(f)

    for interfered_loop_keys in s_interference_result.values():
        delay_inj_targets.update(interfered_loop_keys.keys())
    for parent_delay_loops in parent_delay.values():
        delay_inj_targets.update(parent_delay_loops)

with open(Path(args.output_dir, args.output_prefix + "_DelayInjTargets.json"), 'w') as f:
    json.dump(list(delay_inj_targets), f, indent=4)
