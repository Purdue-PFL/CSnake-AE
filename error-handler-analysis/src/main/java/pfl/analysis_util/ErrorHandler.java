package pfl.analysis_util;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.apache.commons.lang3.tuple.ImmutablePair;
import org.jgrapht.GraphPath;
import org.jgrapht.graph.DefaultEdge;

import com.google.common.collect.ComparisonChain;
import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.classLoader.IMethod.SourcePosition;
import com.ibm.wala.ipa.callgraph.CGNode;

import pfl.util.Utils;

public class ErrorHandler implements Comparable<ErrorHandler>
{
    public enum Type
    {
        THROW_EXCEPTION
    }

    public Type type;
    public IClass clazz;
    public IMethod method;
    public int lineNumber;
    public Map<String, Set<String>> unitTests = new HashMap<>(); // {TestClass: [TestMethod]}
    public Map<ImmutablePair<String, String>, Integer> callstackLength = new HashMap<>(); // {(Class, TestMehod): Shortest call stack length}
    
    public IClass throwPointClass;
    public IMethod throwPointMethod;
    public int throwPointLineNumber;

    // public SourcePosition sourcePosition;

    public ErrorHandler(IClass clazz, IMethod method, int lineNumber, Type type)
    {
        this.clazz = clazz;
        this.method = method;
        this.lineNumber = lineNumber;
        this.type = type;
    }

    public ErrorHandler(IClass clazz, IMethod method, int lineNumber, Type type, IClass throwPointClass, IMethod throwPointMethod, int throwPointLineNumber)
    {
        this.clazz = clazz;
        this.method = method;
        this.lineNumber = lineNumber;
        this.type = type;
        setThrowPoint(throwPointClass, throwPointMethod, throwPointLineNumber);
    }

    // region Type.THROW_EXCEPTION
    public List<CtorConstructionStep> throwableSteps;

    public void setThrowableSteps(List<CtorConstructionStep> steps)
    {
        this.throwableSteps = steps;
    }
    // endregion

    public void setThrowPoint(IClass throwPointClass, IMethod throwPointMethod, int throwPointLineNumber)
    {
        this.throwPointClass = throwPointClass;
        this.throwPointMethod = throwPointMethod;
        this.throwPointLineNumber = throwPointLineNumber;
    }

    // Only store the test case with the shortest stack trace to the error handler
    public void addTestCase(IClass clazz, IMethod method)
    {
        String className = Utils.getFullClassName(clazz);
        String methodName = Utils.getShortMethodName(method);
        unitTests.computeIfAbsent(className, k -> new HashSet<>()).add(methodName);
        // callstackLength.computeIfAbsent(ImmutablePair.of(className, methodName), k -> Integer.MAX_VALUE);
        // if (callstackLength.get(ImmutablePair.of(className, methodName)) > path.getLength())
        // {
        // unitTests.computeIfAbsent(className, k -> new HashSet<>()).add(methodName);
        // callstackLength.put(ImmutablePair.of(className, methodName), path.getLength());
        // }
    }

    public Map<String, Object> toMap()
    {
        return toMap(0);
    }

    // unitTestLimit = 0 means no limit
    public Map<String, Object> toMap(int unitTestLimit)
    {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("Type", this.type.toString());
        r.put("Class", Utils.getFullClassName(clazz));
        r.put("Method", Utils.getFullMethodName(method));
        r.put("LineNumber", lineNumber);
        if (type == Type.THROW_EXCEPTION)
        {
            _THROW_EXCEPTION_toMap(r);
        }
        List<String> tests = new LinkedList<>();
        for (String testClass : unitTests.keySet())
        {
            Set<String> testMethods = unitTests.get(testClass);
            for (String testMethod : testMethods)
            {
                tests.add(testClass + "#" + testMethod);
            }
        }
        if ((tests.size() > unitTestLimit) && (unitTestLimit > 0))
        {
            Collections.shuffle(tests);
            tests = tests.subList(0, unitTestLimit);
        }
        r.put("UnitTests", tests);
        return r;
    }

    public void _THROW_EXCEPTION_toMap(Map<String, Object> r)
    {
        List<Map<String, Object>> ctor = new LinkedList<>();
        throwableSteps.forEach(s -> ctor.add(s.toMap()));
        r.put("ThrowableConstruction", throwableSteps);
        if (throwPointClass != null) 
        {
            r.put("ThrowPointClass", Utils.getFullClassName(throwPointClass));
            r.put("ThrowPointMethod", Utils.getFullMethodName(throwPointMethod));
            r.put("ThrowPointLineNumber", throwPointLineNumber);
        }
    }

    @Override
    public String toString()
    {
        StringBuilder sb = new StringBuilder();
        sb.append("Class: ");
        sb.append(Utils.getFullClassName(clazz));
        sb.append("\n");
        sb.append("Method: ");
        sb.append(Utils.getFullMethodName(method));
        sb.append("\n");
        sb.append("LineNumber: ");
        sb.append(lineNumber);
        sb.append("\n");
        return sb.toString();
    }

    @Override
    public int hashCode()
    {
        // Keep full class and method comparision, otherwise, it may save the incorrect (or unreachable) definition of the EH
        // Due to inheritance, many subclasses may point to a error handler in the same superclass
        return Objects.hash(Utils.getFullClassName(clazz), Utils.getFullMethodName(method), lineNumber);
    }

    @Override
    public boolean equals(Object o)
    {
        if (!(o instanceof ErrorHandler)) return false;
        ErrorHandler rhs = (ErrorHandler) o;
        return compareTo(rhs) == 0;
    }

    @Override
    public int compareTo(ErrorHandler rhs)
    {
        return compareToImpl(rhs);
    }

    public int compareToImpl(ErrorHandler rhs)
    {
        // Only use FullMethodName is enough (contains class name)
        // Otherwise, some inherited method will occur multiple times
        return ComparisonChain.start().compare(Utils.getFullClassName(clazz), Utils.getFullClassName(rhs.clazz))
                .compare(Utils.getFullMethodName(method), Utils.getFullMethodName(rhs.method)).compare(lineNumber, rhs.lineNumber).result();

    }
}
