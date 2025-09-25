package pfl.analysis_util;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.google.common.collect.ComparisonChain;
import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IMethod;

import pfl.util.Utils;

public class MethodIndexedErrorHandler extends ErrorHandler
{
    public MethodIndexedErrorHandler(ErrorHandler eh)
    {
        super(eh.clazz, eh.method, eh.lineNumber, eh.type);
        this.unitTests = eh.unitTests;
        this.callstackLength = eh.callstackLength;
        if (eh.type == Type.THROW_EXCEPTION)
        {
            this.throwableSteps = eh.throwableSteps;
            this.throwPointClass = eh.throwPointClass;
            this.throwPointMethod = eh.throwPointMethod;
            this.throwPointLineNumber = eh.throwPointLineNumber;
        }
    }

    @Override
    public Map<String, Object> toMap()
    {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("Type", this.type.toString());
        r.put("Method", Utils.getFullMethodName(method));
        r.put("LineNumber", lineNumber);
        if (type == Type.THROW_EXCEPTION)
        {
            _THROW_EXCEPTION_toMap(r);
        }
        return r;
    }

    @Override
    public String toString()
    {
        StringBuilder sb = new StringBuilder();
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
        // MethodIndexed
        return Objects.hash(Utils.getFullMethodName(method), lineNumber);
    }

    @Override
    public int compareToImpl(ErrorHandler rhs)
    {
        // MethodIndexed, so we only compare full method name here 
        return ComparisonChain.start().compare(Utils.getFullMethodName(method), Utils.getFullMethodName(rhs.method)).compare(lineNumber, rhs.lineNumber).result();
    }

}
