#!/bin/bash
start_idx=$1
end_idx=$2

for ((i = $start_idx; i < $end_idx; i++)); do
    docker_name=executor_$i
    docker stop $docker_name
    docker rm $docker_name
    screen -X -S $docker_name quit 
done 
