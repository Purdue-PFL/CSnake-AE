import argparse  
import json 
from pathlib import Path 
from typing import * 

parser = argparse.ArgumentParser()
parser.add_argument('--throw', type=str)
parser.add_argument('--branch', type=str)
parser.add_argument('--output_dir', type=str)
args = parser.parse_args()

with open(args.throw, 'r') as f:
    throw_events: Dict[str, Dict[str, str | int | List[Dict[str, str | List[int]]]]] = json.load(f)
with open(args.branch, 'r') as f:
    branch_events: Dict[str, str | int] = json.load(f)

position_indexed_branch: Dict[Tuple[str, str, int], List[Dict[str, str | int]]] = {}
for branch_event in branch_events.values():
    clazz = branch_event['branchInstClass']
    method = branch_event['branchInstMethod']
    line_no = branch_event['branchInstLineNo']
    branch_key = (clazz, method, line_no)

    position_indexed_branch.setdefault(branch_key, [])
    position_indexed_branch[branch_key].append(branch_event)

r: Dict[str, List[str]] = {}
for throw_event in throw_events.values():
    if throw_event['ThrowPointType'] != 'IF_THROW':
        continue 
    
    exec_id = throw_event['ExecPointID']
    clazz = throw_event['Class']
    method = throw_event['Method']
    line_no = throw_event['LineNumber']
    throw_point_pos_key = (clazz, method, line_no)

    try:
        candidate_branch_events = []
        for branch_event in position_indexed_branch[throw_point_pos_key]:
            candidate_branch_events.append(branch_event)
        if len(candidate_branch_events) == 2:
            branch_event_0 = candidate_branch_events[0]
            branch_event_1 = candidate_branch_events[1]
            branch_0_lineno = branch_event_0['LineNumber']
            branch_1_lineno = branch_event_1['LineNumber']
            if branch_0_lineno < branch_1_lineno:
                if branch_0_lineno >= line_no:
                    final_branch_event = branch_event_0 
                else:
                    final_branch_event = branch_event_1 
            else:
                if branch_1_lineno >= line_no:
                    final_branch_event = branch_event_1 
                else:
                    final_branch_event = branch_event_0
            r.setdefault(exec_id, [])
            r[exec_id].append(final_branch_event['ExecPointID'])
        elif len(candidate_branch_events) == 1:
            branch_event = candidate_branch_events[0]
            if branch_event['LineNumber'] >= line_no:
                r.setdefault(exec_id, [])
                r[exec_id].append(branch_event['ExecPointID'])
    except KeyError:
        print("KeyError:", throw_point_pos_key)


with open(Path(args.output_dir, 'throw_branch_pos.json'), 'w') as f:
    json.dump(r, f, indent=4)
