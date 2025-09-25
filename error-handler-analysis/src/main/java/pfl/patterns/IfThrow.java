package pfl.patterns;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.stream.Collector;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.lang3.tuple.ImmutablePair;
import org.jgrapht.Graph;
import org.jgrapht.Graphs;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.graph.DirectedAcyclicGraph;
import org.jgrapht.traverse.TopologicalOrderIterator;

import com.google.common.collect.ComparisonChain;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Streams;
import com.ibm.wala.analysis.typeInference.TypeInference;
import com.ibm.wala.cfg.ControlFlowGraph;
import com.ibm.wala.cfg.IBasicBlock;
import com.ibm.wala.cfg.InducedCFG;
import com.ibm.wala.cfg.exc.intra.MutableCFG;
import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.AnalysisCache;
import com.ibm.wala.ipa.callgraph.AnalysisCacheImpl;
import com.ibm.wala.ipa.callgraph.AnalysisOptions;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.Entrypoint;
import com.ibm.wala.ipa.callgraph.impl.Everywhere;
import com.ibm.wala.ipa.cfg.ExceptionPrunedCFG;
import com.ibm.wala.ipa.cfg.PrunedCFG;
import com.ibm.wala.ipa.cha.ClassHierarchy;
import com.ibm.wala.shrike.sourcepos.Debug;
import com.ibm.wala.ssa.DefUse;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.ISSABasicBlock;
import com.ibm.wala.ssa.SSACFG;
import com.ibm.wala.ssa.SSAConditionalBranchInstruction;
import com.ibm.wala.ssa.SSAGetCaughtExceptionInstruction;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSANewInstruction;
import com.ibm.wala.ssa.SSAOptions;
import com.ibm.wala.ssa.SSAThrowInstruction;
import com.ibm.wala.types.TypeName;
import com.ibm.wala.types.TypeReference;

import pfl.WalaModel;
import pfl.analysis_util.CtorConstructionStep;
import pfl.analysis_util.ErrorHandler;
import pfl.analysis_util.exec_points.ExceptionInjectionPoint;
import pfl.graph.InducedNoExceptionCFG;
import pfl.util.Debugger;
import pfl.util.Utils;

// synchronized block may throw exception at the end
// finally block may throw exception at the end (try-finally)

public class IfThrow extends ThrowBase
{
    public IfThrow(WalaModel model)
    {
        this.model = model;
    }

    public List<ExceptionInjectionPoint> analyze(List<String> includePrefixes, List<String> excludePrefixes) throws Exception
    {
        AnalysisOptions aOptions = new AnalysisOptions();
        AnalysisCache aCache = new AnalysisCacheImpl();
        SortedSet<ExceptionInjectionPoint> result = new TreeSet<>();
        for (IClass clazz : model.getCha())
        {
            if ((!includePrefixes.stream().anyMatch(p -> Utils.getFullClassName(clazz).startsWith(p)))
                    || (excludePrefixes.stream().anyMatch(p -> Utils.getFullClassName(clazz).startsWith(p))))
                continue;
            if (Debugger.instance.SINGLE_METHOD_DEBUG && !Utils.getFullClassName(clazz).contains(Debugger.instance.classTarget))
                continue;
            if (Debugger.instance.SINGLE_METHOD_DEBUG)
                Debugger.println("Entering class" + clazz);

            for (IMethod method : clazz.getAllMethods())
            {
                if (Debugger.instance.SINGLE_METHOD_DEBUG && !Utils.getFullMethodName(method).contains(Debugger.instance.methodTarget))
                    continue;
                if (Debugger.instance.SINGLE_METHOD_DEBUG)
                    Debugger.println("Entering method" + method);

                if (Utils.insideJRELibrary(method.getDeclaringClass().getName().toString()))
                    continue;
                IR methodIR = aCache.getSSACache().findOrCreateIR(method, Everywhere.EVERYWHERE, aOptions.getSSAOptions());
                if (methodIR == null)
                    continue; // Could be an interface method

                Debugger.println(Utils.getFullClassName(clazz));
                Debugger.println(Utils.getFullMethodName(method));
                Debugger.println(methodIR.toString());
                Debugger.println("-------------------------------------------");

                ControlFlowGraph<SSAInstruction, InducedNoExceptionCFG.BasicBlock> methodCFG = sanitizeSSACFG(methodIR.getControlFlowGraph());

                Debugger.println(methodCFG.toString());
                for (InducedNoExceptionCFG.BasicBlock bb : methodCFG)
                {
                    List<InducedNoExceptionCFG.BasicBlock> predBBInclFallthrough = getCFGPredBlocksFollowFallThrough(methodCFG, bb);
                    Debugger.println("====================");
                    Debugger.println(bb.toString());
                    Debugger.println("Contains Throw: " + Utils.basicBlockContainsStmtStype(bb, SSAThrowInstruction.class));
                    Debugger.println("Contains Branch: " + Utils.basicBlockContainsStmtStype(bb, SSAConditionalBranchInstruction.class));
                    Debugger.println("Pred Count: " + methodCFG.getPredNodeCount(bb));
                    Debugger.println("Preds: " + predBBInclFallthrough.stream().map(predbb -> Integer.toString(predbb.getNumber())).collect(Collectors.joining(", ")));

                    // Has throw stmt inside; have branch before this block
                    if ((methodCFG.getPredNodeCount(bb) > 0) && Utils.basicBlockContainsStmtStype(bb, SSAThrowInstruction.class)
                            && predBBInclFallthrough.stream().anyMatch(predBB -> Utils.basicBlockContainsStmtStype(predBB, SSAConditionalBranchInstruction.class)))
                    {
                        // System.out.println("Here");

                        // Find the ConditionalBranch Inst, this is the real position of fault injection
                        InducedNoExceptionCFG.BasicBlock branchBB = predBBInclFallthrough.stream()
                                .filter(predBB -> Utils.basicBlockContainsStmtStype(predBB, SSAConditionalBranchInstruction.class)).findAny().get();
                        SSAInstruction branchInst = Streams.stream(branchBB).filter(inst -> Utils.isStmtType(inst, SSAConditionalBranchInstruction.class)).findAny().get();

                        // Find line number of the throw statement
                        List<SSAInstruction> throwStmts = Streams.stream(bb).filter(inst -> Utils.isStmtType(inst, SSAThrowInstruction.class)).collect(Collectors.toList());

                        Debugger.println(bb.toString());
                        Debugger.println("Throw Stmt Count: " + throwStmts.size());

                        for (SSAInstruction throwStmt : throwStmts)
                        {
                            Debugger.println("Throw Stmt: " + throwStmt);
                            Debugger.println("Exception Source is Caught: " + isThrowingCaughtExceptions(methodIR, (SSAThrowInstruction) throwStmt));

                            // Remove the throw statements where the source exception is caught (if statement in the finally block)
                            // TODO: May cause false negative if the exception is rethrown
                            if (isThrowingCaughtExceptions(methodIR, (SSAThrowInstruction) throwStmt))
                                continue;
                            // Remove assert statements, which is also branch + throw
                            if (isThrowingAssertionError(methodIR, (SSAThrowInstruction) throwStmt))
                                continue;
                            TypeInference tyInf = TypeInference.make(methodIR, true);
                            IClass throwType = tyInf.getType(throwStmt.getUse(0)).getType();
                            Debugger.println("Def Type: " + Utils.getFullClassName(throwType));
                            ExceptionInjectionPoint eh = new ExceptionInjectionPoint(clazz, method, Utils.getSrcLineNumberBySSAInst(methodIR, branchInst.iIndex()), ExceptionInjectionPoint.Type.THROW_EXCEPTION);
                            List<CtorConstructionStep> ctorSteps = throwCtorConstructSteps(throwType);
                            eh.setThrowableSteps(ctorSteps);
                            result.add(eh);
                        }
                    }
                }
            }
        }
        return Lists.newArrayList(result);
    }

    protected ControlFlowGraph<SSAInstruction, InducedNoExceptionCFG.BasicBlock> sanitizeSSACFG(SSACFG methodCFG)
    {
        // MutableCFG<SSAInstruction, ISSABasicBlock> sanitizedCFG = MutableCFG.copyFrom(methodCFG);
        // sanitizedCFG.removeNodeAndEdges(sanitizedCFG.entry());
        // sanitizedCFG.removeNodeAndEdges(sanitizedCFG.exit());
        InducedNoExceptionCFG result = new InducedNoExceptionCFG(methodCFG.getInstructions(), methodCFG.getMethod(), com.ibm.wala.ipa.callgraph.impl.Everywhere.EVERYWHERE);
        return result;
    }

    protected List<InducedNoExceptionCFG.BasicBlock> getCFGPredBlocksFollowFallThrough(ControlFlowGraph<SSAInstruction, InducedNoExceptionCFG.BasicBlock> cfg,
            InducedNoExceptionCFG.BasicBlock bb)
    {
        // Debugger.println("FALLTHROUGH Analysis at: " + bb.getNumber());
        List<InducedNoExceptionCFG.BasicBlock> ret = Lists.newLinkedList(Utils.toIterable(cfg.getPredNodes(bb)));
        Queue<InducedNoExceptionCFG.BasicBlock> explorationQueue = new LinkedList<>();
        Set<Integer> visited = new HashSet<>();
        Set<Integer> added = new HashSet<>();
        ret.forEach(e -> added.add(e.getNumber()));
        for (InducedNoExceptionCFG.BasicBlock predBB : ret)
        {
            List<InducedNoExceptionCFG.BasicBlock> predSuccBB = Lists.newLinkedList(Utils.toIterable(cfg.getSuccNodes(predBB)));
            if (predSuccBB.size() == 1)
                explorationQueue.add(predBB);
        }
        while (!explorationQueue.isEmpty())
        {
            InducedNoExceptionCFG.BasicBlock currentBB = explorationQueue.poll();
            // Debugger.println("Exploring: " + currentBB.getNumber());
            visited.add(currentBB.getNumber());
            List<InducedNoExceptionCFG.BasicBlock> preds = Lists.newLinkedList(Utils.toIterable(cfg.getPredNodes(currentBB)));
            for (InducedNoExceptionCFG.BasicBlock e : preds)
            {
                if (added.contains(e.getNumber()))
                    continue;
                ret.add(e);
                added.add(e.getNumber());
                List<InducedNoExceptionCFG.BasicBlock> predSuccBB = Lists.newLinkedList(Utils.toIterable(cfg.getSuccNodes(e)));
                if ((predSuccBB.size() == 1) && (!visited.contains(e.getNumber())))
                    explorationQueue.add(e);
            }
        }
        return ret;
    }

}
