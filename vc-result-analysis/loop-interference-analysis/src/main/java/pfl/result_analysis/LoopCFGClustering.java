package pfl.result_analysis;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.ObjectInputStream;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IntSummaryStatistics;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;
import org.apache.commons.math3.ml.clustering.KMeansPlusPlusClusterer;
import org.apache.commons.math3.ml.clustering.MultiKMeansPlusPlusClusterer;
import org.jgrapht.Graph;
import org.jgrapht.Graphs;
import org.jgrapht.alg.clustering.GirvanNewmanClustering;
import org.jgrapht.alg.clustering.KSpanningTreeClustering;
import org.jgrapht.alg.clustering.LabelPropagationClustering;
import org.jgrapht.alg.interfaces.ClusteringAlgorithm;
import org.jgrapht.graph.AsWeightedGraph;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.graph.DefaultUndirectedGraph;
import org.jgrapht.graph.DefaultWeightedEdge;
import org.nlpub.watset.graph.ChineseWhispers;
import org.nlpub.watset.graph.MarkovClustering;
import org.nlpub.watset.graph.MarkovClusteringExternal;
import org.nlpub.watset.graph.NodeEmbedding;
import org.nlpub.watset.graph.SpectralClustering;
import org.nlpub.watset.graph.Watset;
import org.nlpub.watset.graph.WatsetClustering;
import org.nlpub.watset.util.Sense;

import com.google.common.collect.HashMultiset;
import com.google.common.collect.Multiset;
import com.google.common.collect.Sets;
import com.google.common.collect.SortedMultiset;
import com.google.common.collect.TreeMultiset;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.AnalysisCache;
import com.ibm.wala.ipa.callgraph.AnalysisCacheImpl;
import com.ibm.wala.ipa.callgraph.AnalysisOptions;
import com.ibm.wala.ipa.callgraph.impl.AllApplicationEntrypoints;
import com.ibm.wala.ipa.callgraph.impl.Everywhere;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAInvokeInstruction;
import com.ibm.wala.types.ClassLoaderReference;
import com.ibm.wala.types.TypeReference;

import pfl.graph.IMethodRawNameNode;
import pfl.result_analysis.utils.LoopHash;
import pfl.result_analysis.utils.LoopItem;
import pfl.result_analysis.utils.Utils;

public class LoopCFGClustering 
{
    public static void main(String[] args) throws Exception
    {
        Options options = new Options();
        options.addOption("loop_cfg", true, "System's control flow graph");
        // options.addOption("cfg", true, "System's control flow graph");
        options.addOption("loops", true, "json file for loops");
        options.addOption("classpath", true, "WALA classpath");
        options.addOption("output", true, "Output path");
        DefaultParser parser = new DefaultParser();
        CommandLine cmd = parser.parse(options, args);

        Map<String, Map<String, Object>> loopMapT = Utils.readJson(cmd.getOptionValue("loops"));
        Map<LoopHash, LoopItem> loopMap = loopMapT.entrySet().stream().collect(Collectors.toMap(e -> LoopHash.wrap(e.getKey()), e -> LoopItem.mapToLoopItem(e.getValue())));

        // Graph<IMethodRawNameNode, DefaultEdge> cgT;
        // try (FileInputStream fis = new FileInputStream(cmd.getOptionValue("cfg"));
        //         BufferedInputStream bis = new BufferedInputStream(fis);
        //         ObjectInputStream ois = new ObjectInputStream(bis);)
        // {
        //     cgT = (DefaultDirectedGraph<IMethodRawNameNode, DefaultEdge>) ois.readObject();
        // }
        // // cgT.removeAllVertices(cgT.vertexSet().stream().filter(v -> v.rawName.contains("java/lang")).collect(Collectors.toList()));
        // System.out.println("Graph Loaded");

        // for (IMethodRawNameNode v: cgT.vertexSet())
        // {
        //     if (v.toString().contains("receiveBlock")) System.out.println(v);
        // }

        Graph<LoopHash, DefaultEdge> loopCFG;
        try (FileInputStream fis = new FileInputStream(cmd.getOptionValue("loop_cfg"));
                BufferedInputStream bis = new BufferedInputStream(fis);
                ObjectInputStream ois = new ObjectInputStream(bis);)
        {
            loopCFG = (DefaultDirectedGraph<LoopHash, DefaultEdge>) ois.readObject();
        }

        // Map<IMethod, List<LoopHash>> methodIndexedLoops = new ConcurrentHashMap<>();
        // Map<LoopHash, IMethod> loopEnclosingMethod = new ConcurrentHashMap<>();
        // Map<IMethodRawNameNode, IMethod> inverseCGVertexMap = new ConcurrentHashMap<>();
        // WalaModel model = new WalaModel(cmd.getOptionValue("classpath"));
        // AnalysisOptions analysisOptions = new AnalysisOptions(model.getScope(), new AllApplicationEntrypoints(model.getScope(), model.getCha()));
        // AnalysisCache analysisCache = new AnalysisCacheImpl();
        // for (LoopHash loopKey : loopMap.keySet())
        // {
        //     LoopItem loopItem = loopMap.get(loopKey);
        //     TypeReference tr = TypeReference.find(ClassLoaderReference.Application, Utils.toWalaFullClassName(loopItem.clazz));
        //     if (tr == null)
        //     {
        //         System.out.println(Utils.toWalaFullClassName(loopItem.clazz));
        //         continue;
        //     }
        //     IClass loopClass = model.getCha().lookupClass(tr);
        //     for (IMethod loopMethod : loopClass.getDeclaredMethods())
        //     {
        //         inverseCGVertexMap.put(new IMethodRawNameNode(loopMethod), loopMethod);
        //         if (!Utils.getShortMethodName(loopMethod).equals(loopItem.func)) continue;
        //         IR methodIR = analysisCache.getSSACache().findOrCreateIR(loopMethod, Everywhere.EVERYWHERE, analysisOptions.getSSAOptions());
        //         if (methodIR == null) continue;
        //         IntSummaryStatistics methodStat = Utils.getMethodIRLineRange(methodIR);
        //         if (loopKey.toString().equals("0dffa2245dc880b3e8824da1d51097b5"))
        //         {
        //             System.out.println(loopMethod);
        //             System.out.println(methodStat);
        //             System.out.println(cgT.containsVertex(new IMethodRawNameNode(loopMethod)));
        //         }
        //         if ((loopItem.startLineNo >= methodStat.getMin()) && (loopItem.endLineNo <= methodStat.getMax()))
        //         {
        //             methodIndexedLoops.computeIfAbsent(loopMethod, k -> new ArrayList<>()).add(loopKey);
        //             loopEnclosingMethod.put(loopKey, loopMethod);
        //         }
        //     }
        // }

        // // System.out.println(Sets.difference(inverseCGVertexMap.keySet(), cgT.vertexSet()).size());
        // ClusteringAlgorithm<IMethodRawNameNode> alg = new LabelPropagationClustering<>(Graphs.undirectedGraph(cgT), new Random(0));
        // ClusteringAlgorithm.Clustering<IMethodRawNameNode> methodClusters = alg.getClustering();
        // List<Set<LoopHash>> loopClusters = new ArrayList<>();
        // Set<LoopHash> clusteredLoops = new HashSet<>();
        // for (Set<IMethodRawNameNode> methodCluster: methodClusters.getClusters())
        // {
        //     Set<LoopHash> loopCluster = new HashSet<>();
        //     for (IMethodRawNameNode method: methodCluster)
        //     {   
        //         IMethod loopIMethod = inverseCGVertexMap.get(method);
        //         if (loopIMethod == null) continue;
        //         loopCluster.addAll(methodIndexedLoops.getOrDefault(loopIMethod, Collections.emptyList()));
        //     }
        //     if (loopCluster.size() > 0) loopClusters.add(loopCluster);
        //     clusteredLoops.addAll(loopCluster);
        // }

        // Set<LoopHash> missingLoops = Sets.difference(loopMap.keySet(), clusteredLoops);
        // // missingLoops.forEach(e -> loopClusters.add(Sets.newHashSet(e)));
        // missingLoops.forEach(e -> System.out.println(e.toString()));

        // MarkovClusteringExternal.Builder<LoopHash, DefaultEdge> algBuilder = MarkovClusteringExternal.<LoopHash, DefaultEdge>builder();
        // algBuilder.setPath(Paths.get("/usr/bin/mcl"));
        // algBuilder.setThreads(1);
        // MarkovClusteringExternal<LoopHash, DefaultEdge> alg = algBuilder.apply(Graphs.undirectedGraph(loopCFG));
        // Graph<LoopHash, DefaultWeightedEdge> g = new DefaultUndirectedGraph<>(DefaultWeightedEdge.class);
        // Graphs.addAllVertices(g, loopCFG.vertexSet());
        // loopCFG.edgeSet().forEach(e -> g.addEdge(loopCFG.getEdgeSource(e), loopCFG.getEdgeTarget(e)));
        // ChineseWhispers<LoopHash, DefaultWeightedEdge> alg = ChineseWhispers.<LoopHash, DefaultWeightedEdge>builder().apply(g);
        ClusteringAlgorithm<LoopHash> alg = new KSpanningTreeClustering<>(Graphs.undirectedGraph(loopCFG), 20);
        // ClusteringAlgorithm<LoopHash> alg = new LabelPropagationClustering<>(Graphs.undirectedGraph(loopCFG), new Random(0));
        ClusteringAlgorithm.Clustering<LoopHash> clustering = alg.getClustering();
        List<Set<LoopHash>> loopClusters = clustering.getClusters();

        Set<LoopHash> missingLoops = Sets.difference(loopMap.keySet(), loopClusters.stream().flatMap(e -> e.stream()).collect(Collectors.toSet()));
        missingLoops.forEach(e -> loopClusters.add(Sets.newHashSet(e)));

        Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        try (Writer bw = Files.newBufferedWriter(Paths.get(cmd.getOptionValue("output"), "LoopCluster.json")))
        {
            List<List<String>> t = loopClusters.stream().map(e -> e.stream().map(e2 -> e2.toString()).collect(Collectors.toList())).collect(Collectors.toList());
            gson.toJson(t, bw);
        }

        SortedMultiset<Integer> stat = TreeMultiset.create();
        for (Set<LoopHash> cluster: loopClusters)
        {
            stat.add(cluster.size());
        }
        Map<Integer, Integer> statO = new TreeMap<>();
        for (int i: stat.elementSet())
        {
            statO.put(i, stat.count(i));
        }
        try (Writer bw = Files.newBufferedWriter(Paths.get(cmd.getOptionValue("output"), "LoopClusterSize.json")))
        {
            gson.toJson(statO, bw);
        }
    }
}
