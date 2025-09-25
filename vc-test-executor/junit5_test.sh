#!/bin/bash
PWD=$(pwd)
CP="$(cat $PWD/env/ozone140_docker_cp.txt)"
PROJECT_PATH=/ozone-1.4.0
JAR=$PROJECT_PATH/hadoop-ozone/integration-test/target/ozone-integration-test-1.4.0-tests.jar:$PROJECT_PATH/hadoop-ozone/ozonefs/target/ozone-filesystem-1.4.0.jar:$PROJECT_PATH/hadoop-ozone/ozonefs-common/target/ozone-filesystem-common-1.4.0.jar:$PROJECT_PATH/hadoop-ozone/client/target/ozone-client-1.4.0.jar:$PROJECT_PATH/hadoop-ozone/common/target/ozone-common-1.4.0.jar:$PROJECT_PATH/hadoop-ozone/csi/target/ozone-csi-1.4.0.jar:$PROJECT_PATH/hadoop-ozone/ozone-manager/target/ozone-manager-1.4.0.jar:$PROJECT_PATH/hadoop-hdds/client/target/hdds-client-1.4.0.jar:$PROJECT_PATH/hadoop-hdds/common/target/hdds-common-1.4.0.jar:$PROJECT_PATH/hadoop-hdds/framework/target/hdds-server-framework-1.4.0.jar:$PROJECT_PATH/hadoop-hdds/container-service/target/hdds-container-service-1.4.0.jar:$PROJECT_PATH/hadoop-hdds/server-scm/target/hdds-server-scm-1.4.0.jar
AGENT_PATH=/error-handler-analysis/fault-injection_old/code_under_test/byteman.jar
BOOTSTRAP_CP=$AGENT_PATH:/vc-bmhelper/target/vc-bmhelper-1.0-SNAPSHOT.jar
BASE_BTM=$PWD/btm/ozone140_base_profile.btm
EXECUTOR_JAR=$PWD/target/vc-test-executor-1.0-SNAPSHOT.jar
EXECUTOR_CP=$PWD/lib/jigawatts.jar
export LD_PRELOAD=/usr/lib/x86_64-linux-gnu/libcriu.so

export MALLOC_ARENA_MAX=4

JAVA_HOME=/usr/lib/jvm/java-1.8.0-openjdk-amd64 java -Xmx32G -XX:ActiveProcessorCount=6 \
    -Dhadoop.tmp.dir=/ozone-1.4.0/hadoop-ozone/integration-test/target/tmp \
    -Dhadoop.log.dir=/ozone-1.4.0/hadoop-ozone/integration-test/target/log \
    -Djava.net.preferIPv4Stack=true \
    -Djava.security.krb5.conf=/ozone-1.4.0/hadoop-ozone/integration-test/target/test-classes/krb5.conf \
    -Djava.security.egd=file:/dev/./urandom \
    -cp $JAR:$CP:$EXECUTOR_CP:$EXECUTOR_JAR pfl.test_execute.driver.JUnit5SingleTest "[engine:junit-jupiter]/[class:org.apache.hadoop.ozone.om.snapshot.TestOmSnapshotFsoWithNativeLib]/[method:testSnapshotDiffWhenOmRestart()]"
