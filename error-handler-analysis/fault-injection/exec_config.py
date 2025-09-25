RETRY_LIMIT = 3
GRPC_PORT = '50051'
GRPC_SERVER_ADDR = 'localhost'
PROJECT_UNDER_TEST_PATH = '/hadoop292-vctest'
SUB_PROJECT_PATH = 'hadoop-hdfs-project/hadoop-hdfs'
PROTOC_PATH = '/protoc'
BYTEMAN_AGENT = '/error-handler-analysis/fault-injection_old/code_under_test/byteman.jar'
BYTEMAN_HELPER = '/error-handler-analysis/fault-injection_old/code_under_test/vc-bmhelper-1.0-SNAPSHOT.jar'
OPENJ9_ARGS = "-javaagent:/error-handler-analysis/analyzer_test/OpenJ9Hack.jar -Xmx32G"

DEFAULT_TIMEOUT = 10 * 60 + 30