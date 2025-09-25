#!/bin/bash
PWD=$(pwd)
HDFS_CP="$(cat $PWD/env/hdfs341_docker_cp.txt)":$PWD/lib/junit-4.13.2-No-Assert.jar:$PWD/lib/junit-jupiter-api-5.8.2-No-Assert.jar
HDFS_PROJECT_PATH=/hdfs-3.4.1/hadoop-hdfs-project/hadoop-hdfs
HDFS_JAR=$HDFS_PROJECT_PATH/target/hadoop-hdfs-3.4.1.jar:$HDFS_PROJECT_PATH/target/hadoop-hdfs-3.4.1-tests.jar:/hdfs-3.4.1/hadoop-hdfs-project/hadoop-hdfs-client/target/hadoop-hdfs-client-3.4.1.jar:/hdfs-3.4.1/hadoop-common-project/hadoop-common/target/hadoop-common-3.4.1.jar
AGENT_PATH=/error-handler-analysis/fault-injection/byteman.jar
BOOTSTRAP_CP=$AGENT_PATH:/vc-bmhelper/target/vc-bmhelper-1.0-SNAPSHOT.jar
BASE_BTM=$PWD/btm/hdfs341_base_injection.btm
EXECUTOR_JAR=$PWD/target/vc-test-executor-1.0-SNAPSHOT.jar
EXECUTOR_CP=$PWD/lib/jigawatts.jar

export HADOOP_COMMON_HOME=$HDFS_PROJECT_PATH/../../hadoop-common-project/hadoop-common/target
export HADOOP_HOME=$HDFS_PROJECT_PATH/../../hadoop-common-project/hadoop-common/target
export LD_LIBRARY_PATH=$LD_LIBRARY_PATH:$HDFS_PROJECT_PATH/target/native/target/usr/local/lib:$HDFS_PROJECT_PATH/../../hadoop-common-project/hadoop-common/target/native/target/usr/local/lib
export DYLD_LIBRARY_PATH=$DYLD_LIBRARY_PATH:$HDFS_PROJECT_PATH/target/native/target/usr/local/lib:$HDFS_PROJECT_PATH/../../hadoop-common-project/hadoop-common/target/native/target/usr/local/lib
export MALLOC_ARENA_MAX=4
export LD_PRELOAD=/usr/lib/x86_64-linux-gnu/libcriu.so

JAVA_HOME=/usr/lib/jvm/java-1.8.0-openjdk-amd64 java -Xmx32G -XX:ActiveProcessorCount=6 \
    -XX:+UseSerialGC -XX:-UsePerfData -XX:GCTimeRatio=4 \
    -javaagent:$AGENT_PATH=script:$BASE_BTM,boot:$BOOTSTRAP_CP,listener:true -Dorg.jboss.byteman.transform.all \
    -Dhadoop.log.dir=$PWD/target/log \
    -Dhadoop.tmp.dir=$PWD/target/tmp \
    -Dtest.build.dir=$PWD/target/test-dir \
    -Dtest.build.data=$PWD/target/test/data \
    -Dtest.build.webapps=$HDFS_PROJECT_PATH/target/test-classes/webapps \
    -Dtest.cache.data=$HDFS_PROJECT_PATH/target/test-classes \
    -Dtest.build.classes=$HDFS_PROJECT_PATH/target/test-classes \
    -Djava.net.preferIPv4Stack=true \
    -Djava.security.krb5.conf=$HDFS_PROJECT_PATH/target/test-classes/krb5.conf \
    -Djava.security.egd=file:///dev/urandom \
    -cp $HDFS_JAR:$HDFS_CP:$EXECUTOR_CP:$EXECUTOR_JAR pfl.test_execute.AppHDFS checkpoint

