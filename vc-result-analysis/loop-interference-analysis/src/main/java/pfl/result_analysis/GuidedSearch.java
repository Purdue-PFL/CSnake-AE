package pfl.result_analysis;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.PrintWriter;
import java.lang.reflect.Type;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IntSummaryStatistics;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;
import org.apache.commons.lang3.StringUtils;
import org.jgrapht.Graph;
import org.jgrapht.GraphPath;
import org.jgrapht.Graphs;
import org.jgrapht.alg.interfaces.ShortestPathAlgorithm;
import org.jgrapht.alg.shortestpath.DijkstraShortestPath;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.jgrapht.graph.DefaultEdge;

import com.github.luben.zstd.ZstdInputStream;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.gson.reflect.TypeToken;
import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.AnalysisCache;
import com.ibm.wala.ipa.callgraph.AnalysisCacheImpl;
import com.ibm.wala.ipa.callgraph.AnalysisOptions;
import com.ibm.wala.ipa.callgraph.impl.AllApplicationEntrypoints;
import com.ibm.wala.ipa.callgraph.impl.Everywhere;
import com.ibm.wala.ipa.cfg.ExceptionPrunedCFG;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAInvokeInstruction;
import com.ibm.wala.types.ClassLoaderReference;
import com.ibm.wala.types.TypeReference;

import pfl.graph.IMethodRawNameNode;
import pfl.result_analysis.graph.CFGEdge;
import pfl.result_analysis.utils.LoopHash;
import pfl.result_analysis.utils.LoopItem;
import pfl.result_analysis.utils.LoopSignature;
import pfl.result_analysis.utils.Utils;
import pfl.result_analysis.utils.VCHashBytes;
import pfl.result_analysis.utils.ViciousCycleResult;

public class GuidedSearch
{
    public static int ENTRY_ROOT_UTIL_THRESHOLD = 40;
    public static LoadingCache<String, Map<String, List<String>>> execSigJsonCache;
    public static LoadingCache<String, Map<String, Map<String, List<List<String>>>>> iterCountJsonCache;
    public static LoadingCache<String, Map<String, List<List<String>>>> execSigCallsiteJsonCache;
    public static LoadingCache<String, Map<ImmutableList<String>, Set<LoopSignature>>> injectionLoopSigDiffCache; // [CallSite] -> Set([LoopSignature])
    public static LoadingCache<String, Map<LoopHash, Map<ImmutableList<String>, Set<LoopSignature>>>> affectedLoopSigDiffCache; // AffectedLoopID -> {[CallSite]: Set([LoopSignature])}
    
    public static String injectionResultPathRoot;
    public static String iterCountResultPathRoot;
    public static Map<LoopHash, LoopItem> loopMap;
    public static Map<VCHashBytes, Map<String, Object>> negateInjection;
    public static Map<VCHashBytes, Map<String, Object>> throwInjection;

    public static boolean PERFORMANCE_INTERFERENCE_ONLY = true;
    public static boolean ENABLE_GENERIC_CONTENTION = false;
    public static boolean ENABLE_RETRY_DETECTION = false;

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
        DefaultParser parser = new DefaultParser();
        CommandLine cmd = parser.parse(options, args);

        //#region Result Cache
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
                    Map<VCHashBytes, Map<ImmutableList<String>, Set<LoopSignature>>> affectedLoopSigDiff = (Map<VCHashBytes, Map<ImmutableList<String>, Set<LoopSignature>>>) ois.readObject();
                    Map<LoopHash, Map<ImmutableList<String>, Set<LoopSignature>>> r = affectedLoopSigDiff.entrySet().stream().collect(
                            Collectors.toConcurrentMap(e -> LoopHash.wrap(e.getKey().toString()), e -> e.getValue()));

                    return r;
                }
            }
        });

        //#endregion Result Cache

        //#region Load Supporting data structures

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

        // Remove all the hasXXX(), util.XXX(), and XXXUtil.YYY() injections
        Set<VCHashBytes> utilInjectionID = new HashSet<>();
        for (VCHashBytes negateKey: negateInjection.keySet())
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
        for (VCHashBytes throwKey: throwInjection.keySet())
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
            for (Map<String, Object> exStep: throwableConstructionSteps)
            {
                String exType = (String) exStep.get("type");
                if (exType.equals("java.lang.IllegalArgumentException")) 
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

        //#endregion Load Supporting data structures

        //#region Prepare masked utility loops
        Map<String, List<String>> entryRootsT = Utils.readJson(cmd.getOptionValue("entry_root"));
        Map<String, List<String>> entryRoots = new HashMap<>();
        entryRootsT.forEach((k, v) -> entryRoots.put(Utils.mapWalaFullMethodSignatureToDotSeparatedMethodName(k), v));
        entryRootsT.clear();

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
        //#endregion Prepare masked utility loops

        //#region Load Loop CFG
        Graph<LoopHash, DefaultEdge> loopCFG;
        try (FileInputStream fis = new FileInputStream(cmd.getOptionValue("loop_cfg"));
                BufferedInputStream bis = new BufferedInputStream(fis);
                ObjectInputStream ois = new ObjectInputStream(bis);)
        {
            loopCFG = (DefaultDirectedGraph<LoopHash, DefaultEdge>) ois.readObject();
        }
        // Essentially, we remove the client
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
        Map<String, Set<LoopHash>> methodToLoopMap = new HashMap<>();
        for (LoopHash loopKey : loopCFG.vertexSet())
        {
            LoopItem loopItem = loopMap.get(loopKey);
            String fullMethodName = loopItem.clazz + "." + loopItem.func;
            methodToLoopMap.computeIfAbsent(fullMethodName, k -> new HashSet<>()).add(loopKey);
        }
        Map<LoopHash, Set<LoopHash>> loopsInSameMethod = new ConcurrentHashMap<>();
        for (LoopHash loopKey: loopCFG.vertexSet())
        {
            LoopItem loopItem = loopMap.get(loopKey);
            String fullMethodName = loopItem.clazz + "." + loopItem.func;
            loopsInSameMethod.put(loopKey, methodToLoopMap.get(fullMethodName));
        }

        //#endregion Load Loop CFG

        // #region Load per-experiment
        injectionResultPathRoot = cmd.getOptionValue("injection_result_path");
        iterCountResultPathRoot = cmd.getOptionValue("iter_count_result_path", injectionResultPathRoot);
        // E(D) edges
        Map<VCHashBytes, Map<LoopHash, Set<LoopHash>>> execInterferenceFromDelay = new ConcurrentHashMap<>(); // RunHash -> {LoopInterference}
        Map<LoopHash, Collection<VCHashBytes>> loopIndexedDelayRunsWithExecInterference = new ConcurrentHashMap<>(); // Injection loop -> [RunHash]
        // E(I) edges
        Map<VCHashBytes, Map<LoopHash, Set<LoopHash>>> execInterferenceFromFault = new ConcurrentHashMap<>();
        Map<LoopHash, Collection<VCHashBytes>> loopIndexedFaultRunsWithExecInterference = new ConcurrentHashMap<>();
        // S+(D) edges
        Map<VCHashBytes, Map<LoopHash, Set<LoopHash>>> iterCountInterferenceFromDelay = new ConcurrentHashMap<>();
        Map<LoopHash, Collection<VCHashBytes>> loopIndexedDelayRunsWithIterCountInterference = new ConcurrentHashMap<>();
        // S+(I) edges
        Map<VCHashBytes, Map<LoopHash, Set<LoopHash>>> iterCountInterferenceFromFault = new ConcurrentHashMap<>();
        Map<LoopHash, Collection<VCHashBytes>> loopIndexedFaultRunsWithIterCountInterference = new ConcurrentHashMap<>();
        for (String injectionRunHashStr : injectionRepeatedRuns.keySet())
        {
            boolean isDelayInjection = injectionTestPlan.get(injectionRunHashStr).get("InjectionType").equals("DELAY");
            File execInterferenceResultFile = Paths.get(injectionResultPathRoot, injectionRunHashStr + ".json").toFile();
            if (isDelayInjection)
                loadInterferenceResult(execInterferenceResultFile, injectionRunHashStr, execInterferenceFromDelay, loopIndexedDelayRunsWithExecInterference, utilLoops);
            else
                loadInterferenceResult(execInterferenceResultFile, injectionRunHashStr, execInterferenceFromFault, loopIndexedFaultRunsWithExecInterference, utilLoops);
            File iterCountIterferenenceResultFile = Paths.get(iterCountResultPathRoot, injectionRunHashStr + "_itercount.json").toFile();
            if (isDelayInjection)
                loadInterferenceResult(iterCountIterferenenceResultFile, injectionRunHashStr, iterCountInterferenceFromDelay, loopIndexedDelayRunsWithIterCountInterference, utilLoops);
            else
                loadInterferenceResult(iterCountIterferenenceResultFile, injectionRunHashStr, iterCountInterferenceFromFault, loopIndexedFaultRunsWithIterCountInterference, utilLoops);
        }
        System.out.println("Loaded");
        // #endregion Load per-experiment

        Set<ViciousCycleResult> results = ConcurrentHashMap.newKeySet();
        //#region Retry loop Type 1
        if (ENABLE_RETRY_DETECTION)
        {
            // S+(D)
            loopIndexedDelayRunsWithIterCountInterference.keySet().parallelStream().forEach(loopKey1 -> // for (LoopHash loopKey1: loopIndexedDelayRunsWithIterCountInterference.keySet())
            {
                try
                {
                    Set<LoopHash> loop1Preds = new HashSet<>(Graphs.predecessorListOf(loopCFG, loopKey1));
                    for (VCHashBytes delayRunHash: loopIndexedDelayRunsWithIterCountInterference.get(loopKey1))
                    {
                        Set<LoopHash> affectedLoops1SPlus_D = iterCountInterferenceFromDelay.get(delayRunHash).get(loopKey1);
                        for (LoopHash loopKey2: affectedLoops1SPlus_D)
                        {
                            // if (loopKey1.toString().equals("f14a4c17ad1a41db2661013456cdb966") && loopKey2.toString().equals("0316afeb8d9ff724a4e1bdf8ec3d473d")) System.out.println("Found");
                            if (loopCFG.containsEdge(loopKey2, loopKey1))
                            {
                                ViciousCycleResult vcr = new ViciousCycleResult();
                                vcr.addLoop(loopKey1, "S+(D)");
                                vcr.addLoop(loopKey2, "CFG");
                                vcr.addInjection(getInjectionID(injectionTestPlan, delayRunHash));
                                vcr.addRunHash(delayRunHash);
                                results.add(vcr);
                            }
        
                            Set<LoopHash> loop0Candidates = new HashSet<>(Graphs.predecessorListOf(loopCFG, loopKey2));
                            loop0Candidates.retainAll(loop1Preds);
                            loop0Candidates.removeIf(loopKey0 -> !isTrueICFGEdge_IterCount(loopKey2, loopKey0, delayRunHash, loopKey1));
                            loop0Candidates.forEach(loopKey0 ->
                            {
                                ViciousCycleResult vcr = new ViciousCycleResult();
                                vcr.addLoop(loopKey0, "CFG");
                                vcr.addLoop(loopKey1, "S+(D)");
                                vcr.addLoop(loopKey2, "ICFG");
                                vcr.addInjection(getInjectionID(injectionTestPlan, delayRunHash));
                                vcr.addRunHash(delayRunHash);
                                results.add(vcr);
                                // if (loopKey1.toString().equals("f14a4c17ad1a41db2661013456cdb966") && loopKey2.toString().equals("0316afeb8d9ff724a4e1bdf8ec3d473d")) System.out.println(vcr);
                            });
                        }
                    }
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }
            });

            // S+(I)
            loopIndexedFaultRunsWithIterCountInterference.keySet().parallelStream().forEach(loopKey1 -> // for (LoopHash loopKey1: loopIndexedFaultRunsWithIterCountInterference.keySet())
            {
                try 
                {
                    Set<LoopHash> loop1Preds = new HashSet<>(Graphs.predecessorListOf(loopCFG, loopKey1));
                    for (VCHashBytes faultRunHash: loopIndexedFaultRunsWithIterCountInterference.get(loopKey1))
                    {
                        Set<LoopHash> affectedLoops1SPlus_D = iterCountInterferenceFromFault.get(faultRunHash).get(loopKey1);
                        for (LoopHash loopKey2: affectedLoops1SPlus_D)
                        {
                            if (loopCFG.containsEdge(loopKey2, loopKey1))
                            {
                                ViciousCycleResult vcr = new ViciousCycleResult();
                                vcr.addLoop(loopKey1, "S+(I)");
                                vcr.addLoop(loopKey2, "CFG");
                                vcr.addInjection(getInjectionID(injectionTestPlan, faultRunHash));
                                vcr.addRunHash(faultRunHash);
                                results.add(vcr);
                            }
        
                            Set<LoopHash> loop0Candidates = new HashSet<>(Graphs.predecessorListOf(loopCFG, loopKey2));
                            loop0Candidates.retainAll(loop1Preds);
                            loop0Candidates.removeIf(loopKey0 -> !isTrueICFGEdge_IterCount(loopKey2, loopKey0, faultRunHash, loopKey1));
                            loop0Candidates.forEach(loopKey0 ->
                            {
                                ViciousCycleResult vcr = new ViciousCycleResult();
                                vcr.addLoop(loopKey0, "CFG");
                                vcr.addLoop(loopKey1, "S+(I)");
                                vcr.addLoop(loopKey2, "ICFG");
                                vcr.addInjection(getInjectionID(injectionTestPlan, faultRunHash));
                                vcr.addRunHash(faultRunHash);
                                results.add(vcr);
                            });
                        }
                    }
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }
            });
        }

        //#endregion

        // #region Timer Monitor (Type 2/3B/3D)

        loopIndexedDelayRunsWithExecInterference.keySet().parallelStream().forEach(loopKey1 -> // for (LoopHash loopKey1 : loopIndexedDelayRunsWithExecInterference.keySet())
        {
            try 
            {
                for (VCHashBytes delayRunHash : loopIndexedDelayRunsWithExecInterference.get(loopKey1))
                {
                    Set<LoopHash> affectedLoops1E_D = execInterferenceFromDelay.get(delayRunHash).get(loopKey1);
                    Set<LoopHash> affectedLoops1SPlus_D = iterCountInterferenceFromDelay.getOrDefault(delayRunHash, new HashMap<>()).getOrDefault(loopKey1, new HashSet<>());
                    for (LoopHash loopKey2 : expandLoopsInSameMethod(execInterferenceFromDelay.get(delayRunHash).get(loopKey1), loopsInSameMethod))
                    {
                        // if (loopKey1.toString().equals("5ae494a5e38f702f3a9afddfdc669f30") && loopKey2.toString().equals("e236495077c697e12a6b37f7a2c15136")) System.out.println("Found"); 
    
                        //#region Type 2: Loop 2 ---E(I)/S+(I)--> Loop 1
                        for (VCHashBytes faultRunHash : loopIndexedFaultRunsWithExecInterference.getOrDefault(loopKey2, new ArrayList<>()))
                        {
                            // Exclude the fault injections that does not have matching signatures with the delay injection
                            Set<LoopSignature> matchingInjectionSignature = affectedLoops1E_D.contains(loopKey2) ? 
                                getMatchingInjectionSignatureForDelay(delayRunHash, loopKey1, faultRunHash, loopKey2) : Collections.emptySet();
                            boolean shouldExclude = affectedLoops1E_D.contains(loopKey2) ? matchingInjectionSignature.isEmpty() : false;
                            if (shouldExclude) continue;
                            
                            // E(I)
                            if (!PERFORMANCE_INTERFERENCE_ONLY)
                            {
                                Set<LoopHash> affectedLoops2E_I = execInterferenceFromFault.get(faultRunHash).get(loopKey2);
                                if (affectedLoops2E_I.contains(loopKey1))
                                {
                                    ViciousCycleResult vcr = new ViciousCycleResult();
                                    vcr.addLoop(loopKey1, "E(D)");
                                    vcr.addLoop(loopKey2, "E(I)");
                                    vcr.addInjection(getInjectionID(injectionTestPlan, delayRunHash));
                                    vcr.addInjection(getInjectionID(injectionTestPlan, faultRunHash), matchingInjectionSignature);
                                    vcr.addRunHash(delayRunHash);
                                    vcr.addRunHash(faultRunHash);
                                    processAdditionalLoop(vcr, loopKey1, affectedLoops1E_D, loopKey2, affectedLoops2E_I, "E(D)", "E(I)");
                                    results.add(vcr);
                                }
                            }
    
                            // S+(I)
                            if (ENABLE_GENERIC_CONTENTION)
                            {
                                Set<LoopHash> affectedLoops2SPlus_I = iterCountInterferenceFromFault.getOrDefault(faultRunHash, new HashMap<>()).getOrDefault(loopKey2, new HashSet<>());
                                if (affectedLoops2SPlus_I.contains(loopKey1))
                                {
                                    ViciousCycleResult vcr = new ViciousCycleResult();
                                    vcr.addLoop(loopKey1, "E(D)");
                                    vcr.addLoop(loopKey2, "S+(I)");
                                    vcr.addInjection(getInjectionID(injectionTestPlan, delayRunHash));
                                    vcr.addInjection(getInjectionID(injectionTestPlan, faultRunHash));
                                    vcr.addRunHash(delayRunHash);
                                    vcr.addRunHash(faultRunHash);
                                    processAdditionalLoop(vcr, loopKey1, affectedLoops1SPlus_D, loopKey2, affectedLoops2SPlus_I, "S+(D)", "S+(I)");
                                    results.add(vcr);
                                }
                            }
                        }
                        //#endregion Type 2
    
                        //#region Type 3B/3D
                        for (VCHashBytes faultRunHash : loopIndexedFaultRunsWithExecInterference.getOrDefault(loopKey2, new ArrayList<>()))
                        {
                            // Exclude the fault injections that does not have matching signatures with the delay injection
                            Set<LoopSignature> matchingInjectionSignature = affectedLoops1E_D.contains(loopKey2) ? 
                                getMatchingInjectionSignatureForDelay(delayRunHash, loopKey1, faultRunHash, loopKey2) : Collections.emptySet();
                            boolean shouldExclude = affectedLoops1E_D.contains(loopKey2) ? matchingInjectionSignature.isEmpty() : false;
                            if (shouldExclude) continue;

                            //#region E(I)
                            if (!PERFORMANCE_INTERFERENCE_ONLY)
                            {
                                Set<LoopHash> affectedLoops2E_I = execInterferenceFromFault.get(faultRunHash).get(loopKey2);
                                // Loop1 --E(D)--> Loop2; 
                                // Loop2 --E(I)--> Loop3; 
                                // Loop3 --ICFG--> Loop1
                                Set<LoopHash> loop3Candidates = new HashSet<>(Graphs.successorListOf(loopCFG, loopKey1));
                                loop3Candidates.retainAll(affectedLoops2E_I);
                                loop3Candidates.removeIf(loopKey3 -> !isTrueICFGEdge_ExecSig(loopKey3, loopKey1, faultRunHash, loopKey2));
                                loop3Candidates = expandLoopsInSameMethod(loop3Candidates, loopsInSameMethod);
                                // if (loopKey1.toString().equals("5ae494a5e38f702f3a9afddfdc669f30") && loopKey2.toString().equals("e236495077c697e12a6b37f7a2c15136"))
                                // {
                                //     System.out.println(loop3Candidates);
                                // }
                                loop3Candidates.forEach(loopKey3 -> 
                                {
                                    ViciousCycleResult vcr = new ViciousCycleResult();
                                    vcr.addLoop(loopKey1, "E(D)");
                                    vcr.addLoop(loopKey2, "E(I)");
                                    vcr.addLoop(loopKey3, "ICFG");
                                    vcr.addInjection(getInjectionID(injectionTestPlan, delayRunHash));
                                    vcr.addInjection(getInjectionID(injectionTestPlan, faultRunHash), matchingInjectionSignature);
                                    vcr.addRunHash(delayRunHash);
                                    vcr.addRunHash(faultRunHash);
                                    processAdditionalLoop(vcr, loopKey1, affectedLoops1E_D, loopKey2, affectedLoops2E_I, "E(D)", "E(I)");
                                    results.add(vcr);
                                    // if (loopKey1.toString().equals("5ae494a5e38f702f3a9afddfdc669f30") && loopKey2.toString().equals("e236495077c697e12a6b37f7a2c15136")) System.out.println(vcr);
                                });
                                // Loop0 --CFG--> Loop1; 
                                // Loop1 --E(D)--> Loop2; 
                                // Loop2 --E(I)--> Loop3; 
                                // Loop3 --ICFG--> Loop0
                                Set<LoopHash> loop0Candidates = new HashSet<>(Graphs.predecessorListOf(loopCFG, loopKey1));
                                loop0Candidates.removeIf(loopKey0 ->
                                {
                                    Set<LoopHash> loop0CFGSuccs = new HashSet<>(Graphs.successorListOf(loopCFG, loopKey0));
                                    loop0CFGSuccs.retainAll(affectedLoops2E_I);
                                    return loop0CFGSuccs.size() == 0;
                                });
                                for (LoopHash loopKey0: loop0Candidates)
                                {
                                    loop3Candidates = new HashSet<>(Graphs.successorListOf(loopCFG, loopKey0));
                                    loop3Candidates.retainAll(affectedLoops2E_I);
                                    loop3Candidates.removeIf(loopKey3 -> !isTrueICFGEdge_ExecSig(loopKey3, loopKey0, faultRunHash, loopKey2));
                                    loop3Candidates = expandLoopsInSameMethod(loop3Candidates, loopsInSameMethod);
                                    loop3Candidates.forEach(loopKey3 -> 
                                    {
                                        ViciousCycleResult vcr = new ViciousCycleResult();
                                        vcr.addLoop(loopKey0, "CFG");
                                        vcr.addLoop(loopKey1, "E(D)");
                                        vcr.addLoop(loopKey2, "E(I)");
                                        vcr.addLoop(loopKey3, "ICFG");
                                        vcr.addInjection(getInjectionID(injectionTestPlan, delayRunHash));
                                        vcr.addInjection(getInjectionID(injectionTestPlan, faultRunHash), matchingInjectionSignature);
                                        vcr.addRunHash(delayRunHash);
                                        vcr.addRunHash(faultRunHash);
                                        processAdditionalLoop(vcr, loopKey1, affectedLoops1E_D, loopKey2, affectedLoops2E_I, "E(D)", "E(I)");
                                        results.add(vcr);
                                    });
                                }
                            }
                            //#endregion E(I)
    
                            //#region S+(I)
                            Set<LoopHash> affectedLoops2SPlus_I = iterCountInterferenceFromFault.getOrDefault(faultRunHash, new HashMap<>()).getOrDefault(loopKey2, new HashSet<>());
                            // Loop1 --E(D)--> Loop2; 
                            // Loop2 --S+(I)--> Loop3; 
                            // Loop3 --ICFG--> Loop1
                            Set<LoopHash> loop3Candidates = new HashSet<>(Graphs.successorListOf(loopCFG, loopKey1));
                            loop3Candidates.retainAll(affectedLoops2SPlus_I);
                            loop3Candidates.removeIf(loopKey3 -> !isTrueICFGEdge_IterCount(loopKey3, loopKey1, faultRunHash, loopKey2));
                            loop3Candidates = expandLoopsInSameMethod(loop3Candidates, loopsInSameMethod);
                            loop3Candidates.forEach(loopKey3 ->
                            {
                                ViciousCycleResult vcr = new ViciousCycleResult();
                                vcr.addLoop(loopKey1, "E(D)");
                                vcr.addLoop(loopKey2, "S+(I)");
                                vcr.addLoop(loopKey3, "ICFG");
                                vcr.addInjection(getInjectionID(injectionTestPlan, delayRunHash));
                                vcr.addInjection(getInjectionID(injectionTestPlan, faultRunHash), matchingInjectionSignature);
                                vcr.addRunHash(delayRunHash);
                                vcr.addRunHash(faultRunHash);
                                processAdditionalLoop(vcr, loopKey1, affectedLoops1SPlus_D, loopKey2, affectedLoops2SPlus_I, "S+(D)", "S+(I)");
                                results.add(vcr);
                            });
                            // Loop0 --CFG--> Loop1; 
                            // Loop1 --E(D)--> Loop2; 
                            // Loop2 --S+(I)--> Loop3; 
                            // Loop3 --ICFG--> Loop0
                            Set<LoopHash> loop0Candidates = new HashSet<>(Graphs.predecessorListOf(loopCFG, loopKey1));
                            loop0Candidates.removeIf(loopKey0 ->
                            {
                                Set<LoopHash> loop0CFGSuccs = new HashSet<>(Graphs.successorListOf(loopCFG, loopKey0));
                                loop0CFGSuccs.retainAll(affectedLoops2SPlus_I);
                                return loop0CFGSuccs.size() == 0;
                            });
                            for (LoopHash loopKey0: loop0Candidates)
                            {
                                loop3Candidates = new HashSet<>(Graphs.successorListOf(loopCFG, loopKey0));
                                loop3Candidates.retainAll(affectedLoops2SPlus_I);
                                loop3Candidates.removeIf(loopKey3 -> !isTrueICFGEdge_IterCount(loopKey3, loopKey0, faultRunHash, loopKey2));
                                loop3Candidates = expandLoopsInSameMethod(loop3Candidates, loopsInSameMethod);
                                loop3Candidates.forEach(loopKey3 -> 
                                {
                                    ViciousCycleResult vcr = new ViciousCycleResult();
                                    vcr.addLoop(loopKey0, "CFG");
                                    vcr.addLoop(loopKey1, "E(D)");
                                    vcr.addLoop(loopKey2, "S+(I)");
                                    vcr.addLoop(loopKey3, "ICFG");
                                    vcr.addInjection(getInjectionID(injectionTestPlan, delayRunHash));
                                    vcr.addInjection(getInjectionID(injectionTestPlan, faultRunHash), matchingInjectionSignature);
                                    vcr.addRunHash(delayRunHash);
                                    vcr.addRunHash(faultRunHash);
                                    processAdditionalLoop(vcr, loopKey1, affectedLoops1SPlus_D, loopKey2, affectedLoops2SPlus_I, "S+(D)", "S+(I)");
                                    results.add(vcr);
                                });
                            }
                            //#endregion S+(I)
                        }
                        //#endregion Type 3B/3D
                    }
                }
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
        });

        //#endregion Timer Monitor (Type 2/3B/3D)

        results.removeIf(vcr -> vcr.loops.contains(LoopHash.wrap("842c295bf9357ebeee80beff0ca4a8cb")) || vcr.loops.contains(LoopHash.wrap("99c5d06459ccbccd035d5512ca2a80fb")));
        results.removeIf(vcr -> vcr.loops.contains(LoopHash.wrap("27bfeb03ed627904849241908b2977d9")));
        results.removeIf(vcr -> vcr.loops.contains(LoopHash.wrap("62eceab3cd7c303fae52987062b23d3c")));
        results.removeIf(vcr -> 
        {
            Set<LoopHash> loopSet = new HashSet<>(vcr.loops);
            return vcr.loops.size() != loopSet.size();
        }); // Remove VCs with duplicate loops inside

        Map<List<VCHashBytes>, List<ViciousCycleResult>> resultGroupByInjection = new HashMap<>();
        for (ViciousCycleResult vcr: results)
        {
            resultGroupByInjection.computeIfAbsent(vcr.injectionIDs, k -> new ArrayList<>()).add(vcr);
        }
        Paths.get(cmd.getOptionValue("output_path")).toFile().mkdirs();
        int idx = 0;
        for (List<ViciousCycleResult> vcrs: resultGroupByInjection.values())
        {
            int outputChunkIdx = idx / 1000;
            Path outputFolder = Paths.get(cmd.getOptionValue("output_path"), StringUtils.leftPad(Integer.toString(outputChunkIdx), 5, '0'));
            outputFolder.toFile().mkdirs();

            Collections.sort(vcrs);
            String fn = "cycle_" + StringUtils.leftPad(Integer.toString(idx), 8, '0') + ".txt";
            try (PrintWriter pw = new PrintWriter(Paths.get(outputFolder.toString(), fn).toString()))
            {
                pw.println("Count: " + vcrs.size());
                vcrs.forEach(e -> pw.println(e));
            }
            idx++;
        }
        System.out.println("Total: " + results.size());

        Map<LoopHash, List<List<ViciousCycleResult>>> resultGroupByInjectionLoopKey = new HashMap<>();
        for (List<ViciousCycleResult> vcrs: resultGroupByInjection.values())
        {
            ViciousCycleResult vcr = vcrs.get(0);
            resultGroupByInjectionLoopKey.computeIfAbsent(vcr.getInjectionLoop(), k -> new ArrayList<>()).add(vcrs);
        }
        idx = 0;
        for (LoopHash injectionLoopKey: resultGroupByInjectionLoopKey.keySet())
        {
            Path outputFolder = Paths.get(cmd.getOptionValue("output_path"), injectionLoopKey.toString());
            outputFolder.toFile().mkdirs();
            for (List<ViciousCycleResult> vcrs:  resultGroupByInjectionLoopKey.get(injectionLoopKey))
            {
                String fn = "cycle_" + StringUtils.leftPad(Integer.toString(idx), 8, '0') + ".txt";
                try (PrintWriter pw = new PrintWriter(Paths.get(outputFolder.toString(), fn).toString()))
                {
                    pw.println("Count: " + vcrs.size());
                    vcrs.forEach(e -> pw.println(e));
                }
                idx++;
            }
        }
        
        System.out.println("Done");
    }

    public static Set<LoopHash> expandLoopsInSameMethod(Collection<LoopHash> loopsToExpand, Map<LoopHash, Set<LoopHash>> loopsInSameMethod)
    {
        Set<LoopHash> r = new HashSet<>(loopsToExpand);
        // loopsToExpand.forEach(e -> r.addAll(loopsInSameMethod.get(e)));
        return r;
    }

    public static void processAdditionalLoop(ViciousCycleResult vcr, LoopHash loopKey1, Set<LoopHash> loop1Interference, LoopHash loopKey2, Set<LoopHash> loop2Interference,
            String interference1Type, String interference2Type)
    {
        Set<LoopHash> intersection = new HashSet<>(loop1Interference);
        intersection.retainAll(loop2Interference);
        intersection.forEach(e -> vcr.addAdditionalLoop(loopKey1, e, interference1Type));
        intersection.forEach(e -> vcr.addAdditionalLoop(loopKey2, e, interference2Type));
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

    public static VCHashBytes getInjectionID(Map<String, Map<String, Object>> injectionTestPlan, VCHashBytes injectionRunHash)
    {
        String injectionRunHashStr = injectionRunHash.toString();
        if (injectionTestPlan.containsKey(injectionRunHashStr))
        {
            Map<String, Object> runProf = injectionTestPlan.get(injectionRunHashStr);
            return VCHashBytes.wrap((String) runProf.get("Injection ID"));
        }
        else // For compatibility, newer injection testplan all have IDs zfill'ed to 32
        {
            System.out.println("WARNING: Removing leading 0s for " + injectionRunHashStr);
            injectionRunHashStr = injectionRunHashStr.substring(1);
            Map<String, Object> runProf = injectionTestPlan.get(injectionRunHashStr);
            return VCHashBytes.wrap((String) runProf.get("Injection ID"));
        }
    }

    //#region Edge Verifiers

    // Loop 1 -- S+ --> Loop 2 -- ICFG --> Loop 0
    public static boolean isTrueICFGEdge_IterCount(LoopHash icfgSrcLoopKey, LoopHash icfgDstLoopKey, VCHashBytes injectionRunHash, LoopHash injectionLoopKey)
    {
        try 
        {
            Path injectionResultPath = Paths.get(iterCountResultPathRoot, injectionRunHash.toString() + "_itercount.json");
            Map<String, Map<String, List<List<String>>>> iterCountResult = iterCountJsonCache.get(injectionResultPath.toAbsolutePath().toString());
            List<List<String>> icfgSrcLoopCallers = iterCountResult.get(injectionLoopKey.toString()).get(icfgSrcLoopKey.toString());
            LoopItem icfgDstLoopProp = loopMap.get(icfgDstLoopKey);
            String icfgDstLoopFullMethodName = icfgDstLoopProp.clazz + "." + icfgDstLoopProp.func;
    
            return icfgSrcLoopCallers.stream().flatMap(e -> e.stream()).anyMatch(callerFunc -> callerFunc.equals(icfgDstLoopFullMethodName));
        }
        catch (NullPointerException e)
        {
            e.printStackTrace();
            return false;
        }
        catch (Exception e)
        {
            System.out.println("Exception in isTrueICFGEdge_IterCount: " + injectionRunHash);
            System.out.println(e);
            return false;
        }
    }

    // Loop 1 -- E --> Loop 2 -- ICFG --> Loop 0
    public static boolean isTrueICFGEdge_ExecSig(LoopHash icfgSrcLoopKey, LoopHash icfgDstLoopKey, VCHashBytes injectionRunHash, LoopHash injectionLoopKey)
    {
        try 
        {
            Path injectionCallSitePath = Paths.get(injectionResultPathRoot, injectionRunHash.toString() + "_callsite.json");
            Map<String, List<List<String>>> execSigCallsites = execSigCallsiteJsonCache.get(injectionCallSitePath.toAbsolutePath().toString());
            List<List<String>> icfgSrcLoopCallers = execSigCallsites.get(icfgSrcLoopKey.toString());
            LoopItem icfgDstLoopProp = loopMap.get(icfgDstLoopKey);
            String icfgDstLoopFullMethodName = icfgDstLoopProp.clazz + "." + icfgDstLoopProp.func;
    
            return icfgSrcLoopCallers.stream().flatMap(e -> e.stream()).anyMatch(callerFunc -> callerFunc.equals(icfgDstLoopFullMethodName));
        }
        catch (NullPointerException e)
        {
            e.printStackTrace();
            return false;
        }
        catch (Exception e)
        {
            System.out.println("Exception in isTrueICFGEdge_ExecSig: " + injectionRunHash);
            System.out.println(e);
            return false;
        }
    }

    public static Set<LoopSignature> getMatchingInjectionSignatureForDelay(VCHashBytes injectionRunHash1, LoopHash injectionLoop1, VCHashBytes injectionRunHash2, LoopHash injectionLoop2)
    {
        try 
        {
            Path injectionRun1SigDiffPath = Paths.get(injectionResultPathRoot, injectionRunHash1.toString() + "_sigdiff.obj.zst");
            Path injectionRun2SigDiffPath = Paths.get(injectionResultPathRoot, injectionRunHash2.toString() + "_sigdiff.obj.zst");

            Map<LoopHash, Map<ImmutableList<String>, Set<LoopSignature>>> injectionRun1AffectedLoopSigDiff = affectedLoopSigDiffCache.get(injectionRun1SigDiffPath.toAbsolutePath().toString());
            Map<ImmutableList<String>, Set<LoopSignature>> injectionLoop2NewSigFromInjection1 = injectionRun1AffectedLoopSigDiff.get(injectionLoop2); // loop2 is affected by injection 1

            Map<ImmutableList<String>, Set<LoopSignature>> injectionLoop2NewSigFromInjection2 = injectionLoopSigDiffCache.get(injectionRun2SigDiffPath.toAbsolutePath().toString());

            // Step 1: Exact Match
            boolean matchSigFound = false;
            Set<LoopSignature> matchingSignature = new HashSet<>();
            for (ImmutableList<String> loop2CallSiteKey: injectionLoop2NewSigFromInjection2.keySet())
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
            if (matchSigFound) return matchingSignature;

            // Step 2: Edit Distance Match
            // int smallestEditDistance = Integer.MAX_VALUE;
            // double smallestRelEditDistance = Double.MAX_VALUE;
            // LoopSignature matchingSig2 = null;
            // for (ImmutableList<String> loop2CallSiteKey: injectionLoop2NewSigFromInjection2.keySet())
            // {
            //     Set<LoopSignature> sigDiffFromInjection2 = injectionLoop2NewSigFromInjection2.get(loop2CallSiteKey);
            //     Set<LoopSignature> sigDiffFromInjection1 = injectionLoop2NewSigFromInjection1.get(loop2CallSiteKey);
            //     if (sigDiffFromInjection1 == null) continue;
            //     for (LoopSignature sig1: sigDiffFromInjection1)
            //     {
            //         for (LoopSignature sig2: sigDiffFromInjection2)
            //         {
            //             int editDistance = Utils.getEditDistance(sig1, sig2);
            //             if (editDistance < smallestEditDistance)
            //             {
            //                 smallestEditDistance = editDistance;
            //                 smallestRelEditDistance = (double) editDistance / (double) sig2.rawSignature.size();
            //                 matchingSig2 = sig2;
            //             }
            //         }
            //     }
            // }
            // if (matchingSig2 != null) matchingSignature.add(matchingSig2);
            return matchingSignature;
        }
        catch (NullPointerException e)
        {
            e.printStackTrace();
            return Collections.emptySet();
        }
        catch (Exception e)
        {
            System.out.println("Exception in getMatchingInjectionSignatureForDelay: " + injectionRunHash1 + "|" + injectionRunHash2);
            System.out.println(e);
            return Collections.emptySet();
        }
    }

    //#endregion Edge Verifiers
}
