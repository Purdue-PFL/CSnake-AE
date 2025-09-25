package pfl.test_execute;

public class Config 
{
    public static int GRPC_PORT = 50051;
    public static String GRPC_SERVER_ADDR = Utils.getDocketHostIPAddr();
    public static Long DEFAULT_TIMEOUT_MS = (10 * 60 + 30L) * 1000;
}
