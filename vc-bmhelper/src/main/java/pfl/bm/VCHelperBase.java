package pfl.bm;

import java.util.LinkedList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.jboss.byteman.rule.Rule;
import org.jboss.byteman.rule.helper.Helper;

public abstract class VCHelperBase extends Helper
{
    public static boolean DEBUG = false;

    public static ThreadLocal<UUID> nextStackID = ThreadLocal.withInitial(() -> UUID.randomUUID());
    public static ConcurrentHashMap<UUID, UUID> stLinkMap = new ConcurrentHashMap<>(); // Child ST -> Parent ST
    public static ConcurrentHashMap<UUID, StackTraceSnapshot> stacktraceMap = new ConcurrentHashMap<>(); // ST_ID -> ST
    public static ConcurrentHashMap<Integer, UUID> runnableHashMap = new ConcurrentHashMap<>(); // Runnable Hash -> parent ST_ID (Set in Executor.submit())
    // Thread can be created by providing a Runnable object.
    // Calling Thread.start() indirectly calls the wrapped Runnable.run(), but the Thread and the wrapped Runnable are different objects.
    // We need to map the Runnable to the Thread object. If Thread.run() is overriden, it doesn't matter because Thread itself is a Runnable.
    // At Thread.<init>, make the mapping
    // At Thread.start(), take a stack snapshot
    // At Runnable.run(), allocate a new STID
    public static ConcurrentHashMap<Integer, Integer> wrappedRunnableMap = new ConcurrentHashMap<>(); // Runnable Hash -> Thread Hash

    public VCHelperBase(Rule rule)
    {
        super(rule);
    }

    protected UUID stackSnapshot()
    {
        StackTraceElement[] stack = getStack();
        UUID curStackID = nextStackID.get();
        stacktraceMap.put(curStackID, new StackTraceSnapshot(stack));
        nextStackID.set(UUID.randomUUID());
        if (DEBUG)
        {
            System.out.println("###Snapshot: " + curStackID + " TID: " + Thread.currentThread().getId());
            System.out.println(stacktraceMap.get(curStackID));
            System.out.println("###Snapshot: " + curStackID + " TID: " + Thread.currentThread().getId() + " END");
        }
        return curStackID;
    }

    protected List<StackTraceSnapshot> getAllStackUntilTop(UUID lastStackID)
    {
        List<StackTraceSnapshot> r = new LinkedList<>();
        if (stacktraceMap.containsKey(lastStackID))
            r.add(0, stacktraceMap.get(lastStackID));
        UUID cur = lastStackID;
        while (stLinkMap.containsKey(cur))
        {
            cur = stLinkMap.get(cur);
            if (stacktraceMap.containsKey(cur))
                r.add(0, stacktraceMap.get(cur));
        }
        return r;
    }

    public void threadCreate(Object r)
    {
        UUID stackID = stackSnapshot();
        runnableHashMap.put(System.identityHashCode(r), stackID);
        if (DEBUG)
            System.out.println(
                    "###PUT: Runnable " + System.identityHashCode(r) + " STID: " + stackID + " Type: " + r.getClass().getTypeName() + " TID: " + Thread.currentThread().getId());
    }

    public void threadExecute(Object r)
    {
        nextStackID.set(UUID.randomUUID());
        linkRunnableStackTrace(r);
    }

    public void mapRunnableToThread(Thread t, Runnable r)
    {
        if (DEBUG)
            System.out.println("###RunnableMapping: " + System.identityHashCode(r) + "(R) TO " + System.identityHashCode(t) + "(T)");
        wrappedRunnableMap.put(System.identityHashCode(r), System.identityHashCode(t));
    }

    protected void linkRunnableStackTrace(Object r)
    {
        int runnableHash = System.identityHashCode(r);
        if (DEBUG)
            System.out.println("###GET: Runnable " + runnableHash + " Type: " + r.getClass().getTypeName() + " TID: " + Thread.currentThread().getId());
        UUID curStackID = nextStackID.get();
        if (!runnableHashMap.containsKey(runnableHash))
        {
            if (DEBUG)
                System.out.println("###GET: Runnable " + runnableHash + " NOT EXIST!!");
            // if Runnable is not there, maybe it's wrapped inside a Thread
            // Find the thread hash by map
            if (!wrappedRunnableMap.containsKey(runnableHash))
                return;
            runnableHash = wrappedRunnableMap.get(runnableHash);
            if (!runnableHashMap.containsKey(runnableHash))
                return;
        }
        UUID parentStackID = runnableHashMap.get(runnableHash);
        stLinkMap.put(curStackID, parentStackID);
        if (DEBUG)
            System.out.println("###Link: " + curStackID + " TO " + parentStackID + " TID: " + Thread.currentThread().getId());
    }
    
}
