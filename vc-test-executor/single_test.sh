#!/bin/bash
PWD=$(pwd)
FLINK_CP="$(cat $PWD/env/flink120_docker_cp.txt)"
FLINK_PROJECT_PATH=/flink-1.20.0
FLINK_JAR=$FLINK_PROJECT_PATH/flink-clients/target/flink-clients-1.20.0.jar:$FLINK_PROJECT_PATH/flink-core/target/flink-core-1.20.0.jar:$FLINK_PROJECT_PATH/flink-java/target/flink-java-1.20.0.jar:$FLINK_PROJECT_PATH/flink-rpc/flink-rpc-akka/target/flink-rpc-akka-1.20.0.jar:$FLINK_PROJECT_PATH/flink-rpc/flink-rpc-akka-loader/target/flink-rpc-akka-loader-1.20.0.jar:$FLINK_PROJECT_PATH/flink-rpc/flink-rpc-core/target/flink-rpc-core-1.20.0.jar:$FLINK_PROJECT_PATH/flink-runtime/target/flink-runtime-1.20.0.jar
FLINK_TEST_JAR=$FLINK_PROJECT_PATH/flink-core/target/flink-core-1.20.0-tests.jar:$FLINK_PROJECT_PATH/flink-java/target/flink-java-1.20.0-tests.jar:$FLINK_PROJECT_PATH/flink-runtime/target/flink-runtime-1.20.0-tests.jar:$FLINK_PROJECT_PATH/flink-tests/target/flink-tests-1.20.0-tests.jar:$FLINK_PROJECT_PATH/flink-test-utils-parent/flink-clients-test-utils/target/flink-clients-test-utils-1.20.0.jar:$FLINK_PROJECT_PATH/flink-test-utils-parent/flink-test-utils/target/flink-test-utils-1.20.0.jar:$FLINK_PROJECT_PATH/flink-test-utils-parent/flink-test-utils/target/flink-test-utils-1.20.0-tests.jar:$FLINK_PROJECT_PATH/flink-test-utils-parent/flink-test-utils-junit/target/flink-test-utils-junit-1.20.0.jar
AGENT_PATH=/error-handler-analysis/fault-injection_old/code_under_test/byteman.jar
BOOTSTRAP_CP=$AGENT_PATH:/vc-bmhelper/target/vc-bmhelper-1.0-SNAPSHOT.jar
BASE_BTM=$PWD/btm/flink120_base_profile.btm
EXECUTOR_JAR=$PWD/target/vc-test-executor-1.0-SNAPSHOT.jar
EXECUTOR_CP=$PWD/lib/jigawatts.jar
export LD_PRELOAD=/usr/lib/x86_64-linux-gnu/libcriu.so

JAVA_HOME=/usr/lib/jvm/java-1.8.0-openjdk-amd64 /usr/lib/jvm/java-8-openjdk-amd64/jre/bin/java -Xmx32G -XX:ActiveProcessorCount=6 \
    -Dflink.log.dir=$PWD/target/log \
    -Dflink.tmp.dir=$PWD/target/tmp \
    -Dtest.build.dir=$PWD/target/test-dir \
    -Dtest.build.data=$PWD/target/test/data \
    -Djava.net.preferIPv4Stack=true \
    -Djava.security.egd=file:///dev/urandom \
    -cp $FLINK_JAR:$FLINK_TEST_JAR:$FLINK_CP:$EXECUTOR_CP:$EXECUTOR_JAR pfl.test_execute.driver.RunSingleTest "org.apache.flink.test.checkpointing.StatefulJobWBroadcastStateMigrationITCase#testSavepoint[Test snapshot: flink1.13-rocksdb-savepoint]"

# JAVA_HOME=/usr/lib/jvm/java-1.8.0-openjdk-amd64 /usr/lib/jvm/java-8-openjdk-amd64/jre/bin/java -cp $FLINK_JAR:$FLINK_TEST_JAR:$FLINK_CP:/root/.m2/repository/junit/junit/4.13.2/junit-4.13.2.jar org.junit.runner.JUnitCore \
# "org.apache.flink.test.checkpointing.AutoRescalingITCase"
