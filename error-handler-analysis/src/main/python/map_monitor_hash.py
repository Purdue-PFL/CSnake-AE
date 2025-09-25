import json 
import argparse
from pathlib import Path 

parser = argparse.ArgumentParser()
parser.add_argument("--input_path", type=str)
parser.add_argument("--output_path", type=str)
args = parser.parse_args()

mapper = {}
idx = 0

loop_path = Path(args.input_path, 'loops.json')
throw_path = Path(args.input_path, 'throw.json')
negate_path = Path(args.input_path, 'negates.json')
branch_path = Path(args.input_path, 'branches.json')


def run_mapper(path: Path):
    global idx 
    global mapper 
    with open(path, 'r') as f:
        exec_points = json.load(f) 
        for exec_key in exec_points.keys():
            mapper[exec_key] = idx 
            idx += 1

run_mapper(loop_path)
run_mapper(throw_path)
run_mapper(negate_path)
run_mapper(branch_path) 

with open(Path(args.output_path, 'exec_id_map.json'), 'w') as f:
    json.dump(mapper, f, indent=2) 
