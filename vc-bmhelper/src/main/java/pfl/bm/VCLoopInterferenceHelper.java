package pfl.bm;

import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.PrintWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.zip.GZIPOutputStream;

import org.apache.commons.lang3.concurrent.AtomicSafeInitializer;
import org.apache.commons.lang3.concurrent.ConcurrentException;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.MutablePair;
import org.jboss.byteman.rule.Rule;
import org.jboss.byteman.rule.helper.Helper;

import com.google.common.hash.HashCode;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.stream.JsonWriter;

import pfl.bm.events.BranchEvent;
import pfl.bm.events.InjectionEvent;
import pfl.bm.events.IterEventBase;
import pfl.bm.events.LinkEvent;

public class VCLoopInterferenceHelper extends Helper
{
    public static boolean DEBUG = false;

    public static Object globalMutex = new Object();
    public static String VCEXP_KEY = "org.jboss.byteman.expName";
    public static String VCOUTPUT_KEY = "org.jboss.byteman.vcWriterPath";
    public static String INJECTION_TIME_KEY = "org.jboss.byteman.injectionTime";
    public static Long injectionMs = null;
    public static String outputPath = null;
    public static AtomicBoolean startInjection = new AtomicBoolean(false);
    public static Instant injectionStartTime = null;
    public static Object injectionStartTimeMutex = new Object();
    public static int LOOP_CALLER_FRAME_COUNT = 2;
    public static AtomicBoolean hasDumped = new AtomicBoolean(false);

    public VCLoopInterferenceHelper(Rule rule)
    {
        super(rule);
        synchronized (globalMutex)
        {
            if ((outputPath == null) && (System.getProperty(VCOUTPUT_KEY) != null))
            {
                System.out.println("###Byteman loaded");
                outputPath = System.getProperty(VCOUTPUT_KEY);
                new File(outputPath).mkdirs();
                injectionMs = Long.parseLong(System.getProperty(INJECTION_TIME_KEY));
            }
        }
    }

    public static void clearEnvironment()
    {
        synchronized (globalMutex)
        {
            injectionMs = null;
            outputPath = null;
            startInjection = new AtomicBoolean(false);
            synchronized (injectionStartTimeMutex)
            {
                injectionStartTime = null;
            }
            loopIDIterIDMap = new ConcurrentHashMap<>();
            parentIterIDMap = new ConcurrentHashMap<>();
            iterEventsMap = new ConcurrentHashMap<>();
            iterIDTIDMap = new ConcurrentHashMap<>();
            runnableMap = new ConcurrentHashMap<>(); 
            wrappedRunnableMap = new ConcurrentHashMap<>(); 
            pendingLinkEvents = new ConcurrentHashMap<>();
            eventObjPool = new ConcurrentHashMap<>();
            iterIDStackMethodIdMap = new ConcurrentHashMap<>();
            methodToMethodIdxMap = new ConcurrentHashMap<>();
            methodIdxCounter = new AtomicInteger();
            loopCountMap = new ConcurrentHashMap<>();
            hasDumped.set(false);
        }
    }

    public void dumpResult()
    {
        synchronized (globalMutex)
        {
            System.out.println("### Dump");
            if (hasDumped.get()) 
            {
                System.out.println("Skipping because has dumped once");
                return;
            }

            /*
             * Map<String, Collection<UUID>> loopIDIterIDMap
             * Format: 
             * | 0x884832CB | 0x00000002 (Ver 2.0) | 64 bit Map length | key1: 32bit Mapped LoopHash | 64 bit Value List Length | value1: 128bit UUID | value2: 128bit UUID |
             * | key2: ...  |
             */
            try (FileOutputStream fos = new FileOutputStream(outputPath + "/LoopIDIterIDMap.bin"))
            {
                BufferedOutputStream bos = new BufferedOutputStream(fos);
                int magicNumber = 0x884832CB;
                bos.write(Utils.intToByteArray(magicNumber));
                int version = 0x00000002;
                bos.write(Utils.intToByteArray(version));
                bos.write(Utils.longToByteArray(Long.valueOf(loopIDIterIDMap.size())));
                for (int loopKey: loopIDIterIDMap.keySet())
                {
                    bos.write(Utils.intToByteArray(loopKey));
                    bos.write(Utils.longToByteArray(loopIDIterIDMap.get(loopKey).size()));
                    for (UUID iterID: loopIDIterIDMap.get(loopKey))
                    {
                        bos.write(Utils.uuidAsBytes(iterID));
                    }
                }
                bos.close();
            }
            catch (IOException e)
            {
            }
    
            /* 
             * Map<UUID, int[]> iterIDStackMethodIdMap iterIDStackMethodIdMap
             * Format:
             * | 0x334832CB | 0x00000001 (Ver 1.0) | 64 bit Map length | 32bit caller count | key1: 128bit UUID | value1: 32bit method ID | value2: 32bit method ID | ... |
             * | key2: ...  |
             */
            try (FileOutputStream fos = new FileOutputStream(outputPath + "/IterIDStackMethodIdMap.bin");
                 BufferedOutputStream bos = new BufferedOutputStream(fos))
            {
                int magicNumber = 0x334832CB;
                bos.write(Utils.intToByteArray(magicNumber));
                int version = 0x00000001;
                bos.write(Utils.intToByteArray(version));
                bos.write(Utils.longToByteArray(Long.valueOf(iterIDStackMethodIdMap.size())));
                bos.write(Utils.intToByteArray(LOOP_CALLER_FRAME_COUNT));
                for (UUID iterID: iterIDStackMethodIdMap.keySet())
                {
                    bos.write(Utils.uuidAsBytes(iterID));
                    bos.write(Utils.intArrayToByteArray(iterIDStackMethodIdMap.get(iterID)));
                }
                bos.flush();
                fos.flush();
            }
            catch (IOException e)
            {
            }
    
            // public static Map<String, Integer> methodToMethodIdxMap = new ConcurrentHashMap<>();
            try (FileWriter fw = new FileWriter(outputPath + "/MethodIdx.json");
                 BufferedWriter bw = new BufferedWriter(fw);)
            {
                SortedMap<Integer, String> methodIdxMap = new TreeMap<>();
                methodToMethodIdxMap.entrySet().forEach(e -> methodIdxMap.put(e.getValue(), e.getKey()));
                Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
                gson.toJson(methodIdxMap, bw);
            }
            catch (IOException e)
            {
            }
    
            /* 
             * Map<UUID, List<IterEventBase>> iterEventsMap
             * Format:
             * | 0x044832CB | 0x00000002 (Ver 2.0) | 64 bit Map length | key1: 128bit Iter UUID | 128bit Parent Iter UUID | 64bit TID |
             * | 64 bit Value List Length | value1: 8bit Type (B/I/L) + 128bit ID(L) / 32bit Mapped ID (B/I) | value2 ... |
             * | key2: ...  |
             */
            try (FileOutputStream fos = new FileOutputStream(outputPath + "/IterEvents.bin"))
            {
                BufferedOutputStream bos = new BufferedOutputStream(fos);
                int magicNumber = 0x044832CB;
                bos.write(Utils.intToByteArray(magicNumber));
                int version = 0x00000002;
                bos.write(Utils.intToByteArray(version));
                bos.write(Utils.longToByteArray(Long.valueOf(iterEventsMap.size())));
                for (UUID key: iterEventsMap.keySet())
                {
                    bos.write(Utils.uuidAsBytes(key));
                    bos.write(Utils.uuidAsBytes(parentIterIDMap.getOrDefault(key, new UUID(0, 0))));
                    bos.write(Utils.longToByteArray(iterIDTIDMap.getOrDefault(key, -1L)));
                    bos.write(Utils.longToByteArray(Long.valueOf(iterEventsMap.get(key).size())));
                    for (IterEventBase event: iterEventsMap.get(key))
                    {
                        bos.write(event.toByteArray());
                    }
                }
                bos.close();
            }
            catch (IOException e)
            {
            }

            hasDumped.set(true);
    
        }
    }

    // #region Fault Injection
    public Map<Integer, Object> ctorStepObjMap = new HashMap<>();
    public Map<Integer, String> ctorStepObjTypeMap = new HashMap<>();

    public void startInjection()
    {
        startInjection.set(true);
    }

    public void stopInjection()
    {
        startInjection.set(false);
        synchronized (injectionStartTimeMutex)
        {
            injectionStartTime = null;
        }
    }

    public boolean stillInject(String callerName, int frameCount)
    {
        boolean callerEquals = callerEquals(callerName, true, true, 0, frameCount);
        return callerEquals && stillInject();
    }

    public boolean stillInject(String callerName, int frameCount, int injectionProbabilityPctg)
    {
        return stillInject(injectionProbabilityPctg) && callerEquals(callerName, true, true, 0, frameCount);
    }

    public boolean stillInject(int injectionProbabilityPctg)
    {
        if (!startInjection.get()) return false;
        int randomValue = ThreadLocalRandom.current().nextInt(100);
        return randomValue < injectionProbabilityPctg;
    }

    public boolean stillInject()
    {
        // System.out.println("### Byteman: stillInject()");
        // return startInjection.get();
        if (!startInjection.get()) return false;
        Instant startTime;
        synchronized (injectionStartTimeMutex)
        {
            if (injectionStartTime == null) injectionStartTime = Instant.now();
            startTime = injectionStartTime;
        }
        Instant nowTime = Instant.now();
        long injectionElapsedMs = Duration.between(startTime, nowTime).toMillis();
        // System.out.println("### Byteman: stillInject() " + (injectionElapsedMs <= injectionMs));
        return injectionElapsedMs <= injectionMs;
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
                Class<?>[] argClasses = Arrays.stream(args).mapToObj(i -> new Integer(i)).map(i ->
                {
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

    public void busyLoop(long delayMs)
    {
        if (!startInjection.get()) return;
        Instant startTime = Instant.now();
        while (true)
        {
            Instant now = Instant.now();
            long delta = Duration.between(startTime, now).toMillis();
            if (delta >= delayMs) break;
        }
    }

    // #endregion Fault Injection

    // #region EventRecorder

    public static ThreadLocal<Deque<MutablePair<Integer, UUID>>> loopIDStack = ThreadLocal.withInitial(() -> new LinkedList<>()); // Stack of the loop IterID per thread, top of the
                                                                                                                                 // stack at front
    public static ThreadLocal<Deque<int[]>> stackMethodIdStack = ThreadLocal.withInitial(() -> new LinkedList<>());
    public static ThreadLocal<UUID> currentIterID = ThreadLocal.withInitial(() -> null);
    public static ThreadLocal<UUID> currentParentIterID = ThreadLocal.withInitial(() -> null);
    public static Map<Integer, Collection<UUID>> loopIDIterIDMap = new ConcurrentHashMap<>(); // LoopID -> [IterID]
    public static Map<UUID, List<IterEventBase>> iterEventsMap = new ConcurrentHashMap<>(); // IterID -> [IterEvents]
    public static Map<UUID, UUID> parentIterIDMap = new ConcurrentHashMap<>(); // IterID -> ParentIterID 
    public static Map<UUID, Long> iterIDTIDMap = new ConcurrentHashMap<>(); // IterID -> TID
    public static Map<Integer, UUID> runnableMap = new ConcurrentHashMap<>(); // Runnable Hash -> ParentIterID
    public static Map<Integer, Integer> wrappedRunnableMap = new ConcurrentHashMap<>(); // RunnalbeHash -> ThreadHash
    public static Map<ImmutablePair<UUID, Integer>, LinkEvent> pendingLinkEvents = new ConcurrentHashMap<>(); // (ParentIterId, RunnableHash) -> LINK event with null childIterID
    public static Map<IterEventBase, IterEventBase> eventObjPool = new ConcurrentHashMap<>();
    public static Map<UUID, int[]> iterIDStackMethodIdMap = new ConcurrentHashMap<>();
    public static Map<String, Integer> methodToMethodIdxMap = new ConcurrentHashMap<>();
    public static AtomicInteger methodIdxCounter = new AtomicInteger();
    public static Map<Integer, AtomicInteger> loopCountMap = new ConcurrentHashMap<>(); // LoopID -> LoopCount

    public void startLoop(Integer loopID)
    {
        currentIterID.set(UUID.randomUUID());
        UUID currentIterIDVal = currentIterID.get();
        MutablePair<Integer, UUID> currentStackTopLoopIDIterID = loopIDStack.get().peekFirst();
        Integer currentStackTopLoopID = null;
        UUID currentStackTopIterID = null;
        if (currentStackTopLoopIDIterID != null) 
        {
            currentStackTopLoopID = currentStackTopLoopIDIterID.getLeft();
            currentStackTopIterID = currentStackTopLoopIDIterID.getRight();
        }    
        if (!loopID.equals(currentStackTopLoopID)) // Enter nested loop, push to stack
        {
            loopIDStack.get().addFirst(MutablePair.of(loopID, currentIterIDVal));
            currentParentIterID.set(currentStackTopIterID);
            stackMethodIdStack.get().addFirst(null);
        }
        else // Update IterID only, currentParentIterID should already been set
        {
            currentStackTopLoopIDIterID.setRight(currentIterIDVal);
        }

        if (currentParentIterID.get() == null) currentParentIterID.set(new UUID(0, 0));
        parentIterIDMap.put(currentIterIDVal, currentParentIterID.get());
        iterIDTIDMap.put(currentIterIDVal, Thread.currentThread().getId());
        loopIDIterIDMap.computeIfAbsent(loopID, k -> new ConcurrentLinkedQueue<>()).add(currentIterIDVal);

        // Sample the caller of this loop
        int currentLoopIterCount = loopCountMap.computeIfAbsent(loopID, k -> new AtomicInteger(0)).incrementAndGet();
        boolean shouldGetCaller = true;
        if ((currentLoopIterCount > 100) && (currentLoopIterCount <= 500))
            shouldGetCaller = Math.random() < 0.1;
        else if ((currentLoopIterCount > 500) && (currentLoopIterCount <= 1000))
            shouldGetCaller = Math.random() < 0.05;
        else if (currentLoopIterCount > 1000)
            shouldGetCaller = Math.random() < 0.01;
        if (shouldGetCaller)
        {
            // callers can be cached, because in each invocation of the loop, the callers are the same
            int[] callers = stackMethodIdStack.get().peekFirst();
            if (callers == null)
            {
                callers = new int[LOOP_CALLER_FRAME_COUNT];
                Arrays.fill(callers, -1);
                StackTraceElement[] currentStack = getStack();
                int triggerIndex = triggerIndex(currentStack);
                int startFrameNo = triggerIndex + 1; // Start from the caller of the current loop's enclosing method
                int endFrameNoExcl = Math.min(startFrameNo + LOOP_CALLER_FRAME_COUNT, currentStack.length);
                
                for (int i = startFrameNo; i < endFrameNoExcl; i++)
                {
                    int callerIdx = i - startFrameNo;
                    StackTraceElement frame = currentStack[i];
                    String callerMethodName = frame.getClassName() + "." + frame.getMethodName();
                    int callerMethodIdx = methodToMethodIdxMap.computeIfAbsent(callerMethodName, k -> methodIdxCounter.getAndIncrement());
                    callers[callerIdx] = callerMethodIdx;
                }
                stackMethodIdStack.get().pollFirst();
                stackMethodIdStack.get().addFirst(callers);

            }
            iterIDStackMethodIdMap.put(currentIterIDVal, callers);
        }
    }

    public void visitBranch(int branchID)
    {
        if (currentIterID.get() == null) return;
        BranchEvent event = new BranchEvent(branchID);
        if (eventObjPool.containsKey(event))
            event = (BranchEvent) eventObjPool.get(event);
        else 
            eventObjPool.put(event, event);
        iterEventsMap.computeIfAbsent(currentIterID.get(), k -> new ArrayList<>()).add(event);
    }

    public void threadCreate(Object r)
    {
        if (currentIterID.get() == null) return;
        int currentRunnableHash = System.identityHashCode(r);
        runnableMap.put(currentRunnableHash, currentIterID.get());
        LinkEvent event = new LinkEvent(currentRunnableHash, null);
        iterEventsMap.computeIfAbsent(currentIterID.get(), k -> new ArrayList<>()).add(event);
        pendingLinkEvents.put(ImmutablePair.of(currentIterID.get(), currentRunnableHash), event);
    }

    public void mapRunnableToThread(Thread t, Runnable r)
    {
        if (currentIterID.get() == null) return;
        if (DEBUG)
            System.out.println("###RunnableMapping: " + System.identityHashCode(r) + "(R) TO " + System.identityHashCode(t) + "(T)");
        wrappedRunnableMap.put(System.identityHashCode(r), System.identityHashCode(t));
    }

    public void threadExecute(Object r)
    {
        int runnableHash = System.identityHashCode(r);
        if (!runnableMap.containsKey(runnableHash))
        {
            if (DEBUG)
                System.out.println("###GET: Runnable " + runnableHash + " NOT EXIST!!");
            // if Runnable is not there, maybe it's wrapped inside a Thread
            // Find the thread hash by map
            if (!wrappedRunnableMap.containsKey(runnableHash))
                return;
            runnableHash = wrappedRunnableMap.get(runnableHash);
            if (!runnableMap.containsKey(runnableHash))
                return;
        }
        UUID parentIterID = runnableMap.get(runnableHash);
        if (parentIterID == null) return;
        ImmutablePair<UUID, Integer> pendingLinkEventKey = ImmutablePair.of(parentIterID, runnableHash);
        LinkEvent parentLinkEvent = pendingLinkEvents.get(pendingLinkEventKey);
        if (parentLinkEvent == null) return;
        currentIterID.set(UUID.randomUUID());
        parentLinkEvent.childIterID = currentIterID.get();
        pendingLinkEvents.remove(pendingLinkEventKey);
    }

    public void endLoop(int loopID)
    {
        if (currentIterID.get() == null) return;
        MutablePair<Integer, UUID> currentLoopIDIterID = loopIDStack.get().peekFirst();
        if ((currentLoopIDIterID == null) || (!currentLoopIDIterID.getLeft().equals(loopID))) return; //We should not pop the loop stack because the loopID does not match
        loopIDStack.get().pollFirst();
        MutablePair<Integer, UUID> parentLoopIDIterID = loopIDStack.get().peekFirst();
        if (parentLoopIDIterID == null)
        {
            currentIterID.set(null);
        }
        else
        {
            currentIterID.set(parentLoopIDIterID.getRight());
        }

        // Reset Parent IterID
        List<MutablePair<Integer, UUID>> loopIDStackList = (LinkedList<MutablePair<Integer, UUID>>) loopIDStack.get();
        if (loopIDStackList.size() >= 2) // Has parent loop
        {
            currentParentIterID.set(loopIDStackList.get(1).getRight());
        }
        else // No parent loop 
        {
            currentParentIterID.set(null);
        }
        stackMethodIdStack.get().pollFirst();
    }

    public void recordInjectionEvent(int injectionID)
    {
        if (currentIterID.get() == null) return;
        InjectionEvent event = new InjectionEvent(injectionID);
        if (eventObjPool.containsKey(event))
            event = (InjectionEvent) eventObjPool.get(event);
        else 
            eventObjPool.put(event, event);
        iterEventsMap.computeIfAbsent(currentIterID.get(), k -> new ArrayList<>()).add(event);
    }

    // #endregion EventRecorder

    // #region Utils

    protected StackTraceSnapshot stackSnapshot()
    {
        StackTraceElement[] stack = getStack();
        return new StackTraceSnapshot(stack);
    }

    public boolean callerClassEquals(Object o, String classFullName)
    {
        Class<?> clazz = o.getClass();
        return Objects.equals(classFullName, clazz.getName());
    }

    // #endregion Utils
}

class InjectionStartTime extends AtomicSafeInitializer<Instant>
{
    @Override
    protected Instant initialize()
    {
        return Instant.now();
    }
}
