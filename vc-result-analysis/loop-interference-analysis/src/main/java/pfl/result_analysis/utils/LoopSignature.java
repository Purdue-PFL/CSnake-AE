package pfl.result_analysis.utils;

import java.io.Serializable;
import java.util.List;

import com.google.common.hash.HashFunction;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;

public class LoopSignature implements Serializable
{
    private static final long serialVersionUID = -7837772297979947818L;
    public List<VCHashBytes> rawSignature;
    public LoopSignature(List<VCHashBytes> rawSignature)
    {
        this.rawSignature = rawSignature;
    }

    @Override
    public boolean equals(Object o)
    {
        if (!(o instanceof LoopSignature)) return false;
        LoopSignature rhs = (LoopSignature) o;
        return this.rawSignature.equals(rhs.rawSignature);
    }

    private volatile int hashCodeValue;
    private static HashFunction hashFunction = Hashing.murmur3_32_fixed();
    @Override
    public int hashCode()
    {
        int result = hashCodeValue;
        if (result == 0)
        {
            Hasher hasher = hashFunction.newHasher();
            for (VCHashBytes e: rawSignature)
            {
                hasher.putInt(e.hashCode());
            }
            result = hasher.hash().asInt();
            // result = rawSignature.hashCode();
            // result = hashFunction.hashBytes(uuidBytes).asInt();
            // result = Arrays.hashCode(uuidBytes);
            hashCodeValue = result;
        }
        return result;
    }
}
