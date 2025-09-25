#!/bin/bash
tmp_dir=$(mktemp -d)
cp ./target/profile-run-analysis-1.0-SNAPSHOT.jar $tmp_dir/
classpath=$tmp_dir/profile-run-analysis-1.0-SNAPSHOT.jar:$(cat ./exec_cp)
echo $tmp_dir

RESULT_DIR=/CSnake-AE/result

# HDFS341
/usr/lib/jvm/java-1.21.0-openjdk-amd64/bin/java -cp $classpath -Xmx128G -XX:+UnlockExperimentalVMOptions -XX:+UseZGC -XX:+UseDynamicNumberOfGCThreads -XX:+ZGenerational -XX:ZMarkStackSpaceLimit=64G pfl.result_analysis.ProfileRunAnalysis \
    -p $RESULT_DIR/traces/profile \
    -o $RESULT_DIR/analysis \
    -output_prefix "HDFS341" \
    -profile_testplan /CSnake-AE/result_sample/static/profile_run.json \
    -v2_result \
    -exec_id_map /CSnake-AE/result_sample/static/exec_id_map.json \
    -nthread 12

