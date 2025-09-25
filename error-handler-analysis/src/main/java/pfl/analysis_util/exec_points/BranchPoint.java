package pfl.analysis_util.exec_points;

import java.util.Map;

import com.google.common.collect.ComparisonChain;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IMethod;

import pfl.util.Utils;

public class BranchPoint extends ExecPointBase implements Comparable<BranchPoint>
{
    public IClass branchInstClass;
    public IMethod branchInstMethod;
    public int branchInstLineNo;
    public int branchID;
    private static HashCode branchHash = null;

    public BranchPoint(IClass clazz, IMethod method, int lineNumber, IClass branchInstClass, IMethod branchInstMethod, int branchInstLineNo, int branchID)
    {
        this.clazz = clazz;
        this.method = method;
        this.lineNumber = lineNumber;
        this.type = Type.BRANCH;
        this.branchInstClass = branchInstClass;
        this.branchInstMethod = branchInstMethod;
        this.branchInstLineNo = branchInstLineNo;
        this.branchID = branchID;
    }

    @Override
    public int compareTo(BranchPoint rhs)
    {
        return ComparisonChain.start().compare(Utils.getFullClassName(branchInstClass), Utils.getFullClassName(rhs.branchInstClass))
                .compare(Utils.getFullMethodName(branchInstMethod), Utils.getFullMethodName(rhs.branchInstMethod)).compare(branchInstLineNo, rhs.branchInstLineNo)
                .compare(branchID, rhs.branchID).result();
    }

    @Override
    public void _toMap_Impl(Map<String, Object> r)
    {
        r.put("branchInstClass", Utils.getFullClassName(branchInstClass));
        r.put("branchInstMethod", Utils.getShortMethodName(branchInstMethod));
        r.put("branchInstLineNo", branchInstLineNo);
        r.put("BranchID", branchID);
    }

    @Override
    public boolean equals(Object o)
    {
        if (!(o instanceof BranchPoint))
            return false;
        BranchPoint rhs = (BranchPoint) o;
        return compareTo(rhs) == 0;
    }

    @Override
    public HashCode getHash()
    {
        if (objHash != null)
            return objHash;
        Hasher hasher = Hashing.murmur3_128().newHasher();
        hasher.putBytes(Utils.getFullClassName(branchInstClass).getBytes());
        hasher.putBytes(Utils.getShortMethodName(branchInstMethod).getBytes());
        hasher.putInt(branchInstLineNo);
        hasher.putInt(branchID);
        objHash = hasher.hash();
        return objHash;
    }

    @Override public String getID()
    {
        if (objID != null)
            return objID;
        Hasher hasher = Hashing.murmur3_128().newHasher();
        hasher.putBytes(Utils.getFullClassName(branchInstClass).getBytes());
        hasher.putBytes(Utils.getShortMethodName(branchInstMethod).getBytes());
        hasher.putInt(branchInstLineNo);
        branchHash = hasher.hash();
        objID = branchHash.toString() + "-" + branchID;
        return objID;
    }

}
