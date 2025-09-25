from typing import *
import math 
import re 
import random   

def compute_unittest_ids_coverage(unittest_ids: List[str], unittest_coverage: List[float], required_coverage_ratio: float, maximum_tests: int, use_random_choice: bool = False) -> List[str]:
    if len(unittest_ids) == 0:
        return []
    
    if not use_random_choice:
        maximum_coverage = unittest_coverage[-1] * required_coverage_ratio
        r: List[str] = []
        
        if math.isclose(maximum_coverage, 0):
            return r 
        
        for idx, unittest_id in enumerate(unittest_ids):
            r.append(unittest_id)
            current_coverage = unittest_coverage[idx]
            if (current_coverage >= maximum_coverage) or (len(r) >= maximum_tests):
                break 
        
        return r 
    else:
        r = random.choices(unittest_ids, k=maximum_tests)
        return list(set(r))


def is_util_throw(clazz: str, method: str) -> bool:
    excl_keys = ['util', 'info', '.tools.', '.tool.', 'metrics', ".proto."]
    
    clazz_lower = clazz.lower()
    excl_status = [x in clazz_lower for x in excl_keys]

    return any(excl_status)

def is_util_negate(clazz: str, method: str) -> bool:
    class_excl_keys = ['util', 'info', '.tools.', '.tool.', 'metrics', 'abstract', ".proto.", "org.apache.hadoop.conf.Configuration"]
    
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
    excl_keys = ['.proto.', 'util', 'metrics', 'abstract', '.tool.', '.tools.']
    
    clazz_lower = clazz.lower()
    excl_status = [x in clazz_lower for x in excl_keys]

    return any(excl_status)

# (Type, Byteman_Class, Byteman_Method, JUnit_Class, JUnit_Method)
def demangle_junit_testname(raw_name: str, junit_byteman_map: Dict[str, str]) -> Tuple[str, str, str, str, str]:
    def map_to_byteman(class_name: str, method_name: str) -> Tuple[str, str]:
        byteman_map_key = class_name + '#' + method_name
        if byteman_map_key in junit_byteman_map:
            real_byteman_full_name = junit_byteman_map[byteman_map_key]
            real_class = real_byteman_full_name.split('#')[0]
            real_method = real_byteman_full_name.split('#')[1]
            return real_class, real_method
        return class_name, method_name

    # JUnit 4 Non-Param
    # org.apache.flink.api.datastream.DataStreamBatchExecutionITCase#batchNonKeyedKeyedTwoInputOperator
    regex = r'^(?P<class>.+)\#(?P<method>[^\[\]]+)$'
    match = re.search(regex, raw_name)
    if match is not None:
        class_name = match.group('class')
        method_name = match.group('method')
        bm_class_name, bm_method_name = map_to_byteman(class_name, method_name)
        return "JUnit4_NON_PARAM", bm_class_name, bm_method_name, class_name, method_name
    
    # Junit 4 Param
    # org.apache.flink.api.scala.actions.CountCollectITCase#testCountCollectOnAdvancedJob[Execution mode = CLUSTER]
    regex = r'^(?P<class>.+)\#(?P<method>[^\[\]]+)\[(?P<param>.*)\]$'
    match = re.search(regex, raw_name)
    if match is not None:
        class_name = match.group('class')
        method_name = match.group('method')
        bm_class_name, bm_method_name = map_to_byteman(class_name, method_name)
        return "JUnit4_PARAM", bm_class_name, bm_method_name, class_name, method_name
    
    # Junit 5 Jupiter Non-param
    # [engine:junit-jupiter]/[class:org.apache.hadoop.ozone.om.snapshot.TestOmSnapshotFsoWithNativeLib]/[method:testListDeleteKey()]
    regex = r'^\[engine\:junit\-jupiter\]\/\[class\:(?P<class>.+)\]\/\[method\:(?P<method>[^\(\)]+)\(.*\)\]$'
    match = re.search(regex, raw_name)
    if match is not None:
        class_name = match.group('class')
        method_name = match.group('method')
        bm_class_name, bm_method_name = map_to_byteman(class_name, method_name)
        return "JUnit5_Jupiter_NON_PARAM", bm_class_name, bm_method_name, class_name, method_name

    # Junit 5 Jupiter Param
    # [engine:junit-jupiter]/[class:org.apache.hadoop.ozone.om.snapshot.TestOzoneManagerSnapshotAcl]/[test-template:testGetAclWithAllowedUser(org.apache.hadoop.ozone.om.helpers.BucketLayout)]/[test-template-invocation:#1]
    regex = r'^\[engine\:junit\-jupiter\]\/\[class\:(?P<class>.+)\]\/\[test\-template\:(?P<method>[^\(\)]+)\(.*\)\]\/\[.*\#.*\]$'
    match = re.search(regex, raw_name)
    if match is not None:
        class_name = match.group('class')
        method_name = match.group('method')
        bm_class_name, bm_method_name = map_to_byteman(class_name, method_name)
        return "JUnit5_Jupiter_PARAM", bm_class_name, bm_method_name, class_name, method_name
    
    # Junit 5 Vintage Non-param
    # [engine:junit-vintage]/[runner:org.apache.hadoop.ozone.client.rpc.TestBlockDataStreamOutput]/[test:testMultiChunkWrite(org.apache.hadoop.ozone.client.rpc.TestBlockDataStreamOutput)]
    regex = r'^\[engine\:junit\-vintage\]\/\[runner\:(?P<class>[^\[\]]+)\]\/\[test\:(?P<method>[^\(\)\[\]\/]+)\(\1\)\]$'
    match = re.search(regex, raw_name)
    if match is not None:
        class_name = match.group('class')
        method_name = match.group('method')
        bm_class_name, bm_method_name = map_to_byteman(class_name, method_name)
        return "JUnit5_Vintage_NON_PARAM", bm_class_name, bm_method_name, class_name, method_name
    
    # Junit 5 Vintage Param
    # [engine:junit-vintage]/[runner:org.apache.hadoop.ozone.container.ozoneimpl.TestOzoneContainer]/[test:%5B7%5D]/[test:testBuildNodeReport%5B7%5D(org.apache.hadoop.ozone.container.ozoneimpl.TestOzoneContainer)]
    regex = r'^\[engine\:junit\-vintage\]\/\[runner\:(?P<class>[^\[\]]+)\]\/\[test\:(?P<param>.*)\]\/\[test\:(?P<method>[^\(\)\[\]\/]+)\2\(\1\)\]$'
    match = re.search(regex, raw_name)
    if match is not None:
        class_name = match.group('class')
        method_name = match.group('method')
        bm_class_name, bm_method_name = map_to_byteman(class_name, method_name)
        return "JUnit5_Vintage_PARAM", bm_class_name, bm_method_name, class_name, method_name

    print("WARNING: Cannot Parse", raw_name)
    raise ValueError(raw_name)
