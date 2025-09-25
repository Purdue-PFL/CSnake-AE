package pfl.result_analysis;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.ObjectInput;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.PrintWriter;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Collector;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;
import org.apache.commons.lang3.concurrent.ConcurrentUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.ImmutableTriple;
import org.eclipse.collections.impl.bimap.mutable.HashBiMap;

import com.github.luben.zstd.ZstdInputStream;
import com.github.luben.zstd.ZstdOutputStream;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.BiMap;
import com.google.common.util.concurrent.UncheckedExecutionException;
import com.google.errorprone.annotations.Immutable;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonIOException;
import com.google.gson.JsonSyntaxException;
import com.google.gson.internal.GsonBuildConfig;
import com.google.gson.reflect.TypeToken;

import pfl.result_analysis.utils.BinaryResultLoader;
import pfl.result_analysis.utils.CustomUUID;
import pfl.result_analysis.utils.LoopSignature;
import pfl.result_analysis.utils.Utils;
import pfl.result_analysis.utils.VCHashBytes;

public class ProfileRunAnalysis
{
    public static void main(String[] args) throws Exception
    {
        Options options = new Options();
        options.addOption("p", "path", true, "Path to the profile run result");
        options.addOption("o", "output", true, "Result output path");
        options.addOption("output_prefix", true, "Output suffix");
        options.addOption("profile_testplan", true, "path to profile run test plan");
        options.addOption("nthread", true, "number of parallel analysis executor");
        options.addOption("sample_pctg", true, "Percentage of profile run sampled");
        options.addOption("v2_result", false, "Reading v2 result");
        options.addOption("exec_id_map", true, "Exec ID String to integer map");
        options.addOption("per_injection_sample_count", true, "Maximum number of unit tests analyzed (for sig coverage) for each injection");
        CommandLineParser parser = new DefaultParser();
        CommandLine cmd = parser.parse(options, args);

        int nThread = Integer.parseInt(cmd.getOptionValue("nthread", Integer.toString(Runtime.getRuntime().availableProcessors() / 4)));
        String basePath = cmd.getOptionValue("path");
        String outputPath = cmd.getOptionValue("output");
        Files.createDirectories(Paths.get(outputPath));
        String outputPrefix = cmd.getOptionValue("output_prefix", "");
        outputPrefix = outputPrefix.equals("") ? outputPrefix : outputPrefix + "_";
        List<String> finishedTestHash = Files.lines(Paths.get(basePath, "progress.log")).map(e -> e.replaceAll("\\r|\\n", "")).collect(Collectors.toList());
        Collections.shuffle(finishedTestHash);

        String profileTestPlanPath = cmd.getOptionValue("profile_testplan");
        Type profileTestPlanType = new TypeToken<ConcurrentHashMap<String, HashMap<String, Object>>>()
        {
        }.getType();
        Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        Map<String, Map<String, Object>> profileTestPlan = Utils.readJson(profileTestPlanPath, profileTestPlanType);

        Map<String, Double> execIDMapper;
        Map<Integer, String> execIDInverseMap;
        boolean useV2Result = cmd.hasOption("v2_result");
        if (cmd.hasOption("v2_result"))
        {
            String execIDMapperPath = cmd.getOptionValue("exec_id_map");
            execIDMapper = Utils.readJson(execIDMapperPath);
            execIDInverseMap = execIDMapper.entrySet().stream().collect(Collectors.toConcurrentMap(e -> e.getValue().intValue(), e -> e.getKey()));
        }
        else
        {
            execIDInverseMap = null;
            System.out.println("WARNING: Use v1 result compatibility mode");
        }

        int samplePctg = Integer.parseInt(cmd.getOptionValue("sample_pctg", "100"));
        if (samplePctg < 100)
        {
            Set<String> sampledAggregatedProfileRuns = new HashSet<>();
            Random r = new Random();
            for (String profileRunHash : profileTestPlan.keySet())
            {
                String aggExpKey = (String) profileTestPlan.get(profileRunHash).get("AggregateExpKey");
                if (!profileRunHash.equals(aggExpKey)) continue;
                int sampleVal = r.nextInt(100);
                if (sampleVal < samplePctg)
                {
                    sampledAggregatedProfileRuns.add(profileRunHash);
                }
            }
            finishedTestHash.removeIf(hash ->
            {
                String aggExpKey = (String) profileTestPlan.get(hash).get("AggregateExpKey");
                return !sampledAggregatedProfileRuns.contains(aggExpKey);
            });
        }
        int perInjectionUnitTestSampleCount = cmd.hasOption("per_injection_sample_count") ? Integer.parseInt(cmd.getOptionValue("per_injection_sample_count")) : Integer.MAX_VALUE;
        // finishedTestHash.removeIf(hash ->
        // {
        // if (!profileTestPlan.containsKey(hash)) return true;
        // String aggExpKey = (String) profileTestPlan.get(hash).get("AggregateExpKey");
        // return !(hash.equals(aggExpKey));
        // });
        // finishedTestHash = finishedTestHash.subList(0, 10);
        System.out.println("Finished Profile TestHash: " + finishedTestHash.size());

        Map<String, List<String>> aggTestHashes = new ConcurrentHashMap<>();
        for (String profileTestHash: profileTestPlan.keySet())
        {
            String aggregateTestHash = (String) profileTestPlan.get(profileTestHash).get("AggregateExpKey");
            aggTestHashes.computeIfAbsent(aggregateTestHash, k -> new ArrayList<>()).add(profileTestHash);
        }

        // #region Basic Event Counter
        // Map<String, Set<VCHashBytes>> reachedInjectionPoints = new ConcurrentHashMap<>(); // TestHash -> [InjectionID]
        // Map<String, Set<VCHashBytes>> reachedLoops = new ConcurrentHashMap<>(); // TestHash -> [LoopID]
        Map<VCHashBytes, Map<VCHashBytes, Collection<String>>> reachableUnittestFromInjectionT = new ConcurrentHashMap<>(); // InjectionID -> {LoopID: [TestHash]}
        Map<VCHashBytes, Collection<String>> reachableUnittestFromLoopT = new ConcurrentHashMap<>(); // LoopID -> [TestHash]
        Map<String, Map<VCHashBytes, Set<VCHashBytes>>> injectionLoopsPerUnitTest = new ConcurrentHashMap<>(); // TestHash -> {InjectionID: [LoopID]}
        // Map<String, Map<VCHashBytes, AtomicLong>> injectionEventCountPerUnitTest = new ConcurrentHashMap<>(); // TestHash -> {InjectionID: Count}
        // Map<String, Map<VCHashBytes, Integer>> loopIterCountPerUnitTest = new ConcurrentHashMap<>(); // TestHash -> {LoopID: Count}

        AtomicInteger progressIndicator = new AtomicInteger();
        Consumer<String> eventCounter = testHash ->
        {
            int currentProgress = progressIndicator.getAndIncrement();
            if ((currentProgress < 5) || (currentProgress % 100 == 0)) System.out.println("Counter At: " + currentProgress + '\t' + testHash);
            try
            {
                File loopIDIterIDMapFile = Paths.get(basePath, testHash, "LoopIDIterIDMap.bin").toFile();
                Map<VCHashBytes, List<CustomUUID>> loopIDIterIDMap = useV2Result ? BinaryResultLoader.loadLoopIDIterIDMap_v2(loopIDIterIDMapFile, execIDInverseMap)
                        : BinaryResultLoader.loadLoopIDIterIDMap(loopIDIterIDMapFile);

                File iterEventsFile = Paths.get(basePath, testHash, "IterEvents.bin").toFile();
                ImmutableTriple<Map<CustomUUID, List<VCHashBytes>>, List<CustomUUID>, List<VCHashBytes>> tTriple = useV2Result
                        ? BinaryResultLoader.load_IterEventsMap_InjectionIterList_ReachedInjectionList_v2_simple(iterEventsFile, execIDInverseMap)
                        : BinaryResultLoader.load_IterEventsMap_InjectionIterList_ReachedInjectionList(iterEventsFile);
                Set<VCHashBytes> reachedInjectionPoint = new HashSet<>(tTriple.getRight());

                // Find all the injection loops
                Map<CustomUUID, VCHashBytes> iterIDLoopIDMap = new ConcurrentHashMap<>();
                for (VCHashBytes loopKey : loopIDIterIDMap.keySet())
                {
                    loopIDIterIDMap.get(loopKey).forEach(iterID -> iterIDLoopIDMap.put(iterID, loopKey));
                }
                List<CustomUUID> injectionIters = tTriple.getMiddle();
                List<VCHashBytes> allReachedInjections = tTriple.getRight();
                Map<VCHashBytes, Set<VCHashBytes>> currentInjectionLoops = injectionLoopsPerUnitTest.computeIfAbsent(testHash, k -> new ConcurrentHashMap<>()); // InjectionID:
                                                                                                                                                                // [LoopID]
                // Map<VCHashBytes, AtomicLong> currentUnitTestInjectionCount = injectionEventCountPerUnitTest.computeIfAbsent(testHash, k -> new ConcurrentHashMap<>());
                // reachedInjectionPoint.forEach(injectionID -> currentUnitTestInjectionCount.put(injectionID, new AtomicLong(0)));
                Utils.commonPoolSyncRun(() ->
                {
                    IntStream.range(0, injectionIters.size()).parallel().forEach(idx ->
                    {
                        CustomUUID injectionIter = injectionIters.get(idx);
                        VCHashBytes injectionID = allReachedInjections.get(idx);
                        VCHashBytes loopID = iterIDLoopIDMap.get(injectionIter);
                        if (loopID != null)
                        {
                            currentInjectionLoops.computeIfAbsent(injectionID, k -> ConcurrentHashMap.newKeySet()).add(loopID);
                            // currentUnitTestInjectionCount.get(injectionID).getAndIncrement();
                        }
                    });
                });

                // Basic Event Counters
                String aggregateTestHash = (String) profileTestPlan.get(testHash).get("AggregateExpKey");
                Set<VCHashBytes> reachedLoop = loopIDIterIDMap.keySet();
                // reachedLoops.put(testHash, reachedLoop);
                // reachedInjectionPoints.put(testHash, reachedInjectionPoint);
                // Map<VCHashBytes, Integer> loopIterCountMap = loopIterCountPerUnitTest.computeIfAbsent(testHash, k -> new HashMap<>());
                // loopIDIterIDMap.entrySet().forEach(e -> loopIterCountMap.put(e.getKey(), e.getValue().size()));

                // Add to reachable list
                reachedLoop.stream().forEach(loopID -> reachableUnittestFromLoopT.computeIfAbsent(loopID, k -> ConcurrentHashMap.newKeySet()).add(aggregateTestHash));
                for (VCHashBytes injectionID : reachedInjectionPoint)
                {
                    for (VCHashBytes loopKey : currentInjectionLoops.getOrDefault(injectionID, new HashSet<>())) // Some injection points does not belong to any loop (i.e., inside
                                                                                                                 // Runnable.run())
                    {
                        reachableUnittestFromInjectionT.computeIfAbsent(injectionID, k -> new ConcurrentHashMap<>()).computeIfAbsent(loopKey, k2 -> ConcurrentHashMap.newKeySet())
                                .add(aggregateTestHash);
                    }
                }
            }
            catch (Exception e1)
            {
                e1.printStackTrace();
            }
        };
        ExecutorService es = Executors.newFixedThreadPool(nThread);
        finishedTestHash.forEach(testHash -> es.submit(() -> eventCounter.accept(testHash)));
        es.shutdown();
        es.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);

        // #endregion Basic Event Counter

        // #region Coverage-guided sorting of unit tests

        // Build Loop Signature Lib
        Map<ImmutablePair<VCHashBytes, VCHashBytes>, Set<LoopSignature>> loopSigLibPerInjection = new ConcurrentHashMap<>(); // {(LoopID, InjectionID): [Signatures]}
                                                                                                                             // For delay injections, the key is (LoopID, LoopID)
        progressIndicator.set(0);
        Consumer<String> loopSigLibBuilder = profileRunHash ->
        {
            int currentProgress = progressIndicator.getAndIncrement();
            if ((currentProgress < 5) || (currentProgress % 100 == 0)) System.out.println("Sig Builder At: " + currentProgress + '\t' + profileRunHash);
            try
            {
                File loopIDIterIDMapFile = Paths.get(basePath, profileRunHash, "LoopIDIterIDMap.bin").toFile();
                Map<VCHashBytes, List<CustomUUID>> loopIDIterIDMap = useV2Result ? BinaryResultLoader.loadLoopIDIterIDMap_v2(loopIDIterIDMapFile, execIDInverseMap)
                        : BinaryResultLoader.loadLoopIDIterIDMap(loopIDIterIDMapFile);

                File iterEventsFile = Paths.get(basePath, profileRunHash, "IterEvents.bin").toFile();
                ImmutableTriple<Map<CustomUUID, List<VCHashBytes>>, List<CustomUUID>, List<VCHashBytes>> tTriple = useV2Result
                        ? BinaryResultLoader.load_IterEventsMap_InjectionIterList_ReachedInjectionList_v2_simple(iterEventsFile, execIDInverseMap)
                        : BinaryResultLoader.load_IterEventsMap_InjectionIterList_ReachedInjectionList(iterEventsFile);
                Map<CustomUUID, List<VCHashBytes>> iterEvents = tTriple.getLeft();
                Map<VCHashBytes, Set<LoopSignature>> loopSignatures = BinaryResultLoader.buildLoopSignatureSingleIter(loopIDIterIDMap, iterEvents);

                // Fault Injection loops
                Map<VCHashBytes, Set<VCHashBytes>> injectionLoops = injectionLoopsPerUnitTest.get(profileRunHash);
                for (VCHashBytes injectionID : injectionLoops.keySet())
                {
                    injectionLoops.get(injectionID).forEach(loopKey -> loopSigLibPerInjection
                            .computeIfAbsent(ImmutablePair.of(loopKey, injectionID), k -> ConcurrentHashMap.newKeySet()).addAll(loopSignatures.get(loopKey)));
                }
                // Delay Injection loops
                for (VCHashBytes loopKey : loopIDIterIDMap.keySet())
                {
                    loopSigLibPerInjection.computeIfAbsent(ImmutablePair.of(loopKey, loopKey), k -> ConcurrentHashMap.newKeySet()).addAll(loopSignatures.get(loopKey));
                }
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
        };
        ExecutorService es2 = Executors.newFixedThreadPool(nThread);
        injectionLoopsPerUnitTest.keySet().forEach(profileRunHash -> es2.submit(() -> loopSigLibBuilder.accept(profileRunHash)));
        es2.shutdown();
        es2.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);

        // Sort the unit test for each (injectionID, loopID) pair
        Set<String> profileRunHashBlacklist = ConcurrentHashMap.newKeySet(); // Profile RunID that are known to be corrupted
        LoadingCache<String, Map<VCHashBytes, Set<LoopSignature>>> loopSignatureCache = CacheBuilder.newBuilder().weakValues().build(
                new CacheLoader<String, Map<VCHashBytes, Set<LoopSignature>>>()
                {
                    @Override
                    public Map<VCHashBytes, Set<LoopSignature>> load(String profileRunHash) throws IOException, ExecutionException, InterruptedException
                    {
                        if (profileRunHashBlacklist.contains(profileRunHash))
                        {
                            throw new IOException("loopSignatureCache blacklisted: " + profileRunHash);
                        }
                        try
                        {
                            File loopIDIterIDMapFile = Paths.get(basePath, profileRunHash, "LoopIDIterIDMap.bin").toFile();
                            Map<VCHashBytes, List<CustomUUID>> loopIDIterIDMap = useV2Result ? BinaryResultLoader.loadLoopIDIterIDMap_v2(loopIDIterIDMapFile, execIDInverseMap)
                                    : BinaryResultLoader.loadLoopIDIterIDMap(loopIDIterIDMapFile);
                            File iterEventsFile = Paths.get(basePath, profileRunHash, "IterEvents.bin").toFile();
                            ImmutableTriple<Map<CustomUUID, List<VCHashBytes>>, List<CustomUUID>, List<VCHashBytes>> tTriple = useV2Result
                                    ? BinaryResultLoader.load_IterEventsMap_InjectionIterList_ReachedInjectionList_v2_simple(iterEventsFile, execIDInverseMap)
                                    : BinaryResultLoader.load_IterEventsMap_InjectionIterList_ReachedInjectionList(iterEventsFile);
                            Map<CustomUUID, List<VCHashBytes>> iterEvents = tTriple.getLeft();
                            Map<VCHashBytes, Set<LoopSignature>> loopSignatures = BinaryResultLoader.buildLoopSignatureSingleIter(loopIDIterIDMap, iterEvents);
                            return loopSignatures;
                        }
                        catch (IOException | UncheckedExecutionException e)
                        {
                            profileRunHashBlacklist.add(profileRunHash);
                            throw e;
                        }

                    }
                });
        ExecutorService es3 = Executors.newFixedThreadPool(nThread);
        progressIndicator.set(0);

        Map<String, Map<String, List<String>>> reachableUnittestFromInjection = new ConcurrentHashMap<>(); // InjectionID: {LoopID: [UnitTestID]}
        Map<String, Map<String, List<Double>>> reachableUnitTestFromInjectionCulmulativeCoverage = new ConcurrentHashMap<>(); // InjectionID: {LoopID: [Culmulative Coverage Until Selection]}
        BiConsumer<VCHashBytes, VCHashBytes> injectionSorter = (injectionID, injectionLoopKey) ->
        {
            int currentProgress = progressIndicator.getAndIncrement();
            if ((currentProgress < 5) || (currentProgress % 100 == 0)) System.out.println("injectionSorter At: " + currentProgress + '\t' + injectionID + "\t" + injectionLoopKey);
            try
            {
                List<String> candidateUnitTestIDs = new ArrayList<>(reachableUnittestFromInjectionT.get(injectionID).get(injectionLoopKey));
                Collections.shuffle(candidateUnitTestIDs);
                candidateUnitTestIDs = candidateUnitTestIDs.subList(0, Math.min(perInjectionUnitTestSampleCount, candidateUnitTestIDs.size()));
                // Collections.sort(candidateUnitTestIDs);
                Set<LoopSignature> injectionLoopSignatures = loopSigLibPerInjection.get(ImmutablePair.of(injectionLoopKey, injectionID));
                Map<String, Set<LoopSignature>> candidateLoopSignatures = new ConcurrentHashMap<>();
                for (String candidateProfileRunHash : candidateUnitTestIDs)
                {
                    Map<VCHashBytes, Set<LoopSignature>> loopSignatures = null;
                    // candidateProfileRunHash is AggregateExpKey, try all runs to get a usable one
                    for (String runHash: aggTestHashes.get(candidateProfileRunHash))
                    {
                        try 
                        {
                            loopSignatures = loopSignatureCache.get(runHash);
                            break;
                        }
                        catch (Exception e)
                        {
                            System.err.println(e);
                        }
                    }
                    if (loopSignatures == null) continue; // All run fails
                    Set<LoopSignature> candidateLoopSignatureCopy = ConcurrentHashMap.newKeySet();
                    candidateLoopSignatureCopy.addAll(loopSignatures.getOrDefault(injectionLoopKey, new HashSet<>()));
                    candidateLoopSignatures.put(candidateProfileRunHash, candidateLoopSignatureCopy);
                }
                ImmutablePair<List<String>, List<Double>> r = greedyConverageGuidedUnitTestSortingWithCoverageReport(candidateUnitTestIDs, candidateLoopSignatures, injectionLoopSignatures);
                reachableUnittestFromInjection.computeIfAbsent(injectionID.toString(), k -> new ConcurrentHashMap<>()).put(injectionLoopKey.toString(), r.getLeft());
                reachableUnitTestFromInjectionCulmulativeCoverage.computeIfAbsent(injectionID.toString(), k -> new ConcurrentHashMap<>()).put(injectionLoopKey.toString(), r.getRight());
                // System.out.println(r.getRight());
            }
            catch (Exception e2)
            {
                e2.printStackTrace();
            }

        };
        int injSorterWorkloadCount = 0;
        for (VCHashBytes injectionID : reachableUnittestFromInjectionT.keySet())
        {
            for (VCHashBytes injectionLoopKey : reachableUnittestFromInjectionT.get(injectionID).keySet())
            {
                es3.submit(() -> injectionSorter.accept(injectionID, injectionLoopKey));
                injSorterWorkloadCount++;
            }
        }
        System.out.println("InjSorter Workload: " + injSorterWorkloadCount);

        // Sort the unit test for each loop delay
        Map<String, List<String>> reachableUnittestFromLoop = new ConcurrentHashMap<>();
        Map<String, List<Double>> reachableUnittestFromLoopCulmulativeCoverage = new ConcurrentHashMap<>();
        Consumer<VCHashBytes> delaySorter = loopKey ->
        {
            int currentProgress = progressIndicator.getAndIncrement();
            if ((currentProgress < 5) || (currentProgress % 100 == 0)) System.out.println("Delay Sorter Builder At: " + currentProgress + '\t' + loopKey);
            try
            {
                List<String> candidateUnitTestIDs = new ArrayList<>(reachableUnittestFromLoopT.get(loopKey));
                Collections.shuffle(candidateUnitTestIDs);
                candidateUnitTestIDs = candidateUnitTestIDs.subList(0, Math.min(perInjectionUnitTestSampleCount, candidateUnitTestIDs.size()));
                // Collections.sort(candidateUnitTestIDs);
                Set<LoopSignature> delayLoopSignatures = loopSigLibPerInjection.get(ImmutablePair.of(loopKey, loopKey));
                Map<String, Set<LoopSignature>> candidateLoopSignatures = new ConcurrentHashMap<>();
                for (String candidateProfileRunHash : candidateUnitTestIDs)
                {
                    Map<VCHashBytes, Set<LoopSignature>> loopSignatures = null;
                    for (String runHash: aggTestHashes.get(candidateProfileRunHash)) // Try all runs until we get one usable
                    {
                        try 
                        {
                            loopSignatures = loopSignatureCache.get(runHash);
                            break;
                        }
                        catch (Exception e)
                        {
                            System.err.println(e);
                        }
                    }
                    if (loopSignatures == null) continue;
                    Set<LoopSignature> candidateLoopSignatureCopy = ConcurrentHashMap.newKeySet();
                    candidateLoopSignatureCopy.addAll(loopSignatures.getOrDefault(loopKey, new HashSet<>()));
                    candidateLoopSignatures.put(candidateProfileRunHash, candidateLoopSignatureCopy);
                }
                ImmutablePair<List<String>, List<Double>> r = greedyConverageGuidedUnitTestSortingWithCoverageReport(candidateUnitTestIDs, candidateLoopSignatures, delayLoopSignatures);
                reachableUnittestFromLoop.put(loopKey.toString(), r.getLeft());
                reachableUnittestFromLoopCulmulativeCoverage.put(loopKey.toString(), r.getRight());
                // System.out.println(r.getRight());
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
        };
        for (VCHashBytes loopKey : reachableUnittestFromLoopT.keySet())
        {
            es3.submit(() -> delaySorter.accept(loopKey));
        }
        System.out.println("DelaySorter Workload Count: " + reachableUnittestFromLoopT.keySet().size());
        es3.shutdown();
        es3.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);

        // #endregion Coverage-guided sorting of unit tests

        // #region Dump Results
        // try (PrintWriter pw = new PrintWriter(Paths.get(outputPath, outputPrefix + "ReachedInjectionPoints.json").toString()))
        // {
        //     Map<String, List<String>> tOut = reachedInjectionPoints.entrySet().stream()
        //             .collect(Collectors.toMap(e -> e.getKey(), e -> e.getValue().stream().map(e2 -> e2.toString()).collect(Collectors.toList())));
        //     gson.toJson(tOut, pw);
        // }
        // try (PrintWriter pw = new PrintWriter(Paths.get(outputPath, outputPrefix + "ReachedLoops.json").toString()))
        // {
        //     Map<String, List<String>> tOut = reachedLoops.entrySet().stream()
        //             .collect(Collectors.toMap(e -> e.getKey(), e -> e.getValue().stream().map(e2 -> e2.toString()).collect(Collectors.toList())));
        //     gson.toJson(tOut, pw);
        // }
        try (PrintWriter pw = new PrintWriter(Paths.get(outputPath, outputPrefix + "ReachableUnittestFromInjection.json").toString()))
        {
            gson.toJson(reachableUnittestFromInjection, pw);
        }
        try (PrintWriter pw = new PrintWriter(Paths.get(outputPath, outputPrefix + "ReachableUnittestFromInjection_Coverage.json").toString()))
        {
            gson.toJson(reachableUnitTestFromInjectionCulmulativeCoverage, pw);
        }
        try (PrintWriter pw = new PrintWriter(Paths.get(outputPath, outputPrefix + "ReachableUnittestFromLoop.json").toString()))
        {
            gson.toJson(reachableUnittestFromLoop, pw);
        }
        try (PrintWriter pw = new PrintWriter(Paths.get(outputPath, outputPrefix + "ReachableUnittestFromLoop_Coverage.json").toString()))
        {
            gson.toJson(reachableUnittestFromLoopCulmulativeCoverage, pw);
        }
        try (PrintWriter pw = new PrintWriter(Paths.get(outputPath, outputPrefix + "InjectionLoopsPerUnitTest.json").toString()))
        {
            Map<String, Map<String, List<String>>> tOut = new HashMap<>();
            for (String testHash : injectionLoopsPerUnitTest.keySet())
            {
                tOut.put(testHash, injectionLoopsPerUnitTest.get(testHash).entrySet().stream()
                        .collect(Collectors.toMap(e -> e.getKey().toString(), e -> e.getValue().stream().map(e2 -> e2.toString()).collect(Collectors.toList()))));
            }
            gson.toJson(tOut, pw);
        }
        try (FileOutputStream fos = new FileOutputStream(Paths.get(outputPath, outputPrefix + "ProfileLoopSignature_PerInjectionID_InjectionLoop.obj").toFile());
                BufferedOutputStream bos = new BufferedOutputStream(fos);
                ObjectOutputStream oos = new ObjectOutputStream(bos);)
        {
            oos.writeObject(loopSigLibPerInjection);
            oos.flush();
            bos.flush();
            fos.flush();
        }

        // #endregion Dump Results
    }

    // Essentially, this is the greedy approximation of the set coverage problem
    // Note: candidateLoopSignatures has extensive usage of getOrDefault. This is because loops without any branches inside won't have any iterations recorded.
    // Thus, no signature items for them.
    private static ImmutablePair<List<String>, List<Double>> greedyConverageGuidedUnitTestSortingWithCoverageReport(List<String> candidateUnitTestIDs,
            Map<String, Set<LoopSignature>> candidateLoopSignatures, Set<LoopSignature> injectionLoopSignatures)
    {
        List<String> unittestSortResult = new ArrayList<>();
        List<Double> coverageDetails = new ArrayList<>();
        Set<LoopSignature> currentCoverage = new HashSet<>();
        while (candidateUnitTestIDs.size() > 0)
        {
            // Greedy sort based on largest coverage improvement
            Collections.sort(candidateUnitTestIDs, new Comparator<String>()
            {
                @Override
                public int compare(String lhs, String rhs)
                {
                    return -Integer.compare(candidateLoopSignatures.getOrDefault(lhs, new HashSet<>()).size(), candidateLoopSignatures.getOrDefault(rhs, new HashSet<>()).size());
                }
            });
            String selectedID = candidateUnitTestIDs.get(0);
            currentCoverage.addAll(candidateLoopSignatures.getOrDefault(selectedID, new HashSet<>()));
            double culmulativeCoveragePctg = currentCoverage.size() / ((double) injectionLoopSignatures.size());
            if (Double.isNaN(culmulativeCoveragePctg))
            {
                // coveragePct is NaN happens when allLoopSigCount == 0
                // This happens when one loop does not contain any (branch) events.
                // This is because 1) startLoop() creates the iterID, but not the iterEventList
                // 2) iterEventList is only created at the first encounter of (branch) event.
                // Therefore, when allLoopSigCount == 0, it means that we always have 100% coverage (because no branch inside).
                culmulativeCoveragePctg = 1.0;
            }
            else if (culmulativeCoveragePctg > 1.0) // TODO: Weird. We have coverage larger than 1?
            {
                culmulativeCoveragePctg = 1.0;
            }
            unittestSortResult.add(selectedID);
            coverageDetails.add(culmulativeCoveragePctg);

            candidateUnitTestIDs.remove(0);
            candidateLoopSignatures.remove(selectedID);

            // Remove all already covered signatures
            for (String unittestID : candidateLoopSignatures.keySet())
            {
                candidateLoopSignatures.get(unittestID).removeAll(currentCoverage);
            }
        }
        return ImmutablePair.of(unittestSortResult, coverageDetails);
    }
}
