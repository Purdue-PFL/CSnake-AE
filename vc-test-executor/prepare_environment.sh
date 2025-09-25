#!/bin/bash
cd /error-handler-analysis; git reset --hard; git pull
cd /hadoop292-vctest/hadoop-hdfs-project/hadoop-hdfs; git reset --hard; git pull
cd /vc-test-executor
mvn clean verify 
