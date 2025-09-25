package pfl.result_analysis;

import java.beans.Customizer;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.lang.reflect.Type;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import javax.print.attribute.standard.PrinterInfo;

import org.apache.commons.lang3.tuple.ImmutableTriple;
import org.apache.commons.math3.stat.correlation.PearsonsCorrelation;
import org.apache.commons.math3.stat.inference.TTest;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Streams;
import com.google.common.hash.HashCode;
import com.google.common.primitives.Ints;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import it.unimi.dsi.io.ByteBufferInputStream;
import pfl.result_analysis.ctx.LoopInterferenceAnalysisContext;
import pfl.result_analysis.utils.BinaryResultLoader;
import pfl.result_analysis.utils.BinaryUtils;
import pfl.result_analysis.utils.CustomUUID;
import pfl.result_analysis.utils.FileCorruptedExcpetion;
import pfl.result_analysis.utils.MagicNumberErrorException;
import pfl.result_analysis.utils.Utils;
import pfl.result_analysis.utils.VCHashBytes;

public class LoopIterCountAnalysis
{
    public static LoopInterferenceAnalysisContext ctx;
    public static LoadingCache<String, Map<VCHashBytes, List<CustomUUID>>> loopIDIterIDMapCache;
    public static LoadingCache<String, Map<CustomUUID, List<VCHashBytes>>> iterEventsCache;
    public static LoadingCache<String, Map<CustomUUID, int[]>> iterIDStackMethodIdMapCache;
    public static LoadingCache<String, Map<Integer, String>> methodIdxCache;
    public static boolean ANVIL_RUN = Utils.isAnvilRun();

    public static void main(String[] args) throws Exception
    {
        ctx = new LoopInterferenceAnalysisContext(args);
        String outputLoopIterCountInterference = Paths.get(ctx.outputPath, ctx.outputPrefix + "LoopIterCountInterference.json").toString();

        Map<String, List<String>> profileRepeatedRuns = new ConcurrentHashMap<>();
        for (String profileRunHash : ctx.profileTestPlan.keySet())
        {
            String aggregateExpKey = (String) ctx.profileTestPlan.get(profileRunHash).get("AggregateExpKey");
            profileRepeatedRuns.computeIfAbsent(aggregateExpKey, k -> new ArrayList<>()).add(profileRunHash);
        }
        Map<String, List<String>> injectionRepeatedRuns = new ConcurrentHashMap<>();
        for (String injectionRunHash : ctx.injectionTestPlan.keySet())
        {
            String aggregateExpKey = (String) ctx.injectionTestPlan.get(injectionRunHash).get("AggregateExpKey");
            injectionRepeatedRuns.computeIfAbsent(aggregateExpKey, k -> new ArrayList<>()).add(injectionRunHash);
        }
        ctx.finishedInjectionRun.removeIf(e -> !injectionRepeatedRuns.keySet().contains(e));

        // #region Cache Loader

        // LoopKey (128bit hash) -> [Iter CustomUUID]
        loopIDIterIDMapCache = CacheBuilder.newBuilder().weakValues()
                .build(new CacheLoader<String, Map<VCHashBytes, List<CustomUUID>>>()
                {
                    @Override
                    public Map<VCHashBytes, List<CustomUUID>> load(String key) throws IOException
                    {
                        return ctx.loadLoopIDIterIDMap(key);
                    }
                });

        // IterKey -> [128bit branchID | 32bit real branch ID]
        iterEventsCache = CacheBuilder.newBuilder().weakValues()
                .build(new CacheLoader<String, Map<CustomUUID, List<VCHashBytes>>>()
                {
                    @Override
                    public Map<CustomUUID, List<VCHashBytes>> load(String key) throws IOException, ExecutionException
                    {
                        System.out.println("Loading: " + key);
                        return ctx.loadIterEventsMap(key);
                    }
                });

        iterIDStackMethodIdMapCache = CacheBuilder.newBuilder().weakValues().build(new CacheLoader<String, Map<CustomUUID, int[]>>()
        {
            @Override
            public Map<CustomUUID, int[]> load(String key) throws Exception
            {
                return ctx.loadIterIDStackMethodIdMap(key);
            }
        });

        methodIdxCache = CacheBuilder.newBuilder().weakValues().build(new CacheLoader<String,Map<Integer, String>>() 
        {
            @Override
            public Map<Integer, String> load(String key) throws Exception
            {
                return ctx.loadMethodIdxMap(key);
            }
        });

        // #endregion

        Map<VCHashBytes, Set<VCHashBytes>> loopIterCountInterferences = new ConcurrentHashMap<>();
        ctx.finishedInjectionRun.sort(new Comparator<String>()
        {
            @Override
            public int compare(String lhs, String rhs)
            {
                Map<String, Object> testProfileL = ctx.injectionTestPlan.get(lhs);
                String lhsProfileRunHash = (String) testProfileL.get("ProfileRunID");
                Map<String, Object> testProfileR = ctx.injectionTestPlan.get(rhs);
                String rhsProfileRunHash = (String) testProfileR.get("ProfileRunID");
                return lhsProfileRunHash.compareTo(rhsProfileRunHash);
            }
        });

        // #region Core Analysis Logic

        // t-Test with p < 0.05 on loop iters
        AtomicInteger progressIndicator = new AtomicInteger();
        Object outputMutex = new Object();
        Consumer<String> processor = injectionRunHash ->
        {
            int currentProgress = progressIndicator.getAndIncrement();
            if ((currentProgress < 5) || (currentProgress % 100 == 0)) System.out.println("At: " + currentProgress + '\t' + injectionRunHash);
            if (ctx.isContinue)
            {
                File jsonOutFile = Paths.get(ctx.perInjectionResultPath, injectionRunHash + "_itercount.json").toFile();
                if (jsonOutFile.exists()) return;
            }

            try
            {
                // System.out.println("Current Injection: " + injectionRunHash);
                Map<String, Object> injectionTestProfile = ctx.injectionTestPlan.get(injectionRunHash);
                String profileRunHash = (String) injectionTestProfile.get("ProfileRunID");
                // Build Run Signature
                Map<VCHashBytes, Map<ImmutableList<String>, List<Integer>>> profileLoopIterStats = buildLoopIterStat(profileRepeatedRuns.get(profileRunHash), false);  // LoopKey -> {Call Site: [IterCount]}
                Map<VCHashBytes, Map<ImmutableList<String>, List<Integer>>> injectionLoopIterStats = buildLoopIterStat(injectionRepeatedRuns.get(injectionRunHash), true);  // LoopKey -> {Call Site: [IterCount]}

                Set<VCHashBytes> injectionLoopSet = ConcurrentHashMap.newKeySet();
                injectionLoopSet.add(VCHashBytes.wrap(HashCode.fromString((String) injectionTestProfile.get("Injection Loop")).asBytes()));

                // Core comparison logic, one-sided t-Test with p < 0.1 on loop iters
                // Need iter count increase
                Map<VCHashBytes, List<ImmutableList<String>>> affectedLoop = new ConcurrentHashMap<>(); // LoopKey -> [[CallSites], (ProfileRun Avg Iter, InjectionRun Avg Iter)]
                                                                                                        // Value would look like this
                                                                                                        // [[foo1, bar1], [5, 10]
                                                                                                        //  [foo2, bar2], [3, 10]]
                for (VCHashBytes loopKey : injectionLoopIterStats.keySet())
                {
                    // Profile run does not contain this loop, but the injection run does
                    // This should be considered as an S+ affected loop as well
                    if (!profileLoopIterStats.containsKey(loopKey))
                    {
                        for (ImmutableList<String> stackMethodKey: injectionLoopIterStats.get(loopKey).keySet())
                        {
                            affectedLoop.computeIfAbsent(loopKey, k -> new ArrayList<>()).add(stackMethodKey);
                            affectedLoop.computeIfAbsent(loopKey, k -> new ArrayList<>()).add(ImmutableList.of("0", "0")); // Nothing in profile run, the magnitude of increment does not matter, so (0, 0)
                        }
                        continue;
                    }
                    Map<ImmutableList<String>, List<Integer>> profileLoopIterStat = profileLoopIterStats.get(loopKey);
                    Map<ImmutableList<String>, List<Integer>> injectionLoopIterStat = injectionLoopIterStats.get(loopKey);
                    for (ImmutableList<String> stackMethodKey: injectionLoopIterStat.keySet())
                    {
                        if (!profileLoopIterStat.containsKey(stackMethodKey)) continue; // TODO: Should we consider this as an S+ edge as well?
                        TTest statTest = new TTest();
                        double[] profileSample = profileLoopIterStat.get(stackMethodKey).stream().mapToDouble(e -> e).toArray();
                        double[] injectionSample = injectionLoopIterStat.get(stackMethodKey).stream().mapToDouble(e -> e).toArray();
                        if ((profileSample.length < 2) || (injectionSample.length < 2)) continue;
                        double profileAvg = Arrays.stream(profileSample).average().getAsDouble();
                        double injectionAvg = Arrays.stream(injectionSample).average().getAsDouble();
                        double pValue = statTest.tTest(profileSample, injectionSample) / 2;
                        if (injectionRunHash.equals("1a3af4250ac5f4c3cf4d0aac2050f304") && loopKey.toString().equals("51e0b016c9ce9499009f2647d47b3245")) 
                        {
                            System.out.println("p-value: " + pValue);
                            System.out.println(profileAvg);
                            System.out.println(injectionAvg);
                        }
                        if ((pValue < 0.1) && (injectionAvg > profileAvg)) 
                        {
                            affectedLoop.computeIfAbsent(loopKey, k -> new ArrayList<>()).add(stackMethodKey);
                            affectedLoop.computeIfAbsent(loopKey, k -> new ArrayList<>()).add(ImmutableList.of(Double.toString(profileAvg), Double.toString(injectionAvg)));
                        }
                    }
                }
                injectionLoopSet.forEach(injectionLoop -> loopIterCountInterferences.computeIfAbsent(injectionLoop, k -> ConcurrentHashMap.newKeySet()).addAll(affectedLoop.keySet()));

                // Dump per-injection result
                Map<VCHashBytes, Map<VCHashBytes, List<ImmutableList<String>>>> affectedLoopT = new HashMap<>();
                injectionLoopSet.forEach(injectionLoop -> affectedLoopT.computeIfAbsent(injectionLoop, k -> new HashMap<>(affectedLoop)));
                Map<String, Map<String, List<ImmutableList<String>>>> affectedLoopTout = affectedLoopT.entrySet().stream().collect(
                        Collectors.toMap(e -> e.getKey().toString(), 
                                         e -> e.getValue().entrySet().stream().collect(
                                                    Collectors.toMap(e2 -> e2.getKey().toString(), e2 -> e2.getValue()))));
                Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
                String jsonOutPath = Paths.get(ctx.perInjectionResultPath, injectionRunHash + "_itercount.json").toString();
                try (PrintWriter pw = new PrintWriter(new BufferedOutputStream(new FileOutputStream(jsonOutPath))))
                {
                    gson.toJson(affectedLoopTout, pw);
                }
                catch (FileNotFoundException e)
                {
                    e.printStackTrace();
                }

                // Dump All Callsites
                Map<String, List<ImmutableList<String>>> injectionRunAllLoopCallsites = injectionLoopIterStats.entrySet().stream().collect(
                    Collectors.toMap(e -> e.getKey().toString(), e -> new ArrayList<>(e.getValue().keySet())));
                Path allCallsiteJsonOutPath = Paths.get(ctx.perInjectionResultPath, injectionRunHash + "_all_callsite.json");
                try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(allCallsiteJsonOutPath)))
                {
                    gson.toJson(injectionRunAllLoopCallsites, pw);
                }

                // Progress Indicator w/ periodic dump of the results
                if ((currentProgress < 5) || (currentProgress % 100 == 0))
                {
                    Set<String> injectionLoopSetT = injectionLoopSet.stream().map(e -> HashCode.fromBytes(e.data).toString()).collect(Collectors.toSet());
                    System.out.println("At: " + currentProgress + " Injection Loop: " + injectionLoopSetT + " Affected Count: " + affectedLoop.size());
                }
            }
            catch (Exception e1)
            {
                e1.printStackTrace();
            }
        };
        ExecutorService es = Executors.newFixedThreadPool(ctx.nThread);
        for (String injectionRunHash : ctx.finishedInjectionRun)
        {
            es.submit(() -> processor.accept(injectionRunHash));
        }
        es.shutdown();
        es.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);

        // #endregion

        Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        try (PrintWriter pw = new PrintWriter(new BufferedOutputStream(new FileOutputStream(outputLoopIterCountInterference))))
        {
            Map<String, Set<String>> tOut = loopIterCountInterferences.entrySet().stream().collect(Collectors.toMap(e -> HashCode.fromBytes(e.getKey().data).toString(),
                    e -> e.getValue().stream().map(e2 -> HashCode.fromBytes(e2.data).toString()).collect(Collectors.toSet())));
            gson.toJson(tOut, pw);
            pw.flush();
        }
    }

    public static Map<VCHashBytes, Map<ImmutableList<String>, List<Integer>>> buildLoopIterStat(List<String> testHashs, boolean bypassCache)
    {
        Map<VCHashBytes, Map<ImmutableList<String>, List<Integer>>> r = new HashMap<>(); // LoopKey -> {Call Site: [IterCount]}
        for (String runHash : testHashs)
        {
            try 
            {
                Map<VCHashBytes, List<CustomUUID>> loopIDIterIDMap;
                Map<CustomUUID, int[]> iterIDStackMethodIdMap;
                Map<Integer, String> methodIdx;
                if (bypassCache)
                {
                    loopIDIterIDMap = ctx.loadLoopIDIterIDMap(runHash);
                    iterIDStackMethodIdMap = ctx.loadIterIDStackMethodIdMap(runHash);
                    methodIdx = ctx.loadMethodIdxMap(runHash);
                }
                else
                {
                    loopIDIterIDMap = loopIDIterIDMapCache.get(runHash);
                    iterIDStackMethodIdMap = iterIDStackMethodIdMapCache.get(runHash);
                    methodIdx = methodIdxCache.get(runHash);
                }
                LoadingCache<List<Integer>, ImmutableList<String>> callsiteStrCache = CacheBuilder.newBuilder().weakValues().build(new CacheLoader<List<Integer>, ImmutableList<String>>() 
                {
                    @Override
                    public ImmutableList<String> load(List<Integer> stackMethodId) throws Exception
                    {
                        ImmutableList.Builder<String> stackMethodKeyBuilder = ImmutableList.builder();
                        stackMethodId.stream().map(e -> methodIdx.getOrDefault(e, "NONE")).forEachOrdered(stackMethodKeyBuilder::add);
                        return stackMethodKeyBuilder.build();
                    }
                });
                for (VCHashBytes loopKey: loopIDIterIDMap.keySet())
                {
                    int iterIdx = 0;
                    Map<ImmutableList<String>, AtomicInteger> loopIterCounter = new HashMap<>();
                    for (CustomUUID iterID: loopIDIterIDMap.get(loopKey))
                    {
                        iterIdx++;
                        int[] stackMethodIdRaw = iterIDStackMethodIdMap.get(iterID);
                        if (stackMethodIdRaw == null) continue;
                        List<Integer> stackMethodId = Ints.asList(stackMethodIdRaw);

                        int restoreFactor = 1; // 1~100: 100%
                        if ((iterIdx > 100) && (iterIdx <= 140)) // 101 ~ 500: 10%
                            restoreFactor = 10;
                        else if ((iterIdx > 140) && (iterIdx <= 165)) // 501 ~ 1000: 5%
                            restoreFactor = 20;
                        else if (iterIdx > 165) // 1001~ : 1%
                            restoreFactor = 100;
                        ImmutableList<String> stackMethodKey = callsiteStrCache.get(stackMethodId);
                        loopIterCounter.computeIfAbsent(stackMethodKey, k -> new AtomicInteger(0)).getAndAdd(restoreFactor);
                    }
                    for (ImmutableList<String> stackMethodKey: loopIterCounter.keySet())
                    {
                        r.computeIfAbsent(loopKey, k -> new HashMap<>()).computeIfAbsent(stackMethodKey, k -> new ArrayList<>()).add(loopIterCounter.get(stackMethodKey).get());
                    }
                }
                callsiteStrCache.invalidateAll();
            }
            catch (ExecutionException | IOException e)
            {
                System.out.println("Skipping: " + runHash + " due to exception in loadLoopIDIterIDMap/loadIterIDStackMethodIdMap");
                e.printStackTrace();
            }
        }
        return r;
    }
}
