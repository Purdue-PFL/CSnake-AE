package pfl.result_analysis;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.PrintWriter;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.lang3.function.TriConsumer;
import org.apache.commons.lang3.function.TriFunction;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.jgrapht.Graph;
import org.jgrapht.Graphs;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.jgrapht.graph.DefaultEdge;

import com.github.luben.zstd.ZstdIOException;
import com.github.luben.zstd.ZstdInputStream;
import com.github.luben.zstd.ZstdOutputStream;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.collect.SortedMultiset;
import com.google.common.collect.TreeMultiset;
import com.google.common.math.Stats;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import pfl.result_analysis.bfs.AlmostEqualVCBFSChain;
import pfl.result_analysis.bfs.VCBFSChain;
import pfl.result_analysis.dataset.LoopInterferenceDataset;
import pfl.result_analysis.dataset.LoopInterferenceDataset.InjectionType;
import pfl.result_analysis.dataset.LoopInterferenceDataset.InterferenceType;
import pfl.result_analysis.utils.LoopHash;
import pfl.result_analysis.utils.LoopItem;
import pfl.result_analysis.utils.LoopSignature;
import pfl.result_analysis.utils.Utils;
import pfl.result_analysis.utils.VCHashBytes;
import pfl.result_analysis.utils.VCLoopElement;

public class GuidedSearchV3
{
    public static LoopInterferenceDataset dataset = LoopInterferenceDataset.build();
    public static LoadingCache<String, Map<String, List<String>>> execSigJsonCache;
    public static LoadingCache<String, Map<String, Map<String, List<List<String>>>>> iterCountJsonCache;
    public static LoadingCache<String, Map<String, List<List<String>>>> execSigCallsiteJsonCache;
    public static LoadingCache<String, Map<ImmutableList<String>, Set<LoopSignature>>> injectionLoopSigDiffCache; // [CallSite] -> Set([LoopSignature])
    public static LoadingCache<String, Map<LoopHash, Map<ImmutableList<String>, Set<LoopSignature>>>> affectedLoopSigDiffCache; // AffectedLoopID -> {[CallSite]: Set([LoopSignature])}
    public static LoadingCache<Path, Map<LoopHash, Set<LoopHash>>> parentDelayCache;
    public static LoadingCache<Path, Map<String, List<List<String>>>> allLoopCallsiteCache; // LoopHash -> [CallSites]

    public static String injectionResultPathRoot;
    public static String iterCountResultPathRoot;
    public static Map<LoopHash, LoopItem> loopMap;
    public static Map<VCHashBytes, Map<String, Object>> negateInjection;
    public static Map<VCHashBytes, Map<String, Object>> throwInjection;
    public static Map<VCHashBytes, Set<VCHashBytes>> throwBranchPos;
    public static Map<VCHashBytes, Map<LoopHash, List<Integer>>> loopTIDCount;
    public static Map<String, Map<String, Object>> injectionTestPlan = new ConcurrentHashMap<>();
    public static Map<String, List<String>> injectionRepeatedRuns = new ConcurrentHashMap<>();
    public static Map<ImmutablePair<VCHashBytes, LoopHash>, Double> eSimScores = new ConcurrentHashMap<>(); // [0, 1] similarity
    public static Map<ImmutablePair<VCHashBytes, LoopHash>, Double> sSimScores = new ConcurrentHashMap<>();

    public static void main(String[] args) throws Exception
    {
        Options options = new Options();
        options.addOption(Option.builder("injection_testplan").hasArgs().build());
        options.addOption("loop_cfg", true, "System's control flow graph");
        options.addOption("injection_result_path", true, "Per-experiment interference result path (JSON)");
        options.addOption("iter_count_result_path", true, "JSON result path for per-experiment S+ loops");
        options.addOption("loops", true, "json file for loops");
        options.addOption("rpc_links", true, "loops from/to rpc calls");
        options.addOption("output_path", true, "result_output_path");
        options.addOption("negate_injection", true, "Details about the NEGATE injection");
        options.addOption("throw_injection", true, "Details about the throw injection");
        options.addOption("throw_branch_position", true, "The branch position for each if-throw pattern");
        // options.addOption("loop_tid_count", true, "Number of TID executing each loop in each run");
        options.addOption("max_delay_count", true, "Maximum Number of Delay in chain");
        options.addOption("max_error_count", true, "Maximum Number of Error in chain");
        options.addOption(Option.builder("beam_size").hasArg().build());
        options.addOption(Option.builder("delay_injection_negative_bias").hasArg().build());
        options.addOption(Option.builder("e_score").hasArg().build());
        options.addOption(Option.builder("s_score").hasArg().build());
        DefaultParser parser = new DefaultParser();
        CommandLine cmd = parser.parse(options, args);

        loadResultCache();

        // #region Load Supporting data structures

        Map<String, Map<String, Object>> loopMapT = Utils.readJson(cmd.getOptionValue("loops"));
        loopMap = loopMapT.entrySet().stream().collect(Collectors.toMap(e -> LoopHash.wrap(e.getKey()), e -> LoopItem.mapToLoopItem(e.getValue())));

        for (String injectionTestPlanPath: cmd.getOptionValues("injection_testplan"))
        {
            Map<String, Map<String, Object>> t = Utils.readJson(injectionTestPlanPath);
            injectionTestPlan.putAll(t);
        }
        for (String injectionRunHash : injectionTestPlan.keySet())
        {
            String aggregateExpKey = (String) injectionTestPlan.get(injectionRunHash).get("AggregateExpKey");
            injectionRepeatedRuns.computeIfAbsent(aggregateExpKey, k -> new ArrayList<>()).add(injectionRunHash);
        }

        Map<String, Map<String, Object>> negateInjectionT = Utils.readJson(cmd.getOptionValue("negate_injection"));
        negateInjection = negateInjectionT.entrySet().stream().collect(Collectors.toConcurrentMap(e -> VCHashBytes.wrap(e.getKey()), e -> e.getValue()));
        negateInjectionT.clear();
        Map<String, Map<String, Object>> throwInjectionT = Utils.readJson(cmd.getOptionValue("throw_injection"));
        throwInjection = throwInjectionT.entrySet().stream().collect(Collectors.toConcurrentMap(e -> VCHashBytes.wrap(e.getKey()), e -> e.getValue()));
        throwInjectionT.clear();

        Map<String, List<String>> throwBranchPosT = Utils.readJson(cmd.getOptionValue("throw_branch_position"));
        throwBranchPos = throwBranchPosT.entrySet().stream()
                .collect(Collectors.toMap(e -> VCHashBytes.wrap(e.getKey()), e -> e.getValue().stream().map(e2 -> VCHashBytes.wrap(e2)).collect(Collectors.toSet())));
        throwBranchPosT.clear();

        // Remove all the hasXXX(), util.XXX(), and XXXUtil.YYY() injections
        Set<VCHashBytes> utilInjectionID = new HashSet<>();
        for (VCHashBytes negateKey : negateInjection.keySet())
        {
            String clazz = (String) negateInjection.get(negateKey).get("Class");
            String method = (String) negateInjection.get(negateKey).get("Method");
            String clazzLower = clazz.toLowerCase();
            if (clazzLower.contains(".proto")) utilInjectionID.add(negateKey);
            if (clazzLower.contains("util")) utilInjectionID.add(negateKey);
            if (clazzLower.contains("info")) utilInjectionID.add(negateKey);
            if (clazzLower.contains(".tools.")) utilInjectionID.add(negateKey);
            if (clazzLower.contains("metrics") || clazzLower.contains("abstract")) utilInjectionID.add(negateKey);
            if (method.contains("contains") || method.startsWith("has") || method.startsWith("next")) utilInjectionID.add(negateKey);
            if (clazz.contains("Map") && method.contains("remove")) utilInjectionID.add(negateKey);
        }
        // Also remove IllegalArgumentException, these are usually related to user input, and cannot be self-sustaining
        for (VCHashBytes throwKey : throwInjection.keySet())
        {
            String clazz = (String) throwInjection.get(throwKey).get("Class");
            String method = (String) throwInjection.get(throwKey).get("Method");
            String clazzLower = clazz.toLowerCase();
            if (clazzLower.contains("util") || clazzLower.contains("info") || clazzLower.contains(".tools.") || clazzLower.contains("metrics"))
            {
                utilInjectionID.add(throwKey);
                continue;
            }
            List<Map<String, Object>> throwableConstructionSteps = (List) throwInjection.get(throwKey).get("ThrowableConstruction");
            for (Map<String, Object> exStep : throwableConstructionSteps)
            {
                String exType = (String) exStep.get("type");
                if (exType.equals("java.lang.IllegalArgumentException") || FilterGuidedSearch.shouldRemoveThrowable(exType))
                {
                    utilInjectionID.add(throwKey);
                    break;
                }
            }
        }
        injectionRepeatedRuns.keySet().removeIf(runHash ->
        {
            VCHashBytes injectionID = VCHashBytes.wrap((String) injectionTestPlan.get(runHash).get("Injection ID"));
            return utilInjectionID.contains(injectionID);
        });

        // Map<String, Map<String, List<Double>>> loopTIDCountT = Utils.readJson(cmd.getOptionValue("loop_tid_count"));
        // loopTIDCount = loopTIDCountT.entrySet().stream().collect(Collectors.toConcurrentMap(e -> VCHashBytes.wrap(e.getKey()), e -> e.getValue().entrySet().stream()
        //         .collect(Collectors.toConcurrentMap(e2 -> LoopHash.wrap(e2.getKey()), e2 -> e2.getValue().stream().map(e3 -> e3.intValue()).collect(Collectors.toList())))));
        // loopTIDCountT.clear();

        if (cmd.hasOption("beam_size"))
        {
            eSimScores = loadSimilarityScores(cmd.getOptionValue("e_score"), eSimScores);
            sSimScores = loadSimilarityScores(cmd.getOptionValue("s_score"), sSimScores);
        }

        // #endregion Load Supporting data structures

        // #region Prepare masked utility loops

        Set<LoopHash> utilLoops = ConcurrentHashMap.newKeySet();
        for (LoopHash loopKey : loopMap.keySet())
        {
            LoopItem loopItem = loopMap.get(loopKey);
            String clazzLower = loopItem.clazz.toLowerCase();
            if (clazzLower.contains("proto") || clazzLower.contains("util") || clazzLower.contains("metrics") || clazzLower.contains("abstract")) utilLoops.add(loopKey);
        }
        // #endregion Prepare masked utility loops

        // #region Load Loop CFG
        Graph<LoopHash, DefaultEdge> loopCFG;
        try (FileInputStream fis = new FileInputStream(cmd.getOptionValue("loop_cfg"));
                BufferedInputStream bis = new BufferedInputStream(fis);
                ObjectInputStream ois = new ObjectInputStream(bis);)
        {
            loopCFG = (DefaultDirectedGraph<LoopHash, DefaultEdge>) ois.readObject();
        }
        // Essentially, we remove the client
        if (cmd.hasOption("rpc_links"))
        {
            try 
            {
                Map<String, Map<String, List<String>>> rpcLinkRawMap = Utils.readJson(cmd.getOptionValue("rpc_links"));
                for (String rpcClientCallName : rpcLinkRawMap.get("LoopToRPCClientCalls").keySet())
                {
                    String[] split = rpcClientCallName.split("\\.");
                    String methodName = split[split.length - 1];
                    for (String rpcServerCallName : rpcLinkRawMap.get("RPCServerCallsToLoops").keySet())
                    {
                        if (rpcServerCallName.contains(methodName))
                        {
                            for (String loop1RawHash : rpcLinkRawMap.get("LoopToRPCClientCalls").get(rpcClientCallName))
                            {
                                LoopHash loop1Hash = LoopHash.wrap(loop1RawHash);
                                for (String loop2RawHash : rpcLinkRawMap.get("RPCServerCallsToLoops").get(rpcServerCallName))
                                {
                                    LoopHash loop2Hash = LoopHash.wrap(loop2RawHash);
                                    Graphs.successorListOf(loopCFG, loop2Hash).forEach(succ -> loopCFG.addEdge(loop1Hash, succ));
                                    Graphs.predecessorListOf(loopCFG, loop1Hash).forEach(pred -> loopCFG.addEdge(pred, loop2Hash));
                                }
                            }
                        }
                    }
                }
            }
            catch (FileNotFoundException e) {}
        }
        else 
        {
            System.out.println("WARNING: Not Using RPC Links for LoopCFG");
        }

        Map<String, Set<LoopHash>> methodToLoopMap = new HashMap<>();
        for (LoopHash loopKey : loopCFG.vertexSet())
        {
            LoopItem loopItem = loopMap.get(loopKey);
            String fullMethodName = loopItem.clazz + "." + loopItem.func;
            methodToLoopMap.computeIfAbsent(fullMethodName, k -> new HashSet<>()).add(loopKey);
        }
        Map<LoopHash, Set<LoopHash>> loopsInSameMethod = new ConcurrentHashMap<>();
        for (LoopHash loopKey : loopCFG.vertexSet())
        {
            LoopItem loopItem = loopMap.get(loopKey);
            String fullMethodName = loopItem.clazz + "." + loopItem.func;
            loopsInSameMethod.put(loopKey, methodToLoopMap.get(fullMethodName));
        }

        for (LoopHash loopKey1 : loopCFG.vertexSet())
        {
            Graphs.successorListOf(loopCFG, loopKey1).forEach(loopKey2 -> dataset.addCFGEdge(loopKey1, loopKey2));
        }

        // #endregion Load Loop CFG

        // #region Load per-experiment
        injectionResultPathRoot = cmd.getOptionValue("injection_result_path");
        iterCountResultPathRoot = cmd.getOptionValue("iter_count_result_path", injectionResultPathRoot);
        for (String injectionRunHashStr : injectionRepeatedRuns.keySet())
        {
            boolean isDelayInjection = injectionTestPlan.get(injectionRunHashStr).get("InjectionType").equals("DELAY");
            File execInterferenceResultFile = Paths.get(injectionResultPathRoot, injectionRunHashStr + ".json").toFile();

            if (isDelayInjection)
            {
                loadInterferenceResult(execInterferenceResultFile, injectionRunHashStr,
                        dataset.getRunHashIndexedLoopInterferenceMap(InjectionType.DELAY, InterferenceType.EXEC_SIG),
                        dataset.getLoopIndexedInjectionRunsMap(InjectionType.DELAY, InterferenceType.EXEC_SIG), utilLoops);
            }
            else
            {
                loadInterferenceResult(execInterferenceResultFile, injectionRunHashStr,
                        dataset.getRunHashIndexedLoopInterferenceMap(InjectionType.ERROR, InterferenceType.EXEC_SIG),
                        dataset.getLoopIndexedInjectionRunsMap(InjectionType.ERROR, InterferenceType.EXEC_SIG), utilLoops);
            }

            File iterCountIterferenenceResultFile = Paths.get(iterCountResultPathRoot, injectionRunHashStr + "_itercount.json").toFile();
            if (isDelayInjection)
            {
                loadInterferenceResult(iterCountIterferenenceResultFile, injectionRunHashStr,
                        dataset.getRunHashIndexedLoopInterferenceMap(InjectionType.DELAY, InterferenceType.ITER_COUNT),
                        dataset.getLoopIndexedInjectionRunsMap(InjectionType.DELAY, InterferenceType.ITER_COUNT), utilLoops);
            }
            else
            {
                loadInterferenceResult(iterCountIterferenenceResultFile, injectionRunHashStr,
                        dataset.getRunHashIndexedLoopInterferenceMap(InjectionType.ERROR, InterferenceType.ITER_COUNT),
                        dataset.getLoopIndexedInjectionRunsMap(InjectionType.ERROR, InterferenceType.ITER_COUNT), utilLoops);
            }
        } 

        // #endregion Load per-experiment

        // #region Parallel BFS
        int maxErrorCount = Integer.valueOf(cmd.getOptionValue("max_error_count"));
        int maxDelayCount = Integer.valueOf(cmd.getOptionValue("max_delay_count"));
        int beamSize = Integer.valueOf(cmd.getOptionValue("beam_size", "50000000"));
        boolean usePrioritizedBeam = cmd.hasOption("beam_size");
        double delayInjNegativeBias = Double.valueOf(cmd.getOptionValue("delay_injection_negative_bias", "0"));
        List<VCBFSChain> bfsQueue = new LinkedList<>();
        List<VCBFSChain> bfsCycles = new ArrayList<>();
        // init BFS
        bfsQueue.addAll(generateInitialChains(dataset.getRunHashIndexedLoopInterferenceMap(InjectionType.ERROR, InterferenceType.EXEC_SIG), InterferenceType.EXEC_SIG, InjectionType.ERROR));
        bfsQueue.addAll(generateInitialChains(dataset.getRunHashIndexedLoopInterferenceMap(InjectionType.ERROR, InterferenceType.ITER_COUNT), InterferenceType.ITER_COUNT, InjectionType.ERROR));
        bfsQueue.addAll(generateInitialChains(dataset.getRunHashIndexedLoopInterferenceMap(InjectionType.DELAY, InterferenceType.EXEC_SIG), InterferenceType.EXEC_SIG, InjectionType.DELAY));
        // bfsQueue.addAll(generateInitialChains(dataset.getRunHashIndexedLoopInterferenceMap(InjectionType.DELAY, InterferenceType.ITER_COUNT), InterferenceType.ITER_COUNT));
        int bfsLevel = 0;
        while (!bfsQueue.isEmpty())
        {   
            // #region ChainExpanders
            BiFunction<VCBFSChain, InterferenceType, List<VCBFSChain>> chainExpanderError = (chain, nextInterferenceType) ->
            {
                List<VCBFSChain> result = new ArrayList<>();
                VCLoopElement lastVLE = chain.getLastVLE();
                LoopHash nextInjectionLoop = lastVLE.affectedLoopID;
                Collection<VCHashBytes> nextRuns = dataset.getInjectionRunsForLoop(nextInjectionLoop, InjectionType.ERROR, nextInterferenceType);
                for (VCHashBytes nextInjectionRunHash: nextRuns)
                {
                    Map<String, Object> runProf = injectionTestPlan.get(nextInjectionRunHash.toString());
                    VCHashBytes nextInjectionID = VCHashBytes.wrap((String) runProf.get("Injection ID"));
                    VCHashBytes nextProfileRunHash = VCHashBytes.wrap((String) runProf.get("ProfileRunID"));
                    Set<LoopSignature> matchingSig = getMatchingInjectionSignature(lastVLE.injectionRunHash, lastVLE.loopID, nextInjectionID, nextInjectionRunHash, nextInjectionLoop);
                    if (matchingSig.isEmpty()) continue;
                    Set<LoopHash> affectedLoops = dataset.getRunHashIndexedLoopInterferenceMap(InjectionType.ERROR, nextInterferenceType).get(nextInjectionRunHash).get(nextInjectionLoop);
                    for (LoopHash nextAffectedLoop: affectedLoops)
                    {
                        VCLoopElement vle = VCLoopElement.build(nextInjectionLoop, nextAffectedLoop, nextInjectionID, nextInjectionRunHash, nextProfileRunHash, nextInterferenceType);
                        VCBFSChain chainCopy = chain.copy();
                        chainCopy.addVLE(vle, InjectionType.ERROR);
                        if (chainCopy.loops.size() == chainCopy.vles.size()) result.add(chainCopy);
                    }
                }
                return result;
            };
            BiFunction<VCBFSChain, InterferenceType, List<VCBFSChain>> chainExpanderDelay = (chain, nextInterferenceType) -> 
            {
                List<VCBFSChain> result = new ArrayList<>();
                VCLoopElement lastVLE = chain.getLastVLE();
                LoopHash nextInjectionLoop = lastVLE.affectedLoopID;
                boolean lastVLEIsSPlus = lastVLE.interferenceType.equals(InterferenceType.ITER_COUNT);
                Collection<VCHashBytes> nextRuns = dataset.getInjectionRunsForLoop(nextInjectionLoop, InjectionType.DELAY, nextInterferenceType);
                for (VCHashBytes nextInjectionRunHash: nextRuns)
                {
                    Map<String, Object> runProf = injectionTestPlan.get(nextInjectionRunHash.toString());
                    VCHashBytes nextInjectionID = VCHashBytes.wrap((String) runProf.get("Injection ID"));
                    VCHashBytes nextProfileRunHash = VCHashBytes.wrap((String) runProf.get("ProfileRunID"));
                    Set<LoopHash> affectedLoops = dataset.getRunHashIndexedLoopInterferenceMap(InjectionType.DELAY, nextInterferenceType).get(nextInjectionRunHash).get(nextInjectionLoop);
                    for (LoopHash nextAffectedLoop: affectedLoops)
                    {
                        // We are connecting a delay injection
                        // Make sure that our delay injection has the same call site as the interfered loop from the last edge
                        //      Run1          Run1           Run1           Run2
                        // L1 -- S+ --> ............................. L2 -- E/S+(D) --> L3        
                        // L1 -- S+ --> L' -- ICFG --> .............. L2 -- E/S+(D) --> L3        
                        // L1 -- S+ --> L' -- ICFG --> L'' -- CFG --> L2 -- E/S+(D) --> L3
                        // S+ affected L2 callsites from Run1 have overlap with L2 injection callsites from Run2
                        if (!hasMatchingIterIncreaseCallsitesForDelay(lastVLE.injectionRunHash, lastVLE.loopID, nextInjectionRunHash, nextInjectionLoop, lastVLEIsSPlus)) continue;
                        VCLoopElement vle = VCLoopElement.build(nextInjectionLoop, nextAffectedLoop, nextInjectionID, nextInjectionRunHash, nextProfileRunHash, nextInterferenceType);
                        VCBFSChain chainCopy = chain.copy();
                        chainCopy.addVLE(vle, InjectionType.DELAY);
                        if (chainCopy.loops.size() == chainCopy.vles.size()) result.add(chainCopy);
                    }
                }
                return result;
            };

            Queue<VCBFSChain> nextLevelQueue = new ConcurrentLinkedQueue<>();
            Consumer<VCBFSChain> processOneChain = chain -> 
            {
                try
                {
                    VCLoopElement lastVLE = chain.getLastVLE();
                    InterferenceType lastEdgeType = lastVLE.interferenceType;
                    switch (lastEdgeType) 
                    {
                        case EXEC_SIG: // Last edge is E, we can continue with another E or S+ edge
                            // Next E(I) Edge
                            nextLevelQueue.addAll(chainExpanderError.apply(chain, InterferenceType.EXEC_SIG));
                            // Next S+(I) Edge
                            List<VCBFSChain> nextLevelChainTemp = chainExpanderError.apply(chain, InterferenceType.ITER_COUNT);
                            for (VCBFSChain nextChain: nextLevelChainTemp)
                            {
                                // Type I: S+
                                nextLevelQueue.add(nextChain);

                                // Type II: S+ --- ICFG
                                VCHashBytes nextInjectionRunHash = nextChain.getLastVLE().injectionRunHash;
                                LoopHash nextAffectedLoop = nextChain.getLastVLE().affectedLoopID;
                                Map<LoopHash, Set<LoopHash>> parentDelayMap = parentDelayCache.get(Paths.get(injectionResultPathRoot, nextInjectionRunHash.toString() + "_parent_delay.json"));
                                for (LoopHash icfgConnectedLoop : dataset.loopICFG.get(nextAffectedLoop))
                                {
                                    if (!parentDelayMap.getOrDefault(nextAffectedLoop, Collections.emptySet()).contains(icfgConnectedLoop)) continue;
                                    // InjectionRunHash is copied from Type I chain
                                    VCLoopElement vle2 = VCLoopElement.build(nextAffectedLoop, icfgConnectedLoop, VCHashBytes.nullSafeValue(), nextChain.getLastVLE().injectionRunHash, 
                                        VCHashBytes.nullSafeValue(), InterferenceType.ICFG);
                                    VCBFSChain nextChain2 = nextChain.copy();
                                    nextChain2.addVLE(vle2, InjectionType.NONE);
                                    if (nextChain2.loops.size() == nextChain2.vles.size()) 
                                        nextLevelQueue.add(nextChain2);
                                    else // Not a simple cycle anyway, skip connecting
                                        continue; 

                                    // Type III: S+ --- ICFG --- CFG
                                    for (LoopHash cfgConnectedLoop : dataset.loopCFG.get(icfgConnectedLoop))
                                    {
                                        if (Objects.equals(cfgConnectedLoop, nextAffectedLoop)) continue; // Should not be the same as the existing ICFG edge
                                        // InjectionRunHash is copied from Type I chain
                                        VCLoopElement vle3 = VCLoopElement.build(icfgConnectedLoop, cfgConnectedLoop, VCHashBytes.nullSafeValue(), nextChain.getLastVLE().injectionRunHash,
                                                VCHashBytes.nullSafeValue(), InterferenceType.CFG);
                                        VCBFSChain nextChain3 = nextChain2.copy();
                                        nextChain3.addVLE(vle3, InjectionType.NONE);
                                        if (nextChain3.loops.size() == nextChain3.vles.size()) nextLevelQueue.add(nextChain3);
                                    }
                                }
                            }
                            break;
                        case ICFG:
                        case CFG:
                        case ITER_COUNT:
                            nextLevelQueue.addAll(chainExpanderDelay.apply(chain, InterferenceType.EXEC_SIG));
                            // nextLevelQueue.addAll(chainExpanderDelay.apply(chain, InterferenceType.ITER_COUNT));
                            break;
                        default:
                            break;
                    }
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }
            };
            // #endregion ChainExpanders

            Map<AlmostEqualVCBFSChain, Collection<VCBFSChain>> chainClusters = clusterChainUseAlmostEqual(bfsQueue);
            List<VCBFSChain> workload = chainClusters.values().parallelStream()
                .map(chains -> new ArrayList<>(chains))
                .map(chains -> chains.get(ThreadLocalRandom.current().nextInt(chains.size())))
                .collect(Collectors.toList());
            if (workload.size() > beamSize)
            {
                Collections.shuffle(workload);
                if (usePrioritizedBeam)
                {
                    workload.parallelStream().forEach(chain -> chain.computeScore(eSimScores, sSimScores, injectionTestPlan, delayInjNegativeBias));
                    workload.sort(new Comparator<VCBFSChain>() {
                        @Override
                        public int compare(VCBFSChain lhs, VCBFSChain rhs)
                        {
                            return Double.compare(lhs.chainScore, rhs.chainScore);
                        }
                    });
                }
                workload.subList(beamSize, workload.size()).clear();
            }
            System.out.println("BFS Level: " + (bfsLevel++) + " Acutal Workload Size: " + bfsQueue.size() + " Sampled Size: " + workload.size());
            bfsQueue.clear();
            workload.parallelStream().forEach(processOneChain);
            // System.out.println("BFS Level: " + (bfsLevel++) + " Acutal Workload Size: " + bfsQueue.size());
            // bfsQueue.parallelStream().forEach(processOneChain);
            // bfsQueue.clear();
            
            nextLevelQueue.parallelStream().filter(c -> cycleCheck(c)).forEach(e -> e.setIsCycle());
            for (VCBFSChain nextChain: nextLevelQueue)
            {
                if (nextChain.loops.size() != nextChain.vles.size()) // Won't be a simple cycle
                    continue;
                else if (nextChain.isCycle()) 
                {
                    bfsCycles.add(nextChain);
                    continue;
                }
                else if (nextChain.getLastVLE().affectedLoopID.equals(nextChain.firstLoop) && !nextChain.isCycle()) // Forms a cycle, but no cause-effect match
                    continue;
                else if (nextChain.delayCount >= maxDelayCount)
                    continue;
                else if (nextChain.errorCount >= maxErrorCount)
                    continue;
                bfsQueue.add(nextChain);
            }
            System.out.println("  Cycles Found: " + bfsCycles.size());
        } // BFS Loop

        // Dump Results
        bfsCycles.forEach(c -> c.canonicalize(injectionTestPlan));
        List<VCBFSChain> cycles = bfsCycles.stream().distinct().sorted().collect(Collectors.toList());
        try (BufferedWriter bw = Files.newBufferedWriter(Paths.get(cmd.getOptionValue("output_path"), "Cycles_Raw.json")))
        {
            List<Map<String, Object>> t = cycles.stream().map(e -> e.toMap(injectionTestPlan)).collect(Collectors.toList());
            Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
            gson.toJson(t, bw);
        }

        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(Paths.get(cmd.getOptionValue("output_path"), "Cycles_Raw_Stat.txt"))))
        {
            pw.println("Cycle Count: " + cycles.size());
            SortedMultiset<Integer> lengthCounter = TreeMultiset.create();
            SortedMultiset<ImmutablePair<Integer, Integer>> injectionCounter = TreeMultiset.create();
            cycles.forEach(e -> lengthCounter.add(e.vles.size()));
            cycles.forEach(e -> injectionCounter.add(ImmutablePair.of(e.delayCount, e.errorCount)));
            for (int i : lengthCounter.elementSet())
            {
                pw.println("Cycle Length: " + i + "\tCount: " + lengthCounter.count(i));
            }
            pw.println();
            pw.println("[Delay, Error]");
            for (ImmutablePair<Integer, Integer> inj: injectionCounter.elementSet())
            {
                int delayCount = inj.getLeft();
                int errorCount = inj.getRight();
                pw.println("[D " + delayCount + ", E " + errorCount + "]: " + injectionCounter.count(inj));
            }
        }

        try (ObjectOutputStream oos = new ObjectOutputStream(new ZstdOutputStream(new BufferedOutputStream(Files.newOutputStream(Paths.get(cmd.getOptionValue("output_path"), "Cycles_Raw.obj.zst"))))))
        {
            oos.writeObject(cycles);
        }
    }

    public static Map<AlmostEqualVCBFSChain, Collection<VCBFSChain>> clusterChainUseAlmostEqual(List<VCBFSChain> chains)
    {
        Map<AlmostEqualVCBFSChain, Collection<VCBFSChain>> r = new ConcurrentHashMap<>();
        chains.parallelStream().forEach(chain -> 
        {
            AlmostEqualVCBFSChain wrappedChain = new AlmostEqualVCBFSChain(chain);
            r.computeIfAbsent(wrappedChain, k -> new ConcurrentLinkedQueue<>()).add(chain);
        });
        return r;
    }

    public static List<VCBFSChain> generateInitialChains(Map<VCHashBytes, Map<LoopHash, Set<LoopHash>>> interferences, InterferenceType interferenceType, InjectionType injectionType) // RunHash -> {InjectionLoopKey: [Interfered Loop]}
    {
        List<VCBFSChain> r = new ArrayList<>();
        for (VCHashBytes runHash: interferences.keySet())
        {
            Map<LoopHash, Set<LoopHash>> currentInterferences = interferences.get(runHash);
            Map<String, Object> runProf = injectionTestPlan.get(runHash.toString());
            VCHashBytes injectionID = VCHashBytes.wrap((String) runProf.get("Injection ID"));
            VCHashBytes profileRunHash = VCHashBytes.wrap((String) runProf.get("ProfileRunID"));
            for (LoopHash injectionLoopKey: currentInterferences.keySet())
            {
                for (LoopHash interferedLoopKey: currentInterferences.get(injectionLoopKey))
                {
                    VCLoopElement vle = VCLoopElement.build(injectionLoopKey, interferedLoopKey, injectionID, runHash, profileRunHash, interferenceType);
                    VCBFSChain chain = VCBFSChain.build(vle, injectionType);
                    r.add(chain);
                }
            }
        }
        return r;
    }

    public static void loadInterferenceResult(File jsonFile, String runHashStr, Map<VCHashBytes, Map<LoopHash, Set<LoopHash>>> runHashIndexedResult,
            Map<LoopHash, Collection<VCHashBytes>> loopIndexedInjectionRuns, Set<LoopHash> utilLoops) throws FileNotFoundException, IOException, ExecutionException
    {
        if (!jsonFile.exists()) return;
        Map<String, List<String>> interferenceResultRaw;
        if (jsonFile.toString().contains("itercount")) // Callsites are contained in the S+ result
        {
            Map<String, Map<String, List<List<String>>>> loadTmp = iterCountJsonCache.get(jsonFile.getAbsolutePath());
            interferenceResultRaw = loadTmp.entrySet().stream().collect(Collectors.toMap(e -> e.getKey(), e -> Lists.newArrayList(e.getValue().keySet())));
        }
        else
        {
            interferenceResultRaw = execSigJsonCache.get(jsonFile.getAbsolutePath());
        }
        VCHashBytes runHash = VCHashBytes.wrap(runHashStr);
        Map<LoopHash, Set<LoopHash>> interferenceResult = interferenceResultRaw.entrySet().stream().collect(Collectors.toMap(e -> LoopHash.wrap(e.getKey()),
                e -> e.getValue().stream().map(l -> LoopHash.wrap(l)).filter(l -> !utilLoops.contains(l)).collect(Collectors.toSet()), (e1, e2) -> e1, ConcurrentHashMap::new));
        interferenceResult.keySet().removeIf(l -> utilLoops.contains(l));
        if (interferenceResult.size() == 0) return;
        runHashIndexedResult.put(runHash, interferenceResult);
        interferenceResult.keySet().forEach(loopKey -> loopIndexedInjectionRuns.computeIfAbsent(loopKey, k -> new ConcurrentLinkedQueue<>()).add(runHash));
    }

    // Run 1: L1 -- S+ --> L2 
    //        L1 -- S+ --> L' -- ICFG --> L2 
    //        L1 -- S+ --> L' -- ICFG --> L'' -- CFG --> L2
    // L2 is affected under callsites [(foo, bar)]
    // Make sure that in Run 2, the injection loop is under the callsite [(foo, bar)]
    public static boolean hasMatchingIterIncreaseCallsitesForDelay(VCHashBytes injectionRunHash1, LoopHash injectionLoop1, VCHashBytes injectionRunHash2, LoopHash injectionLoop2, boolean shouldPullRun1ResultFromSP)
    {
        try 
        {
            Set<List<String>> run1Loop2Callsites;
            if (shouldPullRun1ResultFromSP) // Run1 last edge is S+
            {
                Map<String, Map<String, List<List<String>>>> iterCountRun1Raw = iterCountJsonCache.get(Paths.get(iterCountResultPathRoot, injectionRunHash1.toString() + "_itercount.json").toString());
                run1Loop2Callsites = new HashSet<>(Utils.getEvenlyIndexedSublist(iterCountRun1Raw.get(injectionLoop1.toString()).get(injectionLoop2.toString())));
            }
            else // Run1 last edge is ICFG/CFG
            {
                Map<String, List<List<String>>> allCallsiteRun1Raw = allLoopCallsiteCache.get(Paths.get(iterCountResultPathRoot, injectionRunHash1.toString() + "_all_callsite.json"));
                run1Loop2Callsites = new HashSet<>(allCallsiteRun1Raw.getOrDefault(injectionLoop2.toString(), Collections.emptyList()));
            }
            Map<String, List<List<String>>> allCallsiteRun2Raw = allLoopCallsiteCache.get(Paths.get(iterCountResultPathRoot, injectionRunHash2.toString() + "_all_callsite.json"));
            Set<List<String>> run2Loop2Callsites = new HashSet<>(allCallsiteRun2Raw.getOrDefault(injectionLoop2.toString(), Collections.emptyList()));
            return !Sets.intersection(run1Loop2Callsites, run2Loop2Callsites).isEmpty();
        }
        catch (Exception e)
        {
            e.printStackTrace();
            return false;
        }
    }

    // Run1: Inject in Loop 1, affect loop 2;
    // Run2: Inject in Loop 2 (InjectionID2)
    // Check for matching signature (allow diff for InjectionID2)
    public static Set<LoopSignature> getMatchingInjectionSignature(VCHashBytes injectionRunHash1, LoopHash injectionLoop1, VCHashBytes injectionID2, VCHashBytes injectionRunHash2, LoopHash injectionLoop2)
    {
        try
        {
            Set<VCHashBytes> allowedDiff = throwBranchPos.get(injectionID2);
            Path injectionRun1SigDiffPath = Paths.get(injectionResultPathRoot, injectionRunHash1.toString() + "_sigdiff.obj.zst");
            Path injectionRun2SigDiffPath = Paths.get(injectionResultPathRoot, injectionRunHash2.toString() + "_sigdiff.obj.zst");

            Map<LoopHash, Map<ImmutableList<String>, Set<LoopSignature>>> injectionRun1AffectedLoopSigDiff = affectedLoopSigDiffCache.get(injectionRun1SigDiffPath.toAbsolutePath().toString());
            Map<ImmutableList<String>, Set<LoopSignature>> injectionLoop2NewSigFromInjection1 = injectionRun1AffectedLoopSigDiff.get(injectionLoop2); // loop2 is affected by injection 1
            Map<ImmutableList<String>, Set<LoopSignature>> injectionLoop2NewSigFromInjection2 = injectionLoopSigDiffCache.get(injectionRun2SigDiffPath.toAbsolutePath().toString());

            boolean matchSigFound = false;
            Set<LoopSignature> matchingSignature = new HashSet<>();
            if (allowedDiff == null) // not a if-throw pattern, exact match
            {
                for (ImmutableList<String> loop2CallSiteKey : injectionLoop2NewSigFromInjection2.keySet())
                {
                    Set<LoopSignature> sigDiffFromInjection2 = injectionLoop2NewSigFromInjection2.get(loop2CallSiteKey);
                    Set<LoopSignature> sigDiffFromInjection1 = injectionLoop2NewSigFromInjection1.get(loop2CallSiteKey);
                    if (sigDiffFromInjection1 == null) continue;
                    Set<LoopSignature> intersection = Sets.intersection(sigDiffFromInjection1, sigDiffFromInjection2);
                    if (!intersection.isEmpty())
                    {
                        matchSigFound = true;
                        matchingSignature.addAll(intersection);
                        break;
                    }
                }
                return matchingSignature;
            }
            else // If-throw pattern, allow diff
            {
                for (ImmutableList<String> loop2CallSiteKey : injectionLoop2NewSigFromInjection2.keySet())
                {
                    Set<LoopSignature> sigDiffFromInjection2 = injectionLoop2NewSigFromInjection2.get(loop2CallSiteKey);
                    Set<LoopSignature> sigDiffFromInjection1 = injectionLoop2NewSigFromInjection1.get(loop2CallSiteKey);
                    if (sigDiffFromInjection1 == null) continue;
                    for (LoopSignature sig1 : sigDiffFromInjection1)
                    {
                        for (LoopSignature sig2 : sigDiffFromInjection2)
                        {
                            // sig2 should be shorter than sig1, because the injection terminates the execution
                            if (Utils.loopSigHasOnlyAllowedDiff(sig1, sig2, allowedDiff))
                            {
                                matchSigFound = true;
                                matchingSignature.add(sig1);
                                break;
                            }
                        }
                        if (matchSigFound) break;
                    }
                    if (matchSigFound) break;
                }
                return matchingSignature;
            }
        }
        catch (NullPointerException e)
        {
            // e.printStackTrace();
            // System.err.println(e);
            return Collections.emptySet();
        }
        catch (Exception e)
        {
            if (e.getCause() instanceof FileNotFoundException) return Collections.emptySet();
            if (e.getCause() instanceof ZstdIOException) return Collections.emptySet();
            System.err.println("Exception in getMatchingInjectionSignature: " + injectionRunHash1 + "|" + injectionRunHash2);
            System.err.println(e);
            return Collections.emptySet();
        }
    }

    public static boolean cycleCheck(VCBFSChain chain)
    {
        if (chain.vles.size() <= 1) return false;
        VCLoopElement firstVLE = chain.vles.get(0);
        VCLoopElement lastVLE = chain.getLastVLE();
        if (!Objects.equals(firstVLE.loopID, lastVLE.affectedLoopID)) return false;
        String firstEdgeType = VCLoopElement.getEdgeType(injectionTestPlan.get(firstVLE.injectionRunHash.toString()), firstVLE.interferenceType);
        // String lastEdgeType = VCLoopElement.getEdgeType(injectionTestPlan.get(lastVLE.injectionRunHash.toString()), lastVLE.interferenceType);
        Set<InterferenceType> acceptableEdgeTypes;
        switch (firstEdgeType)
        {
            case "S+(D)": 
                // We don't want consecutive S+ edges, then it is not possible to have S+(D) edges in the cycle
                return false;
            case "E(D)": // Acceptable: CFG, ICFG, S+(I) [S+(D)]
                // Make sure the last edge and the first edge is connectable
                // Check that the S+ affected callsites have overlap with the E(D) injection callsites
                acceptableEdgeTypes = Sets.newHashSet(InterferenceType.CFG, InterferenceType.ICFG, InterferenceType.ITER_COUNT);
                if (!acceptableEdgeTypes.contains(lastVLE.interferenceType)) return false;
                boolean lastVLEIsSPlus = lastVLE.interferenceType.equals(InterferenceType.ITER_COUNT);
                return hasMatchingIterIncreaseCallsitesForDelay(lastVLE.injectionRunHash, lastVLE.loopID, firstVLE.injectionRunHash, firstVLE.loopID, lastVLEIsSPlus);
            case "E(I)": // Acceptable: E(D), E(I)
            case "S+(I)":
                if (lastVLE.interferenceType != InterferenceType.EXEC_SIG) return false;
                Set<LoopSignature> matchingSig = getMatchingInjectionSignature(lastVLE.injectionRunHash, lastVLE.loopID, firstVLE.injectionID, firstVLE.injectionRunHash, firstVLE.loopID);
                return !matchingSig.isEmpty();
            default:
                return false;
        }
    }

    public static Map<ImmutablePair<VCHashBytes, LoopHash>, Double> loadSimilarityScores(String path, Map<ImmutablePair<VCHashBytes, LoopHash>, Double> out) throws FileNotFoundException, IOException
    {
        List<Map<String, Object>> scoreRaw = Utils.readJson(path);
        for (Map<String, Object> scoreItem: scoreRaw)
        {
            List<String> expID = (List<String>) scoreItem.get("InjectionID_InjectionLoop");
            Double score = (Double) scoreItem.get("Score");
            String injID = expID.get(0);
            String injLoop = expID.get(1);
            out.put(ImmutablePair.of(VCHashBytes.wrap(injID), LoopHash.wrap(injLoop)), score);
        }
        return out;
    }

    public static void loadResultCache()
    {
        // #region Result Cache

        execSigJsonCache = CacheBuilder.newBuilder().weakValues().build(new CacheLoader<String, Map<String, List<String>>>()
        {
            @Override
            public Map<String, List<String>> load(String fullPath) throws Exception
            {
                Type retType = new TypeToken<ConcurrentHashMap<String, List<String>>>()
                {
                }.getType();
                return Utils.readJson(fullPath, retType);
            }
        });

        iterCountJsonCache = CacheBuilder.newBuilder().weakValues().build(new CacheLoader<String, Map<String, Map<String, List<List<String>>>>>()
        {
            @Override
            public Map<String, Map<String, List<List<String>>>> load(String fullPath) throws Exception
            {
                Type retType = new TypeToken<ConcurrentHashMap<String, ConcurrentHashMap<String, List<List<String>>>>>()
                {
                }.getType();
                Map<String, Map<String, List<List<String>>>> r = Utils.readJson(fullPath, retType);
                if (r == null) r = new ConcurrentHashMap<>();
                return r;
            }
        });

        execSigCallsiteJsonCache = CacheBuilder.newBuilder().weakValues().build(new CacheLoader<String, Map<String, List<List<String>>>>()
        {
            @Override
            public Map<String, List<List<String>>> load(String fullPath) throws Exception
            {
                Type retType = new TypeToken<ConcurrentHashMap<String, List<List<String>>>>()
                {
                }.getType();
                return Utils.readJson(fullPath, retType);
            }
        });

        injectionLoopSigDiffCache = CacheBuilder.newBuilder().weakValues().build(new CacheLoader<String, Map<ImmutableList<String>, Set<LoopSignature>>>()
        {
            @Override
            public Map<ImmutableList<String>, Set<LoopSignature>> load(String fullPath) throws Exception
            {
                try (FileInputStream fis = new FileInputStream(fullPath);
                        BufferedInputStream bis = new BufferedInputStream(fis);
                        ZstdInputStream zis = new ZstdInputStream(bis);
                        ObjectInputStream ois = new ObjectInputStream(zis);)
                {
                    Map<ImmutableList<String>, Set<LoopSignature>> injectionLoopSigDiff = (Map<ImmutableList<String>, Set<LoopSignature>>) ois.readObject();
                    return injectionLoopSigDiff;
                }
            }
        });

        affectedLoopSigDiffCache = CacheBuilder.newBuilder().weakValues().build(new CacheLoader<String, Map<LoopHash, Map<ImmutableList<String>, Set<LoopSignature>>>>()
        {
            @Override
            public Map<LoopHash, Map<ImmutableList<String>, Set<LoopSignature>>> load(String fullPath) throws Exception
            {
                try (FileInputStream fis = new FileInputStream(fullPath);
                        BufferedInputStream bis = new BufferedInputStream(fis);
                        ZstdInputStream zis = new ZstdInputStream(bis);
                        ObjectInputStream ois = new ObjectInputStream(zis);)
                {
                    ois.readObject();
                    Map<VCHashBytes, Map<ImmutableList<String>, Set<LoopSignature>>> affectedLoopSigDiff = (Map<VCHashBytes, Map<ImmutableList<String>, Set<LoopSignature>>>) ois
                            .readObject();
                    Map<LoopHash, Map<ImmutableList<String>, Set<LoopSignature>>> r = affectedLoopSigDiff.entrySet().stream().collect(
                            Collectors.toConcurrentMap(e -> LoopHash.wrap(e.getKey().toString()), e -> e.getValue()));

                    return r;
                }
            }
        });

        parentDelayCache = CacheBuilder.newBuilder().weakValues().build(new CacheLoader<Path, Map<LoopHash, Set<LoopHash>>>()
        {
            @Override
            public Map<LoopHash, Set<LoopHash>> load(Path fullPath)
            {
                try
                {
                    Map<String, List<String>> t = Utils.readJson(fullPath.toString());
                    return t.entrySet().stream()
                            .collect(Collectors.toMap(e -> LoopHash.wrap(e.getKey()), e -> e.getValue().stream().map(e2 -> LoopHash.wrap(e2)).collect(Collectors.toSet())));
                }
                catch (IOException e)
                {
                    return Collections.emptyMap();
                }
            }
        });

        allLoopCallsiteCache = CacheBuilder.newBuilder().weakValues().build(new CacheLoader<Path, Map<String, List<List<String>>>>()
        {
            @Override
            public Map<String, List<List<String>>> load(Path path) throws Exception
            {
                Type retType = new TypeToken<ConcurrentHashMap<String, List<List<String>>>>()
                {
                }.getType();
                Map<String, List<List<String>>> t = Utils.readJson(path.toString(), retType);
                return t;
            }
        });

        // #endregion Result Cache
    }
}
