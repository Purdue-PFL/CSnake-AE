package pfl.result_analysis.utils;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;

public class CustomUUID implements Comparable<CustomUUID>, Serializable
{
    private static final long serialVersionUID = 2146029330282856308L;
    private final byte[] uuidBytes;
    private CustomUUID(byte[] buf) 
    {
        this.uuidBytes = buf;
    };

    public static CustomUUID wrap(byte[] buf)
    {
        return new CustomUUID(buf);
    }

    public UUID asUUID()
    {
        return BinaryUtils.bytesAsUUID(uuidBytes);
    }

    @Override
    public String toString()
    {
        return asUUID().toString();
    }

    @Override
    public boolean equals(Object o)
    {
        if (!(o instanceof CustomUUID)) return false;
        CustomUUID rhs = (CustomUUID) o;
        return Arrays.equals(this.uuidBytes, rhs.uuidBytes);
        // return this.hashCode() == rhs.hashCode();
    }

    private volatile int hashCodeValue;
    private static HashFunction hashFunction = Hashing.murmur3_32_fixed();
    @Override
    public int hashCode()
    {
        int result = hashCodeValue;
        if (result == 0)
        {
            // byte[] buf = Arrays.copyOfRange(uuidBytes, 0, 4);
            // result = BinaryUtils.byteArrayToInt(buf);
            result = BinaryUtils.byteArrayToInt(uuidBytes, 3); // Read the last 32bit of the UUID
            // result = hashFunction.hashBytes(uuidBytes).asInt();
            // result = Arrays.hashCode(uuidBytes);
            hashCodeValue = result;
        }
        return result;
    }

    @Override
    public int compareTo(CustomUUID rhs)
    {
        return Arrays.compare(this.uuidBytes, rhs.uuidBytes);
    }
}
