#!/bin/bash
tmp_dir=$(mktemp -d)
cp ./target/profile-run-analysis-1.0-SNAPSHOT.jar $tmp_dir/
classpath=$tmp_dir/profile-run-analysis-1.0-SNAPSHOT.jar:$(cat ./exec_cp)
echo $tmp_dir

RESULT_DIR=/CSnake-AE/result

JAVA_HOME=/usr/lib/jvm/java-1.21.0-openjdk-amd64 /usr/lib/jvm/java-1.21.0-openjdk-amd64/bin/java -cp $classpath \
    -Xmx128G -XX:+UnlockExperimentalVMOptions -XX:+UseZGC -XX:+UseDynamicNumberOfGCThreads -XX:+ZGenerational -XX:ZMarkStackSpaceLimit=64G \
    pfl.result_analysis.LoopIterCountAnalysis \
    -profile_run_path $RESULT_DIR/traces/profile \
    -injection_run_path $RESULT_DIR/traces/injection_pass1  \
    -output_path $RESULT_DIR/analysis \
    -output_file_prefix "HDFS341_Pass1" \
    -profile_testplan /CSnake-AE/result_sample/static/profile_run.json \
    -injection_testplan /CSnake-AE/result_sample/static/injection_run_pass1.json \
    -per_injection_result $RESULT_DIR/analysis/per-injection \
    -nthread 12 \
    -v2_result \
    -exec_id_map /CSnake-AE/result_sample/static/exec_id_map.json
