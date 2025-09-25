package pfl.analysis_util.exec_points;

import java.util.Map;

import com.google.common.collect.ComparisonChain;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IMethod;

import pfl.util.RemoveReason;
import pfl.util.Utils;

public class NegatePoint extends ExecPointBase implements Comparable<NegatePoint>
{
    public int lastLineNo = -1;
    public RemoveReason shouldRemove = null;
    public NegatePoint(IClass clazz, IMethod method, int lineNumber)
    {
        this.clazz = clazz;
        this.method = method;
        this.lineNumber = lineNumber;
        this.type = Type.NEGATE;
    }

    public NegatePoint(IClass clazz, IMethod method, int firstLineNo, int lastLineNo)
    {
        this.clazz = clazz;
        this.method = method;
        this.lineNumber = firstLineNo;
        this.lastLineNo = lastLineNo;
        this.type = Type.NEGATE;
    }

    @Override
    public int compareTo(NegatePoint rhs)
    {
        return ComparisonChain.start().compare(Utils.getFullClassName(this.clazz), Utils.getFullClassName(rhs.clazz))
                .compare(Utils.getFullMethodName(this.method), Utils.getFullMethodName(rhs.method)).compare(this.lineNumber, rhs.lineNumber).result();
    }

    @Override
    public void _toMap_Impl(Map<String, Object> r)
    {
        r.put("LastLineNo", lastLineNo);
        if (shouldRemove != null)
        {
            r.put("ShouldRemove?", shouldRemove.shouldRemove);
            r.put("RemoveReason", shouldRemove.reason);
        }
        return;
    }

    @Override
    public boolean equals(Object o)
    {
        if (!(o instanceof NegatePoint))
            return false;
        NegatePoint rhs = (NegatePoint) o;
        return compareTo(rhs) == 0;
    }

    @Override
    public HashCode getHash()
    {
        if (objHash != null)
            return objHash;
        Hasher hasher = Hashing.murmur3_128().newHasher();
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
