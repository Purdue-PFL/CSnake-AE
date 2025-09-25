import matplotlib.pyplot as plt
import json 
import argparse
from typing import *
from pathlib import Path 

CUTOFF = 20

parser = argparse.ArgumentParser()
parser.add_argument('--input', type=str)
parser.add_argument('--output_path', type=str)
parser.add_argument('--output_prefix', type=str)
args = parser.parse_args()

with open(args.input, 'r') as f:
    entry_roots: Dict[str, List[str]] = json.load(f)

functionality: List[str] = []
utility: List[str] = []
for func, ers in entry_roots.items():
    if len(ers) < CUTOFF:
        functionality.append(func) 
    else:
        utility.append(func) 

with open(Path(args.output_path, args.output_prefix + "_functinality.json"), 'w') as f:
    json.dump(functionality, f, indent=4)

with open(Path(args.output_path, args.output_prefix + "_utility.json"), 'w') as f:
    json.dump(utility, f, indent=4)
