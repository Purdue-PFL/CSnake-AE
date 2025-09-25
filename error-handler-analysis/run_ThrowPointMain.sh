#!/bin/bash 
tmp_dir=$(mktemp -d)
cp ./target/error-handler-analysis-1.0-SNAPSHOT.jar $tmp_dir/
classpath=$tmp_dir/error-handler-analysis-1.0-SNAPSHOT.jar:$(cat ./exec_cp)

echo $tmp_dir

OUTPUT_DIR=/CSnake-AE/result/static

TARGET_PATH=/CSnake-AE/analysis_target
/usr/lib/jvm/java-11-openjdk-amd64/bin/java -cp $classpath pfl.ThrowPointMain \
    -cp $TARGET_PATH/hadoop-common-3.4.1.jar:$TARGET_PATH/hadoop-hdfs-3.4.1.jar:$TARGET_PATH/hadoop-hdfs-client-3.4.1.jar \
    -cp_prefix "org.apache.hadoop.hdfs" \
    -cp_prefix_excl "org.apache.hadoop.hdfs.protocol.proto|org.apache.hadoop.hdfs.security|org.apache.hadoop.security|org.apache.hadoop.hdfs.server.namenode.ha.proto" \
    -o $OUTPUT_DIR/throw.json \
    -target_system HDFS341
