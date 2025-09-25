from typing import *
import json
import os
from pathlib import Path
import mmh3
from concurrent.futures import ProcessPoolExecutor
import concurrent.futures
import gc
import configparser
import argparse
from multiprocessing import Pool
import functools
import random 
import copy 

import btm_gen
import zstd_helper
import utils

parser = argparse.ArgumentParser()
parser.add_argument('--target', type=str)
parser.add_argument('--injection_type', type=str)  # Error | Delay | Pass1 | Pass2
args = parser.parse_args()
run_config = configparser.ConfigParser(interpolation=configparser.ExtendedInterpolation())
run_config.read(Path('./run_config', args.target + '.ini'))
profile_key = args.injection_type + "_Injection_Run"

#region Init

UNITTEST_LIST_PATH = run_config['Static']['UNITTEST_LIST_PATH']
THROW_EVENT_PATH = run_config['Static']['THROW_EVENT_PATH']
NEGATE_EVENT_PATH = run_config['Static']['NEGATE_EVENT_PATH']
BRANCH_EVENT_PATH = run_config['Static']['BRANCH_EVENT_PATH']
LOOP_EVENT_PATH = run_config['Static']['LOOP_EVENT_PATH']
LOOP_SIZE_THRESHOLD = -1
if run_config.has_option('Static', 'LOOP_SIZE_THRESHOLD'):
    LOOP_SIZE_THRESHOLD = int(run_config['Static']['LOOP_SIZE_THRESHOLD'])
else:
    print("WARNING: No LOOP_SIZE_THRESHOLD used, monitoring all loops")

PROFILE_TESTPLAN_PATH = run_config[profile_key]['PROFILE_TESTPLAN_PATH']
ReachableUnittestFromInjection_JSON_PATH = run_config[profile_key]['ReachableUnittestFromInjection_JSON_PATH']
INJECTION_LOOP_COVERAGE_JSON_PATH = run_config[profile_key]['INJECTION_LOOP_COVERAGE_JSON_PATH']
ReachableUnittestFromLoop_JSON_PATH = run_config[profile_key]['ReachableUnittestFromLoop_JSON_PATH']
DELAY_LOOP_COVERAGE_JSON_PATH = run_config[profile_key]['DELAY_LOOP_COVERAGE_JSON_PATH']
REPEAT_COUNT = int(run_config[profile_key]['REPEAT_COUNT'])
MAX_EXP_PER_INJECTION = int(run_config[profile_key]['MAX_EXP_PER_INJECTION']) if args.injection_type != 'Pass3' else float(run_config[profile_key]['MAX_EXP_PER_INJECTION'])
COVERAGE_THRESHOLD = float(run_config[profile_key]['COVERAGE_THRESHOLD'])
INJ_PROBABILITY_PERCENTAGE = int(1.0 / REPEAT_COUNT * 100.0)

FAULT_INJECTION_TIME_MS = [120000]
FAULT_UNITTEST_TIMEOUT_MS = 5 * 60 * 1000
DELAY_INJECTION_TIME_MS = [1000, 8000]
DELAY_UNITTEST_TIMEOUT_MS = 15 * 60 * 1000

OUTPUT_PATH = run_config[profile_key]['OUTPUT_PATH']
BTM_OUTPUT_PATH = run_config[profile_key]['BTM_OUTPUT_PATH']
Path(BTM_OUTPUT_PATH).mkdir(parents=True, exist_ok=True)
BTM_WITH_ZSTD = OUTPUT_PATH.endswith('.zst')

with open(THROW_EVENT_PATH, 'r') as f:
    throw_events: Dict[str, Dict[str, str | int | List[Dict[str, str | List[int]]]]] = json.load(f)
with open(NEGATE_EVENT_PATH, 'r') as f:
    negate_events: Dict[str, str | int] = json.load(f)
with open(BRANCH_EVENT_PATH, 'r') as f:
    branch_events: Dict[str, str | int] = json.load(f)
with open(LOOP_EVENT_PATH, 'r') as f:
    loop_events: Dict[str, str | int] = json.load(f)
with open(UNITTEST_LIST_PATH, 'r') as f:
    unittests: List[str] = json.load(f)
with zstd_helper.openZstd(PROFILE_TESTPLAN_PATH, 'rb') if PROFILE_TESTPLAN_PATH.endswith('.zst') else open(
        PROFILE_TESTPLAN_PATH, 'r') as f:
    profile_testplan: Dict[str, Dict[str, str | int]] = json.load(f)

with open(ReachableUnittestFromInjection_JSON_PATH, 'r') as f:
    reachable_unittests_from_injection: Dict[str, Dict[str, List[str]]] = json.load(f)  # InjectionID -> Injection Loop ID -> [UnittestID]
with open(ReachableUnittestFromLoop_JSON_PATH, 'r') as f:
    reachable_unittests_from_loop: Dict[str, List[str]] = json.load(f)  # LoopID -> [UnittestID]
with open(INJECTION_LOOP_COVERAGE_JSON_PATH, 'r') as f:
    injection_coverage_stat: Dict[str, Dict[str, List[float]]] = json.load(f)  # InjectionID -> Injection Loop ID -> [Culmulative Coverage Stat]
with open(DELAY_LOOP_COVERAGE_JSON_PATH, 'r') as f:
    delay_loop_coverage_stat: Dict[str, List[float]] = json.load(f)  # LoopID -> [Culmulative Coverage Stat]

error_guided_delay_injection = False
if run_config.has_option(profile_key, "Delay_Injection_Candidate_From_Error") and (args.injection_type == "Delay"):
    error_guided_delay_injection = True
    with open(run_config[profile_key]['Delay_Injection_Candidate_From_Error'], 'r') as f:
        candidate_delay_injections: Dict[str, List[str]] = json.load(f)  # LoopID -> [UnittestID]

    # Remove any experiments that are not in the error-guided result
    for loop_key in list(candidate_delay_injections.keys()):
        if loop_key not in reachable_unittests_from_loop:
            del candidate_delay_injections[loop_key]
    for loop_key in list(reachable_unittests_from_loop.keys()):
        if loop_key not in candidate_delay_injections:
            del reachable_unittests_from_loop[loop_key]
        else:
            unittest_ids = []
            coverage_stats = []
            for idx in range(len(reachable_unittests_from_loop[loop_key])):
                unittest_id = reachable_unittests_from_loop[loop_key][idx]
                if unittest_id in candidate_delay_injections[loop_key]:
                    unittest_ids.append(unittest_id)
                    coverage_stats.append(delay_loop_coverage_stat[loop_key][idx])
            if len(unittest_ids) > 0:
                reachable_unittests_from_loop[loop_key] = unittest_ids
                delay_loop_coverage_stat[loop_key] = coverage_stats
            else:
                del reachable_unittests_from_loop[loop_key]
else:
    if args.injection_type == "Delay":
        print("WARNING: Generating delay injection with profile run trace coverage only")

junit_to_byteman_class_mapper: Dict[str, str] = {}
if run_config.has_option("Static", 'JUNIT_TO_BYTEMAN_TEST_CLASS_MAPPER'):
    with open(run_config['Static']['JUNIT_TO_BYTEMAN_TEST_CLASS_MAPPER']) as f:
        junit_to_byteman_class_mapper = json.load(f)
else:
    print("WARNING: No Option for ['Static']['JUNIT_TO_BYTEMAN_TEST_CLASS_MAPPER']")

exec_point_id_mapper = None
if run_config.has_option('Static', 'EXECPOINT_ID_MAPPER_PATH'):
    EXECPOINT_ID_MAPPER_PATH = run_config['Static']['EXECPOINT_ID_MAPPER_PATH']
    with open(EXECPOINT_ID_MAPPER_PATH, 'r') as f:
        exec_point_id_mapper: Dict[str, int] = json.load(f)
else:
    print("WARNING: No Option for ['Static']['EXECPOINT_ID_MAPPER_PATH']")

inj_point_clusters: Dict[str, List[List[str]]] = None 
loop_min_delay_time_ms: Dict[str, int] = None 
pass1_delay_target: List[str] = None 
if args.injection_type == 'Pass2':
    inj_point_clusters_path = run_config[profile_key]['INJ_POINT_CLUSTERS']
    with open(inj_point_clusters_path, 'r') as f:
        inj_point_clusters = json.load(f) 
    min_delay_time_path = run_config[profile_key]['MIN_DELAY_TIME']
    with open(min_delay_time_path, 'r') as f:
        loop_min_delay_time_ms = json.load(f)
    pass1_delay_target_path = run_config[profile_key]['PASS1_DELAY_TARGET']
    with open(pass1_delay_target_path, 'r') as f:
        pass1_delay_target = json.load(f)
    
    # Skip the unit tests that have been executed in pass 1
    pass1_unittest_offset: int = int(run_config[profile_key]['PASS1_INJ_OFFSET'])
    for injection_id in reachable_unittests_from_injection.keys():
        for injection_loop_id in reachable_unittests_from_injection[injection_id].keys():
            for _ in range(pass1_unittest_offset):
                try:
                    reachable_unittests_from_injection[injection_id][injection_loop_id].pop(0)
                    injection_coverage_stat[injection_id][injection_loop_id].pop(0)
                except IndexError:
                    break 
    for loop_id in reachable_unittests_from_loop.keys():
        for _ in range(pass1_unittest_offset):
            try:
                reachable_unittests_from_loop[loop_id].pop(0)
                delay_loop_coverage_stat[loop_id].pop(0)
            except IndexError:
                break 

if args.injection_type == 'Pass3':
    DELAY_WEIGHT_NEG_OFFSET = float(run_config.get(profile_key, 'DELAY_WEIGHT_NEG_OFFSET', fallback='0'))
    def load_pass3_score(score_path: Path) -> Dict[Tuple[str, str], float]:
        with open(score_path, 'r') as f:
            d: List[Dict[str, List[str] | float]] = json.load(f)
        r = {}
        for item in d:
            injection_id_injection_loop = item['InjectionID_InjectionLoop']
            score = item['Score']
            injection_id = injection_id_injection_loop[0]
            injection_loop = injection_id_injection_loop[1]
            r[(injection_id, injection_loop)] = score 
        
        return r
    
    e_score_path = Path(run_config[profile_key]['E_SCORES_PATH'])
    s_score_path = Path(run_config[profile_key]['S_SCORES_PATH'])
    e_scores: Dict[Tuple[str, str], float] = load_pass3_score(e_score_path)
    s_scores: Dict[Tuple[str, str], float] = load_pass3_score(s_score_path)

    min_delay_time_path = run_config[profile_key]['MIN_DELAY_TIME']
    with open(min_delay_time_path, 'r') as f:
        loop_min_delay_time_ms = json.load(f)

    # Skip all Pass1/Pass2 tested items
    def skip_processed_pass3(testplan_path: Path):
        with open(testplan_path, 'r') as f:
            testplan: Dict[str, Dict[str, str | int]] = json.load(f) 
        for run_id in testplan.keys():
            run_prop = testplan[run_id]
            injection_id = run_prop['Injection ID']
            injection_loop = run_prop['Injection Loop']
            profile_run_id = run_prop['ProfileRunID']
            agg_exp_key = run_prop['AggregateExpKey']
            if run_id != agg_exp_key:
                continue 
            try:
                if injection_id == injection_loop: # Delay
                    idx = reachable_unittests_from_loop[injection_loop].index(profile_run_id)
                    del reachable_unittests_from_loop[injection_loop][idx]
                    del delay_loop_coverage_stat[injection_loop][idx]
                else:
                    idx = reachable_unittests_from_injection[injection_id][injection_loop].index(profile_run_id)
                    del reachable_unittests_from_injection[injection_id][injection_loop][idx]
                    del injection_coverage_stat[injection_id][injection_loop][idx]
            except ValueError:
                pass 
    
    skip_processed_pass3(Path(run_config[profile_key]['PASS1_TESTPLAN_PATH']))
    skip_processed_pass3(Path(run_config[profile_key]['PASS2_TESTPLAN_PATH']))

# Delete unneeded contents
for injection_id in list(reachable_unittests_from_injection.keys()):
    if injection_id in negate_events:
        clazz = negate_events[injection_id]['Class']
        method = negate_events[injection_id]['Method']
        if utils.is_util_negate(clazz, method):
            del reachable_unittests_from_injection[injection_id]
    elif injection_id in throw_events:
        clazz = throw_events[injection_id]['Class']
        method = throw_events[injection_id]['Method']
        if utils.is_util_throw(clazz, method):
            del reachable_unittests_from_injection[injection_id]

for loop_id in list(reachable_unittests_from_loop.keys()):
    clazz = loop_events[loop_id]['Class']
    method = loop_events[loop_id]['Method']
    if utils.is_util_loop(clazz, method):
        del reachable_unittests_from_loop[loop_id]

gc.collect()
print("JSON loaded")

#endregion Init

#region Base BTM

branch_btm: str = ""
with Pool(32) as p:
    gen_branch_event = functools.partial(btm_gen.branch_event, exec_point_id_mapper=exec_point_id_mapper)
    branch_btm = os.linesep.join(p.map(gen_branch_event, [e for e in branch_events.values() if e["Method"] != '<clinit>'], 1024))
# for branch_event in branch_events.values():
#     if branch_event["Method"] == '<clinit>':
#         continue
#     branch_btm = branch_btm + btm_gen.branch_event(branch_event, exec_point_id_mapper=exec_point_id_mapper) + os.linesep

loop_btm: str = ""
for loop_event in loop_events.values():
    if loop_event["Method"] == '<clinit>':
        continue
    if loop_event.get('AllInvokedMethodSSASize', 0) <= LOOP_SIZE_THRESHOLD:
        continue 
    loop_btm = loop_btm + btm_gen.loop_event_ref(loop_event, exec_point_id_mapper=exec_point_id_mapper) + os.linesep

loop_end_by_return_btm = btm_gen.loop_end_by_return_event(loop_events, exec_point_id_mapper=exec_point_id_mapper)

thread_btm = Path('./btm_templates/threads.btm').read_text()
cluster_ready_btm = Path(run_config[profile_key]['CLUSTER_READY_BTM']).read_text()

base_btm_template = """
HELPER pfl.bm.VCLoopInterferenceHelper
COMPILE

{branch_btm}

{loop_btm}

{loop_end_by_return_btm}

{thread_btm}

{cluster_ready_btm}
"""
base_btm_str = base_btm_template.format(branch_btm=branch_btm,
                                        loop_btm=loop_btm,
                                        loop_end_by_return_btm=loop_end_by_return_btm,
                                        thread_btm=thread_btm,
                                        cluster_ready_btm=cluster_ready_btm)
with open(Path(BTM_OUTPUT_PATH, 'base.btm'), 'w') as f:
    f.write(base_btm_str)

#endregion Base BTM

#region Generate Fault

# Fault: THROW, NEGATE
btm_template = """
HELPER pfl.bm.VCLoopInterferenceHelper
COMPILE

RULE DumpResult1
CLASS {unittest_class}
METHOD {unittest_method}
AT EXIT 
IF true 
DO dumpResult();
ENDRULE 

RULE DumpResult2
CLASS {unittest_class}
METHOD {unittest_method}
AT EXCEPTION EXIT 
IF true 
DO dumpResult();
ENDRULE 

{btm_inject_str}
"""

def generate_fault_tasks_per_inj_loop_local_threshold(
        injection_id: str, 
        injection_loop_id: str, 
        required_coverage_ratio: float, 
        maximum_tests:int, 
        use_random_choice: bool = False
    ) -> List[Tuple[str, Dict]]:

    ret = []
    candidate_unittest_ids = reachable_unittests_from_injection[injection_id][injection_loop_id]
    candidate_unittest_coverage = injection_coverage_stat[injection_id][injection_loop_id]
    unittest_ids = utils.compute_unittest_ids_coverage(unittest_ids=candidate_unittest_ids,
                                                        unittest_coverage=candidate_unittest_coverage,
                                                        required_coverage_ratio=required_coverage_ratio,
                                                        maximum_tests=maximum_tests,
                                                        use_random_choice=use_random_choice)

    for unittest_id in unittest_ids:
        unittest = profile_testplan[unittest_id]['UnitTest']
        if '|' in unittest:
            unittest = unittest.split('|')[0]
        test_type, unittest_class, unittest_method, disp_unittest_class, disp_unittest_method = utils.demangle_junit_testname(unittest, junit_byteman_map=junit_to_byteman_class_mapper)

        loop_caller_full_name = loop_events[injection_loop_id]['Class'] + '.' + loop_events[injection_loop_id][
            'Method']
        injection_cond = ['stillInject(\"{name}\", 5, {inj_probability})'.format(name=loop_caller_full_name, inj_probability=INJ_PROBABILITY_PERCENTAGE)]

        btm_inject_str = ""
        injection_type = ''
        if injection_id in negate_events:
            # Inject Negate
            btm_inject_str = btm_gen.negate_event_inj_w_cond(negate_events[injection_id],
                                                                injection_cond,
                                                                exec_point_id_mapper=exec_point_id_mapper)
            injection_type = 'NEGATE'
        elif injection_id in throw_events:
            # Inject throw
            btm_inject_str = btm_gen.throw_event_inj_w_cond(throw_events[injection_id],
                                                            injection_cond,
                                                            exec_point_id_mapper=exec_point_id_mapper)
            injection_type = 'THROW_EXCEPTION'

        btm_str = btm_template.format(unittest_class=unittest_class,
                                        unittest_method=unittest_method,
                                        btm_inject_str=btm_inject_str)

        for injection_time_ms in FAULT_INJECTION_TIME_MS:
            aggregate_exp_key = None
            for i in range(REPEAT_COUNT):
                hasher = mmh3.mmh3_x64_128(seed=0)
                # hasher.update(btm_str.encode())
                hasher.update(unittest.encode())
                hasher.update(repr(injection_time_ms).encode())
                hasher.update(injection_type.encode())
                hasher.update(injection_id.encode())
                hasher.update(injection_loop_id.encode())
                hasher.update(unittest_id.encode())
                if i > 0:
                    hasher.update(repr(i).encode())
                exp_key = format(hasher.uintdigest(), 'x').zfill(32)

                test_dict = {}
                if "JUnit5" in test_type:
                    test_dict['UnitTest'] = unittest + '|' + unittest_class + '#' + unittest_method
                else:
                    test_dict['UnitTest'] = unittest
                test_dict['ExpKey'] = exp_key
                test_dict['InjectionTimeMs'] = injection_time_ms
                test_dict['UnitTestTimeoutMs'] = FAULT_UNITTEST_TIMEOUT_MS
                test_dict['InjectionType'] = injection_type
                test_dict['Injection ID'] = injection_id
                test_dict['Injection Loop'] = injection_loop_id
                test_dict['ProfileRunID'] = unittest_id
                test_dict['TestDisplayName'] = disp_unittest_class + '#' + disp_unittest_method 
                if i == 0:
                    aggregate_exp_key = exp_key
                test_dict['AggregateExpKey'] = aggregate_exp_key

                btm_dump_path = Path(BTM_OUTPUT_PATH, exp_key + ".btm" + (".zst" if BTM_WITH_ZSTD else ""))
                with zstd_helper.openZstd(btm_dump_path, 'wb') if BTM_WITH_ZSTD else open(btm_dump_path, 'w') as f:
                    f.write(btm_str)

                ret.append((exp_key, test_dict))
    
    return ret

def generate_fault_tasks_local_threshold(injection_id: str, required_coverage_ratio: float, maximum_tests: int) -> List[Tuple[str, Dict]]:
    ret = []
    for injection_loop_id in reachable_unittests_from_injection[injection_id].keys():
        ret_one_loop = generate_fault_tasks_per_inj_loop_local_threshold(injection_id, injection_loop_id, required_coverage_ratio, maximum_tests)
        ret = ret + ret_one_loop

    gc.collect()
    return ret


def generate_fault_tasks(injection_id: str) -> List[Tuple[str, Dict]]:
    return generate_fault_tasks_local_threshold(injection_id=injection_id, required_coverage_ratio=COVERAGE_THRESHOLD, maximum_tests=MAX_EXP_PER_INJECTION)

#endregion Generate Fault

#region Generate Delay

def generate_delay_task_local_threshold(
        loop_id: str, 
        required_coverage_ratio: float, 
        maximum_tests: int, 
        minimum_delay_duration_ms: int = min(DELAY_INJECTION_TIME_MS), 
        use_random_choice: bool = False
    ) -> List[Tuple[str, Dict]]:

    candidate_unittest_ids = reachable_unittests_from_loop[loop_id]
    candidate_unittest_coverage = delay_loop_coverage_stat[loop_id]
    # if not error_guided_delay_injection:
    unittest_ids = utils.compute_unittest_ids_coverage(unittest_ids=candidate_unittest_ids,
                                                    unittest_coverage=candidate_unittest_coverage,
                                                    required_coverage_ratio=required_coverage_ratio,
                                                    maximum_tests=maximum_tests,
                                                    use_random_choice=use_random_choice)
    # else:
    #     unittest_ids = candidate_unittest_ids

    injection_type = 'DELAY'
    ret = []
    for unittest_id in unittest_ids:
        unittest = profile_testplan[unittest_id]['UnitTest']
        if '|' in unittest:
            unittest = unittest.split('|')[0]
        test_type, unittest_class, unittest_method, disp_unittest_class, disp_unittest_method = utils.demangle_junit_testname(unittest, junit_byteman_map=junit_to_byteman_class_mapper)

        for injection_time_ms in DELAY_INJECTION_TIME_MS:
            if injection_time_ms < minimum_delay_duration_ms:
                continue 
            aggregate_exp_key = None
            for i in range(REPEAT_COUNT):
                btm_inject_str = btm_gen.loop_event_inj(loop_events[loop_id], injection_time_ms)
                btm_str = btm_template.format(unittest_class=unittest_class,
                                              unittest_method=unittest_method,
                                              btm_inject_str=btm_inject_str)

                hasher = mmh3.mmh3_x64_128(seed=0)
                # hasher.update(btm_str.encode())
                hasher.update(unittest.encode())
                hasher.update(repr(injection_time_ms).encode())
                hasher.update(injection_type.encode())
                hasher.update(loop_id.encode())
                hasher.update(unittest_id.encode())
                if i > 0:
                    hasher.update(repr(i).encode())
                exp_key = format(hasher.uintdigest(), 'x').zfill(32)

                test_dict = {}
                if "JUnit5" in test_type:
                    test_dict['UnitTest'] = unittest + '|' + unittest_class + '#' + unittest_method
                else:
                    test_dict['UnitTest'] = unittest
                test_dict['ExpKey'] = exp_key
                test_dict['InjectionTimeMs'] = injection_time_ms
                test_dict['UnitTestTimeoutMs'] = DELAY_UNITTEST_TIMEOUT_MS
                test_dict['InjectionType'] = injection_type
                test_dict['Injection ID'] = loop_id
                test_dict['Injection Loop'] = loop_id
                test_dict['TestDisplayName'] = disp_unittest_class + '#' + disp_unittest_method
                test_dict['ProfileRunID'] = unittest_id
                if i == 0:
                    aggregate_exp_key = exp_key
                test_dict['AggregateExpKey'] = aggregate_exp_key

                btm_dump_path = Path(BTM_OUTPUT_PATH, exp_key + ".btm" + (".zst" if BTM_WITH_ZSTD else ""))
                with zstd_helper.openZstd(btm_dump_path, 'wb') if BTM_WITH_ZSTD else open(btm_dump_path, 'w') as f:
                    f.write(btm_str)

                ret.append((exp_key, test_dict))

    gc.collect()
    return ret

def generate_delay_task(loop_id: str) -> List[Tuple[str, Dict]]:
    return generate_delay_task_local_threshold(loop_id=loop_id, required_coverage_ratio=COVERAGE_THRESHOLD, maximum_tests=MAX_EXP_PER_INJECTION)

#endregion Generate Delay

with ProcessPoolExecutor(max_workers=48) as executor:
    futures = []
    testplan: Dict[str, Dict[str, str | int]] = {}
    if args.injection_type == 'Error':
        for idx, injection_id in enumerate(reachable_unittests_from_injection.keys()):
            future = executor.submit(generate_fault_tasks, injection_id)
            futures.append(future)
    elif args.injection_type == 'Delay':
        for idx, loop_id in enumerate(reachable_unittests_from_loop.keys()):
            future = executor.submit(generate_delay_task, loop_id)
            futures.append(future)
    
    elif args.injection_type == 'Pass1':
        for idx, injection_id in enumerate(reachable_unittests_from_injection.keys()):
            future = executor.submit(generate_fault_tasks, injection_id)
            futures.append(future)
        for idx, loop_id in enumerate(reachable_unittests_from_loop.keys()):
            future = executor.submit(generate_delay_task, loop_id)
            futures.append(future)
    
    elif args.injection_type == 'Pass2':
        # Allocate Exp Quota
        error_exp_alloc: Dict[Tuple[str, str], int] = {}
        delay_exp_alloc: Dict[str, int] = {}
        for cluster in inj_point_clusters.values():
            # Remove all delays that cannot be affected
            cluster_copy = [e for e in cluster if (e[0] != e[1]) or ((e[0] == e[1]) and (e[1] in pass1_delay_target))]
            if len(cluster_copy) == 0:
                continue
            choices = random.choices(cluster_copy, k=MAX_EXP_PER_INJECTION)
            for choice in choices:
                cur_inj_id = choice[0]
                cur_inj_loop_id = choice[1]
                if cur_inj_id == cur_inj_loop_id: # Delay Injection
                    delay_exp_alloc[cur_inj_loop_id] = delay_exp_alloc.get(cur_inj_loop_id, 0) + 1
                else:
                    key = (cur_inj_id, cur_inj_loop_id)
                    error_exp_alloc[key] = error_exp_alloc.get(key, 0) + 1 
        
        for (injection_id, injection_loop_id), exp_count in error_exp_alloc.items():
            future = executor.submit(generate_fault_tasks_per_inj_loop_local_threshold, injection_id, injection_loop_id, COVERAGE_THRESHOLD, exp_count)
            futures.append(future)
        for injection_loop_id, exp_count in delay_exp_alloc.items():
            future = executor.submit(generate_delay_task_local_threshold, injection_loop_id, COVERAGE_THRESHOLD, exp_count, loop_min_delay_time_ms.get(injection_loop_id, min(DELAY_INJECTION_TIME_MS)))
            futures.append(future)
    
    elif args.injection_type == 'Pass3':
        # Allocate Exp Quota
        all_quota = int(len(e_scores) * MAX_EXP_PER_INJECTION)
        injection_keys = list(e_scores.keys())
        injection_weights = []
        for injection_key in injection_keys:
            # Smaller score -> Higher intra-cluster similarity 
            # 1.0: Same interference result in all injection runs
            e_score = e_scores.get(injection_key, 1.0)
            s_score = s_scores.get(injection_key, 1.0)
            weight = 1.01 - (0.75 * e_score + 0.25 * s_score)
            if injection_key[0] == injection_key[1]:
                weight = max(0.01, weight - DELAY_WEIGHT_NEG_OFFSET)
            injection_weights.append(weight) 
        choices = random.choices(injection_keys, weights=injection_weights, k=all_quota)

        error_exp_alloc: Dict[Tuple[str, str], int] = {}
        delay_exp_alloc: Dict[str, int] = {}
        for choice in choices:
            cur_inj_id = choice[0]
            cur_inj_loop_id = choice[1]
            if cur_inj_id == cur_inj_loop_id: # Delay Injection
                delay_exp_alloc[cur_inj_loop_id] = delay_exp_alloc.get(cur_inj_loop_id, 0) + 1
            else:
                key = (cur_inj_id, cur_inj_loop_id)
                error_exp_alloc[key] = error_exp_alloc.get(key, 0) + 1 
        
        for (injection_id, injection_loop_id), exp_count in error_exp_alloc.items():
            future = executor.submit(generate_fault_tasks_per_inj_loop_local_threshold, 
                injection_id, 
                injection_loop_id, 
                COVERAGE_THRESHOLD, 
                exp_count, 
                True)
            futures.append(future)
        for injection_loop_id, exp_count in delay_exp_alloc.items():
            future = executor.submit(generate_delay_task_local_threshold, 
                injection_loop_id, 
                COVERAGE_THRESHOLD, 
                exp_count, 
                loop_min_delay_time_ms.get(injection_loop_id, min(DELAY_INJECTION_TIME_MS)), 
                True)
            futures.append(future)            

    for future in concurrent.futures.as_completed(futures):
        ret = future.result()
        for exp_key, test_dict in ret:
            testplan[exp_key] = test_dict

    executor.shutdown()

with zstd_helper.openZstd(OUTPUT_PATH, 'wb') if OUTPUT_PATH.endswith('.json.zst') else open(OUTPUT_PATH, 'w') as f:
    json.dump(testplan, f, indent=4)
print("Exp Count:", len(testplan))
