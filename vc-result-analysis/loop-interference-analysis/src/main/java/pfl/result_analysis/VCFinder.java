package pfl.result_analysis;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ObjectInputStream;
import java.io.OptionalDataException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IntSummaryStatistics;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;
import org.jgrapht.Graph;
import org.jgrapht.Graphs;
import org.jgrapht.alg.connectivity.ConnectivityInspector;
import org.jgrapht.alg.cycle.DirectedSimpleCycles;
import org.jgrapht.alg.cycle.HawickJamesSimpleCycles;
import org.jgrapht.alg.cycle.JohnsonSimpleCycles;
import org.jgrapht.alg.cycle.SzwarcfiterLauerSimpleCycles;
import org.jgrapht.alg.cycle.TarjanSimpleCycles;
import org.jgrapht.alg.interfaces.ShortestPathAlgorithm;
import org.jgrapht.alg.shortestpath.DijkstraShortestPath;
import org.jgrapht.graph.AsSubgraph;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.graph.DirectedPseudograph;
import org.jgrapht.graph.concurrent.AsSynchronizedGraph;

import com.google.common.collect.Lists;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import pfl.graph.IMethodRawNameNode;
import pfl.result_analysis.graph.CFGEdge;
import pfl.result_analysis.graph.ExecEdge;
import pfl.result_analysis.graph.InverseCFGEdge;
import pfl.result_analysis.graph.IterCountCorrelationEdge;
import pfl.result_analysis.graph.IterCountEdge;
import pfl.result_analysis.graph.LoopInterferenceEdge;
import pfl.result_analysis.graph.RPCEdge;
import pfl.result_analysis.graph.StringVertex;
import pfl.result_analysis.graph.WrappingDefaultEdge;
import pfl.result_analysis.utils.LoopHash;
import pfl.result_analysis.utils.LoopItem;
import pfl.result_analysis.utils.Utils;

public class VCFinder
{
    public static int ENTRY_ROOT_UTIL_THRESHOLD = 40;

    public static void main(String[] args) throws Exception
    {
        Options options = new Options();
        options.addOption("loops", true, "json file for loops");
        options.addOption("entry_root", true, "json file to entry roots");
        options.addOption("interference_graph", true, "graph output path");
        DefaultParser parser = new DefaultParser();
        CommandLine cmd = parser.parse(options, args);

        Map<String, Map<String, Object>> loopMapT = Utils.readJson(cmd.getOptionValue("loops"));
        Map<LoopHash, LoopItem> loopMap = loopMapT.entrySet().stream().collect(Collectors.toMap(e -> LoopHash.wrap(e.getKey()), e -> LoopItem.mapToLoopItem(e.getValue())));
        Map<String, List<String>> entryRootsT = Utils.readJson(cmd.getOptionValue("entry_root"));
        Map<String, List<String>> entryRoots = new HashMap<>();
        entryRootsT.forEach((k, v) -> entryRoots.put(Utils.mapWalaFullMethodSignatureToDotSeparatedMethodName(k), v));
        Graph<LoopHash, LoopInterferenceEdge> interferenceGraph = new DirectedPseudograph<>(LoopInterferenceEdge.class);
        try (FileInputStream fis = new FileInputStream(cmd.getOptionValue("interference_graph"));
                BufferedInputStream bis = new BufferedInputStream(fis);
                ObjectInputStream ois = new ObjectInputStream(bis);)
        {
            interferenceGraph = ((DirectedPseudograph<LoopHash, LoopInterferenceEdge>) ois.readObject());
        }
        catch (OptionalDataException ode)
        {
            System.out.println(ode.length);
            System.out.println(ode.eof);
            System.exit(0);
        }

        // #region Remove loops in utility functions
        System.out.println("Before removing utility loops");
        System.out.println("# of vertex: " + interferenceGraph.vertexSet().size());
        System.out.println("# of edge: " + interferenceGraph.edgeSet().size());
        Set<LoopHash> utilLoops = new HashSet<>();
        for (LoopHash loopKey : interferenceGraph.vertexSet())
        {
            LoopItem loopItem = loopMap.get(loopKey);
            if (loopItem.func.equals("run")) continue;
            String fullMethodName = loopItem.clazz + "." + loopItem.func;
            if (!entryRoots.containsKey(fullMethodName)) continue;
            if (entryRoots.get(fullMethodName).size() > ENTRY_ROOT_UTIL_THRESHOLD) utilLoops.add(loopKey);
        }

        // Loops in proto
        for (LoopHash loopKey : interferenceGraph.vertexSet())
        {
            LoopItem loopItem = loopMap.get(loopKey);
            String clazzLower = loopItem.clazz.toLowerCase();
            if (clazzLower.contains("proto") || clazzLower.contains("util") || clazzLower.contains("metrics")) utilLoops.add(loopKey);
        }

        // Isolated loops
        for (LoopHash v : interferenceGraph.vertexSet())
        {
            if ((interferenceGraph.inDegreeOf(v) == 0) && (interferenceGraph.outDegreeOf(v) == 0)) utilLoops.add(v);
        }

        interferenceGraph.removeAllVertices(utilLoops);
        System.out.println("After removing utility loops");
        System.out.println("# of vertex: " + interferenceGraph.vertexSet().size());
        System.out.println("# of edge: " + interferenceGraph.edgeSet().size());

        // #endregion

        // #region Remove redundant edges
        // RPC > CFG > EXEC_INTERFERENCE > INTER_COUNT_INTERFERENCE > ITER_COUNT_CORRELATION
        for (LoopHash ep1 : interferenceGraph.vertexSet())
        {
            for (LoopHash ep2 : interferenceGraph.vertexSet())
            {
                List<LoopInterferenceEdge> edges = new ArrayList<>(interferenceGraph.getAllEdges(ep1, ep2));
                // if (ep1.toString().equals("5ae494a5e38f702f3a9afddfdc669f30") && ep2.toString().equals("0316afeb8d9ff724a4e1bdf8ec3d473d")) System.out.println(edges);
                edges.sort(new Comparator<LoopInterferenceEdge>()
                {
                    @Override
                    public int compare(LoopInterferenceEdge lhs, LoopInterferenceEdge rhs)
                    {
                        return lhs.type.compareTo(rhs.type);
                    }
                });
                if (edges.size() > 1)
                {
                    for (int i = 1; i < edges.size(); i++)
                    {
                        interferenceGraph.removeEdge(edges.get(i));
                    }
                }
            }
        }
        System.out.println("After removing redundant edges");
        System.out.println("# of vertex: " + interferenceGraph.vertexSet().size());
        System.out.println("# of edge: " + interferenceGraph.edgeSet().size());

        // Get CFG OutDegree Stat
        if (false)
        {
        Map<Integer, List<LoopHash>> cfgOutDegree = new HashMap<>();
        Map<Integer, List<LoopHash>> cfgInDegree = new HashMap<>();
        for (LoopHash loopKey: interferenceGraph.vertexSet())
        {
            Set<LoopInterferenceEdge> outEdges = interferenceGraph.outgoingEdgesOf(loopKey);
            Set<LoopInterferenceEdge> outCFGEdges = outEdges.stream().filter(e -> e instanceof CFGEdge).collect(Collectors.toSet());
            int outCFGEdgesCount = outCFGEdges.size();
            cfgOutDegree.computeIfAbsent(outCFGEdgesCount, k -> new ArrayList<>()).add(loopKey);

            Set<LoopInterferenceEdge> inEdges = interferenceGraph.incomingEdgesOf(loopKey);
            Set<LoopInterferenceEdge> inCFGEdges = inEdges.stream().filter(e -> e instanceof CFGEdge).collect(Collectors.toSet());
            int inCFGEdgesCount = inCFGEdges.size();
            cfgInDegree.computeIfAbsent(inCFGEdgesCount, k -> new ArrayList<>()).add(loopKey);
        }
        IntSummaryStatistics stat = cfgOutDegree.keySet().stream().collect(Collectors.summarizingInt(t -> t));
        Map<Integer, Integer> cfgOutDegreeStat = new LinkedHashMap<>();
        for (int i = stat.getMin(); i <= stat.getMax(); i++)
        {
            List<LoopHash> l = cfgOutDegree.getOrDefault(i, new ArrayList<>());
            cfgOutDegreeStat.put(i, l.size());
        }
        Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        try (PrintWriter pw = new PrintWriter("./cfg_stat_out.json"))
        {
            gson.toJson(cfgOutDegreeStat, pw);
            pw.flush();
        }

        stat = cfgInDegree.keySet().stream().collect(Collectors.summarizingInt(t -> t));
        Map<Integer, Integer> cfgInDegreeStat = new LinkedHashMap<>();
        for (int i = stat.getMin(); i <= stat.getMax(); i++)
        {
            List<LoopHash> l = cfgInDegree.getOrDefault(i, new ArrayList<>());
            cfgInDegreeStat.put(i, l.size());
        }
        try (PrintWriter pw = new PrintWriter("./cfg_stat_in.json"))
        {
            gson.toJson(cfgInDegreeStat, pw);
            pw.flush();
        }
        }

        // #endregion

        List<String> loopOfInterest = Lists.newArrayList();
        // Injection Loops
        loopOfInterest.add("5ae494a5e38f702f3a9afddfdc669f30"); // HB Sender | HB Check 7e3fe474781351fa1be0b7b58fb80bda (Exec)
        loopOfInterest.add("f14a4c17ad1a41db2661013456cdb966"); // FBR | Replication 8eee83dc5a4d318724bd1d3368fabb27 (Exec) | Replication 51e0b016c9ce9499009f2647d47b3245 (Stat) |
                                                                // processCommand 0316afeb8d9ff724a4e1bdf8ec3d473d (Stat)
        loopOfInterest.add("e236495077c697e12a6b37f7a2c15136"); // HB Check | Replication 51e0b016c9ce9499009f2647d47b3245 (Stat) | processCommand 0316afeb8d9ff724a4e1bdf8ec3d473d
                                                                // (Stat)
        loopOfInterest.add("8732dda4397b25de116b7d8c58a84037"); // DataXceiver | Set up pipe line 0c236c23339f09bb223bbb8af8783931 (Exec)
        // Affected Loops
        loopOfInterest.add("7e3fe474781351fa1be0b7b58fb80bda"); // HB Check
        loopOfInterest.add("8eee83dc5a4d318724bd1d3368fabb27"); // Replication
        loopOfInterest.add("51e0b016c9ce9499009f2647d47b3245"); // Replication
        loopOfInterest.add("0c236c23339f09bb223bbb8af8783931"); // Set up pipeline
        loopOfInterest.add("0316afeb8d9ff724a4e1bdf8ec3d473d"); // processCommand
        // Other loops
        loopOfInterest.add("6f118fc52b3bfeddbd2b1d1da1992bcf"); // DN blockReport() loop

        System.out.println("HB Sender -> HB Check: " + edgeExist(interferenceGraph, "5ae494a5e38f702f3a9afddfdc669f30", "7e3fe474781351fa1be0b7b58fb80bda"));
        System.out.println("FBR -> Replication 1: " + edgeExist(interferenceGraph, "f14a4c17ad1a41db2661013456cdb966", "8eee83dc5a4d318724bd1d3368fabb27"));
        System.out.println("FBR -> Replication 2: " + edgeExist(interferenceGraph, "f14a4c17ad1a41db2661013456cdb966", "51e0b016c9ce9499009f2647d47b3245"));
        System.out.println("FBR -> Replication 3: " + edgeExist(interferenceGraph, "f14a4c17ad1a41db2661013456cdb966", "0316afeb8d9ff724a4e1bdf8ec3d473d"));
        System.out.println("HB Check -> Replication 1: " + edgeExist(interferenceGraph, "e236495077c697e12a6b37f7a2c15136", "51e0b016c9ce9499009f2647d47b3245"));
        System.out.println("HB Check -> Replication 2: " + edgeExist(interferenceGraph, "e236495077c697e12a6b37f7a2c15136", "0316afeb8d9ff724a4e1bdf8ec3d473d"));
        System.out.println("Replication 1 -> processCommand: " + edgeExist(interferenceGraph, "51e0b016c9ce9499009f2647d47b3245", "0316afeb8d9ff724a4e1bdf8ec3d473d"));
        System.out.println("DataXceiver -> Pipeline Setup: " + edgeExist(interferenceGraph, "8732dda4397b25de116b7d8c58a84037", "0c236c23339f09bb223bbb8af8783931"));

        AsSubgraph<LoopHash, LoopInterferenceEdge> subgraph = new AsSubgraph<>(interferenceGraph, interferenceGraph.vertexSet(),
                interferenceGraph.edgeSet().stream().filter(e -> (e instanceof CFGEdge) || (e instanceof RPCEdge)).collect(Collectors.toSet()));
        ShortestPathAlgorithm<LoopHash, LoopInterferenceEdge> pathFinder = new DijkstraShortestPath<>(subgraph);
        ShortestPathAlgorithm.SingleSourcePaths<LoopHash, LoopInterferenceEdge> sspf = pathFinder.getPaths(LoopHash.wrap("5ae494a5e38f702f3a9afddfdc669f30"));
        System.out.println("HB Sender -> FBR: " + sspf.getPath(LoopHash.wrap("f14a4c17ad1a41db2661013456cdb966")));
        System.out.println("HB Sender -> processCommand: " + sspf.getPath(LoopHash.wrap("0316afeb8d9ff724a4e1bdf8ec3d473d")));

        ShortestPathAlgorithm<LoopHash, LoopInterferenceEdge> pathFinder2 = new DijkstraShortestPath<>(interferenceGraph);
        ;
        System.out.println(
                "Pipeline setup -> DataXceiver.run: " + pathFinder2.getPath(LoopHash.wrap("0c236c23339f09bb223bbb8af8783931"), LoopHash.wrap("8732dda4397b25de116b7d8c58a84037")));
        System.out
                .println("Replication -> HB Sender: " + pathFinder2.getPath(LoopHash.wrap("8eee83dc5a4d318724bd1d3368fabb27"), LoopHash.wrap("5ae494a5e38f702f3a9afddfdc669f30")));

        System.out.println(interferenceGraph.inDegreeOf(LoopHash.wrap("5ae494a5e38f702f3a9afddfdc669f30")));

        // #region Aggregate based on method name
        // if (false)
        // {
        Map<LoopHash, List<String>> loopToEntryRootMap = new HashMap<>();
        Map<String, Set<LoopHash>> entryRootToLoopMap = new HashMap<>();
        for (LoopHash loopKey : interferenceGraph.vertexSet())
        {
            LoopItem loopItem = loopMap.get(loopKey);
            String fullMethodName = loopItem.clazz + "." + loopItem.func;
            loopToEntryRootMap.computeIfAbsent(loopKey, k -> new ArrayList<>()).add(fullMethodName);
            entryRootToLoopMap.computeIfAbsent(fullMethodName, k -> new HashSet<>()).add(loopKey);
            // if (entryRoots.containsKey(fullMethodName))
            // {
            // for (String er: entryRoots.get(fullMethodName))
            // {
            // loopToEntryRootMap.computeIfAbsent(loopKey, k -> new ArrayList<>()).add(er);
            // entryRootToLoopMap.computeIfAbsent(er, k -> new HashSet<>()).add(loopKey);
            // }
            // }
        }
        Graph<StringVertex, WrappingDefaultEdge> entryRootInterferenceGraph = new DefaultDirectedGraph<>(WrappingDefaultEdge.class);
        for (String er1 : entryRootToLoopMap.keySet())
        {
            StringVertex er1V = StringVertex.wrap(er1);
            entryRootInterferenceGraph.addVertex(er1V);
            for (String er2 : entryRootToLoopMap.keySet())
            {
                StringVertex er2V = StringVertex.wrap(er2);
                entryRootInterferenceGraph.addVertex(er2V);
                if (er1.equals(er2)) continue;
                // if (entryRootInterferenceGraph.containsEdge(er1, er2)) continue;
                // boolean connected = false;
                for (LoopHash loopKey1 : entryRootToLoopMap.get(er1))
                {
                    for (LoopHash loopKey2 : entryRootToLoopMap.get(er2))
                    {
                        LoopInterferenceEdge edge = interferenceGraph.getEdge(loopKey1, loopKey2);
                        if (edge != null)
                        {
                            // connected = true;
                            WrappingDefaultEdge originalEdge = entryRootInterferenceGraph.getEdge(er1V, er2V);
                            if ((originalEdge == null) || ((originalEdge != null) && (edge.type.ordinal() < originalEdge.wrappedEdge.type.ordinal())))
                            {
                                if (originalEdge != null) entryRootInterferenceGraph.removeEdge(originalEdge);
                                entryRootInterferenceGraph.addEdge(er1V, er2V, new WrappingDefaultEdge(edge));
                                // break;
                            }
                        }
                    }
                    // if (connected) break;
                }
            }
        }
        System.out.println("Entry root interference graph vertex: " + entryRootInterferenceGraph.vertexSet().size());
        System.out.println("Entry root interference graph edge: " + entryRootInterferenceGraph.edgeSet().size());

        System.out.println(entryRootInterferenceGraph.getEdge(StringVertex.wrap("org.apache.hadoop.hdfs.server.datanode.BPServiceActor.offerService"),
                StringVertex.wrap("org.apache.hadoop.hdfs.server.blockmanagement.HeartbeatManager.heartbeatCheck")));
        System.out.println(entryRootInterferenceGraph.getEdge(StringVertex.wrap("org.apache.hadoop.hdfs.server.blockmanagement.HeartbeatManager.heartbeatCheck"),
                StringVertex.wrap("org.apache.hadoop.hdfs.server.blockmanagement.BlockManager.computeReplicationWorkForBlocks")));
        System.out.println(entryRootInterferenceGraph.getEdge(StringVertex.wrap("org.apache.hadoop.hdfs.server.blockmanagement.BlockManager.computeReplicationWorkForBlocks"),
                StringVertex.wrap("org.apache.hadoop.hdfs.server.datanode.BPServiceActor.processCommand")));
        System.out.println(entryRootInterferenceGraph.getEdge(StringVertex.wrap("org.apache.hadoop.hdfs.server.datanode.BPServiceActor.processCommand"),
                StringVertex.wrap("org.apache.hadoop.hdfs.server.datanode.BPServiceActor.offerService")));
        System.out.println(entryRootInterferenceGraph.getEdge(StringVertex.wrap("org.apache.hadoop.hdfs.server.blockmanagement.HeartbeatManager.heartbeatCheck"),
                StringVertex.wrap("org.apache.hadoop.hdfs.server.datanode.BPServiceActor.processCommand")));
        System.out.println("+++++++++++++++++++++");
        System.out.println(entryRootInterferenceGraph.getEdge(StringVertex.wrap("org.apache.hadoop.hdfs.server.datanode.BPServiceActor.offerService"),
            StringVertex.wrap("org.apache.hadoop.hdfs.server.datanode.BPServiceActor.blockReport")));
        System.out.println(entryRootInterferenceGraph.getEdge(StringVertex.wrap("org.apache.hadoop.hdfs.server.datanode.BPServiceActor.blockReport"),
            StringVertex.wrap("org.apache.hadoop.hdfs.server.namenode.NameNodeRpcServer.blockReport")));
        System.out.println(entryRootInterferenceGraph.getEdge(StringVertex.wrap("org.apache.hadoop.hdfs.server.namenode.NameNodeRpcServer.blockReport"),
            StringVertex.wrap("org.apache.hadoop.hdfs.server.blockmanagement.BlockManager.computeReplicationWorkForBlocks")));
        System.out.println(entryRootInterferenceGraph.getEdge(StringVertex.wrap("org.apache.hadoop.hdfs.server.blockmanagement.BlockManager.computeReplicationWorkForBlocks"),
            StringVertex.wrap("org.apache.hadoop.hdfs.server.datanode.BPServiceActor.processCommand")));
        System.out.println(entryRootInterferenceGraph.getEdge(StringVertex.wrap("org.apache.hadoop.hdfs.server.datanode.BPServiceActor.processCommand"),
            StringVertex.wrap("org.apache.hadoop.hdfs.server.datanode.BPServiceActor.offerService")));
        System.out.println(entryRootInterferenceGraph.getEdge(StringVertex.wrap("org.apache.hadoop.hdfs.server.namenode.NameNodeRpcServer.blockReport"),
            StringVertex.wrap("org.apache.hadoop.hdfs.server.datanode.BPServiceActor.processCommand")));

        SzwarcfiterLauerSimpleCycles<StringVertex, WrappingDefaultEdge> cycleFinder = new SzwarcfiterLauerSimpleCycles<>(entryRootInterferenceGraph);
        AtomicInteger cycleCount = new AtomicInteger();
        List<List<StringVertex>> cycles = new ArrayList<>();
        List<List<StringVertex>> allCycles = new ArrayList<>();
        BiConsumer<PrintWriter, List<StringVertex>> cyclePrinter = (pw, e) -> 
        {
            Map<Integer, Integer> edgeStat = new HashMap<>();
            for (int i = 0; i < e.size() - 1; i++)
            {
                int edgeTypeRaw = entryRootInterferenceGraph.getEdge(e.get(i), e.get(i + 1)).wrappedEdge.type.ordinal();
                edgeStat.put(edgeTypeRaw, edgeStat.getOrDefault(edgeTypeRaw, 0) + 1);
            }
            List<Integer> edgeKey = new ArrayList<>(edgeStat.keySet());
            Collections.sort(edgeKey);
            for (int ty: edgeKey)
            {
                pw.println(LoopInterferenceEdge.Type.values()[ty].toString() + ": " + edgeStat.get(ty));
            }
            for (StringVertex er : e)
            {
                pw.println("Vertex: " + er);
                pw.println("Included Loops: " + entryRootToLoopMap.get(er.rawString));
                pw.println();
            }
            for (int i = 0; i < e.size() - 1; i++)
            {
                pw.println("Edge: " + entryRootInterferenceGraph.getEdge(e.get(i), e.get(i + 1)));
            }
            pw.println("=================================================");
            pw.println();
            pw.flush();
        };
        Comparator<List<StringVertex>> cycleComparator = new Comparator<List<StringVertex>>() 
        {
            @Override
            public int compare(List<StringVertex> lhs, List<StringVertex> rhs)
            {
                return -Double.compare(getCycleScore(entryRootInterferenceGraph, lhs), getCycleScore(entryRootInterferenceGraph, rhs));
            }
        };
        try (PrintWriter pw = new PrintWriter("./cycle.txt"))
        {
            cycleFinder.findSimpleCycles(e ->
            {
                allCycles.add(e);
                if (allCycles.size() % 10000 == 0)
                {
                    Collections.shuffle(allCycles);
                    try (PrintWriter pw2 = new PrintWriter("./cycle_all.txt"))
                    {
                        pw2.println("All Cycles: " + allCycles.size());
                        for (int i = 0; i < allCycles.size(); i++)
                        {
                            pw2.println("Cycle: " + i + "\tScore: " + getCycleScore(entryRootInterferenceGraph, allCycles.get(i)));
                            cyclePrinter.accept(pw2, allCycles.get(i));
                        }
                    }
                    catch (Exception ex) {};
                }

                if (e.size() > 8) return;
                e.add(e.get(0));
                double score = getCycleScore(entryRootInterferenceGraph, e);
                if (score <= 0) return;
                cycles.add(e);
                pw.println("Cycle: " + cycleCount.getAndIncrement() + "\tScore: " + score);
                cyclePrinter.accept(pw, e);
                if (cycleCount.get() % 50 == 0)
                {
                    cycles.sort(cycleComparator);
                    try (PrintWriter pw2 = new PrintWriter("./cycle_sorted.txt"))
                    {
                        AtomicInteger c = new AtomicInteger();
                        cycles.forEach(e2 ->
                        {
                            pw2.println("Cycle: " + c.getAndIncrement() + "\tScore: " + getCycleScore(entryRootInterferenceGraph, e2));
                            cyclePrinter.accept(pw2, e2);
                        });
                    }
                    catch (Exception ex) {};
                }
            });
        }

        cycles.sort(cycleComparator);
        try (PrintWriter pw = new PrintWriter("./cycle_sorted.txt"))
        {
            pw.println("Cycles: " + cycleCount.get());
            cycleCount.set(0);
            cycles.forEach(e ->
            {
                pw.println("Cycle: " + cycleCount.getAndIncrement());
                cyclePrinter.accept(pw, e);
            });
        }

        Utils.dumpObj(entryRootInterferenceGraph, "/home/qian151/research/vc-detect/vc-result-analysis/result/hdfs292_method_indexed_interference_graph.obj");
        Utils.dumpObj(cycles, "/home/qian151/research/vc-detect/vc-result-analysis/result/hdfs292_method_interference_graph_cycles.obj");
        // }
        // #endregion

        // Set<LoopHash> zeroInDegree = new HashSet<>();
        // for (LoopHash v: interferenceGraph.vertexSet())
        // {
        // if (interferenceGraph.inDegreeOf(v) == 0) zeroInDegree.add(v);
        // }
        // System.out.println("Zero In Degree: " + zeroInDegree.size());

        // ConnectivityInspector<LoopHash, LoopInterferenceEdge> connInspector = new ConnectivityInspector<>(subgraph);
        // List<Set<LoopHash>> connectedSets = connInspector.connectedSets();
        // Graph<Set<LoopHash>, DefaultEdge> condensedGraph = new DefaultDirectedGraph<>(DefaultEdge.class);
        // connectedSets.forEach(s -> condensedGraph.addVertex(s));
        // for (int i = 0; i < connectedSets.size(); i++)
        // {
        // for (int j = 0; j < connectedSets.size(); j++)
        // {
        // if (i == j) continue;
        // boolean foundEdge = false;
        // for (LoopHash loop1: connectedSets.get(i))
        // {
        // for (LoopHash loop2: connectedSets.get(j))
        // {
        // if (interferenceGraph.containsEdge(loop1, loop2))
        // {
        // foundEdge = true;
        // break;
        // }
        // }
        // if (foundEdge) break;
        // }
        // if (foundEdge) condensedGraph.addEdge(connectedSets.get(i), connectedSets.get(j));
        // }
        // }
        // System.out.println("Condensed graph vertex: " + condensedGraph.vertexSet().size());
        // System.out.println("Condensed graph edges: " + condensedGraph.edgeSet().size());

        // TarjanSimpleCycles<Set<LoopHash>, DefaultEdge> cycleFinder = new TarjanSimpleCycles<>(condensedGraph);
        // AtomicInteger cycleCount = new AtomicInteger();
        // try (PrintWriter pw = new PrintWriter("./cycle.txt"))
        // {
        // cycleFinder.findSimpleCycles(e ->
        // {
        // pw.println("Cycle: " + cycleCount.getAndIncrement());
        // for (Set<LoopHash> loop: e)
        // {
        // pw.println("Vertex: " + loop);
        // pw.println();
        // }
        // pw.println("=================================================");
        // pw.println();
        // pw.flush();
        // });
        // }

    }

    public static double getCycleScore(Graph<StringVertex, WrappingDefaultEdge> graph, List<StringVertex> cycle)
    {
        int cycleScore = 0;
        int inverseCFGCount = 0;
        int correlationEdgeCount = 0;
        for (int i = 0; i < cycle.size() - 1; i++)
        {
            WrappingDefaultEdge edge = graph.getEdge(cycle.get(i), cycle.get(i + 1));
            if (edge.wrappedEdge instanceof ExecEdge)
                cycleScore += 1;
            else if (edge.wrappedEdge instanceof IterCountEdge)
                cycleScore += 1;
            else if (edge.wrappedEdge instanceof IterCountCorrelationEdge)
                correlationEdgeCount++;
            else if (edge.wrappedEdge instanceof InverseCFGEdge)
                inverseCFGCount++;
        }
        if (inverseCFGCount >= 2) cycleScore = cycleScore - inverseCFGCount + 1;
        if (correlationEdgeCount >= 2) cycleScore = cycleScore - correlationEdgeCount * 2 + 2;
        return (double) cycleScore / (cycle.size() - 1);
    }

    public static Set<LoopInterferenceEdge> edgeExist(Graph<LoopHash, LoopInterferenceEdge> graph, String e1, String e2)
    {
        return graph.getAllEdges(LoopHash.wrap(e1), LoopHash.wrap(e2));
    }
}
