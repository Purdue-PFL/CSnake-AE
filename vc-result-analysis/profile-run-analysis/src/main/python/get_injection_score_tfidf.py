import json 
import argparse 
from pathlib import Path 
from typing import *
from sklearn.preprocessing import MultiLabelBinarizer
from scipy.stats import entropy 
import numpy as np 
from sklearn.metrics import jaccard_score 
from sklearn.metrics.pairwise import cosine_similarity
from sklearn.feature_extraction.text import TfidfVectorizer 

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
    e_corpus = []
    s_corpus = []
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

        e_corpus.append(" ".join(e_interfered_loops))
        s_corpus.append(" ".join(s_interfered_loops))

    def build_vectorizer(corpus: List[str]) -> TfidfVectorizer:
        vectorizer = TfidfVectorizer(
            lowercase=False,  # Don't convert to lowercase
            token_pattern=r"(?u)\b\w+\b",  # Accept single character words
            stop_words=None  # Don't remove stop words
        )
        return vectorizer.fit(corpus)

    e_vectorizer = build_vectorizer(e_corpus)
    s_vectorizer = build_vectorizer(s_corpus)

    e_scores: Dict[tuple[str, str], float] = {}
    s_scores: Dict[tuple[str, str], float] = {}
    for exp_key in e_interference.keys():
        def deduplicate(x: List[List[str]]) -> List[List[str]]:
            r = []
            for e in x:
                r.append(list(set(e)))
            return r 
        e_score = get_score(e_vectorizer, deduplicate(e_interference[exp_key].values()))
        s_score = get_score(s_vectorizer, deduplicate(s_interference[exp_key].values()))

        e_scores[exp_key] = e_score
        s_scores[exp_key] = s_score

    e_scores = [{"InjectionID_InjectionLoop": k, "Score": v} for k, v in sorted(e_scores.items(), key=lambda item: item[1])]
    s_scores = [{"InjectionID_InjectionLoop": k, "Score": v} for k, v in sorted(s_scores.items(), key=lambda item: item[1])]
    with open(Path(args.output_dir, args.output_prefix + '_Score_E.json'), 'w') as f:
        json.dump(e_scores, f, indent=4)
    with open(Path(args.output_dir, args.output_prefix + '_Score_SP.json'), 'w') as f:
        json.dump(s_scores, f, indent=4)

def get_score(vectorizer: TfidfVectorizer, interferences: List[List[str]]): # [0, 1], 1.0 means the interference result is the same acoress all injections
    if len(interferences) == 1:
        return 1.0

    interference_corpus = [" ".join(seq) for seq in interferences]
    X = vectorizer.transform(interference_corpus)
    cos_sim = cosine_similarity(X) # Range [0, 1] due to all positive value from TF-IDF
    n = cos_sim.shape[0]
    mask = ~np.eye(n, dtype=bool)
    cos_sim_no_diag = cos_sim[mask]

    return cos_sim_no_diag.mean()


if __name__ == '__main__':
    main()
