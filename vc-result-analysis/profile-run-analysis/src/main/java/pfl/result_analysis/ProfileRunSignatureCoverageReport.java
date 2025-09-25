package pfl.result_analysis;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.ObjectInputStream;
import java.io.PrintWriter;
import java.lang.reflect.Type;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;
import org.apache.commons.lang3.tuple.ImmutableTriple;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import it.unimi.dsi.fastutil.Hash;
import pfl.result_analysis.utils.BinaryResultLoader;
import pfl.result_analysis.utils.CustomUUID;
import pfl.result_analysis.utils.LoopSignature;
import pfl.result_analysis.utils.Utils;
import pfl.result_analysis.utils.VCHashBytes;

// This class is used during algorithm development phase. Not intended for final testing framework
public class ProfileRunSignatureCoverageReport
{
    public static void main(String[] args) throws Exception
    {
        Options options = new Options();
        options.addOption("p", "path", true, "Path to the profile run result");
        options.addOption("o", "output", true, "Result output path");
        options.addOption("output_prefix", true, "Output suffix");
        options.addOption("profile_testplan", true, "path to profile run test plan");
        options.addOption("signature_lib", true, "Loop signature library from all profile run");
        options.addOption("unittest_injection_loops", true, "Injection loop encountered in each unit test");
        CommandLineParser parser = new DefaultParser();
        CommandLine cmd = parser.parse(options, args);

        String basePath = cmd.getOptionValue("path");
        String outputPath = cmd.getOptionValue("output");
        String outputPrefix = cmd.getOptionValue("output_prefix", "");
        outputPrefix = outputPrefix.equals("") ? outputPrefix : outputPrefix + "_";

        //#region Load Data Structures
        String profileTestPlanPath = cmd.getOptionValue("profile_testplan");
        Type profileTestPlanType = new TypeToken<ConcurrentHashMap<String, HashMap<String, Object>>>()
        {
        }.getType();
        Map<String, Map<String, Object>> profileTestPlan = Utils.readJson(profileTestPlanPath, profileTestPlanType);
        profileTestPlan.entrySet().removeIf(e -> !((String) e.getValue().get("AggregateExpKey")).equals(e.getKey()));

        String injectionLoopsPerUnitTestPath = cmd.getOptionValue("unittest_injection_loops");
        Type injectionLoopsPerUnitTestType = new TypeToken<ConcurrentHashMap<String, HashMap<String, ArrayList<String>>>>()
        {
        }.getType();
        Map<String, Map<String, List<String>>> injectionLoopsPerUnitTestT = Utils.readJson(injectionLoopsPerUnitTestPath, injectionLoopsPerUnitTestType);
        Map<VCHashBytes, Map<VCHashBytes, Set<VCHashBytes>>> injectionLoopsPerUnitTest = new ConcurrentHashMap<>(); // TestHash -> {InjectionID: [LoopID]}
        for (String runHash : injectionLoopsPerUnitTestT.keySet())
        {
            injectionLoopsPerUnitTest.put(VCHashBytes.wrap(runHash), injectionLoopsPerUnitTestT.get(runHash).entrySet().stream().collect(
                    Collectors.toMap(e -> VCHashBytes.wrap(e.getKey()), e -> e.getValue().stream().map(loopIDRaw -> VCHashBytes.wrap(loopIDRaw)).collect(Collectors.toSet()))));
        }

        Map<VCHashBytes, Set<LoopSignature>> loopSignatureLib;
        try (FileInputStream fis = new FileInputStream(Paths.get(outputPath, outputPrefix + "ProfileLoopSignatureLib.obj").toFile());
                BufferedInputStream bis = new BufferedInputStream(fis);
                ObjectInputStream ois = new ObjectInputStream(bis);)
        {
            loopSignatureLib = (ConcurrentHashMap<VCHashBytes, Set<LoopSignature>>) ois.readObject();
        }
        //#endregion Load Data Structures

        //#region Get Signature Coverage
        Map<String, Map<String, Map<String, Double>>> signatureCoverage = new ConcurrentHashMap<>(); // UnitTestID: {InjectionID: {Injection Loop: Coverage}}
        AtomicInteger progressIndicator = new AtomicInteger();
        Consumer<String> processor = profileRunHash -> // for (String profileRunHash : profileTestPlan.keySet())
        {
            int currentProgress = progressIndicator.getAndIncrement();
            if ((currentProgress < 5) || (currentProgress % 100 == 0)) System.out.println("At: " + currentProgress + '\t' + profileRunHash);
            try
            {
                File loopIDIterIDMapFile = Paths.get(basePath, profileRunHash, "LoopIDIterIDMap.bin").toFile();
                File iterEventsFile = Paths.get(basePath, profileRunHash, "IterEvents.bin").toFile();
                if (!loopIDIterIDMapFile.exists() || !iterEventsFile.exists()) return;
    
                Map<VCHashBytes, List<CustomUUID>> loopIDIterIDMap = BinaryResultLoader.loadLoopIDIterIDMap(loopIDIterIDMapFile);
                ImmutableTriple<Map<CustomUUID, List<VCHashBytes>>, List<CustomUUID>, List<VCHashBytes>> tTriple = BinaryResultLoader
                        .load_IterEventsMap_InjectionIterList_ReachedInjectionList(iterEventsFile);
                Map<VCHashBytes, Set<LoopSignature>> loopSignatures = BinaryResultLoader.buildLoopSignatureSingleIter(loopIDIterIDMap, tTriple.getLeft());
                Map<String, Map<String, Double>> currentRunCoverage = signatureCoverage.computeIfAbsent(profileRunHash, k -> new LinkedHashMap<>());
                for (VCHashBytes injectionID: tTriple.getRight())
                {
                    Set<VCHashBytes> injectionLoops = injectionLoopsPerUnitTest.get(VCHashBytes.wrap(profileRunHash)).get(injectionID);
                    if (injectionLoops == null) continue; // Sometimes, an injection does not belong to any loop (i.e., iterID created by Runnable.run()), thus the injectionLoopsPerUnitTest won't contain that loop
                    Map<String, Double> curInjectionCoverage = currentRunCoverage.computeIfAbsent(injectionID.toString(), k -> new LinkedHashMap<>());
                    for (VCHashBytes injectionLoopKey: injectionLoops)
                    {
                        int curLoopSigCount = loopSignatures.get(injectionLoopKey).size();
                        int allLoopSigCount = loopSignatureLib.get(injectionLoopKey).size();
                        double coveragePct = ((double) curLoopSigCount) / allLoopSigCount;
                        if (Double.isNaN(coveragePct)) 
                        {
                            // coveragePct is NaN happens when allLoopSigCount == 0
                            // This happens when one loop does not contain any (branch) events. 
                            // This is because 1) startLoop() creates the iterID, but not the iterEventList
                            // 2) iterEventList is only created at the first encounter of (branch) event.
                            // Therefore, when allLoopSigCount == 0, it means that we always have 100% coverage (because no branch inside).
                            coveragePct = 1;
                        }
                        curInjectionCoverage.put(injectionLoopKey.toString(), coveragePct);
                    }
                }
                for (VCHashBytes delayLoopKey: loopIDIterIDMap.keySet())
                {
                    Map<String, Double> curDelayCoverage = currentRunCoverage.computeIfAbsent(delayLoopKey.toString(), k -> new LinkedHashMap<>());
                    int curLoopSigCount = loopSignatures.get(delayLoopKey).size();
                    int allLoopSigCount = loopSignatureLib.get(delayLoopKey).size();
                    double coveragePct = ((double) curLoopSigCount) / allLoopSigCount;
                    if (Double.isNaN(coveragePct)) 
                    {
                        // See reason above
                        coveragePct = 1;
                    }
                    curDelayCoverage.put(delayLoopKey.toString(), coveragePct);
                }
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
        };
        ExecutorService es = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() / 4);
        profileTestPlan.keySet().forEach(testHash -> es.submit(() -> processor.accept(testHash)));
        es.shutdown();
        es.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
        //#endregion Get Signature Coverage

        Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        try (PrintWriter pw = new PrintWriter(Paths.get(outputPath, outputPrefix + "ProfileRunLoopSigCoverage.json").toFile()))
        {
            gson.toJson(signatureCoverage, pw);
            pw.flush();
        }
    }
}
