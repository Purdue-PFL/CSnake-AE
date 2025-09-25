package pfl.bm;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.PrintWriter;
import java.io.Serializable;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.zip.GZIPOutputStream;

import org.apache.commons.lang3.concurrent.ConcurrentUtils;
import org.eclipse.collections.impl.list.mutable.ListAdapter;
import org.jboss.byteman.rule.Rule;
import org.jboss.byteman.rule.helper.Helper;

import com.google.common.collect.Streams;
import com.google.common.hash.HashCode;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

// A helper instance is created every time a rule using that helper is triggered i.e. every time control reaches an injection point
// If two rules are injected at the same point then a helper is created for each rule triggering.

public class VCHelper extends VCHelperBase
{
    public static Object globalMutex = new Object();
    public static String vcOutput = null;
    public static PrintWriter vcWriter = null;
    public static String VCWRITER_KEY = "org.jboss.byteman.vcWriterPath";
    public static String VCEXP_KEY = "org.jboss.byteman.expName";
    public static AtomicInteger injectCounter = new AtomicInteger(0);
    public static AtomicInteger vcHitCounter = new AtomicInteger(0);
    public static ConcurrentMap<StackTraceSignature, Integer> stackCounterMap = new ConcurrentHashMap<>();

    public Map<Integer, Object> ctorStepObjMap = new HashMap<>();
    public Map<Integer, String> ctorStepObjTypeMap = new HashMap<>();

    public VCHelper(Rule rule)
    {
        super(rule);
        // nextStackID.set(UUID.randomUUID());
        synchronized(globalMutex)
        {
            if ((vcOutput == null) && (System.getProperty(VCWRITER_KEY) != null))
            {
                System.out.println("###Byteman Loaded");
                vcOutput = System.getProperty(VCWRITER_KEY);
                try
                {
                    vcWriter = new PrintWriter(vcOutput + "/debug.log");
                    vcWriter.println(LocalDateTime.now());
                    vcWriter.flush();
                }
                catch (FileNotFoundException e)
                {
                    e.printStackTrace();
                }
                Runtime.getRuntime().addShutdownHook(new Thread() 
                {
                    @Override 
                    public void run() 
                    {
                        Map<String, Object> reportMap = new LinkedHashMap<>();
                        Map<String, List<List<String>>> stacktraceReportMap = new LinkedHashMap<>();
                        Map<String, Integer> stacktraceHitsReportMap = new LinkedHashMap<>();
                        reportMap.put("Injection", injectCounter.get());
                        reportMap.put("VCHit", vcHitCounter.get());
                        //vcWriter.println("Injection: " + injectCounter);
                        HashFunction hasher = Hashing.murmur3_128();
                        for (StackTraceSignature sig: stackCounterMap.keySet())
                        {
                            String sigStr = sig.toString();
                            String sigHash = hasher.hashBytes(sigStr.getBytes()).toString();
                            stacktraceHitsReportMap.put(sigHash, stackCounterMap.get(sig));
                            stacktraceReportMap.put(sigHash, sig.toList());
                        }
                        reportMap.put("StackHitStats", stacktraceHitsReportMap);
                        Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
                        try (PrintWriter pw = new PrintWriter(vcOutput + "/ExecReport.json"))
                        {
                            gson.toJson(reportMap, pw);
                        }
                        catch (FileNotFoundException e1)
                        {
                            e1.printStackTrace();
                        }
                        try (PrintWriter pw = new PrintWriter(vcOutput + "/StackTraces.json"))
                        {
                            gson.toJson(stacktraceReportMap, pw);
                        }
                        catch (FileNotFoundException e1)
                        {
                            e1.printStackTrace();
                        }
                        // for (StackTraceSignature sig: stackCounterMap.keySet())
                        // {
                        //     int count = stackCounterMap.get(sig);
                        //     vcWriter.println(sig.toString());
                        //     vcWriter.println("Count: " + count);
                        // }
                        vcWriter.flush();
                        try (FileOutputStream fos = new FileOutputStream(vcOutput + "/stackCounterMap.obj.gz"))
                        {
                            GZIPOutputStream gos = new GZIPOutputStream(fos);
                            ObjectOutputStream oos = new ObjectOutputStream(gos);
                            oos.writeObject(stackCounterMap);
                            oos.close();
                            gos.close();
                        }
                        catch (IOException e)
                        {
                            e.printStackTrace();
                        }
                    }
                });
            }
        }
    }

    public void createObject(String typeName, int[] args, int idx)
    {
        ctorStepObjTypeMap.put(idx, typeName);
        args = Arrays.copyOfRange(args, 1, args.length);
        Object obj = new Object();
        if (typeName.equals("java.lang.Byte"))
        {
            obj = (byte) 1;
        }
        else if (typeName.equals("java.lang.Character"))
        {
            obj = 'A';
        }
        else if (typeName.equals("java.lang.Double"))
        {
            obj = 1.0d;
        }
        else if (typeName.equals("java.lang.Float"))
        {
            obj = 1.0f;
        }
        else if (typeName.equals("java.lang.Integer"))
        {
            obj = 1;
        }
        else if (typeName.equals("java.lang.Long"))
        {
            obj = 1L;
        }
        else if (typeName.equals("java.lang.Short"))
        {
            obj = (short) 1;
        }
        else if (typeName.equals("java.lang.Boolean"))
        {
            obj = false;
        }
        else if (typeName.equals("java.lang.Double"))
        {
            obj = 1.0d;
        }
        else if (typeName.equals("java.lang.String"))
        {
            obj = "Injection!!";
        }
        else 
        {
            try
            {
                Class<?> c = Class.forName(typeName);
                Class<?>[] argClasses = Arrays.stream(args).mapToObj(i -> new Integer(i)).map(i -> {
                    try
                    {
                        return Class.forName(ctorStepObjTypeMap.get(i));
                    }
                    catch (ClassNotFoundException e)
                    {
                        return Object.class;
                    }
                }).toArray(Class<?>[]::new);
                Constructor<?> ctor = c.getConstructor(argClasses);
                Object[] ctorArgs = Arrays.stream(args).mapToObj(i -> new Integer(i)).map(i -> ctorStepObjMap.get(i)).toArray(Object[]::new);
                obj = ctor.newInstance(ctorArgs);
            }
            catch (ClassNotFoundException e)
            {
                e.printStackTrace();
            }
            catch (NoSuchMethodException e)
            {
                e.printStackTrace();
            }
            catch (SecurityException e)
            {
                e.printStackTrace();
            }
            catch (InstantiationException e)
            {
                e.printStackTrace();
            }
            catch (IllegalAccessException e)
            {
                e.printStackTrace();
            }
            catch (IllegalArgumentException e)
            {
                e.printStackTrace();
            }
            catch (InvocationTargetException e)
            {
                e.printStackTrace();
            }
        }
        ctorStepObjMap.put(idx, obj);
    }

    public Object getObject(int idx)
    {
        return ctorStepObjMap.get(idx);
    }

    public void checkVC()
    {
        if (DEBUG)
            System.out.println("VC Rule: " + rule.getName() + " TID: " + Thread.currentThread().getId());
        vcHitCounter.incrementAndGet();
        UUID lastStackID = stackSnapshot();
        StackTraceSignature signature = new StackTraceSignature(getAllStackUntilTop(lastStackID));
        synchronized (stackCounterMap)
        {
            if (!stackCounterMap.containsKey(signature))
                stackCounterMap.put(signature, 1);
            else
                stackCounterMap.put(signature, stackCounterMap.get(signature) + 1);
        }
        // int currenctStackCount = stackCounterMap.get(signature);
        // if (((currenctStackCount >= 2) && (currenctStackCount <= 5)) || ((currenctStackCount > 5) && (currenctStackCount % 10 == 0)))
        // {
        //     if (vcWriter != null)
        //     {
        //         vcWriter.println("VC Rule: " + this.rule.getName());
        //         vcWriter.println(signature.toString());
        //         vcWriter.println("Count: " + currenctStackCount + " Injection: " + injectCounter + "\n");
        //         vcWriter.flush();
        //     }
        // }
    }

    public void incrementInjectionCounter()
    {
        injectCounter.incrementAndGet();
    }
}



