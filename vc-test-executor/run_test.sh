#!/bin/bash
TEST_SYS=$1
TEST_MODE=$2 # Profile | Injection
PWD=$(pwd)
. $PWD/sys_script/${TEST_SYS}.sh

export LD_PRELOAD=/usr/lib/x86_64-linux-gnu/libcriu.so

get_checkpoint() {

    while true; do
        python3 run_test.py $TEST_SYS $TEST_MODE
        SIZE=$(du -B 1 /criu | awk '{print $1}')
        # We need an image with less then 4GB memory to start
        if [[ $SIZE -lt 4294967296 ]]; then 
            break 
        fi 
    done 

}

get_checkpoint 

while true; do 
    if ! criu restore -D /criu --shell-job --file-locks; then
        get_checkpoint
    fi 
done 
