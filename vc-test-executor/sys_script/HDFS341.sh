#!/bin/bash
PWD=$(pwd)
HDFS_CP="$(cat $PWD/env/hdfs341_docker_cp.txt)"
HDFS_PROJECT_PATH=/hdfs-3.4.1/hadoop-hdfs-project/hadoop-hdfs
HDFS_JAR=$HDFS_PROJECT_PATH/target/hadoop-hdfs-3.4.1.jar:$HDFS_PROJECT_PATH/target/hadoop-hdfs-3.4.1-tests.jar:/hdfs-3.4.1/hadoop-hdfs-project/hadoop-hdfs-client/target/hadoop-hdfs-client-3.4.1.jar:/hdfs-3.4.1/hadoop-common-project/hadoop-common/target/hadoop-common-3.4.1.jar
AGENT_PATH=/error-handler-analysis/fault-injection_old/code_under_test/byteman.jar
BOOTSTRAP_CP=$AGENT_PATH:/vc-bmhelper/target/vc-bmhelper-1.0-SNAPSHOT.jar
BASE_BTM=$PWD/btm/hdfs341_base_profile.btm
EXECUTOR_JAR=$PWD/target/vc-test-executor-1.0-SNAPSHOT.jar
EXECUTOR_CP=$PWD/lib/jigawatts.jar

export HADOOP_COMMON_HOME=$HDFS_PROJECT_PATH/../../hadoop-common-project/hadoop-common/target
export HADOOP_HOME=$HDFS_PROJECT_PATH/../../hadoop-common-project/hadoop-common/target
export LD_LIBRARY_PATH=$LD_LIBRARY_PATH:$HDFS_PROJECT_PATH/target/native/target/usr/local/lib:$HDFS_PROJECT_PATH/../../hadoop-common-project/hadoop-common/target/native/target/usr/local/lib
export DYLD_LIBRARY_PATH=$DYLD_LIBRARY_PATH:$HDFS_PROJECT_PATH/target/native/target/usr/local/lib:$HDFS_PROJECT_PATH/../../hadoop-common-project/hadoop-common/target/native/target/usr/local/lib
export MALLOC_ARENA_MAX=4
export LD_PRELOAD=/usr/lib/x86_64-linux-gnu/libcriu.so
