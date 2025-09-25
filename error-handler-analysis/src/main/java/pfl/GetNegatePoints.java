package pfl;

import static org.junit.Assert.fail;

import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IntSummaryStatistics;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;
import org.apache.commons.lang3.tuple.ImmutableTriple;

import com.github.luben.zstd.ZstdOutputStream;
import com.google.common.base.Joiner;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.hash.HashCode;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.ibm.wala.cfg.IBasicBlock;
import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IField;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.classLoader.Language;
import com.ibm.wala.dataflow.graph.BitVectorSolver;
import com.ibm.wala.examples.analysis.dataflow.ContextInsensitiveReachingDefs;
import com.ibm.wala.examples.analysis.dataflow.IntraprocReachingDefs;
import com.ibm.wala.fixpoint.BitVectorVariable;
import com.ibm.wala.ipa.callgraph.AnalysisCache;
import com.ibm.wala.ipa.callgraph.AnalysisCacheImpl;
import com.ibm.wala.ipa.callgraph.AnalysisOptions;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.CallGraph;
import com.ibm.wala.ipa.callgraph.Entrypoint;
import com.ibm.wala.ipa.callgraph.impl.AllApplicationEntrypoints;
import com.ibm.wala.ipa.callgraph.impl.DefaultEntrypoint;
import com.ibm.wala.ipa.callgraph.impl.Everywhere;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ipa.callgraph.propagation.PointerAnalysis;
import com.ibm.wala.ipa.callgraph.propagation.SSAPropagationCallGraphBuilder;
import com.ibm.wala.ipa.cfg.BasicBlockInContext;
import com.ibm.wala.ipa.cfg.ExplodedInterproceduralCFG;
import com.ibm.wala.ipa.slicer.NormalStatement;
import com.ibm.wala.ipa.slicer.Slicer;
import com.ibm.wala.ipa.slicer.Slicer.ControlDependenceOptions;
import com.ibm.wala.ipa.slicer.Slicer.DataDependenceOptions;
import com.ibm.wala.ipa.slicer.thin.CISlicer;
import com.ibm.wala.ipa.slicer.Statement;
import com.ibm.wala.ipa.slicer.StatementWithInstructionIndex;
import com.ibm.wala.ssa.AllIntegerDueToBranchePiPolicy;
import com.ibm.wala.ssa.ConstantValue;
import com.ibm.wala.ssa.DefUse;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.ISSABasicBlock;
import com.ibm.wala.ssa.SSACFG;
import com.ibm.wala.ssa.SSAConditionalBranchInstruction;
import com.ibm.wala.ssa.SSAGetInstruction;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAInvokeInstruction;
import com.ibm.wala.ssa.SSAOptions;
import com.ibm.wala.ssa.SSAReturnInstruction;
import com.ibm.wala.ssa.SSAThrowInstruction;
import com.ibm.wala.ssa.SymbolTable;
import com.ibm.wala.ssa.Value;
import com.ibm.wala.ssa.analysis.ExplodedControlFlowGraph;
import com.ibm.wala.ssa.analysis.IExplodedBasicBlock;
import com.ibm.wala.types.MethodReference;
import com.ibm.wala.types.TypeReference;

import pfl.analysis_util.exec_points.BranchPoint;
import pfl.analysis_util.exec_points.ExecPointBase;
import pfl.analysis_util.exec_points.NegatePoint;
import pfl.dataflow.DFUtils;
import pfl.graph.InducedNoExceptionCFG;
import pfl.util.LoopUtilSynchronizedWrapper;
import pfl.util.RemoveReason;
import pfl.util.ProgressMonitor;
import pfl.util.Utils;
import scala.collection.JavaConversions;

public class GetNegatePoints 
{
    static AnalysisOptions analysisOptions = new AnalysisOptions();
    static AnalysisCache analysisCache;
    static WalaModel model;
    static CallGraph cg;
    static PointerAnalysis<InstanceKey> pa;

    static Set<String> forceKeep = Sets.newHashSet("c10202ed70fe59298bca094d640bab52", "90d257abd160444cb0101217356a6a87");

    public static void main(String[] args) throws Exception 
    {
        SSAOptions ssaOptions = SSAOptions.defaultOptions();
        ssaOptions.setPiNodePolicy(new AllIntegerDueToBranchePiPolicy());
        analysisCache = new AnalysisCacheImpl(ssaOptions);
        analysisOptions.setSSAOptions(ssaOptions);

        Options options = new Options();
        options.addOption("cp", "classpath", true, "Classpath");
        options.addOption("o", "output", true, "Loops Output JSON Path");
        options.addOption("d", "depth", true, "Number of invocation levels that the analyzer should follow (loops)");
        options.addOption("cp_prefix_excl", true, "Excluded classpath prefix for analysis scope, \"|\" splitted");
        options.addOption("cp_prefix", true, "Included classpath prefix");
        CommandLineParser parser = new DefaultParser();
        CommandLine cmd = parser.parse(options, args);
        String classpath = cmd.getOptionValue("classpath");
        String outputPath = cmd.getOptionValue("output");
        int invokeDepthLimit = Integer.parseInt(cmd.getOptionValue("depth", "10"));
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

        // Invoke boolean-returning functions starting from loops
        model = new WalaModel(classpath);
        DFUtils.analysisCache = analysisCache;
        Set<NegatePoint> negates = new HashSet<>();
        Set<IMethod> processedMethods = new HashSet<>();
        Map<HashCode, Boolean> retValueUsed = new ConcurrentHashMap<>();
        for (IClass clazz: model.getCha())
        {
            if (exclPrefixes.stream().anyMatch(p -> Utils.getFullClassName(clazz).startsWith(p))) continue;
            if (checkInclPrefix && !inclPrefixes.stream().anyMatch(p -> Utils.getFullClassName(clazz).startsWith(p))) continue;
            if (Utils.insideJRELibrary(clazz)) continue;
            for (IMethod method: clazz.getDeclaredMethods())
            {
                IR methodIR = analysisCache.getSSACache().findOrCreateIR(method, Everywhere.EVERYWHERE, analysisOptions.getSSAOptions());
                if (methodIR == null) continue;
                for (SSAInstruction inst: methodIR.getInstructions())
                {
                    if (!(inst instanceof SSAInvokeInstruction)) continue;
                    SSAInvokeInstruction invokeInst = (SSAInvokeInstruction) inst;
                    IMethod declaredTarget = model.getCha().resolveMethod(invokeInst.getDeclaredTarget());
                    if (declaredTarget == null) continue;
                    if (Utils.insideJRELibrary(declaredTarget.getDeclaringClass())) continue;
                    processedMethods.add(declaredTarget);

                    // If this is a function returning a boolean value
                    if (!declaredTarget.getReturnType().equals(TypeReference.Boolean)) continue;
                    IR targetIR = analysisCache.getSSACache().findOrCreateIR(declaredTarget, Everywhere.EVERYWHERE, analysisOptions.getSSAOptions());
                    boolean invokeResultUsed = DFUtils.isRetValueUsed(methodIR, invokeInst);
                    if (targetIR == null)
                    {
                        NegatePoint t = new NegatePoint(declaredTarget.getDeclaringClass(), declaredTarget, -1);
                        // if (t.getID().equals("14abfa47722fc97d498a292ad1944d3f")) negates.add(t);
                        negates.add(t);
                        retValueUsed.put(t.getHash(), retValueUsed.getOrDefault(t.getHash(), false) || invokeResultUsed);
                    }
                    else 
                    {
                        IntSummaryStatistics targetCodeRange = Utils.getMethodIRLineRange(targetIR);
                        int firstLineNo = targetCodeRange.getMin();
                        int lastLineNo = targetCodeRange.getMax(); 
                        NegatePoint t = new NegatePoint(declaredTarget.getDeclaringClass(), declaredTarget, firstLineNo, lastLineNo);
                        retValueUsed.put(t.getHash(), retValueUsed.getOrDefault(t.getHash(), false) || invokeResultUsed);
                        // if (t.getID().equals("14abfa47722fc97d498a292ad1944d3f")) negates.add(t);
                        negates.add(t);
                    }
                }
            }
        }

        negates.removeIf(e -> Utils.getShortMethodName(e.method).equals("equals"));
        negates.removeIf(e -> Utils.getShortMethodName(e.method).startsWith("access$"));
        negates.removeIf(e -> Utils.getShortMethodName(e.method).contains("clinit"));
        negates.removeIf(e -> exclPrefixes.stream().anyMatch(prefix -> Utils.getFullClassName(e.clazz).startsWith(prefix)));
        // List<NegatePoint> negateList = negates.parallelStream().filter(e -> !shouldRemove(e).shouldRemove).collect(Collectors.toList());
        List<NegatePoint> negateList = new ArrayList<>(negates);
        Collections.shuffle(negateList);
        System.out.println("Total: " + negateList.size());
        
        // Build CallGraph
        Set<Entrypoint> eps = negateList.stream().map(e -> new DefaultEntrypoint(e.method, model.getCha())).collect(Collectors.toSet());
        AnalysisOptions ao = new AnalysisOptions(model.getScope(), eps);
        ao.setSSAOptions(ssaOptions);
        SSAPropagationCallGraphBuilder builder = com.ibm.wala.ipa.callgraph.impl.Util.makeZeroCFABuilder(Language.JAVA, ao, analysisCache, model.getCha());
        cg = builder.makeCallGraph(ao, new ProgressMonitor());
        pa = builder.getPointerAnalysis();
        System.out.println("Callgraph Built");
        // Set<String> debugTargets = Sets.newHashSet("01480920beed3077481406d5710f02a3", "5db7044a70793f4b48d653dbf658e2c6", "a3c8017a304a1513ce59e6982e74004d", "7033a4f864ec4912b1ffbb21d4cace19");
        ExecutorService es = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() / 8);
        AtomicInteger progress = new AtomicInteger();
        for (NegatePoint e: negateList)
        {
            // if (!e.getID().equals("ea8dd76407b14a696fa871008e105db2")) continue;
            es.submit(() -> 
            {
                int pgrs = progress.incrementAndGet();
                if (pgrs % 10 == 0) System.out.println("At: " + pgrs);
                if (retValueUsed.get(e.getHash())) 
                    e.shouldRemove = shouldRemove(e);
                else
                    e.shouldRemove = new RemoveReason(true, "Rule 6: Return Value Not Used");
                if (forceKeep.contains(e.getID())) e.shouldRemove = new RemoveReason(false, "");
            });
            // if (progress.getAndIncrement() % 50 == 0) System.out.println("At: " + progress.get());
            // // if (!debugTargets.contains(e.getID())) continue;
            // // System.out.println("At: " + e.method);
            // if (retValueUsed.get(e.getHash())) 
            //     e.shouldRemove = shouldRemove(e);
            // else
            //     e.shouldRemove = new RemoveReason(true, "Rule 6: Return Value Not Used");
            // // System.out.println(e.shouldRemove);
        }
        es.shutdown();
        es.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);

        Collections.sort(negateList);
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
        new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(ExecPointBase.getIDMapForJson(negateList), pw);
        pw.close();
    }

    private static RemoveReason shouldRemove(NegatePoint negatePoint)
    {
        Map<IMethod, List<SSAInstruction>> backwardDataflow = DFUtils.getBackwardSDGInvokeAndGetField(model, negatePoint.method, cg, pa);
        Set<MethodReference> exploredFuncs = backwardDataflow.keySet().stream().map(e -> e.getReference()).collect(Collectors.toSet());
        Set<MethodReference> leafInvocations = new HashSet<>();
        Set<IField> accessedFields = new HashSet<>();
        Map<IMethod, Set<Integer>> invokeReceivers = new HashMap<>(); // Receiver: `obj` of obj.foo(); The instance where the method is invoked
        Map<IMethod, Set<Integer>> getInstDefs = new HashMap<>(); // Set(ValueNumber)
        Map<IMethod, Map<Integer, IField>> getInstDefToField = new HashMap<>(); // {ValueNumber: IField}
        for (IMethod method: backwardDataflow.keySet())
        {
            List<SSAInstruction> insts = backwardDataflow.get(method);
            for (SSAInstruction inst: insts)
            {
                if (inst instanceof SSAInvokeInstruction)
                {
                    SSAInvokeInstruction invokeInst = (SSAInvokeInstruction) inst;
                    MethodReference invokeTarget = invokeInst.getDeclaredTarget();
                    if (!exploredFuncs.contains(invokeTarget)) 
                    {
                        leafInvocations.add(invokeTarget);
                        List<Integer> invokeUses = DFUtils.getSSAUses(inst);
                        if (invokeUses.size() > 0) invokeReceivers.computeIfAbsent(method, k -> new HashSet<>()).add(invokeUses.get(0));
                    }
                }
                else if (inst instanceof SSAGetInstruction)
                {
                    SSAGetInstruction getInst = (SSAGetInstruction) inst;
                    int getDefNum = getInst.getDef();
                    IField getField = Utils.lookupField(model.getCha(), getInst.getDeclaredField());                 
                    accessedFields.add(getField);
                    getInstDefs.computeIfAbsent(method, k -> new HashSet<>()).add(getDefNum);
                    getInstDefToField.computeIfAbsent(method, k -> new HashMap<>()).put(getDefNum, getField);
                }
            }
        }
        leafInvocations.removeIf(e -> Objects.isNull(e));
        accessedFields.removeIf(e -> Objects.isNull(e));
        // System.out.println(backwardDataflow);
        // System.out.println(leafInvocations);
        // System.out.println(accessedFields);

        // Rule 1: Interface Class
        if (negatePoint.clazz.isInterface()) return new RemoveReason(true, "Rule 1: Interface Class");

        // Rule 2: Abstract Class
        if (negatePoint.clazz.isAbstract()) return new RemoveReason(true, "Rule 2: Abstract Class");
        
        // Rule 3: Only standard library used in calculating the return value
        boolean allLibraryInvoke = (leafInvocations.size() > 0) && leafInvocations.stream().allMatch(e -> Utils.getFullMethodName(e).startsWith("java"));
        if (allLibraryInvoke) return new RemoveReason(true, "Rule 3: All Library Invocations");

        // Rule 4: Final Variables
        // This does not consider fields that has methods invoked
        Set<IField> fieldsBeingInvoked = new HashSet<>();
        for (IMethod method: getInstDefs.keySet())
        {
            if (!invokeReceivers.containsKey(method)) continue;
            Set<Integer> invokeReceiverInMethod = invokeReceivers.get(method);
            Set<Integer> getInstDefInMethod = getInstDefs.get(method);
            Map<Integer, IField> fieldsGotInMethod = getInstDefToField.get(method);
            Set<Integer> fieldBeingInvokedNumber = Sets.intersection(invokeReceiverInMethod, getInstDefInMethod);
            fieldBeingInvokedNumber.stream().filter(Objects::nonNull).map(valueNum -> fieldsGotInMethod.get(valueNum)).filter(Objects::nonNull).forEach(f -> fieldsBeingInvoked.add(f));
        }
        accessedFields.removeIf(e -> Utils.getFullClassName(e.getDeclaringClass()).startsWith("java.lang"));
        Set<IField> accessedFieldsNotInvoked = Sets.difference(accessedFields, fieldsBeingInvoked);
        boolean allFinalFieldAccess = (accessedFieldsNotInvoked.size() > 0) && accessedFieldsNotInvoked.stream().allMatch(e -> e.isFinal());
        if (allFinalFieldAccess) return new RemoveReason(true, "Rule 4: Final Field Access");

        // Rule 5: Constant Return Value
        boolean constantReturnValue = leafInvocations.size() == 0 && accessedFields.size() == 0;
        if (constantReturnValue && DFUtils.isReturningConstantBoolean(analysisCache.getIR(negatePoint.method))) 
            return new RemoveReason(true, "Rule 5: Constant Return Value Or No Function Calls when value is calcuated");

        return new RemoveReason(false, "");
    }

    private static List<NegatePoint> getNegatePointsFromInsts(IMethod parentMethod, List<SSAInstruction> insts, int invokeDepthLimit, Set<SSAInstruction> level0InclInst)
    {
        SSAInstruction[] instArr = Iterables.toArray(insts, SSAInstruction.class);
        InducedNoExceptionCFG inducedCFG = new InducedNoExceptionCFG(instArr, parentMethod, Everywhere.EVERYWHERE);
        Queue<ImmutableTriple<IMethod, InducedNoExceptionCFG, Integer>> bfsQueue = new LinkedList<>();
        Set<IMethod> visited = new HashSet<>();
        List<NegatePoint> r = new ArrayList<>();
        bfsQueue.add(ImmutableTriple.of(parentMethod, inducedCFG, 0));
        while (!bfsQueue.isEmpty())
        {
            ImmutableTriple<IMethod, InducedNoExceptionCFG, Integer> c = bfsQueue.poll();
            IMethod currentMethod = c.getLeft();
            InducedNoExceptionCFG currentCFG = c.getMiddle();
            int currentDepth = c.getRight();
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
                        if (Utils.insideJRELibrary(declaredTarget.getDeclaringClass()))
                            continue;
                        // If this is a function returning a boolean value
                        if (declaredTarget.getReturnType().equals(TypeReference.Boolean))
                        {
                            SSAInstruction targetFirstInst = getFirstNonNullInst(declaredTarget);
                            int lineNo = targetFirstInst == null ? -1 : declaredTarget.getLineNumber(targetFirstInst.iIndex());
                            NegatePoint t = new NegatePoint(declaredTarget.getDeclaringClass(), declaredTarget, lineNo);
                            r.add(t);
                            continue;
                        }
                        if (visited.contains(declaredTarget))
                            continue;
                        InducedNoExceptionCFG targetCFG = getInducedNoExceptionCFG(declaredTarget);
                        if (targetCFG == null)
                            continue;
                        bfsQueue.add(ImmutableTriple.of(declaredTarget, targetCFG, currentDepth + 1));
                    }
                }
            }
        }
        return r;
    }

    private static SSAInstruction getFirstNonNullInst(IMethod method)
    {
        IR methodIR = analysisCache.getSSACache().findOrCreateIR(method, Everywhere.EVERYWHERE, analysisOptions.getSSAOptions());
        if (methodIR == null)
            return null;
        for (SSAInstruction inst: methodIR.getInstructions())
        {
            if (inst != null)
                return inst;
        }
        return null;
    }

    private static SSAInstruction getLastNonNullInst(IMethod method)
    {
        IR methodIR = analysisCache.getSSACache().findOrCreateIR(method, Everywhere.EVERYWHERE, analysisOptions.getSSAOptions());
        if (methodIR == null)
            return null;
        SSAInstruction[] insts = methodIR.getInstructions();
        for (int i = insts.length - 1; i >=0; i--)
        {
            SSAInstruction inst = insts[i];
            if (inst != null)
                return inst;
        }
        return null;
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
        if (methodIR == null)
            return null;
        SSACFG methodCFG = methodIR.getControlFlowGraph();
        return new InducedNoExceptionCFG(methodCFG.getInstructions(), methodCFG.getMethod(), Everywhere.EVERYWHERE);
    }

}
