package pfl.analysis_util.exec_points;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import com.google.common.collect.ComparisonChain;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IMethod;

import pfl.analysis_util.CtorConstructionStep;
import pfl.util.Utils;

public class ExceptionInjectionPoint extends ExecPointBase implements Comparable<ExceptionInjectionPoint>
{
    public enum InjectionType
    {
        IF_THROW, LIB_THROW
    }

    public IClass throwPointClass;
    public IMethod throwPointMethod;
    public int throwPointLineNumber; // The line of the throw statements/signature
    public List<CtorConstructionStep> throwableSteps;
    public InjectionType injectionType;

    public ExceptionInjectionPoint(IClass clazz, IMethod method, int lineNumber, Type type)
    {
        this.clazz = clazz;
        this.method = method;
        this.lineNumber = lineNumber;
        this.type = type;
    }
    
    public ExceptionInjectionPoint(IClass clazz, IMethod method, int lineNumber, IClass throwPointClass, IMethod throwPointMethod, int throwPointLineNumber, InjectionType injType)
    {
        this.clazz = clazz;
        this.method = method;
        this.lineNumber = lineNumber;
        this.type = Type.THROW_EXCEPTION;
        this.throwPointClass = throwPointClass;
        this.throwPointMethod = throwPointMethod;
        this.throwPointLineNumber = throwPointLineNumber;
        this.injectionType = injType;
    }

    public void setThrowableSteps(List<CtorConstructionStep> steps)
    {
        this.throwableSteps = steps;
    }

    @Override
    public void _toMap_Impl(Map<String, Object> r)
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
        r.put("ThrowPointType", this.injectionType.name());
    }

    @Override
    public int compareTo(ExceptionInjectionPoint rhs)
    {
        return ComparisonChain.start().compare(Utils.getFullClassName(clazz), Utils.getFullClassName(rhs.clazz))
                .compare(Utils.getFullMethodName(method), Utils.getFullMethodName(rhs.method)).compare(lineNumber, rhs.lineNumber).result();
    }

    @Override
    public boolean equals(Object o)
    {
        if (!(o instanceof ExceptionInjectionPoint)) return false;
        ExceptionInjectionPoint rhs = (ExceptionInjectionPoint) o;
        return compareTo(rhs) == 0;
    }

    @Override
    public HashCode getHash()
    {
        if (objHash != null)
            return objHash;
        Hasher hasher = Hashing.murmur3_128().newHasher();
        // Use class + method due to inheritance
        hasher = baseHash(hasher);
        objHash = hasher.hash();
        return objHash;
    }

    @Override
    public String getID()
    {
        if (objID != null)
            return objID;
        objID = getHash().toString();
        return objID;
    }
}
