import os
import traceback
import re
import subprocess
import time
from openai import OpenAI
import json
import base64
import requests
import pickle
import io
from typing import *
from pathlib import Path
import linecache

# CODE_PATHS = [Path("/local2/qian151/vc-detect-workspace/hadoop292-vctest/")]
# RESULT_PATH = Path('/home/qian151/research/vc-detect/error-handler-analysis/result/hdfs292')
# CODE_PATHS = [Path('/local2/qian151/vc-detect-workspace/hbase-2.6.0')]
# RESULT_PATH = Path('/home/qian151/research/vc-detect/error-handler-analysis/result/hbase260')
# CODE_PATHS = [Path('/local2/qian151/vc-detect-workspace/flink-1.20.0')]
# RESULT_PATH = Path('/home/qian151/research/vc-detect/error-handler-analysis/result/flink120')
# CODE_PATHS = [Path('/local2/qian151/vc-detect-workspace/ozone-1.4.0')]
# RESULT_PATH = Path('/home/qian151/research/vc-detect/error-handler-analysis/result/ozone140')
CODE_PATHS = [Path('/local2/qian151/vc-detect-workspace/hdfs-3.4.1')]
RESULT_PATH = Path('/home/qian151/research/vc-detect/error-handler-analysis/result/hdfs341')

api_key = "sk-2QgC998rJ5-u-_g30j6rB04c2PGwRSEQFQw4yifuGFT3BlbkFJCZoJzTRwkuQiBCkLmcS3w7eWxpqi9FIaVX2k_rxSUA"
client = OpenAI(api_key=api_key)

negate_json = Path(RESULT_PATH, 'negates.json')
with open(negate_json, 'r') as f:
    negate_events: Dict[str, str | int] = json.load(f)


def get_code(negate_dict) -> List[str]:
    classname = negate_dict['Class'].split('.')[-1]
    if '$' in classname:
        classname = classname.split('$')[0]
    filenames = []
    for CODE_PATH in CODE_PATHS:
        filenames = filenames + list(CODE_PATH.rglob(classname + '.java'))
    if len(filenames) == 1:
        filename = str(filenames[0])
        src_lines = []
        target_lineno = negate_dict['LineNumber']
        target_lineno_end = negate_dict['LastLineNo']
        for lineno in range(target_lineno - 5, target_lineno_end + 2):
            line = linecache.getline(filename, lineno=lineno)
            src_lines.append(line.rstrip())
        return src_lines
    return []


for negate_key, negate_dict in negate_events.items():
    print(negate_key)
    target_lineno = negate_dict['LineNumber']
    class_name = negate_dict['Class']
    method_name = negate_dict['Method']
    # if (method_name != "sendImmediately") and (class_name != "org.apache.hadoop.fs.FileSystem$DirListingIterator"):
    #     continue
    if target_lineno <= 0:
        continue
    if 'proto' in class_name.lower():
        continue

    src_lines = get_code(negate_dict)
    # print(src_lines)

    headers = {"Content-Type": "application/json", "Authorization": f"Bearer {api_key}"}
    content = """You are a professional developer in Java-based distributed systems. A user will provide you with a code snippet with a boolean-returning function inside. You will need to decide whether the function satisfies any of the following criteria:

1. The function is a error detector for failures (such as node failures, file corruptions, or network errors) inside that distributed system.

The function snippet is from a Java function whose fully qualified name is \"{class}.{method}\". Give a direct \"Yes\" or \"No\" answer for the code snippet below: 

{src_lines}
"""
    content = content.format(**{'src_lines': '\n'.join(src_lines), 'class': class_name, 'method': method_name})
    # print(content)
    # exit()

    payload = {
        "model": "gpt-4o",
        "messages": [{
            "role": "user", "content": [{
                "type": "text", "text": content
            }]
        }],
        "temperature": 0
    }
    try:
        response = requests.post("https://api.openai.com/v1/chat/completions", headers=headers, json=payload)
        inferred = response.json()['choices'][0]["message"]["content"]
        # print(inferred)
        # exit()
        inferred_str = str(inferred)
        print(class_name + "." + method_name + ':', inferred_str)
        if 'no' in inferred_str.lower():
            negate_dict['IsErrorDetector_GPT'] = 'No'
        elif 'yes' in inferred_str.lower():
            negate_dict['IsErrorDetector_GPT'] = 'Yes'
    except Exception:
        pass 

with open(Path(RESULT_PATH, 'negate_gpt.json'), 'w') as f:
    json.dump(negate_events, f, indent=4)
