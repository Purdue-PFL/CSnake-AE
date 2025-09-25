#!/bin/bash
tmp_dir=$(mktemp -d)
cp ./target/loop-interference-analysis-1.0-SNAPSHOT.jar $tmp_dir/
classpath=$tmp_dir/loop-interference-analysis-1.0-SNAPSHOT.jar:$(cat ./exec_cp)
echo $tmp_dir

RESULT_DIR=/CSnake-AE/result

HDFS341_PATH=/CSnake-AE/analysis_target
JAVA_HOME=/usr/lib/jvm/java-1.11.0-openjdk-amd64 /usr/lib/jvm/java-11-openjdk-amd64/bin/java -Xmx128g -cp $classpath \
    pfl.result_analysis.BuildLoopCFG \
    -loops /CSnake-AE/result_sample/static/loops.json \
    -cfg /CSnake-AE/result/static/hdfs341_cg_dynamic.obj.zst \
    -classpath $HDFS341_PATH/hadoop-common-3.4.1.jar:$HDFS341_PATH/hadoop-hdfs-3.4.1.jar:$HDFS341_PATH/hadoop-hdfs-client-3.4.1.jar \
    -graph_output_path /CSnake-AE/vc-result-analysis/result/hdfs341_loop_cfg_chain1.obj \
    -loop_cfg_path_length 1
