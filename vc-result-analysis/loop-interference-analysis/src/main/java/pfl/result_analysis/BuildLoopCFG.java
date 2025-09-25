package pfl.result_analysis;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IntSummaryStatistics;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;
import org.jgrapht.Graph;
import org.jgrapht.GraphPath;
import org.jgrapht.Graphs;
import org.jgrapht.alg.interfaces.ShortestPathAlgorithm;
import org.jgrapht.alg.shortestpath.DijkstraShortestPath;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.jgrapht.graph.DefaultEdge;

import com.github.luben.zstd.ZstdInputStream;
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
import pfl.result_analysis.graph.CFGEdge;
import pfl.result_analysis.graph.LoopInterferenceEdge;
import pfl.result_analysis.graph.StringVertex;
import pfl.result_analysis.graph.WrappingDefaultEdge;
import pfl.result_analysis.utils.LoopHash;
import pfl.result_analysis.utils.LoopItem;
import pfl.result_analysis.utils.Utils;

public class BuildLoopCFG 
{
    public static int LOOP_CFG_PATH_LENGTH_LIMIT = 1;
    public static void main(String[] args) throws Exception
    {
        Options options = new Options();
        options.addOption("cfg", true, "System's control flow graph");
        options.addOption("loops", true, "json file for loops");
        options.addOption("graph_output_path", true, "loop cfg output path");
        options.addOption("classpath", true, "WALA classpath");
        options.addOption("loop_cfg_path_length", true, "Maximum distance of two loops in the callgraph that they can be connected with a CFG edge");
        DefaultParser parser = new DefaultParser();
        CommandLine cmd = parser.parse(options, args);

        if (cmd.hasOption("loop_cfg_path_length"))
        {
            System.out.println("Using custom LOOP_CFG_PATH_LENGTH_LIMIT: " + cmd.getOptionValue("loop_cfg_path_length"));
            LOOP_CFG_PATH_LENGTH_LIMIT = Integer.valueOf(cmd.getOptionValue("loop_cfg_path_length"));
        }
        else
        {
            System.out.println("Using default LOOP_CFG_PATH_LENGTH_LIMIT: 1");
        }
        Map<String, Map<String, Object>> loopMapT = Utils.readJson(cmd.getOptionValue("loops"));
        Map<LoopHash, LoopItem> loopMap = loopMapT.entrySet().stream().collect(Collectors.toMap(e -> LoopHash.wrap(e.getKey()), e -> LoopItem.mapToLoopItem(e.getValue())));

        Graph<IMethodRawNameNode, DefaultEdge> cgT;
        try (FileInputStream fis = new FileInputStream(cmd.getOptionValue("cfg"));
                BufferedInputStream bis = new BufferedInputStream(fis);
                ZstdInputStream zis = new ZstdInputStream(bis);
                ObjectInputStream ois = new ObjectInputStream(zis);)
        {
            cgT = (DefaultDirectedGraph<IMethodRawNameNode, DefaultEdge>) ois.readObject();
        }
        cgT.removeAllVertices(cgT.vertexSet().stream().filter(v -> v.rawName.split("\\(")[0].contains("java/lang")).collect(Collectors.toList()));
        cgT.removeAllVertices(cgT.vertexSet().stream().filter(v -> v.rawName.split("\\(")[0].contains("org/jboss")).collect(Collectors.toList()));
        System.out.println("Graph Loaded");

        Graph<LoopHash, DefaultEdge> loopCFG = new DefaultDirectedGraph<>(DefaultEdge.class);
        for (LoopHash loopKey: loopMap.keySet())
        {
            loopCFG.addVertex(loopKey);
        }
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
        for (LoopHash loopKey : loopMap.keySet())
        {
            LoopItem loopItem = loopMap.get(loopKey);
            TypeReference tr = TypeReference.find(ClassLoaderReference.Application, Utils.toWalaFullClassName(loopItem.clazz));
            if (tr == null)
            {
                System.out.println(Utils.toWalaFullClassName(loopItem.clazz));
                continue;
            }
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
        ShortestPathAlgorithm<IMethodRawNameNode, DefaultEdge> pathFinder = new DijkstraShortestPath<>(cgT, LOOP_CFG_PATH_LENGTH_LIMIT);
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
                for (IMethod invokeTarget : invokeTargets)
                {
                    IMethodRawNameNode startVertex = new IMethodRawNameNode(invokeTarget);
                    if (!cgT.containsVertex(startVertex)) continue;
                    ShortestPathAlgorithm.SingleSourcePaths<IMethodRawNameNode, DefaultEdge> singleSourcePathFinder = pathFinder.getPaths(startVertex);
                    for (IMethod succIMethodFromInvokeTarget : methodIndexedLoops.keySet())
                    {
                        if (visited.contains(succIMethodFromInvokeTarget)) continue;
                        IMethodRawNameNode endVertex = new IMethodRawNameNode(succIMethodFromInvokeTarget);
                        GraphPath<IMethodRawNameNode, DefaultEdge> path = singleSourcePathFinder.getPath(endVertex);
                        if (path == null) continue; // Not connected
                        if (path.getLength() > LOOP_CFG_PATH_LENGTH_LIMIT) continue; // Too long
                        for (IMethodRawNameNode v : path.getVertexList())
                        {
                            IMethod vIMethod = inverseCGVertexMap.get(v);
                            if (vIMethod == null) continue; // Means that no loop in this method as inverseCGVertexMap is created when iterating through loops
                            if (visited.contains(vIMethod)) continue;
                            visited.add(vIMethod);
                            if (!methodIndexedLoops.containsKey(vIMethod)) continue;
                            for (LoopHash succLoopKey : methodIndexedLoops.get(vIMethod))
                            {
                                // Synchronized to prevent OptionalDataException when loading it back
                                // See https://issues.apache.org/jira/browse/AMQ-2083 and https://stackoverflow.com/questions/44229340/optionaldataexception-with-no-apparent-reason
                                synchronized (loopCFG)
                                {
                                    loopCFG.addEdge(loopKey, succLoopKey, new CFGEdge());
                                    // loopCFG.addEdge(succLoopKey, loopKey, new InverseCFGEdge());
                                }
                            }
                            break; // We don't connect any further down the chain because we have already src loop to another
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

        // Reduce loopCFG by function name
        Map<String, Set<LoopHash>> methodToLoopMap = new HashMap<>();
        for (LoopHash loopKey : loopCFG.vertexSet())
        {
            LoopItem loopItem = loopMap.get(loopKey);
            String fullMethodName = loopItem.clazz + "." + loopItem.func;
            methodToLoopMap.computeIfAbsent(fullMethodName, k -> new HashSet<>()).add(loopKey);
        }
        for (LoopHash loopKey: loopCFG.vertexSet())
        {
            LoopItem loopItem = loopMap.get(loopKey);
            String fullMethodName = loopItem.clazz + "." + loopItem.func;
            Set<LoopHash> loopsToMerge = methodToLoopMap.get(fullMethodName);
            for (LoopHash loopToMerge: loopsToMerge)
            {
                List<LoopHash> succs = Graphs.successorListOf(loopCFG, loopToMerge);
                succs.forEach(succ -> loopCFG.addEdge(loopKey, succ));
            }
        }

        System.out.println("Vertex: " + loopCFG.vertexSet().size());
        System.out.println("Edge: " + loopCFG.edgeSet().size());

        try (FileOutputStream fos = new FileOutputStream(cmd.getOptionValue("graph_output_path"));
             BufferedOutputStream bos = new BufferedOutputStream(fos);
             ObjectOutputStream oos = new ObjectOutputStream(bos);)
        {
            oos.writeObject(loopCFG);
            oos.close();
            bos.close();
            fos.close();
        }
    }    
}
