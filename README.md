# Artifact for EuroSys'26 paper "CSnake: Detecting Self-Sustaining Cascading Failure via Causal Stitching of Fault Propagations"

[![DOI](https://zenodo.org/badge/DOI/10.5281/zenodo.17049892.svg)](https://doi.org/10.5281/zenodo.17049892)

# Prerequisites

All scripts are tested under Ubuntu 22.04. 

## Kernel rseq Support

You kernel must support `rseq`. On Ubuntu 22.04 with 5.15 kernel, it shuold be available by default. You can test it with the following script:

```bash
grep -i rseq /boot/config-`uname -r`
```

If you see

```bash
CONFIG_RSEQ=y
# CONFIG_DEBUG_RSEQ is not set
CONFIG_HAVE_RSEQ=y
```

You are good to go.

## Install Docker

Copy-pasted from [https://docs.docker.com/engine/install/ubuntu/#install-using-the-repository]().

```bash
# Add Docker's official GPG key:
sudo apt-get update
sudo apt-get install ca-certificates curl
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
sudo chmod a+r /etc/apt/keyrings/docker.asc

# Add the repository to Apt sources:
echo \
  "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu \
  $(. /etc/os-release && echo "${UBUNTU_CODENAME:-$VERSION_CODENAME}") stable" | \
  sudo tee /etc/apt/sources.list.d/docker.list > /dev/null
sudo apt-get update

sudo apt-get install docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
```

## Install Other Dependencies

```bash
apt install openjdk-8-jdk openjdk-8-dbg openjdk-21-jdk openjdk-21-dbg openjdk-11-jdk maven zstd python3 python3-pip screen
```

## Hardware Requirements

We expect a relativel well-equipped server instead of a personal computer. We recommend a server with at least 256GB of memory and SSD storage. Any recent Intel or AMD server-grade CPU will work.

# Minimum Working Example

For all the scripts, we assume that you have cloned this repo to `/CSnake-AE`. 

**Important:** All scripts assume this base directory. Moving things around can break things.

## Step 1: Run Static Analysis

```bash
cd /CSnake-AE/error-handler-analysis
JAVA_HOME=/usr/lib/jvm/java-1.11.0-openjdk-amd64 mvn clean verify
JAVA_HOME=/usr/lib/jvm/java-1.11.0-openjdk-amd64 mvn dependency:build-classpath -Dmdep.outputFile=./exec_cp

bash run_ThrowPointMain.sh
bash run_GetAllUnitTests.sh

cd /CSnake-AE/hdfs-3.4.1/hadoop-hdfs-project/hadoop-hdfs
JAVA_HOME=/usr/lib/jvm/java-1.8.0-openjdk-amd64 mvn dependency:build-classpath -Dmdep.outputFile=/CSnake-AE/result/static/host_cp
JAVA_HOME=/usr/lib/jvm/java-1.8.0-openjdk-amd64 mvn clean verify -DskipTests -Dprotoc.path=/CSnake-AE/protoc -Denforcer.skip
cd /CSnake-AE/error-handler-analysis
bash run_GetAllParameterizedTest.sh
python3 /CSnake-AE/error-handler-analysis/src/main/python/parameterized_unit_test_postprocessing.py /CSnake-AE/result/static
bash run_GetAllLoops.sh
bash run_GetBranchPoints.sh
bash run_GetNegatePoints.sh
bash run_MapMonitorHash.sh
bash run_MapThrowToInjectionBranch.sh
```

### Expected Result

You can ignore any output from running the scripts above. After you finish running all of them, you are expected to see these files under `/CSnake-AE/result`.

```bash
/CSnake-AE/result
└── static
    ├── branches.json
    ├── exec_id_map.json
    ├── hdfs341_cg_dynamic.obj.zst
    ├── host_cp
    ├── loops.json
    ├── negates.json
    ├── progress.log
    ├── test_classname_mapper.json
    ├── test_params.json
    ├── tests.json
    ├── tests_with_param.txt
    ├── throw_branch_pos.json
    └── throw.json
```

To reduce the number of tests and injection needed for this minimum example, we put a randomly sampled version of results in `/CSnake-AE/result_sample/static`. All subsequent tasks will use this folder.

## Step 2: Config File for Fault Injection

We have provided a configuration file here `/CSnake-AE/error-handler-analysis/fault-injection/run_config/HDFS341.ini`.

## Step 3: Generate Profile Run Test Plan and BTM Scripts

```bash
cd /CSnake-AE/error-handler-analysis/fault-injection
pip3 install mmh3 zstandard grpcio grpcio-tools
python3.10 01.generate_profile_run.py --target HDFS341
```

### Expected Output

* Profile run test plan: `/CSnake-AE/result_sample/static/profile_run.json`
* Base BTM file: `/CSnake-AE/result/btm/profile/base.btm`
* Injection BTM files: `/CSnake-AE/result/btm/profile/<HASH>.btm`

## Step 4: Run Profile Run

### Build the Docker Image 

```bash
docker run -itd --name execenv ubuntu:22.04 bash
cd /CSnake-AE
docker cp ./error-handler-analysis execenv:/
docker cp ./vc-bmhelper execenv:/
docker cp ./vc-test-executor execenv:/
docker cp ./jigawatts-criu execenv:/
docker cp ./protoc execenv:/
docker cp ./hdfs-3.4.1 execenv:/

docker exec -it execenv bash
apt update
apt install ca-certificates curl openjdk-8-jdk openjdk-8-dbg maven zstd python3 python3-pip screen software-properties-common automake autoconf pkg-config libprotobuf-dev libprotobuf-c-dev protobuf-c-compiler protobuf-compiler python3-protobuf pkg-config libbsd-dev iproute2 libnftables-dev libcap-dev libnl-3-dev libnet-dev libaio-dev libgnutls28-dev python3-future libdrm-dev iptables build-essential
add-apt-repository ppa:criu/ppa
apt update
apt install criu
cd /jigawatts-criu
./autogen.sh
export RPM_ARCH=amd64
export JAVA_HOME=/usr/lib/jvm/java-1.8.0-openjdk-amd64
mkdir build
cd build
../configure 
make
make install
update-java-alternatives -s java-1.8.0-openjdk-amd64
cd /vc-bmhelper
mvn clean verify
cd /vc-test-executor
mvn clean verify
exec <&-

docker commit execenv csnake_eurosys26ae
```

### Launch Test Server

In one terminal, run the following: 

```bash
cd /CSnake-AE/error-handler-analysis/fault-injection
python3.10 02.1.exec_server.py --target HDFS341 --run_type Profile
```

Keep it running while your test clients are running. You can run it inside a `screen` session if your Internet connection is unstable.

### Launch Test Clients

In another terminal, run the following:

```bash
cd /CSnake-AE/error-handler-analysis/fault-injection
./scripts/start_testrunners.sh 0 24 HDFS341 Profile
```

It will launch 24 test containers (ID 0 to 23). The launching of all the containers make take some time. You can run it inside a `screen` session to be safe.

If you machine is weak (e.g., short of memory), you can reduce the number of test containers started by reducing the number `24` to something lower. A server with 256GB of memory should be able to run at least 16 containers concurrently.

### Wait for the Execution to Finish

Trace files will be collected to `/CSnake-AE/result/traces/profile`. You need to manually monitor the content of `/CSnake-AE/result/traces/profile/progress.log`. 

If the contents stop growing for a while (e.g., 15 minutes) and you don't see `java` processes obtaining the CPU resources constantly (instead, they appear in short bursts). The execution is finished.

With 24 containers, this step should finish in less than 1 hour.

### Stop the Test Server and Clients

```bash
cd /CSnake-AE/error-handler-analysis/fault-injection
./scripts/stop_testrunners.sh 0 24
```

This will kill and remove all the containers just started. If you adjusted the `24` to something else, change it accordingly.

In your terminal running the test server (see above), use `Ctrl-C` to stop the script. 

### Expected Results

You can ignore any output from the above scripts. You are expected to see these things under `/CSnake-AE/result/traces/profile`.

```bash
/CSnake-AE/result/traces/profile
├── 00b4336cb369a83642aedc53da040614
│   ├── byteman.log
│   ├── IterEvents.bin
│   ├── IterIDStackMethodIdMap.bin
│   ├── junit.log
│   ├── LoopIDIterIDMap.bin
│   ├── MethodIdx.json
│   ├── rule.btm
│   ├── stdout.log
│   └── trace.jfr
├── 0131ded013c323673b99b0bf40ee1b59
│   ├── byteman.log
│   ├── IterEvents.bin
│   ├── IterIDStackMethodIdMap.bin
│   ├── junit.log
│   ├── LoopIDIterIDMap.bin
│   ├── MethodIdx.json
│   ├── rule.btm
│   ├── stdout.log
│   └── trace.jfr
...
└── progress.log
```


## Step 5: Analyze the Profile Run Traces

```bash
cd /CSnake-AE/vc-result-analysis/profile-run-analysis
JAVA_HOME=/usr/lib/jvm/java-1.11.0-openjdk-amd64 mvn clean verify
JAVA_HOME=/usr/lib/jvm/java-1.11.0-openjdk-amd64 mvn dependency:build-classpath -Dmdep.outputFile=./exec_cp

bash run_ProfileRunAnalysis.sh
```

### Expected Output

You are expected to see these things under `/CSnake-AE/result/analysis`.

```bash
/CSnake-AE/result/analysis
├── HDFS341_InjectionLoopsPerUnitTest.json
├── HDFS341_ProfileLoopSignature_PerInjectionID_InjectionLoop.obj
├── HDFS341_ReachableUnittestFromInjection_Coverage.json
├── HDFS341_ReachableUnittestFromInjection.json
├── HDFS341_ReachableUnittestFromLoop_Coverage.json
└── HDFS341_ReachableUnittestFromLoop.json
```

## Step 6: Generate Injection Run Test Plan and BTM Scripts

For a minimum example, we only run one pass of the injection in 3PA protocol.

```bash
cd /CSnake-AE/error-handler-analysis/fault-injection
python3.10 03.generate_injection.py --target HDFS341 --injection_type Pass1
```

### Expected Output

* Profile run test plan: `/CSnake-AE/result_sample/static/injection_run_pass1.json`
* Base BTM file: `/CSnake-AE/result/btm/injection_pass1/base.btm`
* Injection BTM files: `/CSnake-AE/result/btm/injection_pass1/<HASH>.btm`

## Step 7: Run Injection Run 

### Launch Test Server

In one terminal, run the following: 

```bash
cd /CSnake-AE/error-handler-analysis/fault-injection
python3.10 02.1.exec_server.py --target HDFS341 --run_type Pass1_Injection
```

Keep it running while your test clients are running. You can run it inside a `screen` session if your Internet connection is unstable.

### Launch Test Clients

In another terminal, run the following:

```bash
cd /CSnake-AE/error-handler-analysis/fault-injection
./scripts/start_testrunners.sh 0 24 HDFS341 Injection
```

It will launch 24 test containers (ID 0 to 23). The launching of all the containers make take some time. You can run it inside a `screen` session to be safe.

If you machine is weak (e.g., short of memory), you can reduce the number of test containers started by reducing the number `24` to something lower. A server with 256GB of memory should be able to run at least 16 containers concurrently.

### Wait for the Execution to Finish

Trace files will be collected to `/CSnake-AE/result/traces/injection_pass1`. You need to manually monitor the content of `/CSnake-AE/result/traces/injection_pass1/progress.log`. 

If the contents stop growing for a while (e.g., 15 minutes) and you don't see `java` processes obtaining the CPU resources constantly (instead, they appear in short bursts). The execution is finished.

With 24 containers, this step should finish in around 2-3 hours.

### Stop the Test Server and Clients

```bash
cd /CSnake-AE/error-handler-analysis/fault-injection
./scripts/stop_testrunners.sh 0 24
```

This will kill and remove all the containers just started. If you adjusted the `24` to something else, change it accordingly.

In your terminal running the test server (see above), use `Ctrl-C` to stop the script. 

### Expected Results

You can ignore any output from the above scripts. You are expected to see these things under `/CSnake-AE/result/traces/injection_pass1`.

```bash
/CSnake-AE/result/traces/injection_pass1
├── 0112d77cf783e49af1096fcd9c545b2b
│   ├── byteman.log
│   ├── IterEvents.bin
│   ├── IterIDStackMethodIdMap.bin
│   ├── junit.log
│   ├── LoopIDIterIDMap.bin
│   ├── MethodIdx.json
│   ├── rule.btm
│   └── stdout.log
├── 01dc3e0e9044307f2fc53a6d540250cb
│   ├── byteman.log
│   ├── IterEvents.bin
│   ├── IterIDStackMethodIdMap.bin
│   ├── junit.log
│   ├── LoopIDIterIDMap.bin
│   ├── MethodIdx.json
│   ├── rule.btm
│   └── stdout.log
...
└── progress.log
```

## Step 8: Analyze the Injection Run Traces

```bash
cd /CSnake-AE/vc-result-analysis/profile-run-analysis

bash run_ExecutionSignatureAnalysis.sh
bash run_LoopIterCountAnalysis.sh
bash run_NestedLoopIterInc.sh
```

### Expected Output

You can ignore any output from the scripts. After running all the scripts, the following content is expected under `/CSnake-AE/result/analysis`.

```bash
/CSnake-AE/result/analysis
├── HDFS341_InjectionLoopsPerUnitTest.json
├── HDFS341_Pass1_LoopInterference.json
├── HDFS341_Pass1_LoopIterCountInterference.json
├── HDFS341_Pass1_loopTID.json
├── HDFS341_ProfileLoopSignature_PerInjectionID_InjectionLoop.obj
├── HDFS341_ReachableUnittestFromInjection_Coverage.json
├── HDFS341_ReachableUnittestFromInjection.json
├── HDFS341_ReachableUnittestFromLoop_Coverage.json
├── HDFS341_ReachableUnittestFromLoop.json
└── per-injection
    ├── 01dc3e0e9044307f2fc53a6d540250cb_all_callsite.json
    ├── 01dc3e0e9044307f2fc53a6d540250cb_callsite.json
    ├── 01dc3e0e9044307f2fc53a6d540250cb_itercount.json
    ├── 01dc3e0e9044307f2fc53a6d540250cb.json
    ├── 01dc3e0e9044307f2fc53a6d540250cb_parent_delay.json
    ├── 01dc3e0e9044307f2fc53a6d540250cb_sigdiff.obj.zst
    ...
    ├── ff3f336572a69fa8a07ee871e29ab196_all_callsite.json
    ├── ff3f336572a69fa8a07ee871e29ab196_callsite.json
    ├── ff3f336572a69fa8a07ee871e29ab196_itercount.json
    ├── ff3f336572a69fa8a07ee871e29ab196.json
    ├── ff3f336572a69fa8a07ee871e29ab196_parent_delay.json
    └── ff3f336572a69fa8a07ee871e29ab196_sigdiff.obj.zst
```

## Step 9: Run Parallel BFS

```bash
cd /CSnake-AE/vc-result-analysis/loop-interference-analysis
JAVA_HOME=/usr/lib/jvm/java-1.11.0-openjdk-amd64 mvn clean verify
JAVA_HOME=/usr/lib/jvm/java-1.11.0-openjdk-amd64 mvn dependency:build-classpath -Dmdep.outputFile=./exec_cp

bash run_BuildCFG.sh
bash run_GuidedSearchV3.sh
```

### Expected Output

No potential cycles reported: `Cycles Found: 0`. The result could vary due to nondeterminism in the test execution.
