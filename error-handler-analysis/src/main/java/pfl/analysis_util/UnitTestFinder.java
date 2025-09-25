package pfl.analysis_util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import org.apache.commons.lang3.tuple.ImmutablePair;
import org.jgrapht.Graph;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.graph.concurrent.AsSynchronizedGraph;

import com.google.common.base.Predicates;
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
import com.ibm.wala.types.annotations.Annotation;

import pfl.WalaModel;
import pfl.graph.ConcurrentConnectivityInspector;
import pfl.util.Utils;

public class UnitTestFinder 
{
    private WalaModel model;
    private String targetSystem;
    private static Map<String, Predicate<IClass>> testClassFilter = new HashMap<>();
    private static Map<String, Predicate<SSAInstruction>> testSSAInstFilter = new HashMap<>();

    // Some tests are defined in the base class
    // For junit, we should run the test in the child class, but for byteman, we should intercept the test method at the base class
    public Map<String, String> testDeclaringClassMapper = new HashMap<>(); // testMethod.getClass() -> testMethod.getDeclaringClass()

    static 
    {
        testClassFilter.put("HDFS292", (IClass clazz) -> Utils.getShortClassName(clazz).startsWith("Test"));
        testClassFilter.put("HBase-8389", Predicates.alwaysTrue());
        testClassFilter.put("HBase260", (IClass clazz) -> Utils.getShortClassName(clazz).startsWith("Test"));
        testClassFilter.put("Cassandra500", (IClass clazz) -> Utils.getShortClassName(clazz).endsWith("Test"));
        testClassFilter.put("Flink120", (IClass clazz) -> 
        {
            String shortClassName = Utils.getShortClassName(clazz);
            return shortClassName.endsWith("Test") || shortClassName.endsWith("TCase");
        });
        testClassFilter.put("OZone140", Predicates.alwaysTrue());
        testClassFilter.put("HDFS341", Predicates.alwaysTrue());

        testSSAInstFilter.put("HDFS292", (SSAInstruction inst) -> isMiniDFSCluster(inst));
        testSSAInstFilter.put("HBase-8389", (SSAInstruction inst) -> isMiniHBaseCluster(inst) || isMiniDFSCluster(inst));
        testSSAInstFilter.put("HBase260", (SSAInstruction inst) -> isMiniHBaseCluster(inst));
        testSSAInstFilter.put("Cassandra500", Predicates.alwaysTrue());
        testSSAInstFilter.put("Flink120", (SSAInstruction inst) -> isFlinkMiniCluster(inst));
        testSSAInstFilter.put("OZone140", (SSAInstruction inst) -> isMiniOZoneCluster(inst));
        testSSAInstFilter.put("HDFS341", (SSAInstruction inst) -> isMiniDFSCluster(inst));
    }

    public UnitTestFinder(WalaModel model)
    {
        this.model = model;
        targetSystem = "HDFS292";
    }

    public UnitTestFinder(WalaModel model, String targetSystem)
    {
        this.model = model;
        this.targetSystem = targetSystem;
    }

    public List<String> getTests(List<String> includePrefixes, List<String> excludePrefixes) 
    {
        AnalysisOptions ao = new AnalysisOptions();
        AnalysisCache ac = new AnalysisCacheImpl();
        Set<IClass> clusterTests = new HashSet<>();
        for (IClass clazz: model.getCha())
        {
            if ((!includePrefixes.stream().anyMatch(p -> Utils.getFullClassName(clazz).startsWith(p)))
                    || (excludePrefixes.stream().anyMatch(p -> Utils.getFullClassName(clazz).startsWith(p))))
                continue;
            if (!testClassFilter.get(targetSystem).test(clazz))
                continue;
            for (IMethod method: clazz.getAllMethods())
            {
                if (Utils.insideJRELibrary(method.getDeclaringClass()))
                    continue;
                IR methodIR = ac.getSSACache().findOrCreateIR(method, Everywhere.EVERYWHERE, ao.getSSAOptions());
                if (methodIR == null) continue;
                if (Arrays.stream(methodIR.getInstructions()).anyMatch(inst -> testSSAInstFilter.get(targetSystem).test(inst)))
                {
                    clusterTests.add(clazz);
                    break;
                }
            }
        }
        List<String> unitTests = new ArrayList<>();
        for (IClass testClass: clusterTests)
        {
            if (!clusterTests.contains(testClass))
                continue;
            for (IMethod method: testClass.getAllMethods())
            {
                Collection<Annotation> annotations = method.getAnnotations();
                if (annotations.stream().anyMatch(e -> isJunitTest(Utils.getFullClassName(e.getType()))))
                {   
                    IClass testMethodDeclaringClass = method.getDeclaringClass();
                    if (!testClass.equals(testMethodDeclaringClass))
                    {
                        testDeclaringClassMapper.put(Utils.getFullClassName(testClass) + "#" + Utils.getShortMethodName(method), Utils.getFullClassName(testMethodDeclaringClass) + "#" + Utils.getShortMethodName(method));
                    }
                    unitTests.add(Utils.getFullClassName(testClass) + "#" + Utils.getShortMethodName(method));
                }
            }
        }
        return unitTests;
    }

    private static boolean isJunitTest(String typeName)
    {
        return typeName.contains("org.junit.Test") || typeName.contains("org.junit.jupiter.api.Test") || typeName.contains("org.junit.jupiter.params.ParameterizedTest");
    }

    private static boolean isMiniDFSCluster(SSAInstruction inst)
    {
        return returnTypeMatch(inst, "MiniDFSCluster");
    }   

    private static boolean isMiniHBaseCluster(SSAInstruction inst)
    {
        return returnTypeMatch(inst, "MiniHBaseCluster");
    }

    private static boolean isFlinkMiniCluster(SSAInstruction inst)
    {
        return returnTypeMatch(inst, "MiniCluster");
    }

    private static boolean isMiniOZoneCluster(SSAInstruction inst)
    {
        return returnTypeMatch(inst, "MiniOzoneCluster") || returnTypeMatch(inst, "MiniOzoneClusterImpl");
    }
    
    private static boolean returnTypeMatch(SSAInstruction inst, String typeName)
    {
        if (inst instanceof SSANewInstruction)
        {
            TypeReference tr = ((SSANewInstruction) inst).getConcreteType();
            return Utils.getFullClassName(tr).contains(typeName);
        }
        else if (inst instanceof SSAInvokeInstruction)
        {
            TypeReference tr = ((SSAInvokeInstruction) inst).getDeclaredResultType();
            return Utils.getFullClassName(tr).contains(typeName);
        }
        else
        {
            return false;
        }
    }
}
