import json 
import argparse 
from pathlib import Path 
from typing import *
from sklearn.preprocessing import MultiLabelBinarizer
from scipy.stats import entropy 
import numpy as np 
from sklearn.metrics import jaccard_score 

parser = argparse.ArgumentParser()
parser.add_argument('--injection_plan', nargs='+', type=str)
parser.add_argument('--result_dir', type=str)
parser.add_argument('--output_dir', type=str)
parser.add_argument('--output_prefix', type=str)
args = parser.parse_args()

exclusions = {'d2fe755ced524365c012102d1b0b3842'}

def main():
    injection_plan: Dict[str, Dict[str, str | int]] = {}
    for injection_plan_path in args.injection_plan:
        with open(injection_plan_path, 'r') as f:
            t = json.load(f) 
        injection_plan = {**injection_plan, **t}

    
    e_interference: Dict[tuple[str, str], Dict[str, List[str]]] = {} # (InjectionID, Injection Loop) -> {UnitTest: [Interfered Loops]}
    s_interference: Dict[tuple[str, str], Dict[str, List[str]]] = {}
    for run_id in injection_plan.keys():
        injection_id = injection_plan[run_id]['Injection ID']
        injection_loop = injection_plan[run_id]['Injection Loop']
        profile_run_id = injection_plan[run_id]['ProfileRunID']
        agg_exp_key = injection_plan[run_id]['AggregateExpKey']

        if agg_exp_key != run_id: 
            continue
        if injection_id in exclusions:
            continue 

        e_result_path = Path(args.result_dir, run_id + '.json')
        if not e_result_path.exists():
            continue 
        with open(e_result_path) as f:
            e_interference_result: Dict[str, List[str]] = json.load(f)
        
        e_interfered_loops = []
        for interfered_loop_keys in e_interference_result.values():
            e_interfered_loops.extend(interfered_loop_keys)
        
        s_result_path = Path(args.result_dir, run_id + '_itercount.json')
        if not s_result_path.exists():
            continue 
        with open(s_result_path, 'r') as f:
            s_interference_result: Dict[str, Dict[str, List[List[str]]]] = json.load(f) 
        
        s_interfered_loops = []
        for interfered_loop_keys in s_interference_result.values():
            s_interfered_loops.extend(list(interfered_loop_keys.keys()))
        
        exp_key = (injection_id, injection_loop)
        e_interference.setdefault(exp_key, {}).setdefault(profile_run_id, []).extend(e_interfered_loops)
        s_interference.setdefault(exp_key, {}).setdefault(profile_run_id, []).extend(s_interfered_loops)

    e_scores: Dict[tuple[str, str], float] = {}
    s_scores: Dict[tuple[str, str], float] = {}
    for exp_key in e_interference.keys():
        def deduplicate(x: List[List[str]]) -> List[List[str]]:
            r = []
            for e in x:
                r.append(list(set(e)))
            return r 
        e_score = get_score(deduplicate(e_interference[exp_key].values()))
        s_score = get_score(deduplicate(s_interference[exp_key].values()))

        e_scores[exp_key] = e_score
        s_scores[exp_key] = s_score

    e_scores = [{"InjectionID_InjectionLoop": k, "Score": v} for k, v in sorted(e_scores.items(), key=lambda item: item[1])]
    s_scores = [{"InjectionID_InjectionLoop": k, "Score": v} for k, v in sorted(s_scores.items(), key=lambda item: item[1])]
    with open(Path(args.output_dir, args.output_prefix + '_Score_E.json'), 'w') as f:
        json.dump(e_scores, f, indent=4)
    with open(Path(args.output_dir, args.output_prefix + '_Score_SP.json'), 'w') as f:
        json.dump(s_scores, f, indent=4)

def get_score(interferences: List[List[str]]): # [0, 1], 1.0 means the interference result is the same acoress all injections
    mlb = MultiLabelBinarizer()
    Y = mlb.fit_transform(interferences)

    n_samples = Y.shape[0]
    if n_samples == 1:
        return 1.0

    # Step 1: Compute the dot product (intersection counts)
    intersection = Y @ Y.T  # shape (n_samples, n_samples)

    # Step 2: Compute sums (individual label counts)
    row_sums = Y.sum(axis=1).reshape(-1, 1)  # shape (n_samples, 1)

    # Step 3: Compute union counts
    union = row_sums + row_sums.T - intersection  # shape (n_samples, n_samples)

    # Step 4: Jaccard similarity
    similarity_matrix = np.divide(intersection, union, out=np.zeros_like(intersection, dtype=float), where=union!=0)

    n = Y.shape[0]
    avg_jaccard = (np.sum(similarity_matrix) - np.trace(similarity_matrix)) / (n * (n-1))

    return avg_jaccard

    similarities = np.zeros((n_samples, n_samples))
    for i in range(n_samples):
        for j in range(n_samples):
            similarities[i, j] = jaccard_score(Y[i], Y[j], zero_division=0.0)

    avg_similarity = (np.sum(similarities) - np.trace(similarities)) / (n_samples * (n_samples - 1))

    return avg_similarity


if __name__ == '__main__':
    main()
