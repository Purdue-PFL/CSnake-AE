#!/bin/bash
tmp_dir=$(mktemp -d)
cp ./target/loop-interference-analysis-1.0-SNAPSHOT.jar $tmp_dir/
classpath=$tmp_dir/loop-interference-analysis-1.0-SNAPSHOT.jar:$(cat ./exec_cp)
echo $tmp_dir

RESULT_DIR=/CSnake-AE/result
TEST_CONFIG_PATH=/CSnake-AE/result_sample/static

JAVA_HOME=/usr/lib/jvm/java-1.21.0-openjdk-amd64 /usr/lib/jvm/java-1.21.0-openjdk-amd64/bin/java -cp $classpath \
    -Xmx128G -XX:+UnlockExperimentalVMOptions -XX:+UseZGC -XX:+UseDynamicNumberOfGCThreads -XX:+ZGenerational -XX:ZMarkStackSpaceLimit=64G \
    pfl.result_analysis.GuidedSearchV3 \
    -injection_testplan \
        /CSnake-AE/result_sample/static/injection_run_pass1.json \
    -loop_cfg /CSnake-AE/vc-result-analysis/result/hdfs341_loop_cfg_chain1.obj \
    -injection_result_path $RESULT_DIR/analysis/per-injection \
    -loops $TEST_CONFIG_PATH/loops.json \
    -negate_injection $TEST_CONFIG_PATH/negates.json \
    -throw_injection $TEST_CONFIG_PATH/throw.json \
    -throw_branch_position $TEST_CONFIG_PATH/throw_branch_pos.json \
    -max_delay_count 3 \
    -max_error_count 20 \
    -output_path $RESULT_DIR/analysis

