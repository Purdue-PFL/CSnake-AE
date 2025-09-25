#!/bin/bash
TEST_SYS=$1
TEST_MODE=$2
PWD=$(pwd)

. $PWD/sys_script/${TEST_SYS}_checkpoint_${TEST_MODE}.sh
