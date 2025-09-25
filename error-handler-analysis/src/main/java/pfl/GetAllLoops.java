package pfl;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.ObjectInputStream;
import java.io.PrintWriter;
import java.util.AbstractMap.SimpleEntry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IntSummaryStatistics;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;
import org.jgrapht.Graph;
import org.jgrapht.Graphs;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.jgrapht.graph.DefaultEdge;

import com.github.luben.zstd.ZstdInputStream;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.collect.Streams;
import com.google.gson.GsonBuilder;
import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.AnalysisCache;
import com.ibm.wala.ipa.callgraph.AnalysisCacheImpl;
import com.ibm.wala.ipa.callgraph.AnalysisOptions;
import com.ibm.wala.ipa.callgraph.impl.Everywhere;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.ISSABasicBlock;
import com.ibm.wala.ssa.SSACFG;
import com.ibm.wala.ssa.SSAGotoInstruction;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAInvokeInstruction;
import com.ibm.wala.ssa.SSANewInstruction;
import com.ibm.wala.types.ClassLoaderReference;
import com.ibm.wala.types.MethodReference;
import com.ibm.wala.types.TypeReference;

import edu.colorado.walautil.LoopUtil;
import pfl.analysis_util.LoopItem;
import pfl.graph.IMethodRawNameNode;
import pfl.util.LoopUtilSynchronizedWrapper;
import pfl.util.RemoveReason;
import pfl.util.Utils;
import scala.collection.JavaConversions;

public class GetAllLoops
{
    static AnalysisOptions analysisOptions = new AnalysisOptions();
    static AnalysisCache analysisCache = new AnalysisCacheImpl();
    static Map<IMethod, IR> irCache = new ConcurrentHashMap<>();
    static Set<IMethod> nullIRMethod = ConcurrentHashMap.newKeySet();

    public static void main(String[] args) throws Exception
    {
        Options options = new Options();
        options.addOption("cp", "classpath", true, "Classpath");
        options.addOption("o", "output", true, "Loops Output JSON Path");
        options.addOption("cp_prefix", true, "Included classpath prefix");
        options.addOption("cp_prefix_excl", true, "Excluded classpath prefix for analysis scope, \"|\" splitted");
        options.addOption("cfg", true, "Dynamic CFG");
        CommandLineParser parser = new DefaultParser();
        CommandLine cmd = parser.parse(options, args);
        String classpath = cmd.getOptionValue("classpath");
        String outputPath = cmd.getOptionValue("output");
        List<String> exclPrefixes = new ArrayList<>();
        if (cmd.hasOption("cp_prefix_excl"))
        {
            String cpExclPrefix = cmd.getOptionValue("cp_prefix_excl");
            exclPrefixes.addAll(cpExclPrefix.contains("|") ? Lists.newArrayList(cpExclPrefix.split("\\|")) : Lists.newArrayList(cpExclPrefix));
        }
        boolean checkInclPrefix = cmd.hasOption("cp_prefix");
        List<String> inclPrefixes = new ArrayList<>();
        if (checkInclPrefix)
        {
            String cpInclPrefix = cmd.getOptionValue("cp_prefix");
            inclPrefixes.addAll(cpInclPrefix.contains("|") ? Lists.newArrayList(cpInclPrefix.split("\\|")) : Lists.newArrayList(cpInclPrefix));
        }
        Graph<IMethodRawNameNode, DefaultEdge> callGraph;
        try (FileInputStream fis = new FileInputStream(cmd.getOptionValue("cfg")); BufferedInputStream bis = new BufferedInputStream(fis);
             ZstdInputStream zis = new ZstdInputStream(bis); ObjectInputStream ois = new ObjectInputStream(zis);)
        {
            callGraph = (DefaultDirectedGraph<IMethodRawNameNode, DefaultEdge>) ois.readObject();
        }

        WalaModel model = new WalaModel(classpath);
        // Prefill IR Cache
        for (IClass clazz: model.getCha())
        {
            for (IMethod method: clazz.getDeclaredMethods())
            {
                IR methodIR = analysisCache.getSSACache().findOrCreateIR(method, Everywhere.EVERYWHERE, analysisOptions.getSSAOptions());
                if (methodIR != null) 
                    irCache.put(method, methodIR);
                else 
                    nullIRMethod.add(method);
            }
        }
        System.out.println("Prefill IR Cache Done");

        Collection<LoopItem> loops = new ConcurrentLinkedQueue<>();
        String DEBUG_TARGET = "e2fadb71c7c0a4da3844613474a6b037";
        Map<LoopItem, List<Integer>> timerLoopCandidates = Collections.synchronizedMap(new LinkedHashMap<>());
        Map<LoopItem, List<LoopItem>> timerLoopCandidateNestedCheck = Collections.synchronizedMap(new LinkedHashMap<>());
        Map<IMethodRawNameNode, IMethod> calleeMethodCache = new ConcurrentHashMap<>();
        Set<IMethodRawNameNode> calleeMethodNulls = ConcurrentHashMap.newKeySet(); // Also a cache working with calleeMethodCache, ConcurrentHashMap does not support null value
        // for (IClass clazz : model.getCha())
        List<IClass> clazzes = Lists.newArrayList(model.getCha());
        clazzes.parallelStream().forEach(clazz -> 
        {
            try 
            {
                if (exclPrefixes.stream().anyMatch(p -> Utils.getFullClassName(clazz).startsWith(p))) return;
                if (checkInclPrefix && !inclPrefixes.stream().anyMatch(p -> Utils.getFullClassName(clazz).startsWith(p))) return;
                if ((Utils.insideJRELibrary(clazz))) return;
                
                for (IMethod method : clazz.getDeclaredMethods())
                {
                    IR methodIR = getCachedIR(method);
                    if (methodIR == null) continue;
                    // if (Utils.getShortMethodName(method).equals("retrieveNamespaceInfo"))
                    // {
                    //     System.out.println(methodIR);
                    //     System.exit(0);
                    // }
                    Set<Integer> loopHeaders = Utils.scalaToSet(JavaConversions.setAsJavaSet(LoopUtilSynchronizedWrapper.getLoopHeaders(methodIR)));
                    SSACFG cfg = methodIR.getControlFlowGraph();
                    List<LoopItem> loopsInThisMethod = new ArrayList<>();
                    boolean hasJavaIOorJavaNet = false;
                    for (int header : loopHeaders)
                    {
                        Set<ISSABasicBlock> loopBody = new HashSet<>(JavaConversions.setAsJavaSet(LoopUtilSynchronizedWrapper.getLoopBody(cfg.getBasicBlock(header), methodIR)));
                        Set<ISSABasicBlock> loopBodyAndHead = new HashSet<>(loopBody);
                        loopBodyAndHead.add(cfg.getBasicBlock(header));
                        IntSummaryStatistics loopRange = Utils.getCodeBlockRange(methodIR, loopBody);
                        IntSummaryStatistics wholeLoopRange = Utils.getCodeBlockRange(methodIR, loopBodyAndHead);
                        LoopItem loopItem = new LoopItem(Utils.getFullClassName(clazz), Utils.getShortMethodName(method), wholeLoopRange.getMin(), wholeLoopRange.getMax(),
                                loopRange.getMin(), loopRange.getMax());
                        // if (!loopItem.getLoopID().toString().equals(DEBUG_TARGET)) continue;
    
                        // Get loop operation size
                        Set<IMethod> invokedMethods = new HashSet<>();
                        Queue<IMethod> bfsQueue = new LinkedList<>();
                        for (ISSABasicBlock bb: loopBody)
                        {
                            for (SSAInstruction inst: bb)
                            {
                                if (inst instanceof SSAInvokeInstruction)
                                {
                                    // System.out.println(inst);
                                    MethodReference targetRef = ((SSAInvokeInstruction)inst).getDeclaredTarget();
                                    IMethod target = Utils.lookupMethod(model.getCha(), targetRef);
                                    if (target == null) continue;
                                    if (Utils.getFullMethodName(target).startsWith("java.io") || Utils.getFullMethodName(target).startsWith("java.net")) hasJavaIOorJavaNet = true;
                                    if (target.getDeclaringClass().getClassLoader().getReference().equals(ClassLoaderReference.Primordial)) continue;
                                    bfsQueue.add(target);
                                }
                            }
                        }
                        while (!bfsQueue.isEmpty())
                        {
                            IMethod curMethod = bfsQueue.poll();
                            if (invokedMethods.contains(curMethod)) continue;
                            invokedMethods.add(curMethod);
                            // System.out.println(curMethod);
                            IMethodRawNameNode curMethodNode = new IMethodRawNameNode(curMethod);
                            if (callGraph.containsVertex(curMethodNode))
                            {
                                for (IMethodRawNameNode callee: Graphs.successorListOf(callGraph, curMethodNode))
                                {
                                    // Fill Cache
                                    if (calleeMethodNulls.contains(callee)) continue;
                                    IMethod calleeMethod = calleeMethodCache.get(callee);
                                    if (calleeMethod == null)
                                    {
                                        MethodReference calleeRef = Utils.walaSignatureStrToMethodReference(ClassLoaderReference.Application, callee.rawName);
                                        calleeMethod = Utils.lookupMethod(model.getCha(), calleeRef);
                                        if (calleeMethod != null)
                                            calleeMethodCache.put(callee, calleeMethod);
                                        else 
                                        {
                                            calleeMethodNulls.add(callee);
                                            continue;
                                        }
                                    }
                                    
                                    if (Utils.getFullMethodName(calleeMethod).startsWith("java.io") || Utils.getFullMethodName(calleeMethod).startsWith("java.net")) hasJavaIOorJavaNet = true;
                                    if (calleeMethod.getDeclaringClass().getClassLoader().getReference().equals(ClassLoaderReference.Primordial)) continue;
                                    // System.out.println("Callee: " + calleeMethod);
                                    if (!invokedMethods.contains(calleeMethod)) bfsQueue.add(calleeMethod);
                                }
                            }
                        }
                        List<SSAInstruction> loopBodyInsts = loopBodyAndHead.stream().flatMap(bb -> Streams.stream(bb)).filter(Objects::nonNull).collect(Collectors.toList());
                        List<SSAInstruction> invokedMethodInsts = invokedMethods.stream().map(m -> 
                        {
                            IR ir = getCachedIR(m);
                            if (ir == null) return new ArrayList<SSAInstruction>();
                            return Arrays.stream(ir.getInstructions()).filter(Objects::nonNull).collect(Collectors.toList());
                        }).flatMap(e -> e.stream()).collect(Collectors.toList());
                        long loopInstSize = loopBodyInsts.size() + invokedMethodInsts.size();
                        // if (loopItem.getLoopID().toString().equals(DEBUG_TARGET)) 
                        // {
                        //     System.out.println(loopInstSize);
                        // }
                        loopItem.setAllInvokedApplicationMethodsSSAInstSize((int) loopInstSize);
                        loopItem.setHasJavaIOorJavaNet(hasJavaIOorJavaNet);
                        loops.add(loopItem); 
                        loopsInThisMethod.add(loopItem);
    
                        // Check If Timer triggered loops
                        Set<String> timerSignatures = Sets.newHashSet("java.lang.Thread.sleep", "java.lang.Object.wait");
                        List<SSAInvokeInstruction> loopBodyInvokes = new ArrayList<>(); // Only consider invoke inside loop body, excluding any catch block
                        List<ISSABasicBlock> loopBodyAndHeadList = new ArrayList<>(loopBodyAndHead);
                        loopBodyAndHeadList.sort(Comparator.comparing(ISSABasicBlock::getNumber));
                        int catchBBEnds = -1;
                        for (ISSABasicBlock bb: loopBodyAndHeadList) // Get all Invocations that are NOT in catch block
                        {
                            if (bb.isCatchBlock())
                            {
                                try 
                                {
                                    ISSABasicBlock immPredBB = cfg.getBasicBlock(bb.getNumber() - 1); // WALA puts a Goto block right before the catch block to jump over to the next normal block
                                    int predMinNormalSuccBBNum = Integer.MAX_VALUE; // Closest normal successor of the BBs in the try block
                                    for (ISSABasicBlock succ: cfg.getNormalSuccessors(immPredBB))
                                    {
                                        if ((succ.getNumber() < predMinNormalSuccBBNum) && (succ.getNumber() > bb.getNumber())) predMinNormalSuccBBNum = succ.getNumber();
                                    }
                                    catchBBEnds = predMinNormalSuccBBNum - 1;
                                    // if (loopItem.clazz.contains("HeartbeatManager$Monitor"))
                                    // {
                                    //     System.out.println("Catch BB: " + bb);
                                    //     // System.out.println("ExPreds: " + exPreds);
                                    //     System.out.println("Catch BB Ends: " + catchBBEnds);
                                    // }
                                }
                                catch (ArrayIndexOutOfBoundsException e)
                                {
                                    catchBBEnds = bb.getNumber();
                                }
                            }
                            if (bb.getNumber() > catchBBEnds)
                            {
                                loopBodyInvokes.addAll(Streams.stream(bb).filter(Objects::nonNull)
                                    .filter(i -> i instanceof SSAInvokeInstruction).map(i -> (SSAInvokeInstruction) i).collect(Collectors.toList()));
                            }
                        }
                        List<Integer> loopBodyTimerInvokes = loopBodyInvokes.stream()
                            .filter(i -> timerSignatures.contains(Utils.getFullMethodName(i.getDeclaredTarget())))
                            .map(i -> Utils.getSrcLineNumberBySSAInst(methodIR, i.iIndex())).collect(Collectors.toList());
                        if (loopBodyTimerInvokes.size() > 0)
                        {
                            timerLoopCandidates.put(loopItem, loopBodyTimerInvokes);
                            timerLoopCandidateNestedCheck.put(loopItem, loopsInThisMethod);
                        }
                    }
                }
            }
            catch (Exception e)
            {
                System.err.println("Exception at: " + Utils.getFullClassName(clazz));
                e.printStackTrace();
            }
        });

        // Make sure the timer loops are not nested (Loop A contains Loop B, loop B contains Thread.sleep)
        Set<LoopItem> timerLoops = new HashSet<>();
        for (LoopItem timerCandidate: timerLoopCandidates.keySet())
        {
            // System.out.println("Candidate: " + timerCandidate);
            int enclosedByOtherLoopsCt = 0;
            for (int timerLineNo: timerLoopCandidates.get(timerCandidate))
            {
                for (LoopItem loopFromSameMethod: timerLoopCandidateNestedCheck.get(timerCandidate))
                {
                    // Skip when `timerCandidate` is or is a subloop of `loopFromSameMethod`
                    if ((timerCandidate.loopbodyStartLineNo >= loopFromSameMethod.loopbodyStartLineNo) || (timerCandidate.loopbodyEndLineNo <= loopFromSameMethod.loopbodyEndLineNo)) continue;
                    if ((timerLineNo >= loopFromSameMethod.loopbodyStartLineNo) && (timerLineNo <= loopFromSameMethod.loopbodyEndLineNo)) enclosedByOtherLoopsCt++;
                }
            }
            if (enclosedByOtherLoopsCt != timerLoopCandidates.get(timerCandidate).size())
            {
                timerLoops.add(timerCandidate);
                timerCandidate.shouldRemove = new RemoveReason(true, "Timer inside");
                // System.out.println(timerCandidate);
            }
        }
        // loops.removeIf(loop -> timerLoops.contains(loop));
        loops.removeIf(loop -> loop.func.contains("clinit"));
        loops.removeIf(loop -> loop.func.contains("__jamon"));
        List<LoopItem> loopsList = new ArrayList<>(loops);
        Collections.sort(loopsList);
        PrintWriter pw = new PrintWriter(outputPath);
        new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(
            loopsList.stream().map(e -> new SimpleEntry<>(e.getLoopID().toString(), e.toMap()))
                        .collect(Collectors.toMap(SimpleEntry::getKey, SimpleEntry::getValue, (loopKey1, loopKey2) -> loopKey1, LinkedHashMap::new)),
                LinkedHashMap.class, pw);
        pw.close();
    }

    public static IR getCachedIR(IMethod method)
    {
        IR r = irCache.get(method);
        if (r != null) return r;
        if (nullIRMethod.contains(method)) return null;

        return analysisCache.getSSACache().findOrCreateIR(method, Everywhere.EVERYWHERE, analysisOptions.getSSAOptions());
    }
}
