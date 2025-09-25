#!/bin/bash 
tmp_dir=$(mktemp -d)
cp ./target/error-handler-analysis-1.0-SNAPSHOT.jar $tmp_dir/
classpath=$tmp_dir/error-handler-analysis-1.0-SNAPSHOT.jar:$(cat ./exec_cp)

echo $tmp_dir

OUTPUT_DIR=/CSnake-AE/result/static

PWD=$(pwd)
CP=/CSnake-AE/error-handler-analysis/src/main/resources/junit-4.13.3-DUMMY.jar:"$(cat /CSnake-AE/result/static/host_cp)"
HDFS341_PATH=/CSnake-AE/analysis_target
JAR=$HDFS341_PATH/hadoop-hdfs-3.4.1.jar:$HDFS341_PATH/hadoop-hdfs-3.4.1-tests.jar
HDFS_PROJECT_PATH=/CSnake-AE/hdfs-3.4.1/hadoop-hdfs-project/hadoop-hdfs
export HADOOP_COMMON_HOME=$HDFS_PROJECT_PATH/../../hadoop-common-project/hadoop-common/target
export HADOOP_HOME=$HDFS_PROJECT_PATH/../../hadoop-common-project/hadoop-common/target
export LD_LIBRARY_PATH=$LD_LIBRARY_PATH:$HDFS_PROJECT_PATH/target/native/target/usr/local/lib:$HDFS_PROJECT_PATH/../../hadoop-common-project/hadoop-common/target/native/target/usr/local/lib
export DYLD_LIBRARY_PATH=$DYLD_LIBRARY_PATH:$HDFS_PROJECT_PATH/target/native/target/usr/local/lib:$HDFS_PROJECT_PATH/../../hadoop-common-project/hadoop-common/target/native/target/usr/local/lib
export MALLOC_ARENA_MAX=4
export JAVA_HOME=/usr/lib/jvm/java-1.11.0-openjdk-amd64

/usr/lib/jvm/java-1.11.0-openjdk-amd64/bin/java -Xmx32G -XX:ActiveProcessorCount=6 \
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
    -cp $CP:$JAR:$classpath pfl.GetAllParameterizedTest \
    -static_tests_json $OUTPUT_DIR/tests.json \
    -o $OUTPUT_DIR 
