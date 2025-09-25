#!/bin/bash 
tmp_dir=$(mktemp -d)
cp ./target/error-handler-analysis-1.0-SNAPSHOT.jar $tmp_dir/
classpath=$tmp_dir/error-handler-analysis-1.0-SNAPSHOT.jar:$(cat ./exec_cp)

echo $tmp_dir

OUTPUT_DIR=/CSnake-AE/result/static

TARGET_PATH=/CSnake-AE/analysis_target
/usr/lib/jvm/java-11-openjdk-amd64/bin/java -cp $classpath -Xmx128G pfl.GetAllUnitTests \
    -cp $TARGET_PATH/hadoop-common-3.4.1.jar:$TARGET_PATH/hadoop-hdfs-3.4.1.jar:$TARGET_PATH/hadoop-hdfs-client-3.4.1.jar:$TARGET_PATH/hadoop-common-3.4.1-tests.jar \
    -tcp $TARGET_PATH/hadoop-hdfs-3.4.1-tests.jar \
    -otest $OUTPUT_DIR/tests.json \
    -tcp_prefix "org.apache.hadoop.hdfs" \
    -test_declaring_class_mapper_output $OUTPUT_DIR/test_classname_mapper.json \
    -target_system HDFS341
