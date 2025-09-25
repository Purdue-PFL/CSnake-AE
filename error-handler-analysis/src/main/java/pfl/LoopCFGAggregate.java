package pfl;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ObjectInput;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.jgrapht.Graph;
import org.jgrapht.GraphPath;
import org.jgrapht.alg.connectivity.ConnectivityInspector;
import org.jgrapht.alg.connectivity.KosarajuStrongConnectivityInspector;
import org.jgrapht.alg.interfaces.AStarAdmissibleHeuristic;
import org.jgrapht.alg.interfaces.ShortestPathAlgorithm;
import org.jgrapht.alg.interfaces.StrongConnectivityAlgorithm;
import org.jgrapht.alg.shortestpath.ALTAdmissibleHeuristic;
import org.jgrapht.alg.shortestpath.AStarShortestPath;
import org.jgrapht.alg.shortestpath.DijkstraShortestPath;
import org.jgrapht.alg.shortestpath.FloydWarshallShortestPaths;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.graph.concurrent.AsSynchronizedGraph;

import com.google.common.collect.ComparisonChain;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.classLoader.Language;
import com.ibm.wala.ipa.callgraph.AnalysisCache;
import com.ibm.wala.ipa.callgraph.AnalysisCacheImpl;
import com.ibm.wala.ipa.callgraph.AnalysisOptions;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.CallGraph;
import com.ibm.wala.ipa.callgraph.Entrypoint;
import com.ibm.wala.ipa.callgraph.impl.AllApplicationEntrypoints;
import com.ibm.wala.ipa.callgraph.propagation.SSAPropagationCallGraphBuilder;

import pfl.graph.ConcurrentALTAdmissibleHeuristic;
import pfl.graph.IMethodRawNameNode;
import pfl.util.Utils;

// Generate Entry Roots and CFG
public class LoopCFGAggregate 
{
    public static void main(String[] args) throws Exception
    {
        Options options = new Options();
        options.addOption("cp", "classpath", true, "Classpath");
        options.addOption("saved_graph", true, "Serialized JGraphT graph");
        options.addOption("o", "output", true, "Output Path");
        CommandLineParser parser = new DefaultParser();
        CommandLine cmd = parser.parse(options, args);
        String classpath = cmd.getOptionValue("classpath");
        String savedGraphPath = cmd.getOptionValue("saved_graph");
        String outputDir = cmd.getOptionValue("output");

        Graph<IMethodRawNameNode, DefaultEdge> cgT;
        WalaModel model = new WalaModel(classpath);
        AnalysisOptions aOptions = new AnalysisOptions(model.getScope(), new AllApplicationEntrypoints(model.getScope(), model.getCha()));
        // aOptions.setReflectionOptions(AnalysisOptions.ReflectionOptions.APPLICATION_GET_METHOD);
        AnalysisCache aCache = new AnalysisCacheImpl();
        File savedGraphFile = new File(savedGraphPath);
        if (!savedGraphFile.exists())
        {
            SSAPropagationCallGraphBuilder builder = com.ibm.wala.ipa.callgraph.impl.Util.makeVanillaZeroOneCFABuilder(Language.JAVA, aOptions, aCache, model.getCha());
            CallGraph cg = builder.makeCallGraph(aOptions, null);
            System.out.println("Callgraph Built");
            cgT = new DefaultDirectedGraph<>(DefaultEdge.class);

            for (CGNode cgn: cg)
            {
                IMethodRawNameNode ep1 = new IMethodRawNameNode(cgn.getMethod());
                cgT.addVertex(ep1);
                for (CGNode cgn2: Utils.toIterable(cg.getSuccNodes(cgn)))
                {
                    IMethodRawNameNode ep2 = new IMethodRawNameNode(cgn2.getMethod());
                    cgT.addVertex(ep2);
                    cgT.addEdge(ep1, ep2);
                }
            }
            try (FileOutputStream fos = new FileOutputStream(savedGraphFile); BufferedOutputStream bos = new BufferedOutputStream(fos); ObjectOutputStream oos = new ObjectOutputStream(bos);)
            {
                oos.writeObject(cgT);
            }
        }
        else 
        {
            try (FileInputStream fis = new FileInputStream(savedGraphFile); BufferedInputStream bis = new BufferedInputStream(fis); ObjectInputStream ois = new ObjectInputStream(bis);)
            {
                cgT = (DefaultDirectedGraph<IMethodRawNameNode, DefaultEdge>) ois.readObject();
            }
            System.out.println("Graph Loaded");
        }

        // Find all entry roots
        // Entry root: All the Runnable.run() method + All the Main method 
        // We split the system w.r.t. entry roots.
        Set<IMethodRawNameNode> entryRoots = ConcurrentHashMap.newKeySet();
        for (IClass clazz: model.getCha())
        {
            if (!clazz.getAllImplementedInterfaces().stream().anyMatch(i -> Utils.getFullClassName(i).endsWith("java.lang.Runnable"))) continue;
            for (IMethod method: clazz.getDeclaredMethods())
            {
                if (Utils.getShortMethodName(method).equals("run")) 
                {
                    IMethodRawNameNode node = new IMethodRawNameNode(method);
                    if (cgT.vertexSet().contains(node)) entryRoots.add(new IMethodRawNameNode(method));
                }  
            }
        }
        Iterable<Entrypoint> mainFunctions = com.ibm.wala.ipa.callgraph.impl.Util.makeMainEntrypoints(model.getCha());
        for (Entrypoint entry: mainFunctions)
        {
            IMethod mainMethod = entry.getMethod();
            IMethodRawNameNode node = new IMethodRawNameNode(mainMethod);
            if (cgT.vertexSet().contains(node)) entryRoots.add(new IMethodRawNameNode(mainMethod));
        }
        entryRoots.removeIf(e -> e.toString().startsWith("java"));
        System.out.println("Entry root count: " + entryRoots.size());
        // Find the direct entry roots for all the functions
        Map<IMethodRawNameNode, Set<IMethodRawNameNode>> functionRootsT = new ConcurrentHashMap<>();
        System.out.println("Workload Size: " + entryRoots.size() * cgT.vertexSet().size());
        entryRoots.parallelStream().forEach(entry -> 
        {
            System.out.println("At: " + entry.toString());
            try 
            {
                ShortestPathAlgorithm<IMethodRawNameNode, DefaultEdge> inspector = new DijkstraShortestPath<>(cgT);
                ShortestPathAlgorithm.SingleSourcePaths<IMethodRawNameNode, DefaultEdge> shortestPaths = inspector.getPaths(entry);
                cgT.vertexSet().stream().filter(v -> !v.toString().startsWith("java")).parallel().forEach(target -> 
                {
                    GraphPath<IMethodRawNameNode, DefaultEdge> path = shortestPaths.getPath(target);
                    if ((path == null) || (path.getLength() == 0)) return;
                    List<IMethodRawNameNode> pathNodes = path.getVertexList();
                    // If entry is target's direct root. I.e., no other entry nodes exist in the path between them.
                    if (pathNodes.subList(1, pathNodes.size() - 1).stream().anyMatch(v -> entryRoots.contains(v))) return;
                    functionRootsT.computeIfAbsent(target, k -> ConcurrentHashMap.newKeySet()).add(entry);
                });
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
        });
        // Sort the entry roots based on the number of roots
        List<Map.Entry<IMethodRawNameNode, Set<IMethodRawNameNode>>> t = new ArrayList<>(functionRootsT.entrySet());
        Collections.sort(t, new Comparator<Map.Entry<IMethodRawNameNode, Set<IMethodRawNameNode>>>() {
            @Override
            public int compare(Entry<IMethodRawNameNode, Set<IMethodRawNameNode>> lhs, Entry<IMethodRawNameNode, Set<IMethodRawNameNode>> rhs)
            {
                return ComparisonChain.start().compare(lhs.getValue().size(), rhs.getValue().size()).compare(lhs.getKey().toString(), rhs.getKey().toString()).result();
            }
        });
        Map<String, List<String>> functionRoots = new LinkedHashMap<>();
        t.forEach(e -> functionRoots.put(e.getKey().toString(), e.getValue().stream().map(v -> v.toString()).collect(Collectors.toList())));
        Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        BufferedWriter pw = Files.newBufferedWriter(Paths.get(outputDir, "entry_roots.json"));
        // PrintWriter pw = new PrintWriter("./result/hdfs292_entry_roots.json");
        gson.toJson(functionRoots, pw);
        pw.close();

        Map<Integer, Integer> rootCounts = new TreeMap<>();
        for (String func: functionRoots.keySet())
        {
            int rootsCount = functionRoots.get(func).size();
            rootCounts.put(rootsCount, rootCounts.computeIfAbsent(rootsCount, k -> 0) + 1);
        }
        // pw = new PrintWriter("./result/hdfs292_entry_roots_stat.json");
        pw = Files.newBufferedWriter(Paths.get(outputDir, "entry_roots_stat.json"));
        gson.toJson(rootCounts, pw);
        pw.close();

        List<String> functionNoRoot = cgT.vertexSet().stream().filter(v -> !v.toString().startsWith("java")).filter(v -> !functionRoots.containsKey(v.toString())).map(e -> e.toString()).collect(Collectors.toList());
        // pw = new PrintWriter("./result/hdfs292_no_root_funcs.json");
        pw = Files.newBufferedWriter(Paths.get(outputDir, "no_root_funcs.json"));
        gson.toJson(functionNoRoot, pw);
        pw.close();
    }    
}

