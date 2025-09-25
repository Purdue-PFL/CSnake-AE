package pfl.util;

import java.io.FileNotFoundException;
import java.io.PrintWriter;

public class Debugger 
{
    public static final Debugger instance = new Debugger();
    public PrintWriter debugPW;
    public boolean FLAG = false;
    public boolean SINGLE_METHOD_DEBUG = false;
    public String classTarget = "FsDatasetImpl"; //BlockManager // NameNodeRpcServer
    public String methodTarget = "recoverCheck"; // verifyReplication // getBlocksWithLocations // verifyRequest

    // finally-if example: BlockScanner.addVolumeScanner L227 in Hadoop (hadoop-hdfs) 2.8.4
    // Correct example: BlockPlacementPolicyDefault.chooseTarget L385 in Hadoop (hadoop-hdfs) 2.8.4
    // assert example: BlockManager.removeBlocksAssociatedTo L1232 in Hadoop (hadoop-hdfs) 2.8.4

    private Debugger() 
    {
        try
        {
            debugPW = new PrintWriter("debug.log");
        }
        catch (FileNotFoundException e)
        {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }

    public static Debugger getInstance()
    {
        return instance;
    }

    public static void close()
    {
        instance.debugPW.close();
        instance.debugPW = null;
        instance.FLAG = false;
    }

    public static void setFlag()
    {
        instance.FLAG = true;
    }

    public static boolean getFlag()
    {
        return instance.FLAG;
    }

    public static void println(String s)
    {
        if (!instance.FLAG) return;
        instance.debugPW.println(s);
        instance.debugPW.flush();
    }
    
}

