package pfl;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.IntSummaryStatistics;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.jgrapht.Graph;
import org.jgrapht.Graphs;
import org.jgrapht.alg.connectivity.ConnectivityInspector;
import org.jgrapht.alg.interfaces.ShortestPathAlgorithm;
import org.jgrapht.alg.interfaces.ShortestPathAlgorithm.SingleSourcePaths;
import org.jgrapht.alg.shortestpath.DijkstraShortestPath;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.graph.EdgeReversedGraph;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.hash.HashCode;
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
import com.ibm.wala.ipa.callgraph.impl.AllApplicationEntrypoints;
import com.ibm.wala.ipa.callgraph.impl.Everywhere;
import com.ibm.wala.ipa.callgraph.propagation.SSAPropagationCallGraphBuilder;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAInvokeInstruction;
import com.ibm.wala.types.ClassLoaderReference;
import com.ibm.wala.types.Descriptor;
import com.ibm.wala.types.MethodReference;
import com.ibm.wala.types.Selector;
import com.ibm.wala.types.TypeReference;

import pfl.analysis_util.LoopItem;
import pfl.graph.ConcurrentConnectivityInspector;
import pfl.graph.IMethodRawNameNode;
import pfl.util.ProgressMonitor;
import pfl.util.Utils;

// Finding 1) Loop(s) -> RPC_Client Calls
//         2) RPC_Server Calls -> Loop(s)
public class LoopLinkThroughRPC
{
    public static WalaModel model;

    public static void main(String[] args) throws Exception
    {
        Options options = new Options();
        options.addOption("system", true, "System under analysis");
        options.addOption("cp", true, "classpath");
        options.addOption("output", true, "output path");
        options.addOption("loop_input", true, "loops found in the system from the loop finder");
        options.addOption("saved_cfg", true, "saved control flow graph");
        options.addOption("search_radius", true, "Dijkstra search radius for linking loops to RPC client methods");
        CommandLineParser parser = new DefaultParser();
        CommandLine cmd = parser.parse(options, args);
        String classpath = cmd.getOptionValue("cp");
        String loop_input = cmd.getOptionValue("loop_input");
        String system_under_analysis = cmd.getOptionValue("system");
        String saved_cfg_path = cmd.getOptionValue("saved_cfg");
        String output_path = cmd.getOptionValue("output");
        int search_radius = Integer.valueOf(cmd.getOptionValue("search_radius", "2"));
        model = new WalaModel(classpath);

        Map<String, Map<String, Object>> loopRaw = new HashMap<>();
        try (FileReader fr = new FileReader(loop_input);
                BufferedReader br = new BufferedReader(fr);)
        {
            Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
            loopRaw = gson.fromJson(br, loopRaw.getClass());
        }
        List<LoopItem> loops = new ArrayList<>();
        for (Map<String, Object> loopRawObj : loopRaw.values())
        {
            String className = (String) loopRawObj.get("Class");
            String methodName = (String) loopRawObj.get("Method");
            int startLine = ((Double) loopRawObj.get("StartLine")).intValue();
            int endLine = ((Double) loopRawObj.get("EndLine")).intValue();
            int loopBodyStartLine = ((Double) loopRawObj.get("LoopBodyStartLine")).intValue();
            int loopBodyEndLine = ((Double) loopRawObj.get("LoopBodyEndLine")).intValue();
            LoopItem loopItem = new LoopItem(className, methodName, startLine, endLine, loopBodyStartLine, loopBodyEndLine);
            assert loopItem.getLoopID().toString().equals((String) loopRawObj.get("LoopID"));
            loops.add(loopItem);
        }

        List<ImmutablePair<IMethod, IMethod>> rpcPairs = new ArrayList<>(); // Client Method -> Server Method
        for (ImmutablePair<String, String> rpcClassStrPair : RPCInfo.rpcMap.get(system_under_analysis))
        {
            String clientClassName = rpcClassStrPair.getLeft();
            String serverClassName = rpcClassStrPair.getRight();
            // System.out.println(clientClassName);
            TypeReference tr = TypeReference.find(ClassLoaderReference.Application, Utils.toWalaFullClassName(clientClassName));
            if (tr == null)
            {
                System.out.println("Cannot find client class: " + clientClassName);
                continue;
            }
            IClass clientClass = model.getCha().lookupClass(tr);
            tr = TypeReference.find(ClassLoaderReference.Application, Utils.toWalaFullClassName(serverClassName));
            IClass serverClass = model.getCha().lookupClass(tr);
            for (IMethod clientMethod : clientClass.getDeclaredMethods())
            {
                Selector sel = clientMethod.getSelector();
                // System.out.println("++++ + sel);
                IMethod serverMethod = serverClass.getMethod(sel);
                if (serverMethod != null) rpcPairs.add(ImmutablePair.of(clientMethod, serverMethod));
            }
        }

        Map<IMethod, List<HashCode>> loopsToRpcClientMethod = new ConcurrentHashMap<>(); // RPC Client Method -> Loops (call to client method)
        Map<IMethod, List<HashCode>> rpcServerMethodtoLoops = new ConcurrentHashMap<>(); // RPC Server Method -> Loops (called from server method)
        AnalysisOptions analysisOptions = new AnalysisOptions(model.getScope(), new AllApplicationEntrypoints(model.getScope(), model.getCha()));
        AnalysisCache analysisCache = new AnalysisCacheImpl();
        Graph<IMethodRawNameNode, DefaultEdge> cgT = getJGraphTCFG(saved_cfg_path);
        Graph<IMethodRawNameNode, DefaultEdge> cgTR = new EdgeReversedGraph<>(cgT);
        ConcurrentConnectivityInspector<IMethodRawNameNode, DefaultEdge> cgTConnectivityInspector = new ConcurrentConnectivityInspector<>(cgT);
        Map<String, List<LoopItem>> fullMethodNameIndexedLoops = new ConcurrentHashMap<>();
        loops.forEach(l -> fullMethodNameIndexedLoops.computeIfAbsent(l.clazz + '.' + l.func, k -> new ArrayList<>()).add(l));
        boolean shouldMapProtobufMethods = system_under_analysis.startsWith("HBase") || system_under_analysis.startsWith("OZone");
        System.out.println("Should map protobuf? " + shouldMapProtobufMethods);
        for (ImmutablePair<IMethod, IMethod> rpcPair : rpcPairs)
        {
            // Find Loops with call to an RPC client call
            IMethod clientMethod = rpcPair.getLeft();
            IMethodRawNameNode clientMethodNode = new IMethodRawNameNode(clientMethod);
            // TODO: radius=4 for OZone, 2 for all other systems
            ShortestPathAlgorithm<IMethodRawNameNode, DefaultEdge> dijkstra = new DijkstraShortestPath<>(cgTR, search_radius); 
            IMethodRawNameNode clientMethodNodeForDynamicCFG = shouldMapProtobufMethods ? RPCInfo.protobufWalaMap(clientMethodNode) : clientMethodNode;
            // System.out.println(clientMethod);
            // System.out.println(clientMethodNodeForDynamicCFG);
            SingleSourcePaths<IMethodRawNameNode, DefaultEdge> pathFinder = cgTR.containsVertex(clientMethodNodeForDynamicCFG) ? dijkstra.getPaths(clientMethodNodeForDynamicCFG) : null;
            Consumer<LoopItem> loopItemProcessor = loopItem ->
            {
                try
                {
                    TypeReference tr = TypeReference.find(ClassLoaderReference.Application, Utils.toWalaFullClassName(loopItem.clazz));
                    if (tr == null)
                    {
                        // System.out.println(Utils.toWalaFullClassName(loopItem.clazz));
                        return;
                    }
                    IClass loopClass = model.getCha().lookupClass(tr);
                    for (IMethod loopMethod : loopClass.getDeclaredMethods())
                    {
                        if (!Utils.getShortMethodName(loopMethod).equals(loopItem.func)) continue;
                        IR methodIR = analysisCache.getSSACache().findOrCreateIR(loopMethod, Everywhere.EVERYWHERE, analysisOptions.getSSAOptions());
                        if (methodIR == null) continue;
                        IntSummaryStatistics methodStat = Utils.getMethodIRLineRange(methodIR);
                        if ((loopItem.startLineNo >= methodStat.getMin()) && (loopItem.endLineNo <= methodStat.getMax()))
                        {
                            List<SSAInstruction> invokeInsts = Arrays.stream(methodIR.getInstructions()).filter(Objects::nonNull)
                                    .filter(inst -> inst instanceof SSAInvokeInstruction)
                                    .filter(inst -> loopItem.instInLoopRange(Utils.getSrcLineNumberBySSAInst(methodIR, inst.iIndex()))).collect(Collectors.toList());
                            List<IMethod> invokeTargets = invokeInsts.stream().map(inst -> model.getCha().resolveMethod(((SSAInvokeInstruction) inst).getDeclaredTarget()))
                                    .filter(Objects::nonNull)
                                    .collect(Collectors.toList());
                            for (IMethod loopInvokeTarget : invokeTargets)
                            {
                                // if (Utils.getFullMethodName(loopInvokeTarget).contains("protobuf") && Utils.getFullMethodName(loopInvokeTarget).contains("Blocking"))
                                // {
                                //     System.out.println(loopInvokeTarget);
                                // }
                                IMethodRawNameNode startNode = new IMethodRawNameNode(loopInvokeTarget);
                                // TODO: We use direct call to the RPC for now, using the full call graph is too noisy
                                // If we need to use the call graph info, uncomment the section below
                                if (startNode.equals(clientMethodNode))
                                {
                                    loopsToRpcClientMethod.computeIfAbsent(clientMethod, k -> Collections.synchronizedList(new ArrayList<>())).add(loopItem.getLoopID());
                                    break;
                                }
                                if (!cgT.vertexSet().contains(startNode)) continue;
                                // if (cgTConnectivityInspector.pathExists(startNode, clientMethodNode))
                                if ((pathFinder != null) && pathFinder.getPath(startNode) != null) // pathFinder is on the edge reversed graph, we are finding path from startNode to clientMethod
                                                                           // Using edge reversed graph let us reuse the pathFinder  
                                {
                                    loopsToRpcClientMethod.computeIfAbsent(clientMethod, k -> Collections.synchronizedList(new ArrayList<>())).add(loopItem.getLoopID());
                                    break;
                                }
                            }
                        }
                    }
                }
                catch (Exception ex)
                {
                    ex.printStackTrace();
                }
            };
            ExecutorService es = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
            for (LoopItem loopItem : loops)
            {
                es.submit(() -> loopItemProcessor.accept(loopItem));
            }
            es.shutdown();
            es.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);

            // Find RPC Server calls to loops
            IMethod serverMethod = rpcPair.getRight();
            // System.out.println(serverMethod);
            IMethodRawNameNode serverMethodV = new IMethodRawNameNode(serverMethod);
            if (!cgT.containsVertex(serverMethodV)) 
            {
                System.out.println("Missing vertex: " + serverMethodV);
                continue;
            }
            List<IMethodRawNameNode> succsToRPCServerCall = Graphs.successorListOf(cgT, new IMethodRawNameNode(serverMethod));
            succsToRPCServerCall.add(new IMethodRawNameNode(serverMethod)); // Add the loops in server method as well
            for (IMethodRawNameNode succ : succsToRPCServerCall)
            {
                IMethod succIMethod;
                try
                {
                    succIMethod = model.getCha().resolveMethod(Utils.walaSignatureStrToMethodReference(ClassLoaderReference.Application, succ.rawName));
                    // System.out.println(succIMethod);
                }
                catch (Exception ex)
                {
                    // System.out.println("Error: " + succ);
                    // ex.printStackTrace();
                    continue;
                }
                if (succIMethod == null) continue;

                // Get RPC Server Method Connected Loops
                ShortestPathAlgorithm<IMethodRawNameNode, DefaultEdge> dijkstra2 = new DijkstraShortestPath<>(cgT, search_radius); 
                SingleSourcePaths<IMethodRawNameNode, DefaultEdge> pathFinder2 = dijkstra2.getPaths(succ);
                Consumer<LoopItem> rpcServerLoopProcessor = loopItem ->
                {
                    try
                    {
                        TypeReference tr = TypeReference.find(ClassLoaderReference.Application, Utils.toWalaFullClassName(loopItem.clazz));
                        if (tr == null) return;
                        IClass loopClass = model.getCha().lookupClass(tr);
                        for (IMethod loopMethod : loopClass.getDeclaredMethods())
                        {
                            if (!Utils.getShortMethodName(loopMethod).equals(loopItem.func)) continue;
                            IR methodIR = analysisCache.getSSACache().findOrCreateIR(loopMethod, Everywhere.EVERYWHERE, analysisOptions.getSSAOptions());
                            if (methodIR == null) continue;
                            IntSummaryStatistics methodStat = Utils.getMethodIRLineRange(methodIR);
                            if ((loopItem.startLineNo >= methodStat.getMin()) && (loopItem.endLineNo <= methodStat.getMax()))
                            {
                                IMethodRawNameNode loopCFGNode = new IMethodRawNameNode(loopMethod);
                                if (pathFinder2.getPath(loopCFGNode) != null)
                                {
                                    if (!fullMethodNameIndexedLoops.containsKey(Utils.getFullMethodName(loopMethod))) continue;
                                    rpcServerMethodtoLoops.computeIfAbsent(serverMethod, k -> Collections.synchronizedList(new ArrayList<>())).add(loopItem.getLoopID());
                                }
                            }
                        }
                    }
                    catch (Exception e)
                    {
                        e.printStackTrace();
                    }
                };
                ExecutorService es2 = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
                for (LoopItem loopItem : loops)
                {
                    es2.submit(() -> rpcServerLoopProcessor.accept(loopItem));
                }
                es2.shutdown();
                es2.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
                
                if (!fullMethodNameIndexedLoops.containsKey(Utils.getFullMethodName(succIMethod))) continue;
                rpcServerMethodtoLoops.computeIfAbsent(serverMethod, k -> Collections.synchronizedList(new ArrayList<>()))
                        .addAll(fullMethodNameIndexedLoops.get(Utils.getFullMethodName(succIMethod)).stream().map(e -> e.getLoopID()).collect(Collectors.toList()));
            }

        }
        Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        Map<String, Set<String>> loopsToRpcClientMethodT = loopsToRpcClientMethod.entrySet().stream()
                .collect(Collectors.toMap(e -> Utils.getFullMethodName(e.getKey()), e -> e.getValue().stream().map(loopID -> loopID.toString()).collect(Collectors.toSet()), (v1, v2) -> Sets.union(v1, v2)));
        Map<String, Set<String>> rpcServerMethodtoLoopsT = rpcServerMethodtoLoops.entrySet().stream()
                .collect(Collectors.toMap(e -> Utils.getFullMethodName(e.getKey()), e -> e.getValue().stream().map(loopID -> loopID.toString()).collect(Collectors.toSet()), (v1, v2) -> Sets.union(v1, v2)));
        Map<String, Map<String, Set<String>>> dump = new HashMap<>();
        dump.put("LoopToRPCClientCalls", loopsToRpcClientMethodT);
        dump.put("RPCServerCallsToLoops", rpcServerMethodtoLoopsT);
        PrintWriter pw = new PrintWriter(output_path);
        gson.toJson(dump, pw);
        pw.close();

        // Map<String, List<IMethod>> nameIndexedMethods = new HashMap<>();
        // for (IClass clazz: model.getCha())
        // {
        // if (Utils.insideJRELibrary(clazz)) continue;
        // for (IMethod method: clazz.getDeclaredMethods())
        // {
        // if (Utils.getShortMethodName(method).contains("access$")) continue;
        // nameIndexedMethods.computeIfAbsent(Utils.getShortMethodName(method), k -> new ArrayList<>()).add(method);
        // }
        // }
        // for (String methodName: nameIndexedMethods.keySet())
        // {
        // if (nameIndexedMethods.get(methodName).size() < 2) continue;
        // Map<Descriptor, List<IMethod>> argSignature = new HashMap<>();
        // for (IMethod method: nameIndexedMethods.get(methodName))
        // {
        // if (Utils.getShortClassName(method.getDeclaringClass()).contains("$Builder")) continue;
        // if (Utils.getShortClassName(method.getDeclaringClass()).contains("OrBuilder")) continue;
        // if (Utils.getShortClassName(method.getDeclaringClass()).contains("MBean")) continue;
        // argSignature.computeIfAbsent(method.getDescriptor(), k -> new ArrayList<>()).add(method);
        // }
        // for (Descriptor desc: argSignature.keySet())
        // {
        // if (argSignature.get(desc).size() != 3) continue;
        // System.out.println(Utils.getFullMethodName(argSignature.get(desc).get(0)));
        // System.out.println(argSignature.get(desc));
        // }
        // }
    }

    public static Graph<IMethodRawNameNode, DefaultEdge> getJGraphTCFG(String savedGraphPath) throws Exception
    {
        Graph<IMethodRawNameNode, DefaultEdge> cgT;
        AnalysisOptions aOptions = new AnalysisOptions(model.getScope(), new AllApplicationEntrypoints(model.getScope(), model.getCha()));
        // aOptions.setReflectionOptions(AnalysisOptions.ReflectionOptions.APPLICATION_GET_METHOD);
        AnalysisCache aCache = new AnalysisCacheImpl();
        File savedGraphFile = new File(savedGraphPath);
        if (!savedGraphFile.exists())
        {
            SSAPropagationCallGraphBuilder builder = com.ibm.wala.ipa.callgraph.impl.Util.makeVanillaZeroOneCFABuilder(Language.JAVA, aOptions, aCache, model.getCha());
            CallGraph cg = builder.makeCallGraph(aOptions, new ProgressMonitor());
            System.out.println("Callgraph Built");
            cgT = new DefaultDirectedGraph<>(DefaultEdge.class);

            for (CGNode cgn : cg)
            {
                IMethodRawNameNode ep1 = new IMethodRawNameNode(cgn.getMethod());
                cgT.addVertex(ep1);
                for (CGNode cgn2 : Utils.toIterable(cg.getSuccNodes(cgn)))
                {
                    IMethodRawNameNode ep2 = new IMethodRawNameNode(cgn2.getMethod());
                    cgT.addVertex(ep2);
                    cgT.addEdge(ep1, ep2);
                }
            }
            try (FileOutputStream fos = new FileOutputStream(savedGraphFile);
                    BufferedOutputStream bos = new BufferedOutputStream(fos);
                    ObjectOutputStream oos = new ObjectOutputStream(bos);)
            {
                oos.writeObject(cgT);
            }
        }
        else
        {
            try (FileInputStream fis = new FileInputStream(savedGraphFile);
                    BufferedInputStream bis = new BufferedInputStream(fis);
                    ObjectInputStream ois = new ObjectInputStream(bis);)
            {
                cgT = (DefaultDirectedGraph<IMethodRawNameNode, DefaultEdge>) ois.readObject();
            }
            System.out.println("Graph Loaded");
        };
        return cgT;
    }
}

class RPCInfo
{
    // RPC Client -> RPC Server
    public static Map<String, List<ImmutablePair<String, String>>> rpcMap = new HashMap<>();
    static
    {
        //#region HDFS292
        List<ImmutablePair<String, String>> hdfs292_rpcs = new ArrayList<>();
        hdfs292_rpcs.add(ImmutablePair.of("org.apache.hadoop.hdfs.protocolPB.DatanodeProtocolClientSideTranslatorPB", "org.apache.hadoop.hdfs.server.namenode.NameNodeRpcServer"));
        hdfs292_rpcs.add(ImmutablePair.of("org.apache.hadoop.hdfs.protocol.datatransfer.Sender", "org.apache.hadoop.hdfs.server.datanode.DataXceiver"));
        hdfs292_rpcs.add(ImmutablePair.of("org.apache.hadoop.hdfs.protocolPB.DatanodeLifelineProtocolClientSideTranslatorPB", "org.apache.hadoop.hdfs.server.namenode.NameNodeRpcServer"));
        hdfs292_rpcs.add(ImmutablePair.of("org.apache.hadoop.hdfs.protocolPB.ClientNamenodeProtocolTranslatorPB", "org.apache.hadoop.hdfs.server.namenode.NameNodeRpcServer"));
        hdfs292_rpcs.add(ImmutablePair.of("org.apache.hadoop.hdfs.protocolPB.NamenodeProtocolTranslatorPB", "org.apache.hadoop.hdfs.server.namenode.NameNodeRpcServer"));
        hdfs292_rpcs.add(ImmutablePair.of("org.apache.hadoop.hdfs.protocolPB.ReconfigurationProtocolTranslatorPB", "org.apache.hadoop.hdfs.server.namenode.NameNodeRpcServer"));

        hdfs292_rpcs.add(ImmutablePair.of("org.apache.hadoop.hdfs.protocolPB.InterDatanodeProtocolTranslatorPB", "org.apache.hadoop.hdfs.server.datanode.DataNode"));
        hdfs292_rpcs.add(ImmutablePair.of("org.apache.hadoop.hdfs.protocolPB.ClientDatanodeProtocolTranslatorPB", "org.apache.hadoop.hdfs.server.datanode.DataNode"));
        hdfs292_rpcs.add(ImmutablePair.of("org.apache.hadoop.hdfs.protocolPB.ReconfigurationProtocolTranslatorPB", "org.apache.hadoop.hdfs.server.datanode.DataNode"));
        rpcMap.put("HDFS292", hdfs292_rpcs);
        //#endregion HDFS292

        //#region HDFS341
        List<ImmutablePair<String, String>> hdfs341_rpcs = new ArrayList<>();
        hdfs341_rpcs.add(ImmutablePair.of("org.apache.hadoop.hdfs.protocolPB.DatanodeProtocolClientSideTranslatorPB", "org.apache.hadoop.hdfs.server.namenode.NameNodeRpcServer"));
        hdfs341_rpcs.add(ImmutablePair.of("org.apache.hadoop.hdfs.protocolPB.DatanodeLifelineProtocolClientSideTranslatorPB", "org.apache.hadoop.hdfs.server.namenode.NameNodeRpcServer"));
        hdfs341_rpcs.add(ImmutablePair.of("org.apache.hadoop.hdfs.protocolPB.ClientNamenodeProtocolTranslatorPB", "org.apache.hadoop.hdfs.server.namenode.NameNodeRpcServer"));
        hdfs341_rpcs.add(ImmutablePair.of("org.apache.hadoop.hdfs.protocolPB.NamenodeProtocolTranslatorPB", "org.apache.hadoop.hdfs.server.namenode.NameNodeRpcServer"));
        hdfs341_rpcs.add(ImmutablePair.of("org.apache.hadoop.hdfs.protocolPB.ReconfigurationProtocolTranslatorPB", "org.apache.hadoop.hdfs.server.namenode.NameNodeRpcServer"));
        hdfs341_rpcs.add(ImmutablePair.of("org.apache.hadoop.ipc.protocolPB.RefreshCallQueueProtocolClientSideTranslatorPB", "org.apache.hadoop.hdfs.server.namenode.NameNodeRpcServer"));
        hdfs341_rpcs.add(ImmutablePair.of("org.apache.hadoop.ipc.protocolPB.GenericRefreshProtocolClientSideTranslatorPB", "org.apache.hadoop.hdfs.server.namenode.NameNodeRpcServer"));
        hdfs341_rpcs.add(ImmutablePair.of("org.apache.hadoop.ha.protocolPB.HAServiceProtocolClientSideTranslatorPB", "org.apache.hadoop.hdfs.server.namenode.NameNodeRpcServer"));

        hdfs341_rpcs.add(ImmutablePair.of("org.apache.hadoop.hdfs.protocolPB.InterDatanodeProtocolTranslatorPB", "org.apache.hadoop.hdfs.server.datanode.DataNode"));
        hdfs341_rpcs.add(ImmutablePair.of("org.apache.hadoop.hdfs.protocolPB.ClientDatanodeProtocolTranslatorPB", "org.apache.hadoop.hdfs.server.datanode.DataNode"));
        hdfs341_rpcs.add(ImmutablePair.of("org.apache.hadoop.hdfs.protocolPB.ReconfigurationProtocolTranslatorPB", "org.apache.hadoop.hdfs.server.datanode.DataNode"));

        hdfs341_rpcs.add(ImmutablePair.of("org.apache.hadoop.hdfs.protocol.datatransfer.Sender", "org.apache.hadoop.hdfs.server.datanode.DataXceiver"));
        rpcMap.put("HDFS341", hdfs341_rpcs);
        //#endregion HDFS341

        //#region HBase260
        List<ImmutablePair<String, String>> hbase260_rpcs = new ArrayList<>();
        hbase260_rpcs.add(ImmutablePair.of("org.apache.hadoop.hbase.shaded.protobuf.generated.AdminProtos.AdminService.BlockingInterface",
                                          "org.apache.hadoop.hbase.regionserver.RSRpcServices"));

        hbase260_rpcs.add(ImmutablePair.of("org.apache.hadoop.hbase.shaded.protobuf.generated.ClientProtos.ClientService.BlockingInterface",
                                          "org.apache.hadoop.hbase.regionserver.RSRpcServices"));

        hbase260_rpcs.add(ImmutablePair.of("org.apache.hadoop.hbase.shaded.protobuf.generated.RegistryProtos.ClientMetaService.BlockingInterface",
                                          "org.apache.hadoop.hbase.regionserver.RSRpcServices"));

        hbase260_rpcs.add(ImmutablePair.of("org.apache.hadoop.hbase.shaded.protobuf.generated.BootstrapNodeProtos.BootstrapNodeService.BlockingInterface",
                                          "org.apache.hadoop.hbase.regionserver.RSRpcServices"));


        hbase260_rpcs.add(ImmutablePair.of("org.apache.hadoop.hbase.shaded.protobuf.generated.AdminProtos.AdminService.BlockingInterface",
                                          "org.apache.hadoop.hbase.master.MasterRpcServices"));

        hbase260_rpcs.add(ImmutablePair.of("org.apache.hadoop.hbase.shaded.protobuf.generated.ClientProtos.ClientService.BlockingInterface",
                                          "org.apache.hadoop.hbase.master.MasterRpcServices"));

        hbase260_rpcs.add(ImmutablePair.of("org.apache.hadoop.hbase.shaded.protobuf.generated.RegistryProtos.ClientMetaService.BlockingInterface",
                                          "org.apache.hadoop.hbase.master.MasterRpcServices"));

        hbase260_rpcs.add(ImmutablePair.of("org.apache.hadoop.hbase.shaded.protobuf.generated.BootstrapNodeProtos.BootstrapNodeService.BlockingInterface",
                                          "org.apache.hadoop.hbase.master.MasterRpcServices"));

        hbase260_rpcs.add(ImmutablePair.of("org.apache.hadoop.hbase.shaded.protobuf.generated.MasterProtos.MasterService.BlockingInterface",
                                          "org.apache.hadoop.hbase.master.MasterRpcServices"));

        hbase260_rpcs.add(ImmutablePair.of("org.apache.hadoop.hbase.shaded.protobuf.generated.RegionServerStatusProtos.RegionServerStatusService.BlockingInterface",
                                          "org.apache.hadoop.hbase.master.MasterRpcServices"));

        hbase260_rpcs.add(ImmutablePair.of("org.apache.hadoop.hbase.shaded.protobuf.generated.LockServiceProtos.LockService.BlockingInterface",
                                          "org.apache.hadoop.hbase.master.MasterRpcServices"));

        hbase260_rpcs.add(ImmutablePair.of("org.apache.hadoop.hbase.shaded.protobuf.generated.MasterProtos.HbckService.BlockingInterface",
                                          "org.apache.hadoop.hbase.master.MasterRpcServices"));
        rpcMap.put("HBase260", hbase260_rpcs);
        //#endregion HBase260

        //#region OZone140
        List<ImmutablePair<String, String>> ozone140_rpcs = new ArrayList<>(); // (Client, Server)
        ozone140_rpcs.add(ImmutablePair.of("org.apache.hadoop.hdds.protocol.datanode.proto.ContainerProtos.XceiverClientProtocolService.BlockingInterface",
                                          "org.apache.hadoop.ozone.container.common.transport.server.GrpcXceiverService"));

        ozone140_rpcs.add(ImmutablePair.of("org.apache.hadoop.hdds.protocol.datanode.proto.ContainerProtos.IntraDatanodeProtocolService.BlockingInterface",
                                          "org.apache.hadoop.ozone.container.replication.GrpcReplicationService"));
        
        ozone140_rpcs.add(ImmutablePair.of("org.apache.hadoop.hdds.scm.protocolPB.StorageContainerLocationProtocolClientSideTranslatorPB",
                                          "org.apache.hadoop.hdds.scm.server.SCMClientProtocolServer"));
        
        ozone140_rpcs.add(ImmutablePair.of("org.apache.hadoop.hdds.protocolPB.ReconfigureProtocolClientSideTranslatorPB",
                                          "org.apache.hadoop.hdds.conf.ReconfigurationHandler"));
        
        ozone140_rpcs.add(ImmutablePair.of("org.apache.hadoop.hdds.protocol.scm.proto.InterSCMProtocolProtos.InterSCMProtocolService.BlockingInterface",
                                          "org.apache.hadoop.hdds.scm.ha.InterSCMGrpcService"));
     
        ozone140_rpcs.add(ImmutablePair.of("org.apache.hadoop.hdds.protocol.scm.proto.InterSCMProtocolProtos.InterSCMProtocolService.BlockingInterface",
                                          "org.apache.hadoop.hdds.scm.ha.InterSCMGrpcService"));

        ozone140_rpcs.add(ImmutablePair.of("org.apache.hadoop.ozone.protocolPB.StorageContainerDatanodeProtocolClientSideTranslatorPB",
                                          "org.apache.hadoop.hdds.scm.server.SCMDatanodeProtocolServer"));

        ozone140_rpcs.add(ImmutablePair.of("org.apache.hadoop.hdds.protocolPB.SCMSecurityProtocolClientSideTranslatorPB",
                                          "org.apache.hadoop.hdds.scm.server.SCMSecurityProtocolServer"));
        
        ozone140_rpcs.add(ImmutablePair.of("org.apache.hadoop.hdds.protocolPB.SecretKeyProtocolClientSideTranslatorPB",
                                          "org.apache.hadoop.hdds.scm.server.SCMSecurityProtocolServer"));

        ozone140_rpcs.add(ImmutablePair.of("org.apache.hadoop.hdds.scm.protocolPB.ScmBlockLocationProtocolClientSideTranslatorPB",
                                          "org.apache.hadoop.hdds.scm.server.SCMBlockProtocolServer"));
                        
        ozone140_rpcs.add(ImmutablePair.of("org.apache.hadoop.hdds.protocol.scm.proto.SCMUpdateServiceProtos.SCMUpdateService.BlockingInterface",
                                          "org.apache.hadoop.hdds.scm.update.server.SCMUpdateServiceImpl"));
        rpcMap.put("OZone140", ozone140_rpcs);
        //#endregion OZone140
    }

    // Map BlockingInterface (in WALA) to BlockingStub (in Dynamic trace)
    public static IMethodRawNameNode protobufWalaMap(IMethodRawNameNode node)
    {
        IMethodRawNameNode r = node.copy();
        r.rawName = r.rawName.replace("BlockingInterface", "BlockingStub");
        return r;
    }
}
