import json 
import argparse 
from pathlib import Path 
from typing import *
import numpy as np
from scipy.spatial.distance import pdist 
from scipy.cluster.hierarchy import linkage, fcluster, dendrogram
from matplotlib import pyplot as plt

parser = argparse.ArgumentParser()
parser.add_argument('--loops', type=str)
parser.add_argument('--injection_plan', type=str)
parser.add_argument('--result_dir', type=str)
parser.add_argument('--output_dir', type=str)
parser.add_argument('--output_prefix', type=str)
args = parser.parse_args()

exclusions = {'d2fe755ced524365c012102d1b0b3842'} # Exclude org.apache.hadoop.conf.Configuration.getBoolean

def main():
    with open(args.loops) as f:
        loops: Dict[str, Dict[str, str | int]] = json.load(f)
    with open(args.injection_plan) as f:
        injection_plan: Dict[str, Dict[str, str | int]] = json.load(f) 

    loop_key_idx: Dict[str, int] = {}
    for idx, loop_key in enumerate(loops.keys()):
        loop_key_idx[loop_key] = idx
    injection_id_key_mapper: Dict[str, Tuple[str, str]] = {} # RunHash -> (InjectionID, Injection Loop)
    for run_id, run_prop in injection_plan.items():
        if run_prop['AggregateExpKey'] != run_id: 
            continue 
        injection_key_tuple = (run_prop['Injection ID'], run_prop['Injection Loop'])
        injection_id_key_mapper[run_id] = injection_key_tuple

    # Process interference data
    edge_data_map: Dict[Tuple[str, str], np.ndarray] = {}
    for run_id in injection_plan.keys():
        if injection_plan[run_id]['Injection ID'] in exclusions: 
            continue 
        e_result_path = Path(args.result_dir, run_id + '.json')
        if not e_result_path.exists():
            continue 
        with open(e_result_path) as f:
            e_interference_result: Dict[str, List[str]] = json.load(f)
        
        s_result_path = Path(args.result_dir, run_id + '_itercount.json')
        if not s_result_path.exists():
            continue 
        with open(s_result_path, 'r') as f:
            s_interference_result: Dict[str, Dict[str, List[List[str]]]] = json.load(f) 
        
        # Flatten interference results from multiple injection runs with the same (InjectionID, InjectionLoop)
        injection_id_key = injection_id_key_mapper[run_id]
        encoded_data = edge_data_map.setdefault(injection_id_key, np.zeros(len(loop_key_idx) * 2, dtype=int))
        # E edges
        for interfered_loop_keys in e_interference_result.values():
            for interfered_loop_key in interfered_loop_keys:
                encoded_data[loop_key_idx[interfered_loop_key] * 2] = 1 
        # S+ Edges
        for interfered_loop_keys in s_interference_result.values():
            for interfered_loop_key in interfered_loop_keys.keys():
                encoded_data[loop_key_idx[interfered_loop_key] * 2 + 1] = 1 
    print("Data loaded")

    # clustering
    edge_labels = list(edge_data_map.keys())
    edge_data_raw = [edge_data_map[e] for e in edge_labels]
    edge_data = np.vstack(edge_data_raw)
    cluster_result = cluster(edge_data)
    
    edge_clusters: Dict[int, List[Tuple[str, str]]] = {}
    for idx, cluster_id in enumerate(cluster_result):
        edge_clusters.setdefault(int(cluster_id), []).append(edge_labels[idx])
    edge_clusters = dict(sorted(edge_clusters.items()))

    # dump cluster result and stats
    with open(Path(args.output_dir, args.output_prefix + "_InjectionCluster.json"), 'w') as f:
        json.dump(edge_clusters, f, indent=4)

    cluster_sizes = [len(e) for e in edge_clusters.values()]
    count, label = np.histogram(cluster_sizes, bins=np.arange(max(cluster_sizes) + 1, dtype=int))
    dict_out = {}
    for ct, lbl in zip(count, label):
        if ct == 0: 
            continue
        dict_out[str(lbl)] = int(ct)
    with open(Path(args.output_dir, args.output_prefix + "_InjectionCluster_Stat.json"), 'w') as f:
        json.dump(dict_out, f, indent=4)


def cluster(X: np.ndarray) -> np.ndarray:
    Y = pdist(X, metric='jaccard')
    Z = linkage(Y, method='weighted')
    # fig = plt.figure(figsize=(25, 10))
    # ax = fig.add_subplot(111)
    # dendrogram(Z, ax=ax)
    # fig.savefig('./cluster.png')
    return fcluster(Z, t=1, criterion='inconsistent')

if __name__ == '__main__':
    main()
