package pfl.patterns;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.lang3.tuple.ImmutablePair;
import org.jgrapht.Graph;
import org.jgrapht.Graphs;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.graph.DirectedAcyclicGraph;
import org.jgrapht.traverse.TopologicalOrderIterator;

import com.google.common.collect.ComparisonChain;
import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.cha.ClassHierarchy;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.SSAGetCaughtExceptionInstruction;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSANewInstruction;
import com.ibm.wala.ssa.SSAThrowInstruction;
import com.ibm.wala.types.TypeName;
import com.ibm.wala.types.TypeReference;

import pfl.WalaModel;
import pfl.analysis_util.CtorConstructionStep;
import pfl.util.Debugger;
import pfl.util.Utils;

public abstract class ThrowBase 
{
    protected WalaModel model;

    protected IMethod findSimplistCtor(IClass c)
    {
        // Remove any ctors with parameters that are the same as the class it is creating (avoid loops in the type dependency graph)
        List<IMethod> ctors = c.getDeclaredMethods().stream().filter(m -> Utils.getShortMethodName(m).contains("<init>")).filter(m -> !paramTypeContains(m, c.getReference()))
                .collect(Collectors.toList());
        Debugger.println("Ctor count (No loop): " + ctors.size());
        // ctors.removeIf(m -> paramTypeContains(m, c.getReference()));
        // Debugger.println("Ctor count (no loop): " + ctors.size());
        ctors.sort(new Comparator<IMethod>()
        {
            @Override
            public int compare(IMethod lhs, IMethod rhs)
            {
                // We prefer ctors with less parameters and more primitive types
                return ComparisonChain.start().compare(lhs.getNumberOfParameters(), rhs.getNumberOfParameters()).compare(countPrimitiveParams(rhs), countPrimitiveParams(lhs))
                        .result();
            }
        });
        if (ctors.size() > 0)
            return ctors.get(0);
        else
            return null;
    }

    protected boolean paramTypeContains(IMethod m, TypeReference ty)
    {
        Debugger.println("Method: " + m);
        Debugger.println("Params: " + Utils.getMethodParamTypesSkipped(m, 1));
        Debugger.println(Utils.getMethodParamTypesSkipped(m, 1).stream().map(e -> e.equals(ty)).collect(Collectors.toList()).toString());
        return Utils.getMethodParamTypesSkipped(m, 1).stream().anyMatch(e -> e.equals(ty));
    }

    protected long countPrimitiveParams(IMethod m)
    {
        List<TypeReference> paramType = Utils.getMethodParamTypesSkipped(m, 1);
        return paramType.stream().filter(ty -> ty.isPrimitiveType()).count();
    }

    protected IClass findSimplistImplementation(IClass interfaceClass)
    {
        // Simplist: Ctor has smallest number of params && largest number of primitive types
        ClassHierarchy cha = this.model.getCha();
        Set<IClass> implementors = cha.getImplementors(interfaceClass.getReference());
        return findClassWithSimplistCtor(implementors);
    }

    protected IClass findClassWithSimplistCtor(Collection<IClass> clazzs)
    {
        return clazzs.stream().map(c -> ImmutablePair.of(c, findSimplistCtor(c))).filter(e -> e.getRight() != null).min(new Comparator<ImmutablePair<IClass, IMethod>>()
        {
            @Override
            public int compare(ImmutablePair<IClass, IMethod> lhs, ImmutablePair<IClass, IMethod> rhs)
            {
                IMethod lhsm = lhs.getRight();
                IMethod rhsm = rhs.getRight();
                return ComparisonChain.start().compare(lhsm.getNumberOfParameters(), rhsm.getNumberOfParameters()).compare(countPrimitiveParams(lhsm), countPrimitiveParams(rhsm))
                        .result();
            }
        }).get().getLeft();
    }

    protected List<CtorConstructionStep> throwCtorConstructSteps(IClass throwType)
    {
        // First, we get the type dependency graph
        Graph<String, DefaultEdge> typeDependencyGraph = new DirectedAcyclicGraph<>(DefaultEdge.class);
        typeDependencyGraph.addVertex(Utils.getFullClassName(throwType));
        Queue<IClass> q = new LinkedList<>();
        Map<String, IMethod> ctorIMehods = new HashMap<>();
        Map<IClass, IClass> simplistImpls = new HashMap<>();
        if (!Utils.insideJRELibrary(throwType))
            q.add(throwType);
        while (!q.isEmpty())
        {
            IClass currentType = q.poll();
            IMethod ctor = findSimplistCtor(currentType);
            ctorIMehods.put(Utils.getFullClassName(currentType), ctor);
            if (ctor != null)
            {
                for (int i = 1; i < ctor.getNumberOfParameters(); i++)
                {
                    TypeName tn = ctor.getParameterType(i).getName();
                    String param = "java.lang.Object";
                    if (tn.isPrimitiveType())
                    {
                        param = Utils.primitiveToBoxed(tn.toString());
                    }
                    else
                    {
                        IClass paramIClass = throwType.getClassLoader().lookupClass(tn); // OR model.getCha().lookupClass(ctor.getParameterType(i))
                        if (paramIClass != null)
                        {
                            if (paramIClass.isInterface())
                            {
                                IClass simplistImpl = findSimplistImplementation(paramIClass);
                                simplistImpls.put(paramIClass, simplistImpl);
                                paramIClass = simplistImpl;
                            }
                            param = Utils.getFullClassName(paramIClass);
                            // Continue expansion if not a primitive type
                            if (!typeDependencyGraph.containsVertex(param) && !Utils.insideJRELibrary(param))
                            {
                                q.add(paramIClass);
                            }
                        }
                        else // Some (super)class not in the class path
                        {
                            param = "###NOT_EXIST_IN_CLASSPATH###" + tn.toString();
                        }
                    }
                    typeDependencyGraph.addVertex(param);
                    typeDependencyGraph.addEdge(param, Utils.getFullClassName(currentType));
                }
            }
            else
            {
                Debugger.println("Ctor for " + currentType.getName() + " is null");
            }
        }

        // Iterate through the type dependency graph by topological order
        // For each type, go through the ctors again because the graph does not keep the positional information about the ctor parameters
        TopologicalOrderIterator<String, DefaultEdge> topoIter = new TopologicalOrderIterator<>(typeDependencyGraph);
        Map<String, Integer> typeIdxMapper = new HashMap<>();
        int objIdx = 0;
        List<CtorConstructionStep> steps = new ArrayList<>();
        for (String c : Utils.toIterable(topoIter))
        {
            typeIdxMapper.put(c, objIdx);
            objIdx++;
            List<String> preds = Graphs.predecessorListOf(typeDependencyGraph, c);
            CtorConstructionStep step = new CtorConstructionStep(c);
            if (preds.size() > 0)
            {
                IMethod ctorMethod = ctorIMehods.get(c);
                // We have to get the order of the params, so we have to iterate through the params again
                for (int i = 1; i < ctorMethod.getNumberOfParameters(); i++)
                {
                    TypeName tn = ctorMethod.getParameterType(i).getName();
                    String param = "java.lang.Object";
                    if (tn.isPrimitiveType())
                    {
                        param = Utils.primitiveToBoxed(tn.toString());
                    }
                    else
                    {
                        IClass paramIClass = throwType.getClassLoader().lookupClass(tn);
                        if (paramIClass.isInterface())
                        {
                            paramIClass = simplistImpls.get(paramIClass);
                        }
                        if (paramIClass != null)
                        {
                            param = Utils.getFullClassName(paramIClass);
                        }
                        else // Some (super)class not in the class path
                        {
                            param = tn.toString();
                        }
                    }
                    step.addParam(typeIdxMapper.get(param));
                }
            }
            steps.add(step);
        }
        return steps;
    }

    protected boolean isThrowingCaughtExceptions(IR methodIR, SSAThrowInstruction inst)
    {
        SSAInstruction def = Utils.getDefInstruction_Throw(methodIR, inst, 0);
        return def instanceof SSAGetCaughtExceptionInstruction;
    }

    protected boolean isThrowingAssertionError(IR methodIR, SSAThrowInstruction inst)
    {
        SSAInstruction def = Utils.getDefInstruction_Throw(methodIR, inst, 0);
        if (!(def instanceof SSANewInstruction))
            return false;
        SSANewInstruction newInst = (SSANewInstruction) def;
        TypeReference throwType = newInst.getConcreteType();
        return Utils.getFullClassName(throwType).contains("java.lang.AssertionError");
    }
}
