package pfl.result_analysis.utils;

import java.io.Serializable;
import java.util.Arrays;

import com.google.common.hash.HashCode;

public class LoopHash implements Serializable
{
    private static final long serialVersionUID = -1856397984014743928L;
    public HashCode rawHashCode;
    private LoopHash(HashCode hc)
    {
        rawHashCode = hc;
    }    

    public static LoopHash wrap(String s)
    {
        return new LoopHash(HashCode.fromString(s));
    }

    @Override
    public boolean equals(Object o)
    {
        if (!(o instanceof LoopHash)) return false;
        LoopHash rhs = (LoopHash) o;
        return this.rawHashCode.equals(rhs.rawHashCode);
    }

    @Override 
    public int hashCode()
    {
        return rawHashCode.asInt();
    }

    @Override
    public String toString()
    {
        return rawHashCode.toString();
    }
}
