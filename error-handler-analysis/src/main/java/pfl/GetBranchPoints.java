package pfl;

import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.util.AbstractMap.SimpleEntry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IntSummaryStatistics;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;
import org.apache.commons.lang3.function.TriConsumer;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.ImmutableTriple;

import com.github.luben.zstd.ZstdOutputStream;
import com.google.common.collect.ComparisonChain;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.gson.GsonBuilder;
import com.ibm.wala.cfg.IBasicBlock;
import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.AnalysisCache;
import com.ibm.wala.ipa.callgraph.AnalysisCacheImpl;
import com.ibm.wala.ipa.callgraph.AnalysisOptions;
import com.ibm.wala.ipa.callgraph.impl.Everywhere;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.ISSABasicBlock;
import com.ibm.wala.ssa.SSACFG;
import com.ibm.wala.ssa.SSAConditionalBranchInstruction;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAInvokeInstruction;

import edu.colorado.walautil.LoopUtil;
import pfl.analysis_util.LoopItem;
import pfl.analysis_util.exec_points.BranchPoint;
import pfl.graph.InducedNoExceptionCFG;
import pfl.util.LoopUtilSynchronizedWrapper;
import pfl.util.Utils;
import scala.collection.JavaConversions;

// Get all the branch points inside a loop & Runnable.run()

public class GetBranchPoints
{
    static AnalysisOptions analysisOptions = new AnalysisOptions();
    static AnalysisCache analysisCache = new AnalysisCacheImpl();
    static WalaModel model;

    public static void main(String[] args) throws Exception
    {
        Options options = new Options();
        options.addOption("cp", "classpath", true, "Classpath");
        options.addOption("o", "output", true, "Loops Output JSON Path");
        options.addOption("d1", "depth1", true, "Number of invocation levels that the analyzer should follow (loops)");
        options.addOption("d2", "depth2", true, "Number of invocation levels that the analyzer should follow (runnable)");
        options.addOption("cp_prefix_excl", true, "Excluded classpath prefix for analysis scope, \"|\" splitted");
        options.addOption("cp_prefix", true, "Included classpath prefix");
        CommandLineParser parser = new DefaultParser();
        CommandLine cmd = parser.parse(options, args);
        String classpath = cmd.getOptionValue("classpath");
        String outputPath = cmd.getOptionValue("output");
        int invokeDepthLimit1 = Integer.parseInt(cmd.getOptionValue("depth1", "10"));
        int invokeDepthLimit2 = Integer.parseInt(cmd.getOptionValue("depth2", "5"));
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

        // Branch insts starting from loops
        model = new WalaModel(classpath);

        // Collect all the loop heads, these are not branches
        Map<IMethod, Set<Integer>> loopHeadBranches = new ConcurrentHashMap<>();
        for (IClass clazz: model.getCha())
        {
            if (Utils.insideJRELibrary(clazz)) continue;
            for (IMethod method: clazz.getDeclaredMethods())
            {
                IR methodIR = analysisCache.getSSACache().findOrCreateIR(method, Everywhere.EVERYWHERE, analysisOptions.getSSAOptions());
                if (methodIR == null) continue;
                Set<Integer> loopHeaders = Utils.scalaToSet(JavaConversions.setAsJavaSet(LoopUtil.getLoopHeaders(methodIR)));
                SSACFG cfg = methodIR.getControlFlowGraph();
                for (int header : loopHeaders)
                {
                    Set<ISSABasicBlock> loopBody = new HashSet<>(JavaConversions.setAsJavaSet(LoopUtil.getLoopBody(cfg.getBasicBlock(header), methodIR)));
                    Set<ISSABasicBlock> loopBodyAndHead = new HashSet<>(loopBody);
                    loopBodyAndHead.add(cfg.getBasicBlock(header));
                    IntSummaryStatistics wholeLoopRange = Utils.getCodeBlockRange(methodIR, loopBodyAndHead);
                    loopHeadBranches.computeIfAbsent(method, k -> ConcurrentHashMap.newKeySet()).add(wholeLoopRange.getMin());
                    loopHeadBranches.computeIfAbsent(method, k -> ConcurrentHashMap.newKeySet()).add(wholeLoopRange.getMax());
                }
            }
        }

        Queue<BranchPoint> branches = new ConcurrentLinkedQueue<>();
        // Simply get all of the branch instructions in the code
        for (IClass clazz: model.getCha())
        {
            if (exclPrefixes.stream().anyMatch(p -> Utils.getFullClassName(clazz).startsWith(p))) continue;
            if (checkInclPrefix && !inclPrefixes.stream().anyMatch(p -> Utils.getFullClassName(clazz).startsWith(p))) continue;
            if (Utils.insideJRELibrary(clazz)) continue;
            for (IMethod method: clazz.getDeclaredMethods())
            {
                // if (!Utils.getFullMethodName(method).contains("heartbeatCheck")) continue;

                IR ir = analysisCache.getSSACache().findOrCreateIR(method, Everywhere.EVERYWHERE, analysisOptions.getSSAOptions());
                if (ir == null) continue;
                // SSACFG ssacfg = ir.getControlFlowGraph();
                // for (ISSABasicBlock bb: ssacfg)
                // {
                //     System.out.println(bb);
                //     for (SSAInstruction inst: bb)
                //     {
                //         System.out.println(inst);
                //     }
                // }
                // StringBuilder s = new StringBuilder();
                // for (ISSABasicBlock bb: ssacfg)
                // {
                //     s.append("BB").append(ssacfg.getNumber(bb)).append('\n');
                //     for (int j = bb.getFirstInstructionIndex(); j <= bb.getLastInstructionIndex(); j++)
                //     {
                //         s.append("  ").append(j).append("  ").append(ssacfg.getInstructions()[j]).append('\n');
                //     }

                //     Iterator<ISSABasicBlock> succNodes = ssacfg.getSuccNodes(bb);
                //     while (succNodes.hasNext())
                //     {
                //         s.append("    -> BB").append(ssacfg.getNumber(succNodes.next())).append('\n');
                //     }
                // }
                // System.out.println(s.toString());
                // System.out.println("----------------------");
                InducedNoExceptionCFG cfg = getInducedNoExceptionCFG(method);
                // System.out.println(cfg);
                for (InducedNoExceptionCFG.BasicBlock bb : cfg)
                {
                    for (SSAInstruction inst : bb)
                    {
                        if (!(inst instanceof SSAConditionalBranchInstruction)) continue;
                        int branchInstLineNo = Utils.getSrcLineNumberBySSAInst(ir, inst.iIndex());
                        if (loopHeadBranches.containsKey(method) && loopHeadBranches.get(method).contains(branchInstLineNo)) continue;
                        int branchID = -1;
                        for (InducedNoExceptionCFG.BasicBlock succBB : Utils.toIterable(cfg.getSuccNodes(bb)))
                        {
                            SSAInstruction succBBFirstInst = getFirstNonNullInst(succBB);
                            if (succBBFirstInst == null) continue;
                            branchID++;
                            BranchPoint bp = new BranchPoint(clazz, method, Utils.getSrcLineNumberBySSAInst(ir, succBBFirstInst.iIndex()),
                                    clazz, method, branchInstLineNo, branchID);
                            branches.add(bp);
                        }
                    }
                }
            }
        }

        // ExecutorService es = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
        // AtomicInteger progressIndicator = new AtomicInteger();
        // TriConsumer<AtomicInteger, IClass, IMethod> printProgress = (indicator, clazz, method) ->
        // {
        //     int currentProgress = indicator.getAndIncrement();
        //     if ((currentProgress < 5) || (currentProgress % 1000 == 0))
        //         System.out.println("At: " + currentProgress + '\t' + Utils.getFullMethodName(method));
        // };
        // BiConsumer<IClass, IMethod> loopAnalyzer = (clazz, method) ->
        // {
        //     printProgress.accept(progressIndicator, clazz, method);

        //     IR methodIR = analysisCache.getSSACache().findOrCreateIR(method, Everywhere.EVERYWHERE, analysisOptions.getSSAOptions());
        //     if (methodIR == null)
        //         return;
        //     Set<Integer> loopHeaders = Utils.scalaToSet(JavaConversions.setAsJavaSet(LoopUtilSynchronizedWrapper.getLoopHeaders(methodIR)));
        //     SSACFG cfg = methodIR.getControlFlowGraph();
        //     for (int header : loopHeaders)
        //     {
        //         Set<ISSABasicBlock> loopBody = new HashSet<>(JavaConversions.setAsJavaSet(LoopUtilSynchronizedWrapper.getLoopBody(cfg.getBasicBlock(header), methodIR)));
        //         // Set<ISSABasicBlock> loopBodyAndHead = new HashSet<>(loopBody);
        //         // loopBodyAndHead.add(cfg.getBasicBlock(header));
        //         List<ISSABasicBlock> loopBBList = new ArrayList<>(loopBody);
        //         Collections.sort(loopBBList, (bb1, bb2) -> bb1.getNumber() - bb2.getNumber());
        //         Set<SSAInstruction> loopInsts = loopBBList.stream().map(bb -> StreamSupport.stream(bb.spliterator(), false)).flatMap(Function.identity())
        //                 .collect(Collectors.toSet());
        //         branches.addAll(getBranchPointsFromInsts(method, Lists.newArrayList(methodIR.getInstructions()), invokeDepthLimit1, loopInsts, loopHeadBranches));
        //     }
        // };
        // for (IClass clazz : model.getCha())
        // {
        //     if (exclPrefixes.stream().anyMatch(p -> Utils.getFullClassName(clazz).startsWith(p))) continue;
        //     if (Utils.insideJRELibrary(clazz))
        //         continue;
        //     for (IMethod method : clazz.getDeclaredMethods())
        //     {
        //         es.submit(() -> loopAnalyzer.accept(clazz, method));
        //     }
        // }
        // es.shutdown();
        // es.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
        // // progressReporterTimer.cancel();

        // // Get all branch insts in Runnable objects
        // es = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
        // progressIndicator.set(0);
        // // progressReporterTimer = new Timer();
        // // progressReporterTimer.schedule(timedProgressReporter, 0, 60 * 1000);
        // BiConsumer<IClass, IMethod> runnableAnalyzer = (clazz, method) ->
        // {
        //     printProgress.accept(progressIndicator, clazz, method);

        //     IR methodIR = analysisCache.getSSACache().findOrCreateIR(method, Everywhere.EVERYWHERE, analysisOptions.getSSAOptions());
        //     if (methodIR == null)
        //         return;
        //     branches.addAll(getBranchPointsFromInsts(method, Lists.newArrayList(methodIR.getInstructions()), invokeDepthLimit2, loopHeadBranches));
        // };
        // for (IClass clazz : model.getCha())
        // {
        //     if (exclPrefixes.stream().anyMatch(p -> Utils.getFullClassName(clazz).startsWith(p))) continue;
        //     if (Utils.insideJRELibrary(clazz))
        //         continue;
        //     if (!clazz.getAllImplementedInterfaces().stream().anyMatch(c -> Utils.getFullClassName(c).equals("java.lang.Runnable")))
        //         continue;
        //     for (IMethod method : clazz.getAllMethods())
        //     {
        //         if (!Utils.getShortMethodName(method).equals("run"))
        //             continue;
        //         es.submit(() -> runnableAnalyzer.accept(clazz, method));
        //     }
        // }
        // es.shutdown();
        // es.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
        System.out.println("Analysis Finished, branch count: " + branches.size());
        // progressReporterTimer.cancel();

        List<BranchPoint> branchResult = new ArrayList<>(branches);
        branchResult.removeIf(e -> Utils.getShortMethodName(e.method).contains("clinit"));
        branchResult.removeIf(e -> Utils.getShortMethodName(e.method).contains("__jamon"));
        Collections.sort(branchResult);
        PrintWriter pw;
        if (outputPath.endsWith("json.zst"))
        {
            FileOutputStream fos = new FileOutputStream(outputPath);
            ZstdOutputStream zos = new ZstdOutputStream(fos, 15);
            pw = new PrintWriter(zos);
        }
        else 
        {
            pw = new PrintWriter(outputPath);
        }
        new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(
                branchResult.stream().map(e -> new SimpleEntry<>(e.getID().toString(), e.toMap()))
                        .collect(Collectors.toMap(SimpleEntry::getKey, SimpleEntry::getValue, (branchKey1, branchKey2) -> branchKey1, LinkedHashMap::new)),
                LinkedHashMap.class, pw);
        pw.close();
    }

    // Adapter class for Runnable
    private static List<BranchPoint> getBranchPointsFromInsts(IMethod parentMethod, List<SSAInstruction> insts, int invokeDepthLimit, Map<IMethod, Set<Integer>> instMask)
    {
        return getBranchPointsFromInsts(parentMethod, insts, invokeDepthLimit, new HashSet<>(insts), instMask);
    }

    private static List<BranchPoint> getBranchPointsFromInsts(IMethod parentMethod, List<SSAInstruction> insts, int invokeDepthLimit, Set<SSAInstruction> level0InclInst, Map<IMethod, Set<Integer>> instMask)
    {
        SSAInstruction[] instArr = Iterables.toArray(insts, SSAInstruction.class);
        InducedNoExceptionCFG inducedCFG = new InducedNoExceptionCFG(instArr, parentMethod, Everywhere.EVERYWHERE);
        Queue<ImmutableTriple<IMethod, InducedNoExceptionCFG, Integer>> bfsQueue = new LinkedList<>();
        Set<IMethod> visited = new HashSet<>();
        List<BranchPoint> r = new ArrayList<>();
        bfsQueue.add(ImmutableTriple.of(parentMethod, inducedCFG, 0));
        while (!bfsQueue.isEmpty())
        {
            ImmutableTriple<IMethod, InducedNoExceptionCFG, Integer> c = bfsQueue.poll();
            IMethod currentMethod = c.getLeft();
            InducedNoExceptionCFG currentCFG = c.getMiddle();
            int currentDepth = c.getRight();
            Set<Integer> currentLoopHeadBranchLineNos = instMask.getOrDefault(currentMethod, new HashSet<>());
            visited.add(currentMethod);
            IR currentIR = analysisCache.getSSACache().findOrCreateIR(currentMethod, Everywhere.EVERYWHERE, analysisOptions.getSSAOptions());
            if (currentIR == null)
                continue;
            for (InducedNoExceptionCFG.BasicBlock bb : currentCFG)
            {
                for (SSAInstruction inst : bb)
                {
                    // Only level 0 needs the instruction mask (only loop insts contained)
                    if ((currentDepth == 0) && !level0InclInst.contains(inst)) 
                        continue;
                    if (inst instanceof SSAInvokeInstruction)
                    {
                        if (currentDepth >= (invokeDepthLimit - 1))
                            continue;
                        SSAInvokeInstruction invokeInst = (SSAInvokeInstruction) inst;
                        IMethod declaredTarget = model.getCha().resolveMethod(invokeInst.getDeclaredTarget());
                        if (declaredTarget == null)
                            continue;
                        if (visited.contains(declaredTarget))
                            continue;
                        if (Utils.insideJRELibrary(declaredTarget.getDeclaringClass()))
                            continue;
                        InducedNoExceptionCFG targetCFG = getInducedNoExceptionCFG(declaredTarget);
                        if (targetCFG == null)
                            continue;
                        bfsQueue.add(ImmutableTriple.of(declaredTarget, targetCFG, currentDepth + 1));
                    }
                    else if (inst instanceof SSAConditionalBranchInstruction)
                    {
                        IClass branchInstClass = currentMethod.getDeclaringClass();
                        IMethod branchInstMethod = currentMethod;
                        int branchInstLineNo = Utils.getSrcLineNumberBySSAInst(currentIR, inst.iIndex());
                        if (currentLoopHeadBranchLineNos.contains(branchInstLineNo)) continue; // Ignore all the branches from loop head
                        int branchID = -1;
                        for (InducedNoExceptionCFG.BasicBlock succBB : Utils.toIterable(currentCFG.getSuccNodes(bb)))
                        {
                            SSAInstruction succBBFirstInst = getFirstNonNullInst(succBB);
                            if (succBBFirstInst == null)
                                continue;
                            branchID++;
                            BranchPoint bp = new BranchPoint(branchInstClass, branchInstMethod, Utils.getSrcLineNumberBySSAInst(currentIR, succBBFirstInst.iIndex()),
                                    branchInstClass, branchInstMethod, branchInstLineNo, branchID);
                            r.add(bp);
                        }
                    }
                }
            }
        }
        return r;
    }

    private static SSAInstruction getFirstNonNullInst(InducedNoExceptionCFG.BasicBlock iCfgBB)
    {
        for (SSAInstruction inst : iCfgBB)
        {
            if (inst != null)
                return inst;
        }
        return null;
    }

    private static InducedNoExceptionCFG getInducedNoExceptionCFG(IMethod method)
    {
        IR methodIR = analysisCache.getSSACache().findOrCreateIR(method, Everywhere.EVERYWHERE, analysisOptions.getSSAOptions());
        if (methodIR == null) return null;
        SSACFG methodCFG = methodIR.getControlFlowGraph();
        return new InducedNoExceptionCFG(methodCFG.getInstructions(), methodCFG.getMethod(), Everywhere.EVERYWHERE);
    }
}
