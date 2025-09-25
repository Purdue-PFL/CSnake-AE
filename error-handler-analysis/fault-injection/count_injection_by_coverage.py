import json 
from typing import *
# import pandas as pd 

coverage_json_path = '/local2/qian151/vc-detect-workspace/loop-interference-result/HDFS292_Full_ReachableUnittestFromInjection_Coverage.json'

with open(coverage_json_path, 'r') as f:
    injection_coverage: Dict[str, Dict[str, List[float]]] = json.load(f) 

required_coverage_ratio = 0.9
exp_count = 0
exp_count_list = []

for injection_key in injection_coverage.keys():
    for injection_loop, coverage_list in injection_coverage[injection_key].items():
        coverage_target = coverage_list[-1] * required_coverage_ratio 
        if coverage_target <= 0: 
            continue 
        for idx, coverage_value in enumerate(coverage_list):
            if coverage_value >= coverage_target:
                exp_count_list.append(idx + 1)
                exp_count += min((idx + 1), 3)
                break 

print(exp_count)
# df = pd.DataFrame({'exp_count': exp_count_list})
# df['exp_count'].value_counts().sort_index().to_csv('count.csv')

coverage_json_path = '/local2/qian151/vc-detect-workspace/loop-interference-result/HDFS292_Full_ReachableUnittestFromLoop_Coverage.json'

with open(coverage_json_path, 'r') as f:
    loop_coverage: Dict[str, List[float]] = json.load(f) 

required_coverage_ratio = 0.9
exp_count = 0
exp_count_list = []

for injection_loop, coverage_list in loop_coverage.items():
    coverage_target = coverage_list[-1] * required_coverage_ratio 
    if coverage_target <= 0: 
        continue 
    for idx, coverage_value in enumerate(coverage_list):
        if coverage_value >= coverage_target:
            exp_count_list.append(idx + 1)
            exp_count += min((idx + 1), 3)
            break 

print(exp_count)
# df = pd.DataFrame({'exp_count': exp_count_list})
# df['exp_count'].value_counts().sort_index().to_csv('count2.csv')