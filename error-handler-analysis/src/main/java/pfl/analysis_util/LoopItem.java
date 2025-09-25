package pfl.analysis_util;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;

import org.apache.commons.lang3.builder.EqualsBuilder;

import com.google.common.collect.ComparisonChain;
import com.google.common.hash.HashCode;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;

import pfl.util.RemoveReason;

public class LoopItem implements Serializable, Comparable<LoopItem>
{
    private static final long serialVersionUID = -6139883844696984971L;
    private HashCode loopID = null;
    public String clazz;
    public String func;
    // Loop Head + Loop Body from Walautil
    public int startLineNo;
    public int endLineNo;
    // Loop Body only from WalaUtil
    public int loopbodyStartLineNo;
    public int loopbodyEndLineNo;
    // There are three types of loops.
    // - For loop: startLineNo == loopbodyStartLineNo; endLineNo == loopbodyEndLineNo;
    // loopStartEventLineNo = startLineNo + 1
    // loopEndEventLineNo = endlineNo + 1
    // - while loop: startLineNo > loopbodyStartLineNo; endLineNo == loopBodyEndLineNo;
    // loopStartEventLineNo = startLineNo
    // loopEndEventLineNo = endlineNo + 1
    // - do-while loop: startLineNo > loopbodyStartLineNo; endLineNo == loopBodyEndLineNo;
    // loopStartEventLineNo = startLineNo
    // loopEndEventLineNo = endlineNo + 1
    public int loopStartEventLineNo;
    public int loopEndEventLineNo;

    public int allInvokedApplicationMethodsSSAInstSize;
    public RemoveReason shouldRemove;

    public Boolean hasJavaIOorJavaNet = null;

    public LoopItem(String clazz, String func, int startLineNo, int endLineNo, int loopbodyStartLineNo, int loopbodyEndLineNo)
    {
        this.clazz = clazz;
        this.func = func;
        this.startLineNo = startLineNo;
        this.endLineNo = endLineNo;
        this.loopbodyStartLineNo = loopbodyStartLineNo;
        this.loopbodyEndLineNo = loopbodyEndLineNo;

        if (startLineNo == loopbodyStartLineNo)
        // For loop
        {
            this.loopStartEventLineNo = this.startLineNo + 1;
        }
        else
        // while loop; do-while loop
        {
            this.loopStartEventLineNo = this.startLineNo;
        }
        this.loopEndEventLineNo = this.endLineNo + 1;
    }

    public HashCode getLoopID()
    {
        if (this.loopID != null)
            return loopID;
        Hasher hasher = Hashing.murmur3_128().newHasher();
        hasher.putBytes(clazz.getBytes());
        hasher.putBytes(func.getBytes());
        hasher.putInt(startLineNo);
        hasher.putInt(endLineNo);
        hasher.putInt(loopbodyStartLineNo);
        hasher.putInt(loopbodyEndLineNo);
        hasher.putInt(loopStartEventLineNo);
        hasher.putInt(loopEndEventLineNo);
        this.loopID = hasher.hash();
        return this.loopID;
    }

    public Map<String, Object> toMap()
    {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("Class", this.clazz);
        r.put("Method", this.func);
        r.put("StartLine", this.startLineNo);
        r.put("EndLine", this.endLineNo);
        r.put("LoopBodyStartLine", this.loopbodyStartLineNo);
        r.put("LoopBodyEndLine", this.loopbodyEndLineNo);
        r.put("loopStartEventLineNo", this.loopStartEventLineNo);
        r.put("loopEndEventLineNo", this.loopEndEventLineNo);
        r.put("LoopID", getLoopID().toString());
        r.put("AllInvokedMethodSSASize", this.allInvokedApplicationMethodsSSAInstSize);
        if (shouldRemove != null) 
        {
            r.put("ShouldRemove?", shouldRemove.shouldRemove);
            r.put("RemoveReason", shouldRemove.reason);
        }
        if (hasJavaIOorJavaNet != null)
        {
            r.put("InvokedJavaIOorJavaNet", hasJavaIOorJavaNet);
        }
        return r;
    }

    @Override
    public boolean equals(Object o)
    {
        if (!(o instanceof LoopItem))
            return false;
        LoopItem rhs = (LoopItem) o;
        return new EqualsBuilder().append(this.startLineNo, rhs.startLineNo).append(this.endLineNo, rhs.endLineNo).append(this.func, rhs.func).append(this.clazz, rhs.clazz)
                .append(this.loopbodyStartLineNo, rhs.loopbodyStartLineNo).append(this.loopbodyEndLineNo, rhs.loopbodyEndLineNo)
                .append(this.loopStartEventLineNo, rhs.loopStartEventLineNo).append(this.loopEndEventLineNo, rhs.loopEndEventLineNo).isEquals();
    }

    @Override
    public int hashCode()
    {
        return getLoopID().asInt();
    }

    @Override
    public String toString()
    {
        StringBuilder sb = new StringBuilder();
        sb.append("Loop: ");
        sb.append(clazz);
        sb.append('.');
        sb.append(func);
        sb.append(" | L");
        sb.append(startLineNo);
        sb.append(" (");
        sb.append(loopbodyStartLineNo);
        sb.append(") - L");
        sb.append(endLineNo);
        sb.append(" (");
        sb.append(loopbodyEndLineNo);
        sb.append(")");
        return sb.toString();
    }

    @Override
    public int compareTo(LoopItem rhs)
    {
        return ComparisonChain.start().compare(this.clazz, rhs.clazz).compare(this.func, rhs.func).compare(this.startLineNo, rhs.startLineNo)
                .compare(this.loopbodyStartLineNo, rhs.loopbodyStartLineNo).compare(this.endLineNo, rhs.endLineNo).compare(this.loopbodyEndLineNo, rhs.loopbodyEndLineNo)
                .compare(this.loopStartEventLineNo, rhs.loopStartEventLineNo).compare(this.loopEndEventLineNo, rhs.loopEndEventLineNo).result();
    }

    public boolean instInLoopRange(int instLineNo)
    {
        return (instLineNo >= startLineNo) && (instLineNo <= endLineNo);
    }

    public void setAllInvokedApplicationMethodsSSAInstSize(int size)
    {
        this.allInvokedApplicationMethodsSSAInstSize = size;
    }

    public void setHasJavaIOorJavaNet(boolean v)
    {
        this.hasJavaIOorJavaNet = v;
    }

}
