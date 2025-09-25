package pfl.graph;

import java.io.Serializable;

import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.CallGraph;

public class IMethodRawNameNode implements Serializable
{
    private static final long serialVersionUID = 7571933373143317515L;
    public String rawName;
    public IMethodRawNameNode(IMethod method)
    {
        this.rawName = method.getSignature();
    }

    public IMethodRawNameNode(String methodSig)
    {
        this.rawName = methodSig;
    }

    public static CGNode findCGN(CallGraph cg, IMethodRawNameNode node)
    {
        for (CGNode cgn: cg)
        {
            if (cgn.getMethod().getSignature().equals(node.rawName)) return cgn;
        }
        return null;
    }

    private volatile int hashCodeValue;
    private static HashFunction hashFunction = Hashing.murmur3_32_fixed();
    @Override
    public int hashCode()
    {
        int result = hashCodeValue;
        if (result == 0)
        {
            result = hashFunction.hashBytes(rawName.getBytes()).asInt();
            hashCodeValue = result;
        }
        return result;
    }

    @Override 
    public boolean equals(Object o)
    {
        if (!(o instanceof IMethodRawNameNode)) return false;
        IMethodRawNameNode rhs = (IMethodRawNameNode) o;
        return this.rawName.equals(rhs.rawName);
    }

    @Override
    public String toString()
    {
        return rawName;
    }
}
