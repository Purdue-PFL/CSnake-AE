#!/bin/bash 
tmp_dir=$(mktemp -d)
cp ./target/error-handler-analysis-1.0-SNAPSHOT.jar $tmp_dir/
classpath=$tmp_dir/error-handler-analysis-1.0-SNAPSHOT.jar:$(cat ./exec_cp)

echo $tmp_dir

# HDFS-12914
# /usr/lib/jvm/java-11-openjdk-amd64/bin/java -cp $classpath -Xmx256G -XX:ActiveProcessorCount=48 pfl.LoopLinkThroughRPC \
#     -system HDFS292 \
#     -cp /home/qian151/research/vc-detect/target_bins/hdfs292/hadoop-hdfs-2.9.2.jar:/home/qian151/research/vc-detect/target_bins/hdfs292/hadoop-common-2.9.2.jar:/home/qian151/research/vc-detect/target_bins/hdfs292/hadoop-hdfs-client-2.9.2.jar \
#     -loop_input /home/qian151/research/vc-detect/error-handler-analysis/result/hdfs292/loops.json \
#     -saved_cfg /home/qian151/research/vc-detect/error-handler-analysis/result/hdfs292_cg_dynamic.obj  \
#     -output /home/qian151/research/vc-detect/error-handler-analysis/result/hdfs292/rpc_links.json

# HBase-8389
# /usr/lib/jvm/java-11-openjdk-amd64/bin/java @./argfile pfl.GetAllLoops \
#     -cp /local2/qian151/vc-detect-workspace/hbase8389-hbase/target/hbase-0.94.7.jar:/local2/qian151/vc-detect-workspace/hbase8389-hbase/target/dependency/hadoop-common-2.0.0-alpha.jar:/local2/qian151/vc-detect-workspace/hbase8389-hbase/target/dependency/hadoop-hdfs-2.0.0-alpha.jar \
#     -o /home/qian151/research/vc-detect/error-handler-analysis/result/loop_interference/hbase8389_loops.json

# HBase-2.6.0
# /usr/lib/jvm/java-11-openjdk-amd64/bin/java -cp $classpath -Xmx256G -XX:ActiveProcessorCount=48 pfl.LoopLinkThroughRPC \
#     -system HBase260 \
#     -cp /home/qian151/research/vc-detect/target_bins/hbase260/hbase-common-2.6.0.jar:/home/qian151/research/vc-detect/target_bins/hbase260/hbase-server-2.6.0.jar:/home/qian151/research/vc-detect/target_bins/hbase260/hbase-client-2.6.0.jar:/home/qian151/research/vc-detect/target_bins/hbase260/hbase-protocol-2.6.0.jar:/home/qian151/research/vc-detect/target_bins/hbase260/hbase-protocol-shaded-2.6.0.jar \
#     -loop_input /home/qian151/research/vc-detect/error-handler-analysis/result/hbase260/loops.json \
#     -saved_cfg /home/qian151/research/vc-detect/error-handler-analysis/result/hbase260/hbase260_cg_dynamic.obj  \
#     -output /home/qian151/research/vc-detect/error-handler-analysis/result/hbase260/rpc_links.json

# OZone140
# OZONE_PATH=/home/qian151/research/vc-detect/target_bins/ozone140/bin
# /usr/lib/jvm/java-11-openjdk-amd64/bin/java -cp $classpath -Xmx256G -XX:ActiveProcessorCount=48 pfl.LoopLinkThroughRPC \
#     -system OZone140 \
#     -cp $OZONE_PATH/hdds-client-1.4.0.jar:$OZONE_PATH/hdds-common-1.4.0.jar:$OZONE_PATH/hdds-container-service-1.4.0.jar:$OZONE_PATH/hdds-server-framework-1.4.0.jar:$OZONE_PATH/hdds-server-scm-1.4.0.jar:$OZONE_PATH/ozone-client-1.4.0.jar:$OZONE_PATH/ozone-common-1.4.0.jar:$OZONE_PATH/ozone-csi-1.4.0.jar:$OZONE_PATH/ozone-filesystem-1.4.0.jar:$OZONE_PATH/ozone-filesystem-common-1.4.0.jar:$OZONE_PATH/ozone-manager-1.4.0.jar \
#     -loop_input /home/qian151/research/vc-detect/error-handler-analysis/result/ozone140/loops.json \
#     -saved_cfg /home/qian151/research/vc-detect/error-handler-analysis/result/ozone140/ozone140_cg_dynamic.obj  \
#     -output /home/qian151/research/vc-detect/error-handler-analysis/result/ozone140/rpc_links.json

# HDFS341
HDFS341_PATH=/home/qian151/research/vc-detect/target_bins/hdfs341
/usr/lib/jvm/java-11-openjdk-amd64/bin/java -cp $classpath -Xmx256G -XX:ActiveProcessorCount=48 pfl.LoopLinkThroughRPC \
    -system HDFS341 \
    -cp $HDFS341_PATH/hadoop-common-3.4.1.jar:$HDFS341_PATH/hadoop-hdfs-3.4.1.jar:$HDFS341_PATH/hadoop-hdfs-client-3.4.1.jar \
    -loop_input /home/qian151/research/vc-detect/error-handler-analysis/result/hdfs341/loops.json \
    -saved_cfg /home/qian151/research/vc-detect/error-handler-analysis/result/hdfs341/hdfs341_cg_dynamic.obj \
    -output /home/qian151/research/vc-detect/error-handler-analysis/result/hdfs341/rpc_links.json \
    -search_radius 3
