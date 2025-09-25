package pfl.dataflow;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.stream.Collectors;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.classLoader.Language;
import com.ibm.wala.ipa.callgraph.AnalysisCache;
import com.ibm.wala.ipa.callgraph.AnalysisCacheImpl;
import com.ibm.wala.ipa.callgraph.AnalysisOptions;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.CallGraph;
import com.ibm.wala.ipa.callgraph.Entrypoint;
import com.ibm.wala.ipa.callgraph.impl.DefaultEntrypoint;
import com.ibm.wala.ipa.callgraph.impl.Everywhere;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ipa.callgraph.propagation.PointerAnalysis;
import com.ibm.wala.ipa.callgraph.propagation.SSAPropagationCallGraphBuilder;
import com.ibm.wala.ipa.slicer.NormalStatement;
import com.ibm.wala.ipa.slicer.Slicer;
import com.ibm.wala.ipa.slicer.Slicer.ControlDependenceOptions;
import com.ibm.wala.ipa.slicer.Slicer.DataDependenceOptions;
import com.ibm.wala.ipa.slicer.Statement;
import com.ibm.wala.ipa.slicer.StatementWithInstructionIndex;
import com.ibm.wala.ssa.AllIntegerDueToBranchePiPolicy;
import com.ibm.wala.ssa.DefUse;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.SSAConditionalBranchInstruction;
import com.ibm.wala.ssa.SSAGetInstruction;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAInvokeInstruction;
import com.ibm.wala.ssa.SSAOptions;
import com.ibm.wala.ssa.SSAReturnInstruction;
import com.ibm.wala.ssa.SymbolTable;
import com.ibm.wala.util.CancelException;

import pfl.WalaModel;
import pfl.util.ProgressMonitor;
import pfl.util.Utils;

public class DFUtils
{
    public static boolean DEBUG = false; 

    public static List<SSAInstruction> getLocalBackwardTaint(IR ir, int iIndex)
    {
        SSAInstruction lastInst = ir.getInstructions()[iIndex];
        DefUse du = new DefUse(ir);
        Queue<SSAInstruction> bfsQueue = new LinkedList<>();
        bfsQueue.add(lastInst);
        Set<SSAInstruction> taintedInsts = new HashSet<>();
        taintedInsts.add(lastInst);
        while (!bfsQueue.isEmpty())
        {
            SSAInstruction curInst = bfsQueue.poll();
            if (DEBUG) System.out.println(curInst);
            List<Integer> allUses = getSSAUses(curInst);
            if (DEBUG) System.out.println("Uses: " + allUses);
            List<SSAInstruction> allDefInsts = allUses.stream().map(e -> du.getDef(e)).collect(Collectors.toList());
            if (DEBUG) System.out.println("Def Insts: " + allDefInsts);
            allDefInsts.forEach(inst ->
            {
                if (inst == null) return;
                if (taintedInsts.contains(inst)) return;
                taintedInsts.add(inst);
                bfsQueue.add(inst);
            });
        }
        return taintedInsts.stream().sorted((i1, i2) -> Integer.compare(i1.iIndex(), i2.iIndex())).collect(Collectors.toList());
    }

    public static List<Integer> getSSAUses(SSAInstruction inst)
    {
        List<Integer> r = new ArrayList<>();
        if (inst == null) return r;
        for (int i = 0; i < inst.getNumberOfUses(); i++)
        {
            r.add(inst.getUse(i));
        }
        return r;
    }

    public static boolean isRetValueUsed(IR ir, SSAInvokeInstruction invokeInst)
    {
        DefUse du = new DefUse(ir);
        int invokeRetDefNum = invokeInst.getReturnValue(0);
        return !du.isUnused(invokeRetDefNum);
    }

    public static AnalysisCache analysisCache = new AnalysisCacheImpl();

    public static Map<IMethod, List<SSAInstruction>> getBackwardSDGInvokeAndGetField(WalaModel model, IMethod target, CallGraph cg, PointerAnalysis<InstanceKey> pa)
    {
        try 
        {
            Map<IMethod, IR> localIRCache = new HashMap<>();
            List<Entrypoint> eps = Lists.newArrayList(new DefaultEntrypoint(target, model.getCha()));
            AnalysisOptions ao = new AnalysisOptions(model.getScope(), eps);
            SSAOptions ssaOptions = SSAOptions.defaultOptions();
            ssaOptions.setPiNodePolicy(new AllIntegerDueToBranchePiPolicy());
            ao.setSSAOptions(ssaOptions);
            // AnalysisCache analysisCache = new AnalysisCacheImpl(ssaOptions);

            IR targetIR = localIRCache.computeIfAbsent(target, k -> analysisCache.getIR(target)); 
            if (DEBUG) System.out.println(targetIR);
            if (targetIR == null) return Maps.newConcurrentMap();
    
            Map<IMethod, Set<SSAInstruction>> rT = Maps.newConcurrentMap();
    
            // SSAPropagationCallGraphBuilder builder = com.ibm.wala.ipa.callgraph.impl.Util.makeZeroCFABuilder(Language.JAVA, ao, analysisCache, model.getCha());
            // CallGraph cg = builder.makeCallGraph(ao);
            // PointerAnalysis<InstanceKey> pa = builder.getPointerAnalysis();
            CGNode cgn = cg.getNode(target, Everywhere.EVERYWHERE);
            // for (CGNode nextCGN: Utils.toIterable(cg.getSuccNodes(cgn)))
            // {
            //     System.out.println("Next: " + nextCGN.getMethod());
            // }
            List<SSAReturnInstruction> retInsts = Arrays.stream(targetIR.getInstructions()).filter(inst -> inst instanceof SSAReturnInstruction)
                    .map(inst -> (SSAReturnInstruction) inst).collect(Collectors.toList());
            for (SSAReturnInstruction retInst : retInsts)
            {
                NormalStatement retStmt = new NormalStatement(cgn, retInst.iIndex());
                Collection<Statement> bwdSlice = Slicer.computeBackwardSlice(retStmt, cg, pa, DataDependenceOptions.NO_BASE_NO_HEAP_NO_EXCEPTIONS,
                        ControlDependenceOptions.NO_INTERPROC_NO_EXCEPTION);
                for (Statement stmt: bwdSlice)
                {
                    // System.out.println(stmt);
                    if (!(stmt instanceof StatementWithInstructionIndex)) continue;
                    SSAInstruction stmtInst = ((StatementWithInstructionIndex) stmt).getInstruction();
                    IMethod stmtMethod = ((StatementWithInstructionIndex) stmt).getNode().getMethod();
                    if (Utils.getFullMethodName(stmtMethod).contains("com.ibm.wala")) continue; // FakeRootClass
                    IR stmtMethodIR = localIRCache.computeIfAbsent(stmtMethod, k -> ((StatementWithInstructionIndex) stmt).getNode().getIR());
                    if ((stmtInst instanceof SSAGetInstruction) || (stmtInst instanceof SSAInvokeInstruction))
                    {
                        Set<SSAInstruction> localTainedInsts = rT.computeIfAbsent(stmtMethod, k -> new HashSet<>());
                        localTainedInsts.add(stmtInst);
                        // Augment SDG with local DefUse because invocations caller variables are not tracked
                        try 
                        {
                            List<SSAInstruction> localBackwardTaint = getLocalBackwardTaint(stmtMethodIR, stmtInst.iIndex());
                            localBackwardTaint.stream().filter(e -> (e instanceof SSAGetInstruction) || (e instanceof SSAInvokeInstruction)).forEach(e -> localTainedInsts.add(e));
                        }
                        catch (ArrayIndexOutOfBoundsException e)
                        {
                            e.printStackTrace();
                        }
                        catch (Exception e)
                        {
                            e.printStackTrace();
                            System.out.println("Target: " + target);
                            System.out.println(stmtMethodIR);
                            System.out.println(stmt);
                            System.out.println(stmtInst);
                        }
                    }
                }
            }
    
            Map<IMethod, List<SSAInstruction>> r = Maps.newConcurrentMap();
            for (IMethod key: rT.keySet())
            {
                List<SSAInstruction> valueList = new ArrayList<>(rT.get(key));
                valueList.sort(Comparator.comparing(SSAInstruction::iIndex));
                r.put(key, valueList);
            }
    
            if (DEBUG)
            {
                for (IMethod key: r.keySet())
                {
                    System.out.println(key + ": ");
                    for (SSAInstruction inst: r.get(key))
                    {
                        System.out.println("    " + inst);
                    }
                }
            }
    
            return r;
        }
        catch (IllegalArgumentException | CancelException e)
        {
            return Maps.newConcurrentMap();
        }

    }

    public static boolean isReturningConstantBoolean(IR ir)
    {
        SymbolTable st = ir.getSymbolTable();   
        Set<Boolean> realRetValues = new HashSet<>();
        for (SSAInstruction inst: Utils.toIterable(ir.iterateAllInstructions()))
        {
            if (!(inst instanceof SSAReturnInstruction)) continue;
            SSAReturnInstruction retInst = (SSAReturnInstruction) inst;
            int retValueNum = retInst.getUse(0);
            if (!st.isBooleanOrZeroOneConstant(retValueNum)) return false;
            if (st.isZeroOrFalse(retValueNum))
                realRetValues.add(false);
            else if (st.isOneOrTrue(retValueNum))
                realRetValues.add(true);
        }
        return realRetValues.size() == 1;
    }
}
