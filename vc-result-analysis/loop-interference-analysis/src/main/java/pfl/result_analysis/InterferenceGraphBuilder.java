package pfl.result_analysis;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.ObjectInputStream;
import java.io.ObjectOutput;
import java.io.ObjectOutputStream;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IntSummaryStatistics;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Collector;
import java.util.stream.Collectors;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;
import org.jgrapht.Graph;
import org.jgrapht.GraphPath;
import org.jgrapht.alg.interfaces.ShortestPathAlgorithm;
import org.jgrapht.alg.shortestpath.DijkstraShortestPath;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.graph.DirectedMultigraph;
import org.jgrapht.graph.DirectedPseudograph;
import org.jgrapht.graph.concurrent.AsSynchronizedGraph;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
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
import pfl.result_analysis.graph.ExecEdge;
import pfl.result_analysis.graph.InverseCFGEdge;
import pfl.result_analysis.graph.IterCountCorrelationEdge;
import pfl.result_analysis.graph.IterCountEdge;
import pfl.result_analysis.graph.LoopInterferenceEdge;
import pfl.result_analysis.graph.RPCEdge;
import pfl.result_analysis.utils.LoopHash;
import pfl.result_analysis.utils.LoopItem;
import pfl.result_analysis.utils.Utils;

public class InterferenceGraphBuilder
{
    public static int CFG_PATH_LENGTH_LIMIT = 2;
    public static void main(String[] args) throws Exception
    {
        Options options = new Options();
        options.addOption("loops", true, "json file for loops");
        options.addOption("exec_interference_result", true, "path to the loop exec interference json");
        options.addOption("iter_count_interference_result", true, "path to the loop iter (stat) interference json");
        options.addOption("iter_count_corr_result", true, "path to the loop iter count correlation json");
        options.addOption("entry_root", true, "json file to entry roots");
        options.addOption("rpc_links", true, "loops from/to rpc calls");
        options.addOption("cfg", true, "system control flow graph");
        options.addOption("classpath", true, "WALA classpath");
        options.addOption("graph_output_path", true, "graph output path");
        DefaultParser parser = new DefaultParser();
        CommandLine cmd = parser.parse(options, args);

        Map<String, Map<String, Object>> loopMapT = Utils.readJson(cmd.getOptionValue("loops"));
        Map<LoopHash, LoopItem> loopMap = loopMapT.entrySet().stream().collect(Collectors.toMap(e -> LoopHash.wrap(e.getKey()), e -> LoopItem.mapToLoopItem(e.getValue())));
        Map<String, List<String>> execInterferenceMapT = Utils.readJson(cmd.getOptionValue("exec_interference_result"));
        Map<LoopHash, List<LoopHash>> execInterferenceMap = execInterferenceMapT.entrySet().stream()
                .collect(Collectors.toMap(e -> LoopHash.wrap(e.getKey()), e -> e.getValue().stream().map(l -> LoopHash.wrap(l)).collect(Collectors.toList())));
        Map<String, List<String>> iterCountInterferenceMapT = Utils.readJson(cmd.getOptionValue("iter_count_interference_result"));
        Map<LoopHash, List<LoopHash>> iterCountInterferenceMap = iterCountInterferenceMapT.entrySet().stream()
                .collect(Collectors.toMap(e -> LoopHash.wrap(e.getKey()), e -> e.getValue().stream().map(l -> LoopHash.wrap(l)).collect(Collectors.toList())));
        Map<String, List<String>> iterCountCorrelationMapT = Utils.readJson(cmd.getOptionValue("iter_count_corr_result"));
        Map<LoopHash, List<LoopHash>> iterCountCorrelationMap = iterCountCorrelationMapT.entrySet().stream()
                .collect(Collectors.toMap(e -> LoopHash.wrap(e.getKey()), e -> e.getValue().stream().map(l -> LoopHash.wrap(l)).collect(Collectors.toList())));
        Map<String, Map<String, List<String>>> rpcLinkRawMap = Utils.readJson(cmd.getOptionValue("rpc_links"));
        Map<String, List<String>> entryRoots = Utils.readJson(cmd.getOptionValue("entry_root"));
        Graph<IMethodRawNameNode, DefaultEdge> cgT;
        try (FileInputStream fis = new FileInputStream(cmd.getOptionValue("cfg"));
                    BufferedInputStream bis = new BufferedInputStream(fis);
                    ObjectInputStream ois = new ObjectInputStream(bis);)
        {
            cgT = (DefaultDirectedGraph<IMethodRawNameNode, DefaultEdge>) ois.readObject();
        }
        cgT.removeAllVertices(cgT.vertexSet().stream().filter(v -> v.rawName.contains("java/lang")).collect(Collectors.toList()));

        Graph<LoopHash, LoopInterferenceEdge> interferenceGraph = new DirectedPseudograph<>(LoopInterferenceEdge.class);
        for (LoopHash loopKey: loopMap.keySet())
        {
            interferenceGraph.addVertex(loopKey);
        }
        // Type I edge: Exec signature interferences
        Set<LoopHash> missingLoop = new HashSet<>();
        for (LoopHash loopKey1: execInterferenceMap.keySet())
        {
            for (LoopHash loopKey2: execInterferenceMap.get(loopKey1))
            {
                if (!interferenceGraph.containsVertex(loopKey1))
                {
                    missingLoop.add(loopKey1);
                    continue;
                }
                if (!interferenceGraph.containsVertex(loopKey2))
                {
                    missingLoop.add(loopKey2);
                    continue;
                }
                interferenceGraph.addEdge(loopKey1, loopKey2, new ExecEdge());
            }
        }
        System.out.println("Type I edge finished, missing loop: " + missingLoop.size());
        missingLoop.clear();
        // Type II edge: Exec iter count interferences
        for (LoopHash loopKey1: iterCountInterferenceMap.keySet())
        {
            for (LoopHash loopKey2: iterCountInterferenceMap.get(loopKey1))
            {
                if (!interferenceGraph.containsVertex(loopKey1))
                {
                    missingLoop.add(loopKey1);;
                    continue;
                }
                if (!interferenceGraph.containsVertex(loopKey2))
                {
                    missingLoop.add(loopKey2);
                    continue;
                }
                interferenceGraph.addEdge(loopKey1, loopKey2, new IterCountEdge());
            }
        }
        System.out.println("Type II edge finished, missing loop: " + missingLoop.size());

        // Type III: CFG Edges
        // In the loop body of loop A, it calls f1(), f2()
        // In the CFG, we get all the paths with length less than 4 starting from f1() and f2(),
        // and get all the functions in those paths.
        // Suppose the functions are f3(), f4(), and f5(), which includes loop B, C, D.
        // Loop A has CFG Edges to Loops B, C, and D
        Map<IMethod, List<LoopHash>> methodIndexedLoops = new ConcurrentHashMap<>();
        Map<LoopHash, IMethod> loopEnclosingMethod = new ConcurrentHashMap<>();
        Map<LoopHash, List<IMethod>> loopInvokeTargets = new ConcurrentHashMap<>();
        Map<IMethodRawNameNode, IMethod> inverseCGVertexMap = new ConcurrentHashMap<>();
        WalaModel model = new WalaModel(cmd.getOptionValue("classpath"));
        AnalysisOptions analysisOptions = new AnalysisOptions(model.getScope(), new AllApplicationEntrypoints(model.getScope(), model.getCha()));
        AnalysisCache analysisCache = new AnalysisCacheImpl();
        for (LoopHash loopKey: loopMap.keySet())
        {
            LoopItem loopItem = loopMap.get(loopKey);
            TypeReference tr = TypeReference.find(ClassLoaderReference.Application, Utils.toWalaFullClassName(loopItem.clazz));
            IClass loopClass = model.getCha().lookupClass(tr);
            for (IMethod loopMethod : loopClass.getDeclaredMethods())
            {
                inverseCGVertexMap.put(new IMethodRawNameNode(loopMethod), loopMethod);
                if (!Utils.getShortMethodName(loopMethod).equals(loopItem.func)) continue;
                IR methodIR = analysisCache.getSSACache().findOrCreateIR(loopMethod, Everywhere.EVERYWHERE, analysisOptions.getSSAOptions());
                if (methodIR == null) continue;
                IntSummaryStatistics methodStat = Utils.getMethodIRLineRange(methodIR);
                if ((loopItem.startLineNo >= methodStat.getMin()) && (loopItem.endLineNo <= methodStat.getMax()))
                {
                    methodIndexedLoops.computeIfAbsent(loopMethod, k -> new ArrayList<>()).add(loopKey);
                    loopEnclosingMethod.put(loopKey, loopMethod);
                    List<SSAInstruction> invokeInsts = Arrays.stream(methodIR.getInstructions()).filter(Objects::nonNull)
                            .filter(inst -> inst instanceof SSAInvokeInstruction)
                            .filter(inst -> loopItem.instInLoopRange(Utils.getSrcLineNumberBySSAInst(methodIR, inst.iIndex()))).collect(Collectors.toList());
                    List<IMethod> invokeTargets = invokeInsts.stream().map(inst -> model.getCha().resolveMethod(((SSAInvokeInstruction) inst).getDeclaredTarget()))
                            .filter(Objects::nonNull)
                            .collect(Collectors.toList());
                    loopInvokeTargets.put(loopKey, invokeTargets);
                }
            }
        }
        ShortestPathAlgorithm<IMethodRawNameNode, DefaultEdge> pathFinder = new DijkstraShortestPath<>(cgT, CFG_PATH_LENGTH_LIMIT);
        System.out.println("Type III Total Loop: " + loopMap.size());
        Consumer<LoopHash> typeIIIProcessor = loopKey ->
        {
            try 
            {
                if (!loopEnclosingMethod.containsKey(loopKey))
                {
                    System.out.println("Missing IMethod for loop: " + loopKey);
                    return;
                }
                List<IMethod> invokeTargets = loopInvokeTargets.get(loopKey);
                Set<IMethod> visited = new HashSet<>();
                for (IMethod invokeTarget: invokeTargets)
                {
                    IMethodRawNameNode startVertex = new IMethodRawNameNode(invokeTarget);
                    if (!cgT.containsVertex(startVertex)) continue;
                    ShortestPathAlgorithm.SingleSourcePaths<IMethodRawNameNode, DefaultEdge> singleSourcePathFinder = pathFinder.getPaths(startVertex);
                    for (IMethod succIMethodFromInvokeTarget: methodIndexedLoops.keySet())
                    {
                        if (visited.contains(succIMethodFromInvokeTarget)) continue;
                        IMethodRawNameNode endVertex = new IMethodRawNameNode(succIMethodFromInvokeTarget);
                        GraphPath<IMethodRawNameNode, DefaultEdge> path = singleSourcePathFinder.getPath(endVertex);
                        if (path == null) continue; // Not connected
                        if (path.getLength() > CFG_PATH_LENGTH_LIMIT) continue; // Too long
                        for (IMethodRawNameNode v: path.getVertexList())
                        {
                            IMethod vIMethod = inverseCGVertexMap.get(v);
                            if (vIMethod == null) continue; // Means that no loop in this method as inverseCGVertexMap is created when iterating through loops
                            if (visited.contains(vIMethod)) continue;
                            visited.add(vIMethod);
                            if (!methodIndexedLoops.containsKey(vIMethod)) continue;
                            for (LoopHash succLoopKey: methodIndexedLoops.get(vIMethod))
                            {
                                // Synchronized to prevent OptionalDataException when loading it back
                                // See https://issues.apache.org/jira/browse/AMQ-2083 and https://stackoverflow.com/questions/44229340/optionaldataexception-with-no-apparent-reason
                                synchronized(interferenceGraph)
                                {
                                    interferenceGraph.addEdge(loopKey, succLoopKey, new CFGEdge());
                                    interferenceGraph.addEdge(succLoopKey, loopKey, new InverseCFGEdge());
                                }
                            }
                        }
                    }
                }
            }
            catch (Throwable e)
            {
                System.out.println("Error at: " + loopKey);
                e.printStackTrace();
            }
        };
        loopMap.keySet().parallelStream().forEach(e -> typeIIIProcessor.accept(e));
        System.out.println("Type III edge finished");

        // Type IV: RPC Edges
        // Loop 1 -> RPC_Client_Call ----> RPC_Server_Call -> Loop 2
        for (String rpcClientCallName: rpcLinkRawMap.get("LoopToRPCClientCalls").keySet())
        {
            String[] split = rpcClientCallName.split("\\.");
            String methodName = split[split.length - 1];
            for (String rpcServerCallName: rpcLinkRawMap.get("RPCServerCallsToLoops").keySet())
            {
                if (rpcServerCallName.contains(methodName))
                {
                    for (String loop1RawHash: rpcLinkRawMap.get("LoopToRPCClientCalls").get(rpcClientCallName))
                    {
                        LoopHash loop1Hash = LoopHash.wrap(loop1RawHash);
                        for (String loop2RawHash: rpcLinkRawMap.get("RPCServerCallsToLoops").get(rpcServerCallName))
                        {
                            LoopHash loop2Hash = LoopHash.wrap(loop2RawHash);
                            interferenceGraph.addEdge(loop1Hash, loop2Hash, new RPCEdge());
                        }
                    }
                }
            }
        }
        System.out.println("Type IV edge finished");

        // Type V: Iteration count correlation edges
        missingLoop.clear();
        for (LoopHash loopKey1: iterCountCorrelationMap.keySet())
        {
            for (LoopHash loopKey2: iterCountCorrelationMap.get(loopKey1))
            {
                if (!interferenceGraph.containsVertex(loopKey1))
                {
                    missingLoop.add(loopKey1);;
                    continue;
                }
                if (!interferenceGraph.containsVertex(loopKey2))
                {
                    missingLoop.add(loopKey2);
                    continue;
                }
                interferenceGraph.addEdge(loopKey1, loopKey2, new IterCountCorrelationEdge());
                interferenceGraph.addEdge(loopKey2, loopKey1, new IterCountCorrelationEdge());
            }
        }
        System.out.println("Type V edge finished, missing loop: " + missingLoop.size());

        System.out.println("# of vertex: " + interferenceGraph.vertexSet().size());
        System.out.println("# of edge: " + interferenceGraph.edgeSet().size());

        try (FileOutputStream fos = new FileOutputStream(cmd.getOptionValue("graph_output_path"));
             BufferedOutputStream bos = new BufferedOutputStream(fos);
             ObjectOutputStream oos = new ObjectOutputStream(bos);)
        {
            oos.writeObject(interferenceGraph);
            oos.close();
            bos.close();
            fos.close();
        }
    }

}
