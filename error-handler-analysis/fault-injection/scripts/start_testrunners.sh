#!/bin/bash
start_idx=$1
end_idx=$2
if [ -z ${3+x} ]; then
    target_sys=HDFS292
else
    target_sys=$3 
fi 
test_mode=$4 # Profile | Injection

if [[ $test_mode == Profile ]]; then
    btm_src_path=/CSnake-AE/result/btm/profile/base.btm
    btm_target_path=/vc-test-executor/btm/hdfs341_base_profile.btm
elif [[ $test_mode == Injection ]]; then 
    btm_src_path=/CSnake-AE/result/btm/injection_pass1/base.btm
    btm_target_path=/vc-test-executor/btm/hdfs341_base_injection.btm
else
    echo "Something Wrong"
fi 

HDFS341() {
    for ((i = $start_idx; i < $end_idx; i++)); do
        docker_name=executor_$i
        docker run --name $docker_name --privileged -v /dev/shm:/dev/shm --security-opt seccomp=unconfined -itd csnake_eurosys26ae bash
        docker cp $btm_src_path $docker_name:$btm_target_path
        docker exec -it $docker_name bash -c "cd /vc-bmhelper; mvn clean verify"
        docker exec -it $docker_name bash -c "cd /hdfs-3.4.1/hadoop-hdfs-project/hadoop-hdfs; mvn clean verify -DskipTests -Dprotoc.path=/protoc -Denforcer.skip"
        docker exec -it $docker_name bash -c "cd /hdfs-3.4.1/hadoop-hdfs-project/hadoop-hdfs-client; mvn clean verify -DskipTests -Dprotoc.path=/protoc -Denforcer.skip"
        docker exec -it $docker_name bash -c "cd /hdfs-3.4.1/hadoop-common-project/hadoop-common; mvn clean verify -DskipTests -Dprotoc.path=/protoc -Denforcer.skip"
        docker exec -it $docker_name bash -c "cd /vc-test-executor; mvn clean verify"
        screen -S $docker_name -d -m docker exec -it $docker_name bash -c "cd vc-test-executor; ./run_test.sh HDFS341 ${test_mode}"
    done
}

$target_sys
