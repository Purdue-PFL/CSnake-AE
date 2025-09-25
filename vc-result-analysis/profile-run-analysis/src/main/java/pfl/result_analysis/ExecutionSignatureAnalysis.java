package pfl.result_analysis;

import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.apache.commons.lang3.tuple.ImmutableTriple;

import com.github.luben.zstd.ZstdOutputStream;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
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
import pfl.result_analysis.utils.LoopSignature;
import pfl.result_analysis.utils.MagicNumberErrorException;
import pfl.result_analysis.utils.Utils;
import pfl.result_analysis.utils.VCHashBytes;

// LoopInterference Analysis Algorithm 1: Change in the execution signature
public class ExecutionSignatureAnalysis
{
    public static String profileResultRoot;
    public static String injectionResultRoot;
    public static String outputPath;
    public static String outputPrefix;
    public static String outputLoopInterferenceJsonPath;
    public static List<String> finishedInjectionRun;
    public static Set<String> finishedProfileRun;
    public static boolean DEBUG = false;
    public static String debugOutputPath;
    public static boolean ANVIL_RUN = Utils.isAnvilRun();
    public static void main(String[] args) throws Exception
    {
        LoopInterferenceAnalysisContext analysisContext = new LoopInterferenceAnalysisContext(args);
        debugOutputPath = analysisContext.perInjectionResultPath;
        if (DEBUG)
        {
            profileResultRoot = Paths.get("/local1/qian151/vc-detect-workspace/loop-interference-result/hdfs292_injection").toString();
            injectionResultRoot = Paths.get("/local1/qian151/vc-detect-workspace/loop-interference-result/hdfs292_injection").toString();
            outputPath = "/local1/qian151/vc-detect-workspace/loop-interference-result/HDFS-9178_LoopInterference_manual.json";
            debugOutputPath = "/local1/qian151/vc-detect-workspace/loop-interference-result/hdfs292_injection";
            finishedInjectionRun = Files.lines(Paths.get(injectionResultRoot, "progress.log")).map(e -> e.replaceAll("\\r|\\n", ""))
                    .filter(e -> Paths.get(injectionResultRoot, e).toFile().exists()).collect(Collectors.toList());
            // finishedInjectionRun = ConcurrentHashMap.newKeySet();
            // finishedInjectionRun = new ArrayList<>();
            // finishedInjectionRun.add("2c9e38f594f538f6d1ff067bc6aa4e25");
            // finishedInjectionRun.add("43584835399950587e69f601ed89a462");
            // finishedInjectionRun.add("43584835399950587e69f601ed89a462");
            // finishedInjectionRun.add("43584835399950587e69f601ed89a462");
            // finishedProfileRun = Files.lines(Paths.get(profileResultRoot, "progress.log")).map(e -> e.replaceAll("\\r|\\n", ""))
            //         .filter(e -> Paths.get(profileResultRoot, e).toFile().exists()).collect(Collectors.toCollection(ConcurrentHashMap::newKeySet));
            finishedProfileRun = ConcurrentHashMap.newKeySet();
            finishedProfileRun.add("ca1d57207ebf939791666e83ac2e8a14");
            finishedProfileRun.add("cace6c62c5183b21073085139bdc23e4");
            finishedProfileRun.add("6f49676d542e0d928d931600b455e1ff");
            finishedProfileRun.add("fccac7f8014b3b609cdfd5654318d10");
            finishedProfileRun.add("765f820caa889f516ecbae5fe608930f");
            finishedProfileRun.add("5363c3a6dc7267cdd89c4e231c87953");
            // finishedProfileRun.add("91cce06cdf310e357956a58fa83bd40a");
            // finishedProfileRun.add("da83e40a2993abbc9d6fb7b75981133");
            // finishedProfileRun.add("3890f9cb5d259e274367597d0d7b3b85");
        }
        else 
        {
            profileResultRoot = analysisContext.profileResultRoot;
            injectionResultRoot = analysisContext.injectionResultRoot;
            outputPath = analysisContext.outputPath;
            outputPrefix = analysisContext.outputPrefix;
            outputLoopInterferenceJsonPath = Paths.get(outputPath, outputPrefix + "LoopInterference.json").toString();;
            finishedInjectionRun = analysisContext.finishedInjectionRun;
            finishedProfileRun = analysisContext.finishedProfileRun;
        }
        Map<String, Map<String, Object>> injectionTestPlan = analysisContext.injectionTestPlan;
        Map<String, Map<String, Object>> profileTestPlan = analysisContext.profileTestPlan;

        Map<String, List<String>> profileRepeatedRuns = new ConcurrentHashMap<>();
        for (String profileRunHash: profileTestPlan.keySet())
        {   
            String aggregateExpKey = (String) profileTestPlan.get(profileRunHash).get("AggregateExpKey");
            profileRepeatedRuns.computeIfAbsent(aggregateExpKey, k -> new ArrayList<>()).add(profileRunHash);
        }
        Map<String, List<String>> injectionRepeatedRuns = new ConcurrentHashMap<>();
        for (String injectionRunHash: injectionTestPlan.keySet())
        {   
            String aggregateExpKey = (String) injectionTestPlan.get(injectionRunHash).get("AggregateExpKey");
            injectionRepeatedRuns.computeIfAbsent(aggregateExpKey, k -> new ArrayList<>()).add(injectionRunHash);
        }
        finishedInjectionRun.removeIf(e -> !injectionRepeatedRuns.keySet().contains(e));

        //#region Cache Loader

        // LoopKey (128bit hash) -> [Iter CustomUUID]
        LoadingCache<String, Map<VCHashBytes, List<CustomUUID>>> loopIDIterIDMapCache = CacheBuilder.newBuilder().weakValues()
                .build(new CacheLoader<String, Map<VCHashBytes, List<CustomUUID>>>()
                {
                    @Override
                    public Map<VCHashBytes, List<CustomUUID>> load(String key) throws IOException
                    {
                        return analysisContext.loadLoopIDIterIDMap(key);
                    }
                });

        // IterKey -> [128bit branchID | 32bit real branch ID]
        LoadingCache<String, Map<CustomUUID, List<VCHashBytes>>> iterEventsCache = CacheBuilder.newBuilder().weakValues()
                .build(new CacheLoader<String, Map<CustomUUID, List<VCHashBytes>>>()
                {
                    @Override
                    public Map<CustomUUID, List<VCHashBytes>> load(String key) throws IOException, ExecutionException
                    {
                        System.out.println("Loading: " + key);
                        return analysisContext.loadIterEventsMap(key);
                    }
                });

        LoadingCache<String, Map<CustomUUID, int[]>> iterIDStackMethodIdMapCache = CacheBuilder.newBuilder().weakValues().build(new CacheLoader<String, Map<CustomUUID, int[]>>()
        {
            @Override
            public Map<CustomUUID, int[]> load(String key) throws Exception
            {
                return analysisContext.loadIterIDStackMethodIdMap(key);
            }
        });

        LoadingCache<String, Map<Integer, String>> methodIdxCache = CacheBuilder.newBuilder().weakValues().build(new CacheLoader<String,Map<Integer, String>>() 
        {
            @Override
            public Map<Integer, String> load(String key) throws Exception
            {
                return analysisContext.loadMethodIdxMap(key);
            }
        });

        LoadingCache<String, Map<VCHashBytes, Map<ImmutableList<String>, Set<LoopSignature>>>> profileLoopSignatureCache = CacheBuilder.newBuilder().weakValues()
        .build(new CacheLoader<String, Map<VCHashBytes, Map<ImmutableList<String>, Set<LoopSignature>>>>()
        {
            @Override
            public Map<VCHashBytes, Map<ImmutableList<String>, Set<LoopSignature>>> load(String profileRunHash) throws IOException, ExecutionException, InterruptedException
            {
                return BinaryResultLoader.buildLoopSignatureByCallsite(loopIDIterIDMapCache.get(profileRunHash), iterEventsCache.get(profileRunHash), iterIDStackMethodIdMapCache.get(profileRunHash), methodIdxCache.get(profileRunHash));
            }
        });

        //#endregion

        //#region Core analysis logic

        Map<VCHashBytes, Set<VCHashBytes>> loopInterferences = new ConcurrentHashMap<>();
        AtomicInteger progressIndicator = new AtomicInteger();
        Object outputMutex = new Object();
        Instant overallStartTime = Instant.now();
        Consumer<String> processor = (injectionRunHash) ->
        {
            int currentProgress = progressIndicator.getAndIncrement();
            if ((currentProgress < 5) || (currentProgress % 100 == 0)) System.out.println("At: " + currentProgress + '\t' + injectionRunHash);
            if (analysisContext.isContinue)
            {
                File jsonOutFile = Paths.get(debugOutputPath, injectionRunHash + ".json").toFile();
                if (jsonOutFile.exists()) return;
            }
            Instant startTime = Instant.now();

            Map<String, Object> testProfile = injectionTestPlan.get(injectionRunHash);
            String profileRunHash = (String) testProfile.get("ProfileRunID");
            if (!finishedProfileRun.contains(profileRunHash)) 
            {
                System.out.println("Injection: " + injectionRunHash + " skipped due to missing profile run.");
                return;
            }

            // Generate Reference Loop Signatures
            Instant startBuildingReferenceRun = Instant.now();
            Map<VCHashBytes, Map<ImmutableList<String>, Set<LoopSignature>>> referenceLoopSignatures = new ConcurrentHashMap<>(); // LoopID -> {[CallSite]: Set([BranchEvents])} 
            for (String profileRepeatedRunHash: profileRepeatedRuns.get(profileRunHash))
            {
                try 
                {
                    Map<VCHashBytes, Map<ImmutableList<String>, Set<LoopSignature>>> refSig = profileLoopSignatureCache.get(profileRepeatedRunHash);
                    for (VCHashBytes loopKey: refSig.keySet())
                    {
                        Map<ImmutableList<String>, Set<LoopSignature>> loopSigs = refSig.get(loopKey);
                        Map<ImmutableList<String>, Set<LoopSignature>> aggregateLoopSigs = referenceLoopSignatures.computeIfAbsent(loopKey, k -> new ConcurrentHashMap<>());
                        for (ImmutableList<String> stackMethodKey: loopSigs.keySet())
                        {
                            aggregateLoopSigs.computeIfAbsent(stackMethodKey, k -> ConcurrentHashMap.newKeySet()).addAll(loopSigs.get(stackMethodKey));
                        }
                    }
                }
                catch (ExecutionException e1)
                {
                    System.out.println("Skipping loading profile run: " + profileRepeatedRunHash + " due to exception in loading reference loop signatures.");
                    e1.printStackTrace();
                }
            }  
            if (referenceLoopSignatures.size() == 0) 
            {
                System.out.println("Injection: " + injectionRunHash + " skipped due to no reference run had been loaded.");
                return;
            } 
            // System.out.println(referenceLoopSignatures);
            // System.out.println("Profile Loaded");

            // Compare Injection Run
            Instant startBuildingInjectionRun = Instant.now();
            try
            {
                Set<VCHashBytes> injectionLoopSet = ConcurrentHashMap.newKeySet();
                injectionLoopSet.add(VCHashBytes.wrap(HashCode.fromString((String) testProfile.get("Injection Loop")).asBytes()));
                
                Map<VCHashBytes, Map<ImmutableList<String>, Set<LoopSignature>>> injectionLoopSignatures = new ConcurrentHashMap<>();
                Map<LoopSignature, Integer> injectionLoopSignatureCount = new HashMap<>();
                int successfulInjectionRunCt = 0;
                for (String injectionRepeatedRun: injectionRepeatedRuns.get(injectionRunHash))
                {
                    try
                    {
                        Map<VCHashBytes, Map<ImmutableList<String>, Set<LoopSignature>>> currentLoopSignatures = 
                            BinaryResultLoader.buildLoopSignatureByCallsite(loopIDIterIDMapCache.get(injectionRepeatedRun), 
                                                                            analysisContext.loadIterEventsMap(injectionRepeatedRun), 
                                                                            iterIDStackMethodIdMapCache.get(injectionRepeatedRun), 
                                                                            methodIdxCache.get(injectionRepeatedRun));
                        for (VCHashBytes loopKey: currentLoopSignatures.keySet())
                        {
                            Map<ImmutableList<String>, Set<LoopSignature>> loopSigs = currentLoopSignatures.get(loopKey);
                            Map<ImmutableList<String>, Set<LoopSignature>> aggregateLoopSigs = injectionLoopSignatures.computeIfAbsent(loopKey, k -> new ConcurrentHashMap<>());
                            for (ImmutableList<String> stackMethodKey: loopSigs.keySet())
                            {
                                aggregateLoopSigs.computeIfAbsent(stackMethodKey, k -> ConcurrentHashMap.newKeySet()).addAll(loopSigs.get(stackMethodKey));
                                for (LoopSignature sig: loopSigs.get(stackMethodKey))
                                {
                                    injectionLoopSignatureCount.put(sig, injectionLoopSignatureCount.getOrDefault(sig, 0) + 1);
                                }
                            }
                        }
                        successfulInjectionRunCt++;
                    }
                    catch (IOException | ExecutionException | InterruptedException injectionE)
                    {
                        System.out.println("Exception At: " + injectionRepeatedRun + " Skipped");
                        // injectionE.printStackTrace();
                        System.out.println(injectionE);
                    }
                }
                // int injectionSigRepeatThreshold = ((Double) Math.ceil(injectionRepeatedRuns.get(injectionRunHash).size() / 2.0)).intValue();
                int injectionSigRepeatThreshold = successfulInjectionRunCt;
                injectionLoopSignatures.values().forEach(e -> e.values().forEach(sigs -> sigs.removeIf(sig -> injectionLoopSignatureCount.get(sig) < injectionSigRepeatThreshold)));
                // System.out.println(injectionLoopSignatures);

                // Compare the injection run with the reference run, on injectionLoopIDIterIDMap
                // Get signature diff
                // Criteria: 1) Loop not exist in reference run
                // 2) Iteration signature not in the reference run
                Set<VCHashBytes> affectedLoop = ConcurrentHashMap.newKeySet();
                Map<ImmutableList<String>, Set<LoopSignature>> injectionLoopSigDiff = new HashMap<>(); // [CallSite] -> Set([LoopSignature])
                Map<VCHashBytes, Map<ImmutableList<String>, Set<LoopSignature>>> affectedLoopSigDiff = new HashMap<>(); // AffectedLoopID -> {[CallSite]: Set([LoopSignature])}
                for (VCHashBytes loopKey: injectionLoopSignatures.keySet())
                {
                    Map<ImmutableList<String>, Set<LoopSignature>> referenceLoopSignature = referenceLoopSignatures.get(loopKey);
                    Map<ImmutableList<String>, Set<LoopSignature>> injectionLoopSignature = injectionLoopSignatures.get(loopKey);
                    Map<ImmutableList<String>, Set<LoopSignature>> sigDiff = getSignatureDiff(referenceLoopSignature, injectionLoopSignature);
                    if (injectionLoopSet.contains(loopKey))
                    {
                        injectionLoopSigDiff = sigDiff;
                    }
                    else
                    {
                        for (ImmutableList<String> stackMethodKey: sigDiff.keySet())
                        {
                            Set<LoopSignature> sigDiffCurrentCallSite = sigDiff.get(stackMethodKey);
                            if (sigDiffCurrentCallSite.size() > 0)
                            {
                                affectedLoop.add(loopKey);
                                Map<ImmutableList<String>, Set<LoopSignature>> sigDiffMap = affectedLoopSigDiff.computeIfAbsent(loopKey, k -> new HashMap<>());
                                sigDiffMap.put(stackMethodKey, sigDiffCurrentCallSite);
                            }
                        }
                    }
                }
                injectionLoopSet.forEach(injectionLoop -> loopInterferences.computeIfAbsent(injectionLoop, k -> ConcurrentHashMap.newKeySet()).addAll(affectedLoop));

                Map<VCHashBytes, Set<ImmutableList<String>>> affectedLoopCallSites = new HashMap<>(); // LoopKey -> [Call Stack]
                Map<List<Integer>, ImmutableList<String>> callSiteNameCache = new HashMap<>();
                for (String injectionRepeatedRunHash: injectionRepeatedRuns.get(injectionRunHash))
                {
                    try 
                    {
                        Map<CustomUUID, int[]> iterIDStackMethodIdMap = iterIDStackMethodIdMapCache.get(injectionRepeatedRunHash);
                        Map<Integer, String> methodIdx = methodIdxCache.get(injectionRepeatedRunHash);
                        Map<VCHashBytes, List<CustomUUID>> loopIDIterIDMap = loopIDIterIDMapCache.get(injectionRepeatedRunHash);
                        Map<CustomUUID, VCHashBytes> iterIDLoopIDMap = new HashMap<>();
                        for (VCHashBytes loopKey: loopIDIterIDMap.keySet())
                        {
                            loopIDIterIDMap.get(loopKey).forEach(e -> iterIDLoopIDMap.put(e, loopKey));
                        }
                        
                        for (CustomUUID iterID: iterIDStackMethodIdMap.keySet())
                        {
                            VCHashBytes loopID = iterIDLoopIDMap.get(iterID);
                            if (loopID == null) continue;
                            if (!affectedLoop.contains(loopID)) continue;
                            List<Integer> stackMethodIDs = Ints.asList(iterIDStackMethodIdMap.get(iterID));
                            ImmutableList<String> callSite = callSiteNameCache.computeIfAbsent(stackMethodIDs, k -> 
                                stackMethodIDs.stream().map(e -> methodIdx.getOrDefault(e, "NONE")).collect(ImmutableList.toImmutableList()));
                            affectedLoopCallSites.computeIfAbsent(loopID, k -> new HashSet<>()).add(callSite);
                        }
                    }
                    catch (Exception e)
                    {
                        System.out.println("affectedLoopCallSites: " + injectionRepeatedRunHash + " skipped due to exception");
                        // e.printStackTrace();
                        System.out.println(e);
                    }
                }
                callSiteNameCache.clear();

                // Print out per-experiment results
                Map<VCHashBytes, Set<VCHashBytes>> affectedLoopOutputT = new HashMap<>();
                injectionLoopSet.forEach(injectionLoop -> affectedLoopOutputT.computeIfAbsent(injectionLoop, k -> ConcurrentHashMap.newKeySet()).addAll(affectedLoop));
                Map<String, Set<String>> affectedLoopOutput = affectedLoopOutputT.entrySet().stream().collect(Collectors.toMap(e -> HashCode.fromBytes(e.getKey().data).toString(),
                        e -> e.getValue().stream().map(e2 -> HashCode.fromBytes(e2.data).toString()).collect(Collectors.toSet())));
                Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
                String jsonOutPath = Paths.get(debugOutputPath, injectionRunHash + ".json").toString();
                try (PrintWriter pw = new PrintWriter(new BufferedOutputStream(new FileOutputStream(jsonOutPath))))
                {
                    gson.toJson(affectedLoopOutput, pw);
                }
                catch (FileNotFoundException e)
                {
                    e.printStackTrace();
                }

                Path sigDiffOutputPath = Paths.get(debugOutputPath, injectionRunHash + "_sigdiff.obj.zst");
                try (OutputStream os = Files.newOutputStream(sigDiffOutputPath);
                     BufferedOutputStream bos = new BufferedOutputStream(os);
                     ZstdOutputStream zos = new ZstdOutputStream(bos);
                     ObjectOutputStream oos = new ObjectOutputStream(zos);)
                {
                    oos.writeObject(injectionLoopSigDiff);
                    oos.writeObject(affectedLoopSigDiff);
                }

                Path callSiteOutputJsonPath = Paths.get(debugOutputPath, injectionRunHash + "_callsite.json");
                try (BufferedWriter writer = Files.newBufferedWriter(callSiteOutputJsonPath))
                {
                    gson.toJson(affectedLoopCallSites, writer);
                }

                // Status Report
                if ((currentProgress < 5) || (currentProgress % 100 == 0))
                {
                    Set<String> injectionLoopSetT = injectionLoopSet.stream().map(e -> HashCode.fromBytes(e.data).toString()).collect(Collectors.toSet());
                    System.out.println("At: " + currentProgress + " Injection Loop: " + injectionLoopSetT + " Affected Count: " + affectedLoop.size() + " Duration: "
                            + Duration.between(startTime, Instant.now()).toSeconds() + " Time Elapsed: " + Duration.between(overallStartTime, Instant.now()).toSeconds());
                }
            }
            catch (Exception e1)
            {
                e1.printStackTrace();
            }
        };

        //#endregion

        //#region Calling the core analysis logic

        finishedInjectionRun.sort(new Comparator<String>()
        {
            @Override
            public int compare(String lhs, String rhs)
            {
                Map<String, Object> testProfileL = injectionTestPlan.get(lhs);
                String lhsProfileRunHash = (String) testProfileL.get("ProfileRunID");
                Map<String, Object> testProfileR = injectionTestPlan.get(rhs);
                String rhsProfileRunHash = (String) testProfileR.get("ProfileRunID");
                return lhsProfileRunHash.compareTo(rhsProfileRunHash);
            }
        });
        // for (String e: finishedInjectionRun)
        // {
        //     processor.accept(e);
        // }
        ExecutorService es = Executors.newFixedThreadPool(analysisContext.nThread);
        finishedInjectionRun.forEach(e -> es.submit(() -> processor.accept(e)));
        es.shutdown();
        es.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);

        //#endregion

        Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        try (PrintWriter pw = new PrintWriter(new BufferedOutputStream(new FileOutputStream(outputLoopInterferenceJsonPath))))
        {
            Map<String, Set<String>> tOut = loopInterferences.entrySet().stream().collect(Collectors.toMap(e -> HashCode.fromBytes(e.getKey().data).toString(),
                    e -> e.getValue().stream().map(e2 -> HashCode.fromBytes(e2.data).toString()).collect(Collectors.toSet())));
            gson.toJson(tOut, pw);
            pw.flush();
        }
    }

    public static Map<ImmutableList<String>, Set<LoopSignature>> getSignatureDiff(
            Map<ImmutableList<String>, Set<LoopSignature>> profileLoopSignature, Map<ImmutableList<String>, Set<LoopSignature>> injectionLoopSignature)
    {
        Map<ImmutableList<String>, Set<LoopSignature>> r = new HashMap<>(injectionLoopSignature);
        if (profileLoopSignature == null) return r;
        for (ImmutableList<String> stackMethodKey: injectionLoopSignature.keySet())
        {
            Set<LoopSignature> injectionSig = new HashSet<>(injectionLoopSignature.get(stackMethodKey));
            Set<LoopSignature> profileSig = profileLoopSignature.get(stackMethodKey);
            if (profileSig == null) continue;
            injectionSig.removeAll(profileSig);
            r.put(stackMethodKey, injectionSig);
        }
        return r;
    }
}
