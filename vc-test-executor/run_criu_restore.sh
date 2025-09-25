#!/bin/bash
PWD=$(pwd)
EXECUTOR_JAR=$PWD/target/vc-test-executor-1.0-SNAPSHOT.jar
EXECUTOR_CP=$PWD/lib/jigawatts.jar
HDFS_CP="$(cat ./env/hdfs292_docker_cp.txt)"
HDFS_PROJECT_PATH=/hadoop292-vctest/hadoop-hdfs-project/hadoop-hdfs
HDFS_JAR=$HDFS_PROJECT_PATH/target/test-classes:$HDFS_PROJECT_PATH/target/classes
AGENT_PATH=/error-handler-analysis/fault-injection_old/code_under_test/byteman.jar
BOOTSTRAP_CP=$AGENT_PATH:/error-handler-analysis/fault-injection_old/code_under_test/vc-bmhelper-1.0-SNAPSHOT.jar
BASE_BTM=$PWD/btm/hdfs292_base.btm

export HADOOP_COMMON_HOME=$HDFS_PROJECT_PATH/../../hadoop-common-project/hadoop-common/target
export HADOOP_HOME=$HDFS_PROJECT_PATH/../../hadoop-common-project/hadoop-common/target
export LD_LIBRARY_PATH=$LD_LIBRARY_PATH:$HDFS_PROJECT_PATH/target/native/target/usr/local/lib:$HDFS_PROJECT_PATH/../../hadoop-common-project/hadoop-common/target/native/target/usr/local/lib
export DYLD_LIBRARY_PATH=$DYLD_LIBRARY_PATH:$HDFS_PROJECT_PATH/target/native/target/usr/local/lib:$HDFS_PROJECT_PATH/../../hadoop-common-project/hadoop-common/target/native/target/usr/local/lib
export MALLOC_ARENA_MAX=4
export LD_PRELOAD=/usr/lib/x86_64-linux-gnu/libcriu.so
export JAVA_HOME=/usr/lib/jvm/java-1.8.0-openjdk-amd64

criu restore -D /criu --shell-job --file-locks
