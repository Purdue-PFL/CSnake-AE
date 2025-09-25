package pfl.result_analysis.utils;

import com.google.common.hash.HashFunction;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;

public class WrappedString 
{
    String originalStr;

    private WrappedString(String str)
    {
        this.originalStr = str;
    }

    public static WrappedString wrap(String str)
    {
        return new WrappedString(str);
    }

    private static HashFunction hashFunction = Hashing.murmur3_32_fixed();
    private int hashCode = 0;

    public boolean startsWith(String prefix)
    {
        return originalStr.startsWith(prefix);
    }
    
    @Override
    public int hashCode()
    {
        if (hashCode == 0)
        {
            hashCode = hashFunction.hashBytes(originalStr.getBytes()).asInt();
        }
        return hashCode;
    }

    @Override
    public boolean equals(Object o)
    {
        if (!(o instanceof WrappedString)) return false;
        WrappedString rhs = (WrappedString) o;
        return this.hashCode() == rhs.hashCode() && this.originalStr.equals(rhs.originalStr);
    }

    @Override
    public String toString()
    {
        return originalStr;
    }
}
