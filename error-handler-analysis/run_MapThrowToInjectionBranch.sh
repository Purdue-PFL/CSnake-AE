#!/bin/bash

OUTPUT_DIR=/CSnake-AE/result/static

python3.10 src/main/python/map_throw_to_injection_branch.py \
    --throw $OUTPUT_DIR/throw.json \
    --branch $OUTPUT_DIR/branches.json \
    --output_dir $OUTPUT_DIR 
