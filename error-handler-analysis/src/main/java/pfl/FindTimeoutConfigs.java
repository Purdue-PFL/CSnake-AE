package pfl;

import java.io.File;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

import javax.swing.plaf.basic.BasicCheckBoxMenuItemUI;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.checkerframework.checker.units.qual.radians;
import org.jgrapht.Graph;
import org.jgrapht.Graphs;
import org.jgrapht.alg.connectivity.ConnectivityInspector;
import org.jgrapht.graph.AsSubgraph;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.graph.EdgeReversedGraph;
import org.jgrapht.traverse.TopologicalOrderIterator;

import com.google.common.collect.ComparisonChain;
import com.google.common.collect.Lists;
import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import com.ibm.wala.cast.ir.ssa.AstIRFactory;
import com.ibm.wala.cast.java.ipa.callgraph.JavaSourceAnalysisScope;
import com.ibm.wala.cast.java.ipa.slicer.AstJavaSlicer;
import com.ibm.wala.cast.java.translator.jdt.ecj.ECJClassLoaderFactory;
import com.ibm.wala.classLoader.ClassLoaderFactoryImpl;
import com.ibm.wala.classLoader.Language;
import com.ibm.wala.classLoader.SourceDirectoryTreeModule;
import com.ibm.wala.dataflow.graph.BitVectorSolver;
import com.ibm.wala.examples.analysis.dataflow.ContextInsensitiveReachingDefs;
import com.ibm.wala.fixedpoint.impl.AbstractFixedPointSolver;
import com.ibm.wala.ipa.callgraph.AnalysisCache;
import com.ibm.wala.ipa.callgraph.AnalysisCacheImpl;
import com.ibm.wala.ipa.callgraph.AnalysisOptions;
import com.ibm.wala.ipa.callgraph.AnalysisScope;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.CallGraph;
import com.ibm.wala.ipa.callgraph.IAnalysisCacheView;
import com.ibm.wala.ipa.callgraph.impl.AllApplicationEntrypoints;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ipa.callgraph.propagation.PointerAnalysis;
import com.ibm.wala.ipa.callgraph.propagation.SSAPropagationCallGraphBuilder;
import com.ibm.wala.ipa.cfg.BasicBlockInContext;
import com.ibm.wala.ipa.cfg.ExplodedInterproceduralCFG;
import com.ibm.wala.ipa.cha.ClassHierarchy;
import com.ibm.wala.ipa.cha.ClassHierarchyFactory;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.ipa.slicer.NormalReturnCallee;
import com.ibm.wala.ipa.slicer.NormalReturnCaller;
import com.ibm.wala.ipa.slicer.NormalStatement;
import com.ibm.wala.ipa.slicer.SDG;
import com.ibm.wala.ipa.slicer.Slicer;
import com.ibm.wala.ipa.slicer.Statement;
import com.ibm.wala.ipa.slicer.Slicer.ControlDependenceOptions;
import com.ibm.wala.ipa.slicer.Slicer.DataDependenceOptions;
import com.ibm.wala.ipa.slicer.thin.CISlicer;
import com.ibm.wala.properties.WalaProperties;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.SSAConditionalBranchInstruction;
import com.ibm.wala.ssa.SSAGetInstruction;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAInvokeInstruction;
import com.ibm.wala.ssa.SSAPutInstruction;
import com.ibm.wala.ssa.analysis.IExplodedBasicBlock;
import com.ibm.wala.types.ClassLoaderReference;
import com.ibm.wala.types.FieldReference;
import com.ibm.wala.util.CancelException;
import com.ibm.wala.util.graph.NumberedGraph;
import com.ibm.wala.util.graph.impl.GraphInverter;
import com.ibm.wala.util.intset.IntIterator;
import com.ibm.wala.util.intset.IntSet;

import pfl.analysis_util.TimeoutConfigStatement;
import pfl.util.ProgressMonitor;
import pfl.util.Utils;

public class FindTimeoutConfigs
{
    static AnalysisOptions analysisOptions = new AnalysisOptions();
    static AnalysisCache analysisCache = new AnalysisCacheImpl();

    public static void main(String[] args) throws Exception
    {
        Options options = new Options();
        options.addOption("cp", "classpath", true, "Classpath");
        options.addOption("o", "output", true, "Output file");
        options.addOption("srcpath", true, "Source code path, : splitted");
        CommandLineParser parser = new DefaultParser();
        CommandLine cmd = parser.parse(options, args);
        String classpath = cmd.getOptionValue("classpath");
        String outputPath = cmd.getOptionValue("output");
        String srcpath = cmd.getOptionValue("srcpath");

        WalaModel model = new WalaModel(classpath);
        ClassHierarchy cha;
        if (srcpath != null)
        {
            // Source Analysis Mode, DOES NOT WORK, DO NOT USE
            AnalysisScope scope = new JavaSourceAnalysisScope();
            String[] stdlibs = WalaProperties.getJ2SEJarFiles();
            for (String lib: stdlibs)
            {
                scope.addToScope(ClassLoaderReference.Primordial, new JarFile(lib));
            }
            for (String appLib: classpath.split(":"))
            {
                scope.addToScope(ClassLoaderReference.Application, new JarFile(appLib));
            }
            for (String src: srcpath.split(":"))
            {
                scope.addToScope(JavaSourceAnalysisScope.SOURCE, new SourceDirectoryTreeModule(new File(src)));
            }
            cha = ClassHierarchyFactory.makeWithRoot(scope, new ECJClassLoaderFactory(model.getScope().getExclusions()));
            // cha = ClassHierarchyFactory.makeWithRoot(scope, new ClassLoaderFactoryImpl(model.getScope().getExclusions()));
            analysisOptions = new AnalysisOptions(scope, new AllApplicationEntrypoints(scope, cha));
            analysisCache = new AnalysisCacheImpl(AstIRFactory.makeDefaultFactory());
        }
        else 
        {
            // Jar analysis mode
            analysisOptions = new AnalysisOptions(model.getScope(), new AllApplicationEntrypoints(model.getScope(), model.getCha()));
            cha = model.getCha();
        }
        SSAPropagationCallGraphBuilder builder = com.ibm.wala.ipa.callgraph.impl.Util.makeVanillaZeroOneCFABuilder(Language.JAVA, analysisOptions, analysisCache, cha);
        CallGraph cg = builder.makeCallGraph(analysisOptions, new ProgressMonitor());
        PointerAnalysis<InstanceKey> pa = builder.getPointerAnalysis();
        // SDG<InstanceKey> sdg = new SDG<>(cg, pa, DataDependenceOptions.NONE, ControlDependenceOptions.NONE);
        System.out.println("Callgraph Built");

        // for (CGNode cgn: cg)
        // {
        //     if (Utils.getShortMethodName(cgn.getMethod()).equals("main"))
        //     {
        //         for (SSAInstruction inst: cgn.getIR().getInstructions())
        //         {
        //             System.out.println(inst);
        //         }
        //     }
        // }

        Set<Statement> timeoutCheckConditionInsts = cg.stream().parallel().map(cgn -> findCallToCurrentTime(cgn)).flatMap(l -> l.stream())
                .map(stmt ->
                {
                    // System.out.println(stmt);
                    try
                    {
                        Collection<Statement> slice = Slicer.computeForwardSlice(stmt, cg, pa, DataDependenceOptions.NO_BASE_NO_HEAP_NO_EXCEPTIONS, ControlDependenceOptions.NONE);
                        // System.out.println(slice);
                        return slice;
                    }
                    catch (IllegalArgumentException | CancelException e)
                    {
                        return new ArrayList<Statement>();
                    }
                }).flatMap(l -> l.stream()).filter(stmt ->
                {
                    if (stmt.getKind() != Statement.Kind.NORMAL) return false;
                    NormalStatement ns = (NormalStatement) stmt;
                    return !shouldIgnoreStmt(ns) && ns.getInstruction() instanceof SSAConditionalBranchInstruction;
                }).collect(Collectors.toSet());

        // ExplodedInterproceduralCFG icfg = ExplodedInterproceduralCFG.make(cg);
        // ContextInsensitiveReachingDefs reachingDefs = new ContextInsensitiveReachingDefs(icfg, cha);
        // BitVectorSolver<BasicBlockInContext<IExplodedBasicBlock>> solver = reachingDefs.analyze();
        // for (Statement s: timeoutCheckConditionInsts)
        // {
        //     NormalStatement timeoutCheckConditionInst = (NormalStatement) s;
        //     String cgnName = Utils.getShortMethodName(timeoutCheckConditionInst.getNode().getMethod());
        //     SSAInstruction ssaInst = timeoutCheckConditionInst.getInstruction();
        //     for (BasicBlockInContext<IExplodedBasicBlock> bb: icfg)
        //     {
        //         if (!bb.getNode().toString().contains(cgnName)) continue;
        //         IExplodedBasicBlock delegate = bb.getDelegate();
        //         if (!ssaInst.equals(delegate.getInstruction())) continue;
        //         IntSet solution = solver.getOut(bb).getValue();
        //         System.out.println("Timeout Check: " + s);
        //         if (solution == null) continue;
        //         IntIterator intIterator = solution.intIterator();
        //         List<com.ibm.wala.util.collections.Pair<CGNode, Integer>> applicationDefs = new ArrayList<>();
        //         while (intIterator.hasNext()) 
        //         {
        //           int next = intIterator.next();
        //           final com.ibm.wala.util.collections.Pair<CGNode, Integer> def = reachingDefs.getNodeAndInstrForNumber(next);
        //           if (def.fst.getMethod().getDeclaringClass().getClassLoader().getReference().equals(ClassLoaderReference.Application) && !Utils.insideJRELibrary(def.fst.getMethod().getDeclaringClass())) {
        //             System.out.println(def.fst);
        //             // applicationDefs.add(def);
        //           }
        //         }
        //     }
        // }

        // Get the definition to each timeout conditional check
        // This is to find the definition of the timeout configuration.
        // Algo: Backward slice from the timeout check, then find the topological roots (def) reaching the timeout check in the SDG
        Map<TimeoutConfigStatement, Set<TimeoutConfigStatement>> defToTimeoutChecks = new ConcurrentHashMap<>();
        ExecutorService es = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
        Consumer<Statement> processor = timeoutCheckConditionInst ->
        {
            SDG<InstanceKey> sdg = new SDG<>(cg, pa, DataDependenceOptions.NO_BASE_NO_HEAP_NO_EXCEPTIONS, ControlDependenceOptions.NONE);
            try
            {
                Collection<Statement> bwdSlice = Slicer.computeBackwardSlice(sdg, timeoutCheckConditionInst);
                // Collection<Statement> bwdSlice = AstJavaSlicer.computeBackwardSlice(sdg, Collections.singleton(timeoutCheckConditionInst));
                Set<TimeoutConfigStatement> topoRootToTimeoutCheck = getSDGSliceTopoRoots(sdg, bwdSlice);
                defToTimeoutChecks.put(TimeoutConfigStatement.build((NormalStatement) timeoutCheckConditionInst, false), topoRootToTimeoutCheck);
                // System.out.println("Timeout Check: " + timeoutCheckConditionInst);
                // System.out.println("Incoming Defs: " + topoRootToTimeoutCheck);
                // for (Statement n: topoRootToTimeoutCheck)
                // {
                //     if (n.getKind() != Statement.Kind.NORMAL) continue;
                //     NormalStatement ns = (NormalStatement) n;
                //     System.out.println(ImmutablePair.of(Utils.getFullMethodName(ns.getNode().getMethod()), Utils.getSrcLineNumberBySSAInst(ns.getNode().getIR(), ns.getInstructionIndex())));
                // }
            }
            catch (IllegalArgumentException | CancelException e)
            {
                e.printStackTrace();
            }
        };
        timeoutCheckConditionInsts.forEach(e -> es.submit(() -> processor.accept(e)));
        es.shutdown();
        es.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
                
        List<TimeoutConfigStatement> timeoutCheckConditionSrcs = timeoutCheckConditionInsts.stream().map(stmt -> TimeoutConfigStatement.build((NormalStatement) stmt, false)).sorted().collect(Collectors.toList());

        // for (Statement stmt: timeoutCheckConditionInsts)
        // {
        // NormalStatement ns = (NormalStatement) stmt;
        // System.out.println(ns.getInstruction());
        // System.out.println(Utils.getSrcLineNumberBySSAInst(ns.getNode().getIR(), ns.getInstructionIndex()));
        // Collection<Statement> bw = Slicer.computeBackwardSlice(stmt, cg, pa, DataDependenceOptions.NO_BASE_NO_HEAP_NO_EXCEPTIONS, ControlDependenceOptions.NONE);
        // System.out.println(bw);
        // }

        // System.out.println(timeoutCheckConditionInsts);
        Set<TimeoutConfigStatement> timeoutDefs = defToTimeoutChecks.values().stream().flatMap(s -> s.stream()).collect(Collectors.toSet());
        boolean shouldExit = false;
        while (!shouldExit)
        {
            Set<TimeoutConfigStatement> timeoutDefsGetExpanded = ConcurrentHashMap.newKeySet();
            Consumer<TimeoutConfigStatement> expander = timeoutCheckStmt -> 
            {
                try 
                {
                    // System.out.println(timeoutCheckStmt);
                    if (timeoutCheckStmt.type != TimeoutConfigStatement.Type.FIELD_ACCESS)
                    {
                        timeoutDefsGetExpanded.add(timeoutCheckStmt);
                        return;
                    }
                    Set<Statement> matchingPuts = cg.stream().parallel().map(cgn -> findMatchingPut(cgn, timeoutCheckStmt)).flatMap(l -> l.stream()).collect(Collectors.toSet());
                    // System.out.println(matchingPuts);
                    SDG<InstanceKey> sdg = new SDG<>(cg, pa, DataDependenceOptions.NO_BASE_NO_HEAP_NO_EXCEPTIONS, ControlDependenceOptions.NONE);
                    Collection<Statement> bwdSlice = Slicer.computeBackwardSlice(sdg, matchingPuts);
                    // bwdSlice.forEach(e -> System.out.println("   " + e));
                    Set<TimeoutConfigStatement> expandedDefs = getSDGSliceTopoRoots(sdg, bwdSlice);
                    expandedDefs.forEach(e -> e.setExpandedFrom(timeoutCheckStmt));
                    timeoutDefsGetExpanded.addAll(expandedDefs);
                    // topoRootToTimeoutCheck.forEach(e -> System.out.println("||| " + e));
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }
            };
            ExecutorService es2 = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
            timeoutDefs.stream().forEach(e -> es2.submit(() -> expander.accept(e)));
            es2.shutdown();
            es2.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);

            shouldExit = timeoutDefsGetExpanded.equals(timeoutDefs);
            timeoutDefs = timeoutDefsGetExpanded;
        }
        Map<String, Object> outMap = new LinkedHashMap<>();
        List<TimeoutConfigStatement> timeoutDefsGetExpandedT = timeoutDefs.stream().sorted().collect(Collectors.toList());
        outMap.put("Timeout Defs", timeoutDefsGetExpandedT);
        outMap.put("Timeout Checks", timeoutCheckConditionSrcs);
        // outMap.put("Timeout Check Defs Raw", defToTimeoutChecks);
        try (PrintWriter pw = new PrintWriter(outputPath))
        {
            new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().registerTypeAdapter(TimeoutConfigStatement.class, TimeoutConfigStatement.jsonSerializer)
                .create().toJson(outMap, pw);
            pw.flush();
        }
    }

    public static Set<TimeoutConfigStatement> getSDGSliceTopoRoots(SDG<InstanceKey> sdg, Collection<Statement> sliceStmts)
    {
        Graph<Statement, DefaultEdge> subSDG = new DefaultDirectedGraph<>(DefaultEdge.class);
        for (Statement n: sliceStmts)
        {
            subSDG.addVertex(n);
            for (Statement succ: Utils.toIterable(sdg.getSuccNodes(n)))
            {
                if (sliceStmts.contains(succ))
                {
                    subSDG.addVertex(succ);
                    subSDG.addEdge(n, succ);
                }
            }
        }
        TopologicalOrderIterator<Statement, DefaultEdge> topoIter = new TopologicalOrderIterator<>(subSDG);
        Set<TimeoutConfigStatement> r = new HashSet<>();
        for (Statement n: Utils.toIterable(topoIter))
        {
            if (Graphs.predecessorListOf(subSDG, n).size() == 0)
            {
                if (n.getKind() == Statement.Kind.NORMAL)
                {
                    r.add(TimeoutConfigStatement.build((NormalStatement) n, true));
                }
            }
            else 
            {
                break;
            }
        }
        return r;
    }

    public static ImmutablePair<String, Integer> getNormalStatementLineNumber(NormalStatement ns)
    {
        return ImmutablePair.of(Utils.getFullMethodName(ns.getNode().getMethod()), Utils.getSrcLineNumberBySSAInst(ns.getNode().getIR(), ns.getInstructionIndex()));
    }

    public static boolean shouldIgnoreStmt(NormalStatement stmt)
    {
        return Utils.insideJRELibrary(stmt.getNode().getMethod().getDeclaringClass()) || Utils.getShortMethodName(stmt.getNode().getMethod()).equals("equals")
                || Utils.getFullMethodName(stmt.getNode().getMethod()).contains("org.apache.hadoop.hdfs.protocol.proto");
    }

    public static List<Statement> findBranchStmts(CGNode n)
    {
        if (Utils.insideJRELibrary(n.getMethod().getDeclaringClass())) return Lists.newArrayList();
        IR ir = n.getIR();
        if (ir == null) return Lists.newArrayList();
        List<Statement> r = new ArrayList<>();
        for (SSAInstruction inst : ir.getInstructions())
        {
            if (!(inst instanceof SSAConditionalBranchInstruction)) continue;
            r.add(new NormalStatement(n, inst.iIndex()));
        }
        return r;
    }

    public static List<Statement> findMatchingPut(CGNode n, TimeoutConfigStatement stmt)
    {
        if (Utils.insideJRELibrary(n.getMethod().getDeclaringClass())) return Lists.newArrayList();
        IR ir = n.getIR();
        if (ir == null) return Lists.newArrayList();
        List<Statement> r = new ArrayList<>();
        for (SSAInstruction inst : ir.getInstructions())
        {
            if (!(inst instanceof SSAPutInstruction)) continue;
            SSAPutInstruction putInst = (SSAPutInstruction) inst;
            FieldReference field = putInst.getDeclaredField();
            if (Utils.getFullClassName(field.getDeclaringClass()).equals(stmt.clazz) && field.getName().toString().equals(stmt.fieldName))
            {            
                r.add(new NormalStatement(n, inst.iIndex()));
            }
        }
        return r;
    }

    public static List<Statement> findCallToCurrentTime(CGNode n)
    {
        IR ir = n.getIR();
        if (ir == null) return Lists.newArrayList();
        List<Statement> r = new ArrayList<>();
        for (SSAInstruction inst : ir.getInstructions())
        {
            if (!(inst instanceof SSAInvokeInstruction)) continue;
            SSAInvokeInstruction invoke = (SSAInvokeInstruction) inst;
            String invokeTarget = Utils.getFullMethodName(invoke.getDeclaredTarget());
            if (invokeTarget.contains("System.currentTimeMillis") || invokeTarget.contains("System.nanoTime") || invokeTarget.contains("Instant.now"))
            {
                r.add(new NormalReturnCaller(n, inst.iIndex()));
                // r.add(new NormalStatement(n, inst.iIndex()));
            }
        }
        return r;
    }

    public static boolean isCurrentTimeMillisInvoke(Statement s)
    {
        if (s.getKind() != Statement.Kind.NORMAL_RET_CALLER) return false;
        NormalReturnCaller ns = (NormalReturnCaller) s;
        SSAInstruction inst = ns.getInstruction();
        if (!(inst instanceof SSAInvokeInstruction)) return false;
        SSAInvokeInstruction invokeInst = (SSAInvokeInstruction) inst;
        String invokeTarget = Utils.getFullMethodName(invokeInst.getDeclaredTarget());
        return invokeTarget.contains("System.currentTimeMillis") || invokeTarget.contains("System.nanoTime") || invokeTarget.contains("Instant.now");
    }
}
