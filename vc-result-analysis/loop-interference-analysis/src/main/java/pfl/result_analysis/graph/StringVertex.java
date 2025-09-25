package pfl.result_analysis.graph;

import java.io.Serializable;

import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;

public class StringVertex implements Serializable, Comparable<StringVertex>
{
    private static final long serialVersionUID = -501262334493483936L;
    public String rawString;
    private StringVertex(String rawStr)
    {
        this.rawString = rawStr;
    }    

    public static StringVertex wrap(String rawString)
    {
        return new StringVertex(rawString);
    }

    @Override
    public String toString()
    {
        return rawString;
    }

    private volatile int hashCodeValue;
    private static HashFunction hashFunction = Hashing.murmur3_32_fixed();
    @Override
    public int hashCode()
    {
        int result = hashCodeValue;
        if (result == 0)
        {
            result = hashFunction.hashBytes(rawString.getBytes()).asInt();
            hashCodeValue = result;
        }
        return result;
    }

    @Override 
    public boolean equals(Object o)
    {
        if (!(o instanceof StringVertex)) return false;
        StringVertex rhs = (StringVertex) o;
        return this.rawString.equals(rhs.rawString);
    }

    @Override
    public int compareTo(StringVertex rhs)
    {
        return this.rawString.compareTo(rhs.rawString);
    }    
}
