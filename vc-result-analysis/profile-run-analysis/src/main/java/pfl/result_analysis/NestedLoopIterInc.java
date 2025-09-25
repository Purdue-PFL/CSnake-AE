package pfl.result_analysis;

import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.apache.commons.lang3.tuple.ImmutableTriple;
import org.apache.commons.math3.stat.StatUtils;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.HashMultiset;
import com.google.common.collect.Multiset;
import com.google.common.hash.HashCode;
import com.google.common.math.Stats;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import pfl.result_analysis.ctx.LoopInterferenceAnalysisContext;
import pfl.result_analysis.utils.CustomUUID;
import pfl.result_analysis.utils.Utils;
import pfl.result_analysis.utils.VCHashBytes;
import pfl.result_analysis.utils.VCStatUtils;

// Analysis confirming that the S+/ICFG edge is true based on the increase in the child loop (for nested loop) iterations
public class NestedLoopIterInc
{
    public static LoopInterferenceAnalysisContext ctx;
    public static LoadingCache<String, Map<VCHashBytes, List<CustomUUID>>> loopIDIterIDMapCache;
    public static LoadingCache<String, ImmutableTriple<Map<CustomUUID, List<VCHashBytes>>, Map<CustomUUID, CustomUUID>, Map<CustomUUID, Long>>> iterEventsCache;
    public static Map<String, List<String>> profileRepeatedRuns;
    public static Map<String, List<String>> injectionRepeatedRuns;

    public static double STAT_CLOSE_THRESHOLD = 0.05;

    // InjectionRunHash -> {LoopKey: [# of TID running that loop]}
    public static Map<String, Map<VCHashBytes, List<Integer>>> loopTIDCounts = new ConcurrentHashMap<>();

    public static void main(String[] args) throws Exception
    {
        ctx = new LoopInterferenceAnalysisContext(args);
        profileRepeatedRuns = new ConcurrentHashMap<>();
        for (String profileRunHash : ctx.profileTestPlan.keySet())
        {
            String aggregateExpKey = (String) ctx.profileTestPlan.get(profileRunHash).get("AggregateExpKey");
            profileRepeatedRuns.computeIfAbsent(aggregateExpKey, k -> new ArrayList<>()).add(profileRunHash);
        }
        injectionRepeatedRuns = new ConcurrentHashMap<>();
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

        iterEventsCache = CacheBuilder.newBuilder().weakValues()
                .build(new CacheLoader<String, ImmutableTriple<Map<CustomUUID, List<VCHashBytes>>, Map<CustomUUID, CustomUUID>, Map<CustomUUID, Long>>>()
                {
                    @Override
                    public ImmutableTriple<Map<CustomUUID, List<VCHashBytes>>, Map<CustomUUID, CustomUUID>, Map<CustomUUID, Long>> load(String key) throws IOException, ExecutionException
                    {
                        System.out.println("Loading: " + key);
                        Map<CustomUUID, CustomUUID> parentIterMap = new ConcurrentHashMap<>();
                        Map<CustomUUID, Long> iterIDTIDMap = new ConcurrentHashMap<>();
                        Map<CustomUUID, List<VCHashBytes>> iterEventsMap = ctx.loadIterEventsMap_v2(key, ctx.execIDInverseMap, parentIterMap, iterIDTIDMap);
                        return ImmutableTriple.of(iterEventsMap, parentIterMap, iterIDTIDMap);
                    }
                });
        // #endregion

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

        AtomicInteger progressIndicator = new AtomicInteger();
        Instant overallStartTime = Instant.now();
        // for (String injectionRunHash : ctx.finishedInjectionRun)
        Consumer<String> processor = (injectionRunHash) ->
        {
            int currentProgress = progressIndicator.getAndIncrement();
            if ((currentProgress < 5) || (currentProgress % 100 == 0)) System.out.println("At: " + currentProgress + '\t' + injectionRunHash);
            Path jsonOutPath = Paths.get(ctx.perInjectionResultPath, injectionRunHash + "_parent_delay.json");
            if (ctx.isContinue)
            {
                if (Files.exists(jsonOutPath)) return;
            }
            Instant startTime = Instant.now();

            try
            {
                Map<String, Object> injectionTestProfile = ctx.injectionTestPlan.get(injectionRunHash);
                String profileRunHash = (String) injectionTestProfile.get("ProfileRunID");

                // Build Profile Run Signature
                // ChildLoop: {ParentLoop: [ParentIter1_IterCount, ParentIter2_IterCount, ...]}
                Map<VCHashBytes, Map<VCHashBytes, List<Integer>>> profileNestedLoopStats = buildNestedLoopStat(profileRunHash, profileRepeatedRuns.get(profileRunHash), false);
                Map<VCHashBytes, Map<VCHashBytes, List<Integer>>> injectionNestedLoopStats = buildNestedLoopStat(injectionRunHash, injectionRepeatedRuns.get(injectionRunHash),true);

                Map<VCHashBytes, Set<VCHashBytes>> potentiallyDelayedParentLoops = new HashMap<>(); // ChildLoopKey -> [Potentially Delayed Parent Loop Key]
                for (VCHashBytes childLoopKey : injectionNestedLoopStats.keySet())
                {
                    Map<VCHashBytes, List<Integer>> profileNestedLoopStat = profileNestedLoopStats.get(childLoopKey);
                    if (profileNestedLoopStat == null) continue;
                    Map<VCHashBytes, List<Integer>> injectionNestedLoopStat = injectionNestedLoopStats.get(childLoopKey);
                    for (VCHashBytes parentLoopKey : injectionNestedLoopStat.keySet())
                    {
                        List<Integer> profileStat = profileNestedLoopStat.get(parentLoopKey);
                        if (profileStat == null) continue;
                        List<Integer> injectionStat = injectionNestedLoopStat.get(parentLoopKey);
                        if (!VCStatUtils.isStatisticallyClose(profileStat, injectionStat, STAT_CLOSE_THRESHOLD) && (Stats.meanOf(injectionStat) > Stats.meanOf(profileStat)))
                            potentiallyDelayedParentLoops.computeIfAbsent(childLoopKey, k -> new HashSet<>()).add(parentLoopKey);
                    }
                }

                // Print per-experiment result
                Map<String, List<String>> potentiallyDelayedParentLoopsO = potentiallyDelayedParentLoops.entrySet().stream()
                        .collect(Collectors.toMap(e -> e.getKey().toString(), e -> e.getValue().stream().map(e2 -> e2.toString()).collect(Collectors.toList())));
                Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
                try (BufferedWriter bw = Files.newBufferedWriter(jsonOutPath))
                {
                    gson.toJson(potentiallyDelayedParentLoopsO, bw);
                }

                // Status Report
                if ((currentProgress < 5) || (currentProgress % 100 == 0))
                {
                    System.out.println("At: " + currentProgress + '\t' + injectionRunHash + " Duration: " + Duration.between(startTime, Instant.now()).toSeconds()
                            + " Time Elapsed: " + Duration.between(overallStartTime, Instant.now()).toSeconds());
                }
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
        };
        Utils.parallelRunTasks(ctx.finishedInjectionRun, processor, ctx.nThread);

        Map<String, Map<String, List<Integer>>> loopTIDCountsO = loopTIDCounts.entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey(), e -> e.getValue().entrySet().stream().collect(Collectors.toMap(e2 -> e2.getKey().toString(), e2 -> e2.getValue()))));
        Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        Path jsonOutputPath = Paths.get(ctx.outputPath, ctx.outputPrefix + "loopTID.json");
        try (BufferedWriter bw = Files.newBufferedWriter(jsonOutputPath))
        {
            gson.toJson(loopTIDCountsO, bw);
        }
    }

    public static Map<VCHashBytes, Map<VCHashBytes, List<Integer>>> buildNestedLoopStat(String runHash, List<String> repeatedRunHashes, boolean bypassCache)
            throws IOException, ExecutionException, InterruptedException
    {
        Map<VCHashBytes, Map<VCHashBytes, List<Integer>>> result = new ConcurrentHashMap<>(); // ChildLoop: {ParentLoop: [ParentIter1_IterCount, ParentIter2_IterCount, ...]}
        Map<VCHashBytes, List<Integer>> loopTIDCount = new ConcurrentHashMap<>();
        for (String curRunHash : repeatedRunHashes)
        {
            Map<CustomUUID, CustomUUID> parentIterMap;
            Map<CustomUUID, Long> iterIDTIDMap;
            Map<VCHashBytes, List<CustomUUID>> loopIDIterIDMap;
            if (bypassCache)
            {
                parentIterMap = new ConcurrentHashMap<>();
                iterIDTIDMap = new ConcurrentHashMap<>();
                ctx.loadIterEventsMap_v2(runHash, ctx.execIDInverseMap, parentIterMap, iterIDTIDMap);
                loopIDIterIDMap = ctx.loadLoopIDIterIDMap(runHash);
            }
            else
            {
                ImmutableTriple<Map<CustomUUID, List<VCHashBytes>>, Map<CustomUUID, CustomUUID>, Map<CustomUUID, Long>> r = iterEventsCache.get(curRunHash);
                // iterEvents = r.getLeft();
                parentIterMap = r.getMiddle();
                iterIDTIDMap = r.getRight();
                loopIDIterIDMap = loopIDIterIDMapCache.get(runHash);
            }
            Map<CustomUUID, VCHashBytes> iterIDLoopIDMap = new ConcurrentHashMap<>();
            for (VCHashBytes loopKey : loopIDIterIDMap.keySet())
            {
                loopIDIterIDMap.get(loopKey).forEach(e -> iterIDLoopIDMap.put(e, loopKey));
            }

            // Count child loop iters
            Utils.commonPoolSyncRun(() ->             // for (VCHashBytes childLoopKey : loopIDIterIDMap.keySet())
            {
                loopIDIterIDMap.keySet().parallelStream().forEach(childLoopKey -> 
                {
                    Multiset<CustomUUID> parentIterNestedCounter = HashMultiset.create(); // Parent IterID -> # of child loop iters
                    Set<Long> iterTIDs = new HashSet<>();
                    for (CustomUUID childIterID : loopIDIterIDMap.get(childLoopKey))
                    {
                        iterTIDs.add(iterIDTIDMap.get(childIterID));
                        CustomUUID parentIterID = parentIterMap.get(childIterID);
                        if (parentIterID == null) continue;
                        parentIterNestedCounter.add(parentIterID);
                    }
                    loopTIDCount.computeIfAbsent(childLoopKey, k -> new ArrayList<>()).add(iterTIDs.size());
    
                    Map<VCHashBytes, List<Integer>> childLoopParentStat = new HashMap<>(); // Parent LoopKey -> [# of child loop iters]
                    for (CustomUUID parentIterID : parentIterNestedCounter.elementSet())
                    {
                        VCHashBytes parentLoopKey = iterIDLoopIDMap.get(parentIterID);
                        if (parentLoopKey == null) continue;
                        childLoopParentStat.computeIfAbsent(parentLoopKey, k -> new ArrayList<>()).add(parentIterNestedCounter.count(parentIterID));
                    }
                    result.put(childLoopKey, childLoopParentStat);
                });
            });
        }
        loopTIDCounts.put(runHash, loopTIDCount);
        return result;
    }

}
