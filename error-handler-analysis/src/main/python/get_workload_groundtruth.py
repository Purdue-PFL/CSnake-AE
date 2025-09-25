import json
import argparse 
from pathlib import Path 
from typing import * 

parser = argparse.ArgumentParser()
parser.add_argument("--delay_testplan", type=str)
parser.add_argument("--error_testplan", type=str)
parser.add_argument("--vc_result_dir", type=str)
parser.add_argument("--output_ut", type=str)
parser.add_argument("--output_inj", type=str)
args = parser.parse_args()

testplans: Dict[str, Dict[str, str|int]] = {}
with open(args.delay_testplan, 'r') as f:
    testplans = json.load(f)
with open(args.error_testplan, 'r') as f:
    testplans = testplans | json.load(f) 

result_dir = Path(args.vc_result_dir)
all_unittest: Set[str] = set()
all_injection_id: Set[str] = set()
for result in result_dir.glob("*.txt"):
    exp_ids: Set[str] = set()
    with open(result, 'r') as f:
        start_parse = False 
        start_inj_id_parse = False
        for line in f.readlines():
            if line.startswith("InjectionID:"):
                start_inj_id_parse = True
            elif start_inj_id_parse and (not line.startswith('  ')):
                start_inj_id_parse = False
            elif start_inj_id_parse:
                inj_id = line.strip()
                all_injection_id.add(inj_id)
                # print(inj_id)

            if line.startswith("ExpID:"):
                start_parse = True 
            elif start_parse and (line.startswith("Score") or line.startswith("Additional")):
                start_parse = False 
            elif start_parse:
                exp_id = line.strip()
                exp_ids.add(exp_id)
                # print(exp_id)

    for exp_id in exp_ids:
        if not exp_id in testplans: 
            continue 
        unittest_name = testplans[exp_id]['UnitTest']
        if '|' in unittest_name:
            unittest_name = unittest_name.split('|')[0]
        all_unittest.add(unittest_name)

with open(args.output_ut, 'w') as f:
    json.dump(list(all_unittest), f, indent=4)

with open(args.output_inj, 'w') as f:
    json.dump(list(all_injection_id), f, indent=4) 


    