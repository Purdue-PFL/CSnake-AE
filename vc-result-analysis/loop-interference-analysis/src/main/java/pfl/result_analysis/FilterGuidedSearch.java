package pfl.result_analysis;

import java.io.BufferedInputStream;
import java.io.BufferedWriter;
import java.io.ObjectInputStream;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.DoubleSummaryStatistics;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;
import org.apache.commons.lang3.StringUtils;

import com.google.common.collect.ComparisonChain;
import com.google.common.collect.ConcurrentHashMultiset;
import com.google.common.collect.HashMultiset;
import com.google.common.collect.Multiset;
import com.google.common.collect.Sets;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import pfl.result_analysis.utils.LoopHash;
import pfl.result_analysis.utils.LoopItem;
import pfl.result_analysis.utils.Utils;
import pfl.result_analysis.utils.VCClusterKey1;
import pfl.result_analysis.utils.VCClusterKey2;
import pfl.result_analysis.utils.VCClusterKey1;
import pfl.result_analysis.utils.VCHashBytes;
import pfl.result_analysis.utils.ViciousCycleResult;

public class FilterGuidedSearch 
{
    public static void main(String[] args) throws Exception
    {
        Options options = new Options();
        options.addOption("raw_result_path", true, "Raw result path");
        options.addOption("output_path", true, "result_output_path");
        options.addOption("negate_injection", true, "Details about the NEGATE injection");
        options.addOption("throw_injection", true, "Details about the throw injection");
        options.addOption("loops", true, "json file for loops");
        options.addOption("negate_injection_gpt", true, "GPT Filtered Negation Injection");
        options.addOption("loop_cluster", true, "Clustering result of loop");
        options.addOption("ozone_filter", false, "Filter for Apache OZone");
        options.addOption("hdfs3_filter", false, "Filter for Apache OZone");
        DefaultParser parser = new DefaultParser();
        CommandLine cmd = parser.parse(options, args);

        Set<ViciousCycleResult> vcResult2;
        try (ObjectInputStream ois = new ObjectInputStream(new BufferedInputStream(Files.newInputStream(Paths.get(cmd.getOptionValue("raw_result_path"))))))
        {
            vcResult2 = (Set<ViciousCycleResult>) ois.readObject();
        }

        Map<LoopHash, LoopItem> loopMap;
        Map<VCHashBytes, Map<String, Object>> negateInjection;
        Map<VCHashBytes, Map<String, Object>> throwInjection;
        Map<VCHashBytes, Set<VCHashBytes>> throwBranchPos;
        Map<VCHashBytes, Boolean> isErrorDetectorGPT;

        Map<String, Map<String, Object>> loopMapT = Utils.readJson(cmd.getOptionValue("loops"));
        loopMap = loopMapT.entrySet().stream().collect(Collectors.toMap(e -> LoopHash.wrap(e.getKey()), e -> LoopItem.mapToLoopItem(e.getValue())));
        loopMapT.clear();
        Map<String, Map<String, Object>> negateInjectionT = Utils.readJson(cmd.getOptionValue("negate_injection"));
        negateInjection = negateInjectionT.entrySet().stream().collect(Collectors.toConcurrentMap(e -> VCHashBytes.wrap(e.getKey()), e -> e.getValue()));
        negateInjectionT.clear();
        Map<String, Map<String, Object>> throwInjectionT = Utils.readJson(cmd.getOptionValue("throw_injection"));
        throwInjection = throwInjectionT.entrySet().stream().collect(Collectors.toConcurrentMap(e -> VCHashBytes.wrap(e.getKey()), e -> e.getValue()));
        throwInjectionT.clear();
        negateInjectionT = Utils.readJson(cmd.getOptionValue("negate_injection_gpt"));
        isErrorDetectorGPT = negateInjectionT.entrySet().stream().collect(Collectors.toConcurrentMap(e -> VCHashBytes.wrap(e.getKey()), e -> 
        {
            Map<String, Object> v = e.getValue();
            if (!v.containsKey("IsErrorDetector_GPT")) return false;
            String gptAnswer = (String) v.get("IsErrorDetector_GPT");
            return gptAnswer.equals("Yes");
        }));
        negateInjectionT.clear();
        List<List<String>> loopClusterT = Utils.readJson(cmd.getOptionValue("loop_cluster"));
        Map<LoopHash, Integer> loopCluster = new HashMap<>();
        Multiset<Integer> loopClusterSize = HashMultiset.create();
        for (int i = 0; i < loopClusterT.size(); i++)
        {
            for (String loopKeyRaw: loopClusterT.get(i))
            {
                loopCluster.put(LoopHash.wrap(loopKeyRaw), i);
                loopClusterSize.add(i);
            }
        }

        Set<VCHashBytes> maskedInjections = ConcurrentHashMap.newKeySet();
        for (VCHashBytes negationInjectionID: negateInjection.keySet())
        {
            Map<String, Object> prop = negateInjection.get(negationInjectionID);
            String method = (String) prop.get("Method");
            String clazz = (String) prop.get("Class");
            if (method.toLowerCase().contains("fortest")) maskedInjections.add(negationInjectionID);
            if (method.toLowerCase().contains("due")) maskedInjections.add(negationInjectionID);
            if (method.toLowerCase().contains("equal")) maskedInjections.add(negationInjectionID);
            if (method.toLowerCase().contains("running")) maskedInjections.add(negationInjectionID);
            if (method.toLowerCase().equals("shouldrun")) maskedInjections.add(negationInjectionID);
            if (method.toLowerCase().contains("safemode")) maskedInjections.add(negationInjectionID);
            if (method.toLowerCase().contains("should")) maskedInjections.add(negationInjectionID);
            if (method.toLowerCase().contains("isempty")) maskedInjections.add(negationInjectionID);
            if (clazz.contains("Set")) maskedInjections.add(negationInjectionID);
            if (method.toLowerCase().startsWith("has")) maskedInjections.add(negationInjectionID);
            if (method.contains("isRestartingNode")) maskedInjections.add(negationInjectionID);
            if (method.toLowerCase().contains("isopenforwrite")) maskedInjections.add(negationInjectionID);
            if (clazz.contains("org.apache.hadoop.hdfs.security")) maskedInjections.add(negationInjectionID);

            // HBase
            // if (method.contains("setAbortRequested")) maskedInjections.add(negationInjectionID);
            if (clazz.contains("org.apache.hadoop.hbase.security")) maskedInjections.add(negationInjectionID);

            // Flink
            if (clazz.contains("org.apache.flink.core.classloading")) maskedInjections.add(negationInjectionID);

            // OZone
            if (clazz.toLowerCase().contains("config")) maskedInjections.add(negationInjectionID);
            if (method.equals("loadFromSnapshotInfoTable")) maskedInjections.add(negationInjectionID);

            // HDFS 341
            if (method.equals("isBlockTokenEnabled")) maskedInjections.add(negationInjectionID);
            if (clazz.toLowerCase().contains("conf")) maskedInjections.add(negationInjectionID);
        }
        for (VCHashBytes throwInjectionID: throwInjection.keySet())
        {
            Map<String, Object> prop = throwInjection.get(throwInjectionID);
            List<Map<String, Object>> throwableConstructionSteps = (List<Map<String, Object>>) prop.get("ThrowableConstruction");
            if (throwableConstructionSteps.stream().anyMatch(e -> shouldRemoveThrowable((String) e.get("type")))) maskedInjections.add(throwInjectionID); 
            String method = (String) prop.get("Method");
            String clazz = (String) prop.get("Class");
            if (clazz.contains("org.apache.hadoop.hdds.security")) maskedInjections.add(throwInjectionID);

            if (cmd.hasOption("hdfs3_filter") && method.equals("<init>")) maskedInjections.add(throwInjectionID);
        }
        if (cmd.hasOption("hdfs3_filter")) maskedInjections.add(VCHashBytes.wrap("ac598a77e2b6de2a2f299315396d3a6d"));

        Set<LoopHash> getterLoops = ConcurrentHashMap.newKeySet();
        Set<LoopHash> maskedLoops = ConcurrentHashMap.newKeySet();
        for (LoopHash loopKey: loopMap.keySet())
        {
            LoopItem loopItem = loopMap.get(loopKey);
            if (loopItem.clazz.toLowerCase().contains("metric") || loopItem.func.toLowerCase().contains("metric")) maskedLoops.add(loopKey);
            if (loopItem.clazz.contains("org.apache.flink.runtime.operators.sort.QuickSort")) maskedLoops.add(loopKey);
            if (loopItem.clazz.contains("org.apache.flink.configuration")) maskedLoops.add(loopKey);
            // OZone
            if (loopItem.clazz.contains("org.apache.hadoop.hdds.security")) maskedLoops.add(loopKey);
            if (loopItem.func.contains("startStateMachineThread")) maskedLoops.add(loopKey);
            if (loopItem.clazz.contains("org.apache.hadoop.hdds.server.http")) maskedLoops.add(loopKey);
            if (loopItem.func.startsWith("get")) getterLoops.add(loopKey);

            // HDFS341
            if (loopItem.func.contains("waitForAllAcks")) maskedLoops.add(loopKey);
            if (cmd.hasOption("hdfs3_filter") && loopItem.func.startsWith("wait")) maskedLoops.add(loopKey);
            if (cmd.hasOption("hdfs3_filter") && loopItem.func.startsWith("isConnectedToNN")) maskedLoops.add(loopKey);
            if (cmd.hasOption("hdfs3_filter") && loopItem.func.startsWith("printReferenceTraceInfo")) maskedLoops.add(loopKey);
            if (cmd.hasOption("hdfs3_filter") && loopItem.func.equals("register")) maskedLoops.add(loopKey);
            if (cmd.hasOption("hdfs3_filter") && loopItem.func.equals("<init>")) getterLoops.add(loopKey);
        }
        if (cmd.hasOption("hdfs3_filter")) getterLoops.add(LoopHash.wrap("3f38f4bb5ff082c2aae37a1704fcbf34"));

        // vcResult2.removeIf(vcr -> loopClusterSize.count(loopCluster.get(vcr.loops.get(0))) <= 3);
        vcResult2.removeIf(vcr -> 
        {
            Set<LoopHash> loopSet = new HashSet<>(vcr.loops);
            return vcr.loops.size() != loopSet.size();
        }); // Remove VCs with duplicate loops inside
        vcResult2.removeIf(vcr -> 
        {
            Set<LoopHash> loopSet = new HashSet<>(vcr.loops);
            return !Sets.intersection(loopSet, maskedLoops).isEmpty();
        });
        vcResult2.removeIf(vcr ->
        {
            Set<VCHashBytes> injectionIDs = new HashSet<>(vcr.injectionIDs);
            return !Sets.intersection(injectionIDs, maskedInjections).isEmpty();
        });
        vcResult2.removeIf(vcr ->
        {
            for (int i = 0; i < vcr.injectionIDs.size(); i++)
            {
                VCHashBytes injectionID = vcr.injectionIDs.get(i);
                if (isErrorDetectorGPT.containsKey(injectionID))
                {
                    // First Negation injection should be an error detector
                    // Should NOT be removed (thus the !)
                    return !isErrorDetectorGPT.get(injectionID);
                }
            }
            return false;
        });
        if (cmd.hasOption("ozone_filter") || cmd.hasOption("hdfs3_filter")) vcResult2.removeIf(vcr -> getterLoops.contains(vcr.loops.get(0)));
        if (cmd.hasOption("hdfs3_filter")) vcResult2.removeIf(vcr -> loopMap.get(vcr.loops.get(0)).clazz.contains("namenode") && loopMap.get(vcr.loops.get(1)).clazz.contains("DataStreamer"));
        if (cmd.hasOption("hdfs3_filter")) vcResult2.removeIf(vcr -> loopMap.get(vcr.loops.get(0)).clazz.contains("namenode") && loopMap.get(vcr.loops.get(1)).clazz.contains("DataXceiver"));
        if (cmd.hasOption("hdfs3_filter")) vcResult2.removeIf(vcr -> loopMap.get(vcr.loops.get(0)).clazz.contains("namenode") && loopMap.get(vcr.loops.get(1)).clazz.contains("BlockReceiver"));
        vcResult2.removeIf(vcr -> loopMap.get(vcr.loops.get(0)).clazz.contains("org.apache.hadoop.ipc"));
        vcResult2.removeIf(vcr -> vcr.loops.stream().anyMatch(l -> loopMap.get(l).clazz.contains("org.apache.hadoop.hbase.ipc")));
        vcResult2.removeIf(vcr -> vcr.loops.stream().anyMatch(l -> loopMap.get(l).clazz.contains("org.apache.hadoop.hdds.server.events")));
        vcResult2.removeIf(vcr -> vcr.loops.stream().anyMatch(l -> loopMap.get(l).clazz.contains("Grpc")));
        Multiset<VCHashBytes> injectionIDScores = ConcurrentHashMultiset.create();
        Multiset<LoopHash> loopIDScores = ConcurrentHashMultiset.create();
        for (ViciousCycleResult vcr: vcResult2)
        {
            vcr.loops.forEach(l -> loopIDScores.add(l));
            vcr.injectionIDs.stream().filter(e -> !e.equals(VCHashBytes.nullSafeValue())).forEach(e -> injectionIDScores.add(e));
        }
        for (ViciousCycleResult vcr:vcResult2)
        {
            DoubleSummaryStatistics loopScore = vcr.loops.stream().map(e -> loopIDScores.count(e)).collect(Collectors.summarizingDouble(e -> e));
            DoubleSummaryStatistics injectionIDScore = vcr.injectionIDs.stream().filter(e -> !e.equals(VCHashBytes.nullSafeValue())).map(e -> injectionIDScores.count(e)).collect(Collectors.summarizingDouble(e -> e));
            vcr.setScore(loopScore.getAverage() + loopScore.getMax() + injectionIDScore.getAverage() + injectionIDScore.getMax());
        }

        List<ViciousCycleResult> vcResult2List = new ArrayList<>(vcResult2);
        Collections.sort(vcResult2List, new Comparator<ViciousCycleResult>() 
        {
            @Override
            public int compare(ViciousCycleResult lhs, ViciousCycleResult rhs)
            {
                return ComparisonChain.start().compare(lhs.score, rhs.score).compare(lhs, rhs).result();
            }
        });
        Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        try (BufferedWriter bw = Files.newBufferedWriter(Paths.get(cmd.getOptionValue("output_path"), "cycles_non_clustered.json")))
        {
            gson.toJson(vcResult2List.stream().map(e -> e.toMap()).collect(Collectors.toList()), bw);
        }

        // Dump All workload
        Set<VCHashBytes> allInjectionRunHashes = new HashSet<>();
        for (ViciousCycleResult vcr: vcResult2)
        {
            allInjectionRunHashes.addAll(vcr.runHashes);
        }
        try (BufferedWriter bw = Files.newBufferedWriter(Paths.get(cmd.getOptionValue("output_path"), "all_inj_workload.json")))
        {
            gson.toJson(allInjectionRunHashes.stream().map(e -> e.toString()).collect(Collectors.toList()), bw);
        }

        // Group VCs based on loops inside 
        Map<List<LoopHash>, List<ViciousCycleResult>> resultGroupByLoop = new LinkedHashMap<>();
        for (ViciousCycleResult vcr: vcResult2List)
        {
            resultGroupByLoop.computeIfAbsent(vcr.loops, k -> new ArrayList<>()).add(vcr);
        }

        int idx = 0;
        for (List<ViciousCycleResult> vcrs: resultGroupByLoop.values())
        {
            int outputChunkIdx = idx / 1000;
            Path outputFolder = Paths.get(cmd.getOptionValue("output_path"), StringUtils.leftPad(Integer.toString(outputChunkIdx), 5, '0'));
            outputFolder.toFile().mkdirs();

            Collections.sort(vcrs, new Comparator<ViciousCycleResult>() 
            {
                @Override
                public int compare(ViciousCycleResult lhs, ViciousCycleResult rhs)
                {
                    return ComparisonChain.start().compare(lhs.score, rhs.score).compare(lhs, rhs).result();
                }
            });
            String fn = "cycle_" + StringUtils.leftPad(Integer.toString(idx), 8, '0') + ".txt";
            try (PrintWriter pw = new PrintWriter(Paths.get(outputFolder.toString(), fn).toString()))
            {
                pw.println("Count: " + vcrs.size());
                vcrs.forEach(e -> pw.println(e));
            }
            idx++;
        }

        // Group VCs based on Class of delay injection loop & error injection loop
        Map<VCClusterKey1, List<List<ViciousCycleResult>>> resultCluster1 = new LinkedHashMap<>();
        resultGroupByLoop.values().forEach(e -> resultCluster1.computeIfAbsent(VCClusterKey1.build(e.get(0), loopMap), l -> new ArrayList<>()).add(e));
        Path outputFolder = Paths.get(cmd.getOptionValue("output_path"), "cluster_v1");
        Files.createDirectories(outputFolder);
        int cycleIdx = 0;
        int clusterIdx = 0;
        for (List<List<ViciousCycleResult>> vcrss: resultCluster1.values())
        {
            Path clusterOutputFolder = Paths.get(outputFolder.toString(), StringUtils.leftPad(Integer.toString(clusterIdx), 3, '0'));
            Files.createDirectories(clusterOutputFolder);
            for (List<ViciousCycleResult> vcrs: vcrss)
            {
                String fn = "cycle_" + StringUtils.leftPad(Integer.toString(cycleIdx), 8, '0') + ".txt";
                try (PrintWriter pw = new PrintWriter(Paths.get(clusterOutputFolder.toString(), fn).toFile()))
                {
                    pw.println("Count: " + vcrs.size());
                    vcrs.forEach(e -> pw.println(e));
                }
                cycleIdx++;
            }
            clusterIdx++;
        }
        System.out.println("Total: " + vcResult2.size());

        Map<VCClusterKey2, List<List<ViciousCycleResult>>> resultCluster2 = new LinkedHashMap<>();
        resultGroupByLoop.values().forEach(e -> resultCluster2.computeIfAbsent(VCClusterKey2.build(e.get(0), loopMap, loopCluster), l -> new ArrayList<>()).add(e));
        outputFolder = Paths.get(cmd.getOptionValue("output_path"), "cluster_v2");
        Files.createDirectories(outputFolder);
        cycleIdx = 0;
        clusterIdx = 0;
        for (List<List<ViciousCycleResult>> vcrss: resultCluster2.values())
        {
            Path clusterOutputFolder = Paths.get(outputFolder.toString(), StringUtils.leftPad(Integer.toString(clusterIdx), 3, '0'));
            Files.createDirectories(clusterOutputFolder);
            for (List<ViciousCycleResult> vcrs: vcrss)
            {
                String fn = "cycle_" + StringUtils.leftPad(Integer.toString(cycleIdx), 8, '0') + ".txt";
                try (PrintWriter pw = new PrintWriter(Paths.get(clusterOutputFolder.toString(), fn).toFile()))
                {
                    pw.println("Count: " + vcrs.size());
                    vcrs.forEach(e -> pw.println(e));
                }
                cycleIdx++;
            }
            clusterIdx++;
        }
    }    

    public static boolean shouldRemoveThrowable(String type)
    {
        return type.contains("InstantiationException") || type.contains("NoSuchMethodException") || type.contains("IllegalAccessException") 
            || type.contains("ClassNotFoundException") || type.contains("NoSuchFieldException") || type.contains("InvocationTargetException")
            || type.contains("java.security") || type.contains("java.io.FileNotFoundException") || type.contains("javax.xml");
    }
}
