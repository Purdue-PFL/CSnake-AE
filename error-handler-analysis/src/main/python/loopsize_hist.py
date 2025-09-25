import argparse 
import numpy as np 
import json 
import matplotlib.pyplot as plt

parser = argparse.ArgumentParser()
parser.add_argument('--loop', type=str)
args = parser.parse_args()

with open(args.loop) as f:
    loops = json.load(f)

loop_sizes = []
for loop_key, loop_item in loops.items():
    loop_size = loop_item['AllInvokedMethodSSASize']
    loop_size = min(loop_size, 1000)
    loop_sizes.append(loop_size)

fig = plt.figure(figsize=(20, 20))
ax = fig.add_subplot(111)
ax.hist(loop_sizes, bins=1000)
fig.savefig('loopsize_hist.png')

count, label = np.histogram(loop_sizes, bins=np.arange(1001, dtype=int))
dict_out = {}
for ct, lbl in zip(count, label):
    dict_out[str(lbl)] = int(ct)
with open("./loopsize_hist.json", 'w') as f:
    json.dump(dict_out, f, indent=4)
