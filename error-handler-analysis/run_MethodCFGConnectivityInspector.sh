#!/bin/bash

# HDFS-12914
/usr/lib/jvm/java-11-openjdk-amd64/bin/java @./argfile -Xmx256G -XX:ActiveProcessorCount=48 pfl.inspector.MethodCFGConnectivityInspector -saved_graph ./result/hdfs292_cg_01cfa_noreflection.obj -start_func 'org.apache.hadoop.hdfs.server.datanode.metrics.DataNodeDiskMetrics$1.run' -end_func 'org.apache.hadoop.hdfs.server.namenode.FSDirectory.resetLastInodeId'