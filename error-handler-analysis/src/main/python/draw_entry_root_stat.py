import matplotlib.pyplot as plt
import json 
import argparse
from typing import *
from pathlib import Path 

parser = argparse.ArgumentParser()
parser.add_argument('--input', type=str)
parser.add_argument('--output_path', type=str)
args = parser.parse_args()

with open(args.input, 'r') as f:
    entry_root_stat: Dict[str, int] = json.load(f)

range_list = [int(x) for x in entry_root_stat.keys()]
print("Min:", min(range_list), "Max:", max(range_list))
stat_raw = []
entry_count = []
for i in range(1, max(range_list) + 1):
    count = entry_root_stat.get(str(i), 0)
    stat_raw.append(count)
    entry_count.append(i)

fig = plt.figure()
ax = fig.add_subplot(111)
ax.bar(entry_count, stat_raw)
fig.savefig(Path(args.output_path, 'hdfs292_entry_root_stat.png'))
