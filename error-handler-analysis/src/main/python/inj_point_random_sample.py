import json 
import csv
import argparse 
from typing import * 
import random
import pprint 

parser = argparse.ArgumentParser()
parser.add_argument('--input', type=str) 
parser.add_argument('--output', type=str, default='./output.csv')
parser.add_argument('--sample_size', type=int, default=30)
args = parser.parse_args() 

# Filters
def is_util_throw(clazz: str, method: str) -> bool:
    excl_keys = ['util', 'info', '.tools.', '.tool.', 'metrics', ".proto."]
    
    clazz_lower = clazz.lower()
    excl_status = [x in clazz_lower for x in excl_keys]

    return any(excl_status)

def is_util_negate(clazz: str, method: str) -> bool:
    class_excl_keys = ['util', 'info', '.tools.', '.tool.', 'metrics', 'abstract', ".proto."]
    
    clazz_lower = clazz.lower()
    clazz_excl_status = [x in clazz_lower for x in class_excl_keys]
    if any(clazz_excl_status):
        return True 
    
    if ('contains' in method) or method.startswith('has') or method.startswith('next'):
        return True 
    
    if ('Map' in clazz) and ('remove' in method):
        return True 
    
    return False 

def is_util_loop(clazz: str, method: str) -> bool:
    excl_keys = ['.proto.', 'util', 'metrics', 'abstract', '.tool']
    
    clazz_lower = clazz.lower()
    excl_status = [x in clazz_lower for x in excl_keys]

    return any(excl_status)

with open(args.input, 'r') as f:
    inj_points: Dict[str, Dict[str, str | int]] = json.load(f) 

if 'loop' in args.output:
    for key in list(inj_points.keys()):
        if is_util_loop(inj_points[key]['Class'], inj_points[key]['Method']):
            del inj_points[key]
elif 'negate' in args.output:
    for key in list(inj_points.keys()):
        if is_util_negate(inj_points[key]['Class'], inj_points[key]['Method']):
            del inj_points[key]
elif 'throw' in args.output:
    for key in list(inj_points.keys()):
        if is_util_throw(inj_points[key]['Class'], inj_points[key]['Method']):
            del inj_points[key]

keys_sample = random.sample(list(inj_points.keys()), args.sample_size)
with open(args.output, 'w', newline='') as f:
    writer = csv.writer(f)
    writer.writerow(['Items'])
    for key in keys_sample:
        if 'ThrowableConstruction' in inj_points[key]:
            del inj_points[key]['ThrowableConstruction']
        value = pprint.pformat(inj_points[key], sort_dicts=False)
        writer.writerow([value])

