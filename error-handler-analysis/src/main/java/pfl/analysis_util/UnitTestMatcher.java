package pfl.analysis_util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.lang3.tuple.ImmutablePair;
import org.jgrapht.Graph;
import org.jgrapht.GraphPath;
import org.jgrapht.alg.interfaces.ShortestPathAlgorithm;
import org.jgrapht.alg.shortestpath.BFSShortestPath;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.graph.DirectedPseudograph;
import org.jgrapht.graph.concurrent.AsSynchronizedGraph;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.classLoader.Language;
import com.ibm.wala.ipa.callgraph.AnalysisCache;
import com.ibm.wala.ipa.callgraph.AnalysisCacheImpl;
import com.ibm.wala.ipa.callgraph.AnalysisOptions;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.CallGraph;
import com.ibm.wala.ipa.callgraph.CallGraphBuilderCancelException;
import com.ibm.wala.ipa.callgraph.Entrypoint;
import com.ibm.wala.ipa.callgraph.impl.DefaultEntrypoint;
import com.ibm.wala.ipa.callgraph.impl.Everywhere;
import com.ibm.wala.ipa.callgraph.propagation.SSAPropagationCallGraphBuilder;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAInvokeInstruction;
import com.ibm.wala.ssa.SSANewInstruction;
import com.ibm.wala.types.TypeReference;

import pfl.WalaModel;
import pfl.graph.ConcurrentConnectivityInspector;
import pfl.util.Utils;

public class UnitTestMatcher
{
    private WalaModel model;

    public UnitTestMatcher(WalaModel model)
    {
        this.model = model;
    }

    public List<ErrorHandler> match(List<ErrorHandler> ehs) throws CallGraphBuilderCancelException
    {
        // CallGraph cg = model.getCallGraph();

        // Only use entrypoints from unit tests to construct a call graph
        List<Entrypoint> entrypoints = new ArrayList<>();
        for (IClass clazz : model.getCha())
        {
            if (Utils.insideJRELibrary(clazz))
                continue;
            if (!Utils.getShortClassName(clazz).startsWith("Test"))
                continue;
            for (IMethod method : clazz.getAllMethods())
            {
                entrypoints.add(new DefaultEntrypoint(method, model.getCha()));
            }
        }
        AnalysisOptions o = new AnalysisOptions(model.getScope(), entrypoints);
        o.setReflectionOptions(AnalysisOptions.ReflectionOptions.APPLICATION_GET_METHOD);
        // SSAPropagationCallGraphBuilder builder = com.ibm.wala.ipa.callgraph.impl.Util.makeZeroCFABuilder(Language.JAVA, o, new AnalysisCacheImpl(),cha,scope);
        SSAPropagationCallGraphBuilder builder2 = com.ibm.wala.ipa.callgraph.impl.Util.makeZeroOneCFABuilder(Language.JAVA, o, new AnalysisCacheImpl(), model.getCha(),
                model.getScope());
        CallGraph cg = builder2.makeCallGraph(o, null);
        System.out.println("Callgraph built");

        // Convert call graph into JGraphT graph
        Graph<CGNode, DefaultEdge> cgT = new AsSynchronizedGraph<>(new DefaultDirectedGraph<>(DefaultEdge.class));
        Map<ImmutablePair<IClass, IMethod>, CGNode> inverseNodeMap = new ConcurrentHashMap<>();
        for (CGNode cgn : cg)
        {
            if (Utils.insideJRELibrary(cgn.getMethod().getDeclaringClass().getName().toString()))
                continue;
            cgT.addVertex(cgn);
            for (CGNode ep2 : Utils.toIterable(cg.getSuccNodes(cgn)))
            {
                cgT.addVertex(ep2);
                cgT.addEdge(cgn, ep2);
            }
            inverseNodeMap.put(getCGNSignature(cgn), cgn);
        }

        // We only need tests with MiniDFSCluster
        Map<CGNode, Boolean> hasMiniDFSCluster = new ConcurrentHashMap<>();
        Set<IClass> testClassHasMiniDFSCluster = new HashSet<>();
        for (CGNode cgn : cg)
        {
            if (Utils.insideJRELibrary(cgn.getMethod().getDeclaringClass().getName().toString()))
                continue;
            if (cgn.getIR() == null) // Native methods
            {
                hasMiniDFSCluster.put(cgn, false);
            }
            else
            {
                if (testClassHasMiniDFSCluster.contains(cgn.getMethod().getDeclaringClass()))
                {
                    hasMiniDFSCluster.put(cgn, true);
                    continue;
                }
                boolean cgnHasMiniDFSCluster = Arrays.stream(cgn.getIR().getInstructions()).anyMatch(inst -> isMiniDFSCluster(inst));
                hasMiniDFSCluster.put(cgn, cgnHasMiniDFSCluster);
                if (cgnHasMiniDFSCluster)
                {
                    testClassHasMiniDFSCluster.add(cgn.getMethod().getDeclaringClass());
                }
            }
        }

        // Add unit tests to error handlers by reachability
        ConcurrentConnectivityInspector<CGNode, DefaultEdge> pathFinder = new ConcurrentConnectivityInspector<>(cgT);
        ehs.parallelStream().forEach(eh ->
        {
            CGNode ehNode = inverseNodeMap.get(ImmutablePair.of(eh.clazz, eh.method));
            if (!cgT.containsVertex(ehNode))
            {
                synchronized (ehs)
                {
                    System.out.println("Missing EHNode:");
                    System.out.println(eh);
                }
                return;
            }
            for (CGNode cgn : cg)
            {
                if (Utils.insideJRELibrary(cgn.getMethod().getDeclaringClass().getName().toString()))
                    continue;
                if (!hasMiniDFSCluster.get(cgn))
                    continue;
                // We only need unit test here
                if (!Utils.getShortClassName(cgn.getMethod().getDeclaringClass()).startsWith("Test"))
                    continue;
                if (pathFinder.pathExists(cgn, ehNode))
                {
                    eh.addTestCase(cgn.getMethod().getDeclaringClass(), cgn.getMethod());
                }
            }
        });
        return ehs;
    }

    private ImmutablePair<IClass, IMethod> getCGNSignature(CGNode cgn)
    {
        return ImmutablePair.of(cgn.getMethod().getDeclaringClass(), cgn.getMethod());
    }

    private boolean isMiniDFSCluster(SSAInstruction inst)
    {
        if (inst instanceof SSANewInstruction)
        {
            TypeReference tr = ((SSANewInstruction) inst).getConcreteType();
            return Utils.getFullClassName(tr).contains("MiniDFSCluster");
        }
        else if (inst instanceof SSAInvokeInstruction)
        {
            TypeReference tr = ((SSAInvokeInstruction) inst).getDeclaredResultType();
            return Utils.getFullClassName(tr).contains("MiniDFSCluster");
        }
        else
        {
            return false;
        }
    }

}
