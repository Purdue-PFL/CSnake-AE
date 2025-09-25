package pfl.result_analysis.utils;

import java.io.Serializable;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.lang3.StringUtils;

import com.google.common.hash.HashCode;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;

public class VCHashBytes implements Serializable
{
    private static final long serialVersionUID = -8475185330609960907L;
    public final byte[] data;
    public static Map<VCHashBytes, VCHashBytes> objPool = new ConcurrentHashMap<>();

    private VCHashBytes(byte[] data)
    {
        if (data == null)
        {
            throw new NullPointerException();
        }
        this.data = data;
    }

    public static VCHashBytes wrap(String str)
    {
        // Python-exported 128-bit hash value does not have leading zeros, pad it
        // Only pad to 32 for now, assuming murmur3_128
        if (str.length() % 2 != 0) str = StringUtils.leftPad(str, 32, '0');
        return wrap(HashCode.fromString(str).asBytes());
    }

    public static VCHashBytes wrap(byte[] data)
    {
        VCHashBytes o = new VCHashBytes(data);
        VCHashBytes pooledObj = objPool.get(o);
        if (pooledObj == null)
        {
            objPool.put(o, o);
            pooledObj = o;
        }
        return pooledObj;
    }

    @Override
    public String toString()
    {
        if (data.length == 16)
        {
            HashCode hc = HashCode.fromBytes(data);
            return hc.toString();
        }
        else if (data.length == 20)
        {
            byte[] branchHashRaw = Arrays.copyOfRange(data, 0, 16);
            byte[] realBranchIDRaw = Arrays.copyOfRange(data, 16, 20);
            HashCode branchHash = HashCode.fromBytes(branchHashRaw);
            int realbranchID = BinaryUtils.byteArrayToInt(realBranchIDRaw);
            return branchHash.toString() + "-" + realbranchID;
        }
        else 
        {
            return Arrays.toString(data);
        }
    }

    @Override
    public boolean equals(Object other)
    {
        if (!(other instanceof VCHashBytes))
        {
            return false;
        }
        return Arrays.equals(data, ((VCHashBytes) other).data);
    }

    private volatile int hashCodeValue;
    // private static HashFunction hashFunction = Hashing.adler32();
    @Override
    public int hashCode()
    {
        int result = hashCodeValue;
        if (result == 0)
        {
            if (data.length == 16)
            {
                // byte[] branchHashRaw = Arrays.copyOfRange(data, 0, 4);
                result = BinaryUtils.byteArrayToInt(data);
            }
            else if (data.length == 20)
            {
                // byte[] buf = new byte[8];
                // System.arraycopy(data, 0, buf, 0, 4);
                // System.arraycopy(data, 16, buf, 4, 4);
                // result = Arrays.hashCode(buf);
                // byte[] branchHashRaw = Arrays.copyOfRange(data, 0, 4);
                // byte[] realBranchIDRaw = Arrays.copyOfRange(data, 16, 20);
                int branchHash = BinaryUtils.byteArrayToInt(data);
                int realbranchID = BinaryUtils.byteArrayToInt(data, 4);
                result = branchHash * 31 + realbranchID;

                // Hasher hasher = hashFunction.newHasher();
                // hasher.putBytes(data, 0, 4);
                // hasher.putBytes(data, 16, 4);
                // result = hasher.hash().asInt();
            }
            else 
            {
                result = Arrays.hashCode(data);
            }
            hashCodeValue = result;
        }
        return result;
    }
}
