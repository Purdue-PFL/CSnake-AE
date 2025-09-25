package pfl.bm.events;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import com.google.common.collect.ComparisonChain;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;

import pfl.bm.StackTraceSnapshot;

public class BranchEvent extends IterEventBase 
{
    private static final long serialVersionUID = 8108905405889080846L;
    public int branchID;

    public BranchEvent(int branchID)
    {
        this.type = Type.BRANCH;
        this.branchID = branchID;
    }

    @Override
    public Map<String, Object> toMap()
    {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("Type", this.type.toString());
        r.put("BranchID", branchID);
        return r;
    }

    @Override
    public byte[] toByteArray()
    {
        byte[] r = new byte[5];
        ByteBuffer rBuf = ByteBuffer.wrap(r);
        rBuf.put("B".getBytes(StandardCharsets.UTF_8)[0]);
        rBuf.putInt(branchID);
        return r;
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(type, branchID);
    }

    @Override
    public boolean equals(Object o)
    {
        if (!(o instanceof BranchEvent)) return false;
        BranchEvent rhs = (BranchEvent) o;
        return this.branchID == rhs.branchID;
    }
}
