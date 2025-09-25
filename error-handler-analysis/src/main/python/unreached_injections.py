import json 
from pathlib import Path 

throw_path = '/home/qian151/research/vc-detect/error-handler-analysis/result/hdfs292/throw.json'
negate_path = '/home/qian151/research/vc-detect/error-handler-analysis/result/hdfs292/negates.json'
reached_injection_path = '/local2/qian151/vc-detect-workspace/loop-interference-result/HDFS292/HDFS292_Full_ReachableUnittestFromInjection.json'

with open(throw_path, 'r') as f:
    throws = json.load(f) 
with open(negate_path, 'r') as f:
    negates = json.load(f)

all_negate_keys = []
for negate_key, negate_item in negates.items():
    if not negate_item.get('ShouldRemove?', False):
        all_negate_keys.append(negate_key)

all_throw_keys = throws.keys()

with open(reached_injection_path) as f:
    reached_injections = json.load(f)

reached_injection_keys = reached_injections.keys()

with open('./unreached_throw.json', 'w') as f:
    unreached = set(all_throw_keys) - set(reached_injection_keys)
    json.dump(list(unreached), f, indent=4) 

with open('./unreached_negate.json', 'w') as f:
    unreached = set(all_negate_keys) - set(reached_injection_keys)
    json.dump(list(unreached), f, indent=4) 

