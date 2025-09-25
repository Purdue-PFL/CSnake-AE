import json 
import re 
import sys 

RESULT_DIR=sys.argv[1]

ALL_JUNIT_DISPLAY_NAMES = '{RESULT_DIR}/tests_with_param.txt'.format(RESULT_DIR=RESULT_DIR)
with open(ALL_JUNIT_DISPLAY_NAMES) as f:
    all_tests = f.readlines()
all_tests = [x.strip() for x in all_tests]

r = []
for test_name in all_tests:
    regex = r'(?P<method>.+)\((?P<class>.+)\)$'
    match = re.search(regex, test_name)
    if match is not None:
        method_name = match.group('method')
        class_name = match.group('class')
        full_name = class_name + "#" + method_name
        if full_name not in r:
            r.append(full_name)
    else:
        print("Skipping", test_name)

r.sort()
with open('{RESULT_DIR}/test_params.json'.format(RESULT_DIR=RESULT_DIR), 'w') as f:
    json.dump(r, f, indent=4)
