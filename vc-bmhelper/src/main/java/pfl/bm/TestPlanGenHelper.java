package pfl.bm;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;
import java.util.zip.GZIPOutputStream;

import org.jboss.byteman.rule.Rule;
import org.jboss.byteman.rule.helper.Helper;

import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

public class TestPlanGenHelper extends VCHelperBase
{
    public Object globalMutex = new Object();
    public static Set<String> errorHandlerHits = ConcurrentHashMap.newKeySet();
    public static boolean firstExecuted = false;
    public static String VCWRITER_KEY = "org.jboss.byteman.vcWriterPath";
    public static ConcurrentMap<String, StackTraceSignature> hitStackMap = new ConcurrentHashMap<>();

    public TestPlanGenHelper(Rule rule)
    {
        super(rule);
        synchronized (globalMutex)
        {
            if ((!firstExecuted) && (System.getProperty(VCWRITER_KEY) != null))
            {
                firstExecuted = true;
                Runtime.getRuntime().addShutdownHook(new Thread() 
                {
                    @Override 
                    public void run() 
                    {
                        try (PrintWriter pw = new PrintWriter(System.getProperty(VCWRITER_KEY) + "/ErrorHandlerHits.json"))
                        {
                            Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
                            gson.toJson(errorHandlerHits.toArray(), pw);
                        }
                        catch (FileNotFoundException e)
                        {
                            e.printStackTrace();
                        }
                        try (PrintWriter pw = new PrintWriter(System.getProperty(VCWRITER_KEY) + "/EHHitsStack.json"))
                        {
                            Map<String, List<List<String>>> o = hitStackMap.entrySet().stream().collect(Collectors.toMap(e -> e.getKey(), e -> e.getValue().toList()));
                            Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
                            gson.toJson(o, pw);
                        }
                        catch (FileNotFoundException e)
                        {
                            e.printStackTrace();
                        }
                    }
                });
            }
        }
    }

    public void hit(String key)
    {
        errorHandlerHits.add(key);
        UUID lastStackID = stackSnapshot();
        StackTraceSignature signature = new StackTraceSignature(getAllStackUntilTop(lastStackID));
        hitStackMap.put(key, signature);
    }
    
}
