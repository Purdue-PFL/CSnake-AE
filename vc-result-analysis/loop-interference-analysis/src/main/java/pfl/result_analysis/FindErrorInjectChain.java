package pfl.result_analysis;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.PrintWriter;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.stream.Collector;
import java.util.stream.Collectors;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;
import org.apache.commons.lang3.function.TriConsumer;
import org.jgrapht.Graph;
import org.jgrapht.Graphs;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.jgrapht.graph.DefaultEdge;

import com.github.luben.zstd.ZstdInputStream;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.HashMultiset;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Multiset;
import com.google.common.collect.Sets;
import com.google.common.collect.SortedMultiset;
import com.google.common.collect.TreeMultiset;
import com.google.common.math.Stats;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import pfl.result_analysis.dataset.LoopInterferenceDataset;
import pfl.result_analysis.dataset.LoopInterferenceDataset.InjectionType;
import pfl.result_analysis.dataset.LoopInterferenceDataset.InterferenceType;
import pfl.result_analysis.utils.LoopHash;
import pfl.result_analysis.utils.LoopItem;
import pfl.result_analysis.utils.LoopSignature;
import pfl.result_analysis.utils.Utils;
import pfl.result_analysis.utils.VCHashBytes;
import pfl.result_analysis.utils.VCLoopElement;

public class FindErrorInjectChain
{
    public static int ENTRY_ROOT_UTIL_THRESHOLD = 40;
    public static int GENERIC_CONTENTION_TID_THRESHOLD = 5;
    public static LoadingCache<String, Map<String, List<String>>> execSigJsonCache;
    public static LoadingCache<String, Map<String, Map<String, List<List<String>>>>> iterCountJsonCache;
    public static LoadingCache<String, Map<String, List<List<String>>>> execSigCallsiteJsonCache;
    public static LoadingCache<String, Map<ImmutableList<String>, Set<LoopSignature>>> injectionLoopSigDiffCache; // [CallSite] -> Set([LoopSignature])
    public static LoadingCache<String, Map<LoopHash, Map<ImmutableList<String>, Set<LoopSignature>>>> affectedLoopSigDiffCache; // AffectedLoopID -> {[CallSite]:
                                                                                                                                // Set([LoopSignature])}
    public static LoadingCache<Path, Map<LoopHash, Set<LoopHash>>> parentDelayCache;

    public static String injectionResultPathRoot;
    public static String iterCountResultPathRoot;
    public static Map<LoopHash, LoopItem> loopMap;
    public static Map<VCHashBytes, Map<String, Object>> negateInjection;
    public static Map<VCHashBytes, Map<String, Object>> throwInjection;
    public static Map<VCHashBytes, Set<VCHashBytes>> throwBranchPos;
    public static Map<VCHashBytes, Map<LoopHash, List<Integer>>> loopTIDCount;

    public static LoopInterferenceDataset dataset = LoopInterferenceDataset.build();

    public static void main(String[] args) throws Exception
    {
        Options options = new Options();
        options.addOption("injection_testplan", true, "Injection Testplan");
        options.addOption("loop_cfg", true, "System's control flow graph");
        options.addOption("injection_result_path", true, "Per-experiment interference result path (JSON)");
        options.addOption("iter_count_result_path", true, "JSON result path for per-experiment S+ loops");
        options.addOption("loops", true, "json file for loops");
        options.addOption("rpc_links", true, "loops from/to rpc calls");
        options.addOption("entry_root", true, "json file to entry roots");
        options.addOption("output_path", true, "result_output_path");
        options.addOption("negate_injection", true, "Details about the NEGATE injection");
        options.addOption("throw_injection", true, "Details about the throw injection");
        options.addOption("throw_branch_position", true, "The branch position for each if-throw pattern");
        options.addOption("maximum_error_injection", true, "Maximum number of error injection in the chain");
        options.addOption("loop_tid_count", true, "Number of TID executing each loop in each run");
        DefaultParser parser = new DefaultParser();
        CommandLine cmd = parser.parse(options, args);

        loadResultCache();

        // #region Load Supporting data structures

        Map<String, Map<String, Object>> loopMapT = Utils.readJson(cmd.getOptionValue("loops"));
        loopMap = loopMapT.entrySet().stream().collect(Collectors.toMap(e -> LoopHash.wrap(e.getKey()), e -> LoopItem.mapToLoopItem(e.getValue())));

        Type testplanType = new TypeToken<ConcurrentHashMap<String, HashMap<String, Object>>>()
        {
        }.getType();
        Map<String, Map<String, Object>> injectionTestPlan = Utils.readJson(cmd.getOptionValue("injection_testplan"), testplanType);
        Map<String, List<String>> injectionRepeatedRuns = new ConcurrentHashMap<>();
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

        // #endregion Load Supporting data structures

        // #region Prepare masked utility loops
        // Map<String, List<String>> entryRootsT = Utils.readJson(cmd.getOptionValue("entry_root"));
        Map<String, List<String>> entryRoots = new HashMap<>();
        // entryRootsT.forEach((k, v) -> entryRoots.put(Utils.mapWalaFullMethodSignatureToDotSeparatedMethodName(k), v));
        // entryRootsT.clear();

        Set<LoopHash> utilLoops = new HashSet<>();
        for (LoopHash loopKey : loopMap.keySet())
        {
            LoopItem loopItem = loopMap.get(loopKey);
            if (loopItem.func.equals("run")) continue;
            String fullMethodName = loopItem.clazz + "." + loopItem.func;
            if (!entryRoots.containsKey(fullMethodName)) continue;
            if (entryRoots.get(fullMethodName).size() > ENTRY_ROOT_UTIL_THRESHOLD) utilLoops.add(loopKey);
        }
        entryRoots.clear();
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

        // #region Error Injection Chain BFS
        int maxErrorInjCount = Integer.valueOf(cmd.getOptionValue("maximum_error_injection"));
        Set<ImmutableList<VCLoopElement>> vcBFSResult = ConcurrentHashMap.newKeySet();
        // Initiate with length = 1 E(I) chain
        for (LoopHash injLoop1 : dataset.getLoopIndexedInjectionRunsMap(InjectionType.ERROR, InterferenceType.EXEC_SIG).keySet())
        {
            for (VCHashBytes injRun1 : dataset.getLoopIndexedInjectionRunsMap(InjectionType.ERROR, InterferenceType.EXEC_SIG).get(injLoop1))
            {
                Map<String, Object> runProf = injectionTestPlan.get(injRun1.toString());
                VCHashBytes injectionID = VCHashBytes.wrap((String) runProf.get("Injection ID"));
                VCHashBytes profileRunHash = VCHashBytes.wrap((String) runProf.get("ProfileRunID"));
                for (LoopHash affectedLoop1 : dataset.getRunHashIndexedLoopInterferenceMap(InjectionType.ERROR, InterferenceType.EXEC_SIG).get(injRun1).get(injLoop1))
                {
                    VCLoopElement vle = VCLoopElement.build(injLoop1, affectedLoop1, injectionID, injRun1, profileRunHash, InterferenceType.EXEC_SIG);
                    ImmutableList<VCLoopElement> il = ImmutableList.of(vle);
                    if (!vcBFSResult.contains(il))
                    {
                        vcBFSResult.add(il);
                    }
                }
            }
        }
        if (maxErrorInjCount == 0)
        {
            System.out.println("INFO: maximum_error_injection is 0, skipping BFS prefill with E(I) edges");
            vcBFSResult.clear();
        }
        System.out.println("Initial Queue Size: " + vcBFSResult.size());

        // Get error injection Chain
        AtomicInteger progressCounter = new AtomicInteger();
        new ArrayList<>(vcBFSResult).parallelStream().forEach(e ->
        {
            try
            {
                int currentProgress = progressCounter.getAndIncrement();

                Queue<ImmutableList<VCLoopElement>> bfsQueue = new LinkedList<>();
                bfsQueue.add(e);
                do
                {
                    ImmutableList<VCLoopElement> curChain = bfsQueue.poll();
                    if (curChain.size() > maxErrorInjCount) break;
                    vcBFSResult.add(curChain);

                    if (curChain.size() == maxErrorInjCount) continue;

                    VCLoopElement lastLoopInChain = Iterables.getLast(curChain);
                    LoopHash nextInjectionLoop = lastLoopInChain.affectedLoopID;
                    for (VCHashBytes nextInjectionRunHash : dataset.getLoopIndexedInjectionRunsMap(InjectionType.ERROR, InterferenceType.EXEC_SIG).getOrDefault(nextInjectionLoop, Collections.emptyList()))
                    {
                        Map<String, Object> runProf = injectionTestPlan.get(nextInjectionRunHash.toString());
                        VCHashBytes nextInjectionID = VCHashBytes.wrap((String) runProf.get("Injection ID"));
                        VCHashBytes nextProfileRunHash = VCHashBytes.wrap((String) runProf.get("ProfileRunID"));
                        Set<LoopSignature> matchingSig = getMatchingInjectionSignature(lastLoopInChain.injectionRunHash, lastLoopInChain.loopID, nextInjectionID, nextInjectionRunHash, nextInjectionLoop);
                        // if (lastLoopInChain.injectionID.equals(VCHashBytes.wrap("abbef5ce9ed16873fcb636bb7d8f0018")))
                        // {
                        //     System.out.println(lastLoopInChain);
                        //     System.out.println(nextInjectionRunHash);
                        //     System.out.println(nextInjectionLoop);
                        //     // System.exit(0);
                        // }
                        if (matchingSig.isEmpty()) continue;
                        for (LoopHash nextAffectedLoop : dataset.getRunHashIndexedLoopInterferenceMap(InjectionType.ERROR, InterferenceType.EXEC_SIG).get(nextInjectionRunHash) .get(nextInjectionLoop))
                        {
                            VCLoopElement vle = VCLoopElement.build(nextInjectionLoop, nextAffectedLoop, nextInjectionID, nextInjectionRunHash, nextProfileRunHash, InterferenceType.EXEC_SIG);
                            ImmutableList.Builder<VCLoopElement> nextChainBuilder = ImmutableList.builder();
                            nextChainBuilder.addAll(curChain);
                            nextChainBuilder.add(vle);
                            ImmutableList<VCLoopElement> nextChain = nextChainBuilder.build();
                            if (!vcBFSResult.contains(nextChain)) bfsQueue.add(nextChain);
                        }
                    }
                } while (!bfsQueue.isEmpty());
                System.out.println("Queue " + currentProgress + " Done" + "\tChain Count: " + vcBFSResult.size());
            }
            catch (Exception ex)
            {
                ex.printStackTrace();
            }
        });

        // Connect S+ and CFG/ICFG edges for delay injection candidate
        Map<String, Map<String, List<Double>>> loopTIDCountT = Utils.readJson(cmd.getOptionValue("loop_tid_count"));
        loopTIDCount = loopTIDCountT.entrySet().stream().collect(Collectors.toConcurrentMap(e -> VCHashBytes.wrap(e.getKey()), e -> e.getValue().entrySet().stream()
                .collect(Collectors.toConcurrentMap(e2 -> LoopHash.wrap(e2.getKey()), e2 -> e2.getValue().stream().map(e3 -> e3.intValue()).collect(Collectors.toList())))));
        loopTIDCountT.clear();
        Set<ImmutableList<VCLoopElement>> vcChainResult = ConcurrentHashMap.newKeySet();
        TriConsumer<ImmutableList<VCLoopElement>, VCHashBytes, LoopHash> chainExpander = (curChain, nextInjectionRunHash, nextOriginLoop) ->
        {
            try
            {
                Map<String, Object> runProf = injectionTestPlan.get(nextInjectionRunHash.toString());
                VCHashBytes nextInjectionID = VCHashBytes.wrap((String) runProf.get("Injection ID"));
                VCHashBytes nextProfileRunHash = VCHashBytes.wrap((String) runProf.get("ProfileRunID"));
                for (LoopHash nextAffectedLoop : dataset.getRunHashIndexedLoopInterferenceMap(InjectionType.ERROR, InterferenceType.ITER_COUNT).get(nextInjectionRunHash).get(nextOriginLoop))
                {
                    // Type I: S+(I)
                    VCLoopElement vle1 = VCLoopElement.build(nextOriginLoop, nextAffectedLoop, nextInjectionID, nextInjectionRunHash, nextProfileRunHash, InterferenceType.ITER_COUNT);
                    ImmutableList.Builder<VCLoopElement> builder = ImmutableList.builder();
                    builder.addAll(curChain);
                    builder.add(vle1);
                    try
                    {
                        if (Stats.meanOf(loopTIDCount.get(vle1.injectionRunHash).get(vle1.affectedLoopID)) >= GENERIC_CONTENTION_TID_THRESHOLD) vcChainResult.add(builder.build());
                    }
                    catch (NullPointerException e)
                    {
                        // NPE happens when the loopTIDCount.get(vle1.injectionRunHash).get(vle1.affectedLoopID) is null
                        // This is because the loopTIDCount is based on the iterEventsMap (each iterID -> TID)
                        // When the iterID is not associated with an entry in iterEventsMap (happens when no branch is recorded), the value above will be null
                        // It is safe to ignore this, and not add it to the vcChainResult
                        // This is because those loops are usually small loops, which have a small chance of causing any contention
                    }
                    
    
                    // Type II: S+(I) --- ICFG
                    // Load potential parent delay
                    Map<LoopHash, Set<LoopHash>> parentDelayMap = parentDelayCache
                            .get(Paths.get(injectionResultPathRoot, nextInjectionRunHash.toString() + "_parent_delay.json"));
                    for (LoopHash icfgConnectedLoop : dataset.loopICFG.get(nextAffectedLoop))
                    {
                        if (!parentDelayMap.getOrDefault(nextAffectedLoop, Collections.emptySet()).contains(icfgConnectedLoop)) continue;
                        VCLoopElement vle2 = VCLoopElement.build(nextAffectedLoop, icfgConnectedLoop, VCHashBytes.nullSafeValue(), VCHashBytes.nullSafeValue(),
                                VCHashBytes.nullSafeValue(), InterferenceType.ICFG);
                        builder = ImmutableList.builder();
                        builder.addAll(curChain);
                        builder.add(vle1, vle2);
                        vcChainResult.add(builder.build());
    
                        // Type III: S+(I) --- ICFG --- CFG
                        for (LoopHash cfgConnectedLoop : dataset.loopCFG.get(icfgConnectedLoop))
                        {
                            if (Objects.equals(cfgConnectedLoop, nextAffectedLoop)) continue; // Should not be the same as the existing ICFG edge
                            VCLoopElement vle3 = VCLoopElement.build(icfgConnectedLoop, cfgConnectedLoop, VCHashBytes.nullSafeValue(), VCHashBytes.nullSafeValue(),
                                    VCHashBytes.nullSafeValue(), InterferenceType.CFG);
                            builder = ImmutableList.builder();
                            builder.addAll(curChain);
                            builder.add(vle1, vle2, vle3);
                            vcChainResult.add(builder.build());
                        }
                    }
                }
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
        };
        // Store all the S+(I) links
        progressCounter.set(0);
        dataset.getLoopIndexedInjectionRunsMap(InjectionType.ERROR, InterferenceType.ITER_COUNT).keySet().parallelStream().forEach(injLoop1 ->
        {
            dataset.getLoopIndexedInjectionRunsMap(InjectionType.ERROR, InterferenceType.ITER_COUNT).get(injLoop1).parallelStream().forEach(injRun1 -> 
            {
                int currentProgress = progressCounter.getAndIncrement();
                chainExpander.accept(ImmutableList.of(), injRun1, injLoop1);
                System.out.println("Error Chain " + currentProgress + " Done" + "\tChain Count: " + vcChainResult.size());
            });
        });
        // Continue the link on all the BFS-based error chains
        vcBFSResult.parallelStream().forEach(curChain ->
        {
            try
            {
                int currentProgress = progressCounter.getAndIncrement();
                VCLoopElement lastVLE = Iterables.getLast(curChain);
                LoopHash nextOriginLoop = lastVLE.affectedLoopID;
                for (VCHashBytes nextInjectionRun: dataset.getLoopIndexedInjectionRunsMap(InjectionType.ERROR, InterferenceType.ITER_COUNT).getOrDefault(nextOriginLoop, Collections.emptyList()))
                {
                    chainExpander.accept(curChain, nextInjectionRun, nextOriginLoop);
                }
                System.out.println("Error Chain " + currentProgress + " Done" + "\tChain Count: " + vcChainResult.size());
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
        });

        // #endregion Error Injection Chain BFS

        vcChainResult.removeIf(e -> e.stream().anyMatch(e2 -> e2.loopID.equals(LoopHash.wrap("842c295bf9357ebeee80beff0ca4a8cb"))));
        vcChainResult.removeIf(e -> e.stream().anyMatch(e2 -> e2.affectedLoopID.equals(LoopHash.wrap("842c295bf9357ebeee80beff0ca4a8cb"))));
        vcChainResult.removeIf(e -> e.stream().anyMatch(e2 -> e2.loopID.equals(e2.affectedLoopID)));
        try (BufferedWriter bw = Files.newBufferedWriter(Paths.get(cmd.getOptionValue("output_path"), "VC_Error_Chains.json")))
        {
            List<List<Map<String, String>>> vcChainResultO = vcChainResult.stream().map(e -> e.stream().map(e2 -> e2.toMap()).collect(Collectors.toList()))
                    .collect(Collectors.toList());
            Collections.sort(vcChainResultO, new Comparator<List<Map<String, String>>>()
            {
                @Override
                public int compare(List<Map<String, String>> lhs, List<Map<String, String>> rhs)
                {
                    return Integer.compare(lhs.size(), rhs.size());
                }
            });
            Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
            gson.toJson(vcChainResultO, bw);
        }
        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(Paths.get(cmd.getOptionValue("output_path"), "VC_Error_Chain_Stat.txt"))))
        {
            pw.println("Chain Count: " + vcChainResult.size());
            SortedMultiset<Integer> lengthCounter = TreeMultiset.create();
            vcChainResult.stream().forEach(e -> lengthCounter.add(e.size()));
            for (int i : lengthCounter.elementSet())
            {
                pw.println("Chain Length: " + i + "\tCount: " + lengthCounter.count(i));
            }

            Set<LoopHash> delayInjectionLoop = new HashSet<>();
            vcChainResult.stream().forEach(e -> delayInjectionLoop.add(Iterables.getLast(e).affectedLoopID));
            pw.println("Delay Injection Loop Count: " + delayInjectionLoop.size());
            pw.println(delayInjectionLoop);
        }
        try (BufferedWriter bw = Files.newBufferedWriter(Paths.get(cmd.getOptionValue("output_path"), "VC_Delay_Inj_Candidate.json")))
        {
            Map<LoopHash, Set<VCHashBytes>> delayInjectionUnitTests = new HashMap<>();
            for (ImmutableList<VCLoopElement> chain: vcChainResult)
            {
                Set<VCHashBytes> profileRunHashes = chain.stream().map(e -> e.profileRunHash).filter(e -> !e.equals(VCHashBytes.nullSafeValue())).collect(Collectors.toSet());
                delayInjectionUnitTests.computeIfAbsent(Iterables.getLast(chain).affectedLoopID, k -> new HashSet<>()).addAll(profileRunHashes);
            }
            Map<String, List<String>> delayInjectionUnitTestsO = delayInjectionUnitTests.entrySet().stream().collect(
                    Collectors.toMap(e -> e.getKey().toString(), e -> e.getValue().stream().map(e2 -> e2.toString()).collect(Collectors.toList())));
            Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
            gson.toJson(delayInjectionUnitTestsO, bw);
        }
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

    // Run1: Inject in Loop 1, affect loop 2;
    // Run2: Inject in Loop 2 (InjectionID2)
    // Check for matching signature (allow diff for InjectionID2)
    public static Set<LoopSignature> getMatchingInjectionSignature(VCHashBytes injectionRunHash1, LoopHash injectionLoop1, VCHashBytes injectionID2,
            VCHashBytes injectionRunHash2, LoopHash injectionLoop2)
    {
        try
        {
            Set<VCHashBytes> allowedDiff = throwBranchPos.get(injectionID2);
            Path injectionRun1SigDiffPath = Paths.get(injectionResultPathRoot, injectionRunHash1.toString() + "_sigdiff.obj.zst");
            Path injectionRun2SigDiffPath = Paths.get(injectionResultPathRoot, injectionRunHash2.toString() + "_sigdiff.obj.zst");

            Map<LoopHash, Map<ImmutableList<String>, Set<LoopSignature>>> injectionRun1AffectedLoopSigDiff = affectedLoopSigDiffCache
                    .get(injectionRun1SigDiffPath.toAbsolutePath().toString());
            Map<ImmutableList<String>, Set<LoopSignature>> injectionLoop2NewSigFromInjection1 = injectionRun1AffectedLoopSigDiff.get(injectionLoop2); // loop2 is affected by
                                                                                                                                                      // injection 1

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
            System.err.println("Exception in getMatchingInjectionSignature: " + injectionRunHash1 + "|" + injectionRunHash2);
            System.err.println(e);
            return Collections.emptySet();
        }
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

        // #endregion Result Cache
    }
}
