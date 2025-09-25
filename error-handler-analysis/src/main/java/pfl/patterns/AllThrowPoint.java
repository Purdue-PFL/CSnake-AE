package pfl.patterns;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.apache.commons.lang3.tuple.ImmutablePair;
import org.jgrapht.Graph;
import org.jgrapht.Graphs;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.graph.concurrent.AsSynchronizedGraph;

import com.google.common.collect.Lists;
import com.google.common.collect.Streams;
import com.ibm.wala.analysis.typeInference.TypeInference;
import com.ibm.wala.cfg.ControlFlowGraph;
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
import com.ibm.wala.shrike.shrikeCT.InvalidClassFileException;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.SSAConditionalBranchInstruction;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAInvokeInstruction;
import com.ibm.wala.ssa.SSAThrowInstruction;
import com.ibm.wala.types.MethodReference;
import com.ibm.wala.types.TypeReference;

import pfl.WalaModel;
import pfl.analysis_util.CtorConstructionStep;
import pfl.analysis_util.ErrorHandler;
import pfl.analysis_util.exec_points.ExceptionInjectionPoint;
import pfl.analysis_util.exec_points.ExceptionInjectionPoint.InjectionType;
import pfl.graph.InducedNoExceptionCFG;
import pfl.util.Debugger;
import pfl.util.Utils;

public class AllThrowPoint extends IfThrow
{
    AnalysisOptions aOptions = new AnalysisOptions(model.getScope(), new AllApplicationEntrypoints(model.getScope(), model.getCha()));
    AnalysisCache aCache = new AnalysisCacheImpl();
    // CallGraph cg;
    // Graph<CGNode, DefaultEdge> inverseCG;
    Map<ImmutablePair<IClass, IMethod>, CGNode> inverseNodeMap;

    public AllThrowPoint(WalaModel model)
    {
        super(model);
        aOptions.setReflectionOptions(AnalysisOptions.ReflectionOptions.APPLICATION_GET_METHOD);
    }

    public List<ExceptionInjectionPoint> analyze(List<String> includePrefixes, List<String> excludePrefixes) throws Exception
    {
        /*

        // Get a callgraph for Type 2 throw (See below)
        SSAPropagationCallGraphBuilder builder2 = com.ibm.wala.ipa.callgraph.impl.Util.makeVanillaZeroOneCFABuilder(Language.JAVA, aOptions, aCache, model.getCha());
        cg = builder2.makeCallGraph(aOptions, null);
        // For Type 2 throw, we need to traverse the call graph backwards. We create an inverse CG here.
        inverseCG = new AsSynchronizedGraph<>(new DefaultDirectedGraph<>(DefaultEdge.class));
        inverseNodeMap = new ConcurrentHashMap<>();
        for (CGNode cgn : cg)
        {
            if (Utils.insideJRELibrary(cgn.getMethod().getDeclaringClass().getName().toString()))
                continue;
            inverseCG.addVertex(cgn);
            for (CGNode ep2 : Utils.toIterable(cg.getSuccNodes(cgn)))
            {
                inverseCG.addVertex(ep2);
                inverseCG.addEdge(ep2, cgn);
            }
            inverseNodeMap.put(ImmutablePair.of(cgn.getMethod().getDeclaringClass(), cgn.getMethod()), cgn);
        }
        System.out.println("Callgraph Generated");

        */
        
        Set<ExceptionInjectionPoint> resultThrow = ConcurrentHashMap.newKeySet();
        Set<ExceptionInjectionPoint> resultLibraryInvoke = ConcurrentHashMap.newKeySet();
        AtomicInteger progressIndicator = new AtomicInteger();
        List<ImmutablePair<IClass, IMethod>> analysisWorkload = new ArrayList<>();
        for (IClass clazz : model.getCha())
        {
            if ((!includePrefixes.stream().anyMatch(p -> Utils.getFullClassName(clazz).startsWith(p)))
                    || (excludePrefixes.stream().anyMatch(p -> Utils.getFullClassName(clazz).startsWith(p))))
                continue;
            for (IMethod method : clazz.getDeclaredMethods())
            {
                analysisWorkload.add(ImmutablePair.of(clazz, method));
            }
        }
        BiConsumer<IClass, IMethod> analysisFunc = (clazz, method) ->
        {
            int currentProgress = progressIndicator.getAndIncrement();
            if ((currentProgress < 5) || (currentProgress % 1000 == 0))
                System.out.println("At: " + currentProgress + "\t" + Utils.getFullClassName(clazz));

            Predicate<SSAInstruction> throwStmtFilter = inst -> Utils.isStmtType(inst, SSAThrowInstruction.class);
            processMethod(clazz, method, resultThrow, throwStmtFilter, true);

            Predicate<SSAInstruction> libraryInvokeStmtFilter = inst ->
            {
                if (!Utils.isStmtType(inst, SSAInvokeInstruction.class))
                    return false;
                SSAInvokeInstruction invokeStmt = (SSAInvokeInstruction) inst;
                MethodReference invokeTarget = invokeStmt.getDeclaredTarget();
                if (!Utils.insideJRELibrary(invokeTarget.getDeclaringClass()))
                    return false;
                try
                {
                    IMethod invokeTargetIMethod = Utils.lookupMethod(model.getCha(), invokeTarget);
                    TypeReference[] declaredEx = invokeTargetIMethod.getDeclaredExceptions();
                    // List<IClass> declaredExClass = declaredEx.stream().map(ex -> model.getCha().lookupClass(ex)).collect(Collectors.toList());
                    // Debugger.println(invokeStmt.toString());
                    // Debugger.println(declaredExClass.toString());
                    if (declaredEx.length > 0)
                        Debugger.println("+++++ " + invokeTarget.toString());
                    return declaredEx.length > 0;
                }
                catch (Exception e)
                {
                    return false;
                }
            };
            processMethod(clazz, method, resultLibraryInvoke, libraryInvokeStmtFilter, false);
        };
        ExecutorService es = new ForkJoinPool(32);
        analysisWorkload.parallelStream().forEach(e -> es.submit(() -> analysisFunc.accept(e.getLeft(), e.getRight())));
        es.shutdown();
        es.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
        System.out.println("SSAThrowInstruction: " + resultThrow.size());
        System.out.println("SSAInvokeInstruction: " + resultLibraryInvoke.size());
        Debugger.println(resultLibraryInvoke.stream().map(e -> e.toMap()).collect(Collectors.toList()).toString());
        List<ExceptionInjectionPoint> r = Lists.newArrayList(resultThrow);
        r.addAll(resultLibraryInvoke);
        Collections.sort(r);
        return r;
    }

    private void processMethod(IClass clazz, IMethod method, Set<ExceptionInjectionPoint> resultSet, Predicate<SSAInstruction> targetStmtFilter, boolean injectAtBranch)
    {
        if (Utils.insideJRELibrary(method.getDeclaringClass().getName().toString()))
            return;
        IR methodIR = aCache.getSSACache().findOrCreateIR(method, Everywhere.EVERYWHERE, aOptions.getSSAOptions());
        if (methodIR == null)
            return; // Could be an interface method

        Set<SSAInstruction> processedInsts = new HashSet<>();
        ControlFlowGraph<SSAInstruction, InducedNoExceptionCFG.BasicBlock> methodCFG = sanitizeSSACFG(methodIR.getControlFlowGraph());
        // Type 1: Branch then throw, inject at branch
        if (injectAtBranch)
        {
            for (InducedNoExceptionCFG.BasicBlock bb : methodCFG)
            {
                List<InducedNoExceptionCFG.BasicBlock> predBBInclFallthrough = getCFGPredBlocksFollowFallThrough(methodCFG, bb);
                // Has throw stmt inside; have branch before this block
                if ((methodCFG.getPredNodeCount(bb) > 0) && Utils.basicBlockContainsStmtsPredicate(bb, targetStmtFilter)
                        && predBBInclFallthrough.stream().anyMatch(predBB -> Utils.basicBlockContainsStmtStype(predBB, SSAConditionalBranchInstruction.class)))
                {
                    // Find the ConditionalBranch Inst, this is the real position of fault injection
                    InducedNoExceptionCFG.BasicBlock branchBB = predBBInclFallthrough.stream()
                            .filter(predBB -> Utils.basicBlockContainsStmtStype(predBB, SSAConditionalBranchInstruction.class)).findAny().get();
                    SSAInstruction branchInst = Streams.stream(branchBB).filter(inst -> Utils.isStmtType(inst, SSAConditionalBranchInstruction.class)).findAny().get();

                    // Find line number of the throw statement
                    List<SSAInstruction> targetStmts = Streams.stream(bb).filter(targetStmtFilter).collect(Collectors.toList());
                    for (SSAInstruction targetStmt : targetStmts)
                    {
                        if (Utils.isStmtType(targetStmt, SSAThrowInstruction.class))
                        {
                            // Remove the throw statements where the source exception is caught (if statement in the finally block)
                            // TODO: May cause false negative if the exception is rethrown
                            if (isThrowingCaughtExceptions(methodIR, (SSAThrowInstruction) targetStmt))
                                continue;
                            // Remove assert statements, which is also branch + throw
                            if (isThrowingAssertionError(methodIR, (SSAThrowInstruction) targetStmt))
                                continue;
                        }

                        try
                        {
                            ExceptionInjectionPoint eh = getErrorHandlerThrowEx(methodIR, clazz, method, branchInst, methodIR, clazz, method, targetStmt, ExceptionInjectionPoint.InjectionType.IF_THROW);
                            resultSet.add(eh);
                        }
                        catch (UnsupportedOperationException | InvalidClassFileException e)
                        {
                        }
                        processedInsts.add(targetStmt);
                    }
                }
            }
            // System.out.println("Processed Type 1 Throw: " + processedInsts.size());
        }

        Debugger.println("At method: " + method);
        Debugger.println(methodCFG.toString());
        // Type 2: ThrowStmt not guarded by any branch stmt, trace the call graph upwards until finding the first branch stmt guarding the invocation
        // The injection should happen at the branch stmt, otherwise we cannot hit such throw stmt
        // UPDATE: We are ignoring this type for now. For throw stmts, we are ignoring them. For library calls that have a throw signature, they should be directly thrown
        // at the library call.
        /*
        if (false)
        {
            for (InducedNoExceptionCFG.BasicBlock bb : methodCFG)
            {
                List<SSAInstruction> targetStmts = Streams.stream(bb).filter(targetStmtFilter).collect(Collectors.toList());
                targetStmts.removeAll(processedInsts);
                if (targetStmts.size() <= 0)
                    continue;

                Debugger.println("Type 2: Cond 1");
                List<InducedNoExceptionCFG.BasicBlock> predBBInclFallthrough = getCFGPredBlocksFollowFallThrough(methodCFG, bb);
                // The throwstmt is not guarded by any branch stmt locally. Thus the predBB must contains BB0.
                if (!(predBBInclFallthrough.stream().anyMatch(e -> e.getNumber() == 0) || (bb.getNumber() == 0)))
                    continue;
                Debugger.println("Type 2: Cond 2");
                if (!inverseNodeMap.containsKey(ImmutablePair.of(clazz, method)))
                    continue; // Sometimes the CGN is just not there, no idea why
                Debugger.println("Type 2: Cond 3");

                // Use BFS on the inverse Callgraph to get all the branch stmts that leads to the throw point
                CGNode startCGN = inverseNodeMap.get(ImmutablePair.of(clazz, method));
                List<ImmutablePair<SSAInstruction, CGNode>> branchInstToCurrentThrow = new ArrayList<>();
                Queue<CGNode> bfsQueue = new LinkedList<>();
                bfsQueue.add(startCGN);
                Set<CGNode> visited = new HashSet<>();
                while (!bfsQueue.isEmpty())
                {
                    CGNode currentNode = bfsQueue.poll();
                    visited.add(currentNode);
                    Debugger.println("BFS Current At: " + currentNode.getMethod());
                    List<CGNode> predToCurrentFunc = Graphs.successorListOf(inverseCG, currentNode);
                    for (CGNode predFunc : predToCurrentFunc)
                    {
                        Debugger.println("Pred At: " + predFunc.getMethod());
                        IR predIR = predFunc.getIR();
                        ControlFlowGraph<SSAInstruction, InducedNoExceptionCFG.BasicBlock> predCFG = sanitizeSSACFG(predIR.getControlFlowGraph());
                        boolean doNotBFS = false;
                        for (InducedNoExceptionCFG.BasicBlock bbInPredFunc : predCFG)
                        {
                            if (!Utils.basicBlockContainsStmtStype(bbInPredFunc, SSAInvokeInstruction.class))
                                continue;
                            List<SSAInstruction> invokeStmts = Streams.stream(bbInPredFunc).filter(inst -> Utils.isStmtType(inst, SSAInvokeInstruction.class))
                                    .collect(Collectors.toList());
                            // We are invoking the current method
                            if (!invokeStmts.stream().anyMatch(inst -> ((SSAInvokeInstruction) inst).getDeclaredTarget().equals(currentNode.getMethod().getReference())))
                                continue;
                            List<InducedNoExceptionCFG.BasicBlock> predBBInPredFuncInclFallthrough = getCFGPredBlocksFollowFallThrough(predCFG, bbInPredFunc);
                            if ((predCFG.getPredNodeCount(bbInPredFunc) > 0) && predBBInPredFuncInclFallthrough.stream()
                                    .anyMatch(predBB -> Utils.basicBlockContainsStmtStype(predBB, SSAConditionalBranchInstruction.class)))
                            {
                                // We have a branch, do not keep exploring predFunc
                                InducedNoExceptionCFG.BasicBlock branchBBInPredFunc = predBBInPredFuncInclFallthrough.stream()
                                        .filter(predBB -> Utils.basicBlockContainsStmtStype(predBB, SSAConditionalBranchInstruction.class)).findAny().get();
                                SSAInstruction branchInstInPredFunc = Streams.stream(branchBBInPredFunc)
                                        .filter(inst -> Utils.isStmtType(inst, SSAConditionalBranchInstruction.class)).findAny().get();
                                branchInstToCurrentThrow.add(ImmutablePair.of(branchInstInPredFunc, predFunc));
                                doNotBFS = true;
                            }
                        }
                        Debugger.println("doNotBFS: " + doNotBFS);
                        if (!doNotBFS && !visited.contains(predFunc))
                        {
                            bfsQueue.add(predFunc);
                        }
                    }
                }

                // Match the branch stmts to throw stmts
                for (SSAInstruction targetStmt : targetStmts)
                {
                    if (processedInsts.contains(targetStmt))
                        continue;
                    branchInstToCurrentThrow.forEach(e ->
                    {
                        SSAInstruction bInst = e.getLeft();
                        IMethod bMethod = e.getRight().getMethod();
                        try
                        {
                            ExceptionInjectionPoint eh = getErrorHandlerThrowEx(e.getRight().getIR(), bMethod.getDeclaringClass(), bMethod, bInst, methodIR, clazz, method,
                                    targetStmt);
                            resultSet.add(eh);
                        }
                        catch (UnsupportedOperationException | InvalidClassFileException e1)
                        {
                        }
                    });
                    processedInsts.add(targetStmt);
                }
            }
            // System.out.println("Processed Type 1 + 2 Throw: " + processedInsts.size());
        }
        */

        // Type 3: In case we have missed anything
        for (SSAInstruction inst : methodIR.getInstructions())
        {
            if (!targetStmtFilter.test(inst))
                continue;
            if (processedInsts.contains(inst))
                continue;
            if (Utils.isStmtType(inst, SSAThrowInstruction.class))
            {
                // Remove the throw statements where the source exception is caught (if statement in the finally block)
                // TODO: May cause false negative if the exception is rethrown
                if (isThrowingCaughtExceptions(methodIR, (SSAThrowInstruction) inst))
                    continue;
                // Remove assert statements, which is also branch + throw
                if (isThrowingAssertionError(methodIR, (SSAThrowInstruction) inst))
                    continue;
            }
            try
            {
                ExceptionInjectionPoint eh = getErrorHandlerThrowEx(methodIR, clazz, method, inst, methodIR, clazz, method, inst, ExceptionInjectionPoint.InjectionType.LIB_THROW);
                resultSet.add(eh);
            }
            catch (UnsupportedOperationException | InvalidClassFileException e)
            {
            }

        }
    }

    private ExceptionInjectionPoint getErrorHandlerThrowEx(IR injectionIR, IClass injectionClass, IMethod injectionMethod, SSAInstruction injectionStmt, IR throwPointIR,
            IClass throwPointClass, IMethod throwPointMethod, SSAInstruction canThrowStmt, ExceptionInjectionPoint.InjectionType injectionType) throws UnsupportedOperationException, InvalidClassFileException
    {
        TypeInference tyInf = TypeInference.make(throwPointIR, true);
        IClass throwType;
        if (Utils.isStmtType(canThrowStmt, SSAThrowInstruction.class))
        {
            throwType = tyInf.getType(canThrowStmt.getUse(0)).getType();
        }
        else if (Utils.isStmtType(canThrowStmt, SSAInvokeInstruction.class))
        {
            SSAInvokeInstruction invokeStmt = (SSAInvokeInstruction) canThrowStmt;
            MethodReference invokeTarget = invokeStmt.getDeclaredTarget();
            IMethod invokeTargetIMethod = Utils.lookupMethod(model.getCha(), invokeTarget);
            List<TypeReference> declaredEx = Lists.newArrayList(invokeTargetIMethod.getDeclaredExceptions());
            List<IClass> declaredExClass = declaredEx.stream().map(ex -> model.getCha().lookupClass(ex)).collect(Collectors.toList());
            throwType = findClassWithSimplistCtor(declaredExClass);
        }
        else
        {
            // This should not happen, but as a safeguard. Discard the current error handler if it happens instead of generating useless EHs.
            throw new UnsupportedOperationException();
        }
        ExceptionInjectionPoint eip = new ExceptionInjectionPoint(injectionClass, injectionMethod, Utils.getSrcLineNumberBySSAInst(injectionIR, injectionStmt.iIndex()),
                throwPointClass, throwPointMethod, Utils.getSrcLineNumberBySSAInst(throwPointIR, canThrowStmt.iIndex()), injectionType);
        List<CtorConstructionStep> ctorSteps = throwCtorConstructSteps(throwType);
        eip.setThrowableSteps(ctorSteps);
        return eip;
    }

}
