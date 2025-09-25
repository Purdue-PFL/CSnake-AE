package pfl.analysis_util;

import org.apache.commons.lang3.builder.EqualsBuilder;

import com.google.common.collect.ComparisonChain;
import com.google.common.collect.Ordering;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import com.ibm.wala.ipa.slicer.NormalStatement;
import com.ibm.wala.ssa.SSAGetInstruction;
import com.ibm.wala.ssa.SSAInstanceofInstruction;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.types.FieldReference;

import pfl.util.Utils;

public class TimeoutConfigStatement implements Comparable<TimeoutConfigStatement>
{
    public enum Type
    {
        NORMAL_STATEMENT, FIELD_ACCESS
    }

    public Type type;
    public String clazz;
    public String method;
    public int lineNo;
    public String fieldName;

    public TimeoutConfigStatement expandedFrom;

    public TimeoutConfigStatement(String clazz, String fieldName)
    {
        this.type = Type.FIELD_ACCESS;
        this.clazz = clazz;
        this.fieldName = fieldName;
    }

    public TimeoutConfigStatement(String clazz, String method, int lineNo)
    {
        this.type = Type.NORMAL_STATEMENT;
        this.clazz = clazz;
        this.method = method;
        this.lineNo = lineNo;
    }

    public void setExpandedFrom(TimeoutConfigStatement origin)
    {
        expandedFrom = origin;
    }

    private static TimeoutConfigStatement buildNoTrackGet(NormalStatement stmt)
    {
        String className = Utils.getFullClassName(stmt.getNode().getMethod().getDeclaringClass());
        String methodName = Utils.getShortMethodName(stmt.getNode().getMethod());
        int lineNo = Utils.getSrcLineNumberBySSAInst(stmt.getNode().getIR(), stmt.getInstructionIndex());
        return new TimeoutConfigStatement(className, methodName, lineNo);
    }

    public static TimeoutConfigStatement build(NormalStatement stmt, boolean trackGet)
    {
        if (trackGet)
        {
            SSAInstruction inst = stmt.getInstruction();
            if (inst instanceof SSAGetInstruction)
            {
                SSAGetInstruction getInst = (SSAGetInstruction) inst;
                FieldReference field = getInst.getDeclaredField();
                return new TimeoutConfigStatement(Utils.getFullClassName(field.getDeclaringClass()), field.getName().toString());
            }
            else
            {
                return buildNoTrackGet(stmt);
            }
        }
        else 
        {
            return buildNoTrackGet(stmt);
        }
    }

    private static HashFunction hashFunc = Hashing.murmur3_32_fixed();
    @Override
    public int hashCode()
    {
        Hasher hasher = hashFunc.newHasher();
        hasher.putInt(type.ordinal());
        if (type == Type.NORMAL_STATEMENT)
        {
            hasher.putBytes(clazz.getBytes());
            hasher.putBytes(method.getBytes());
            hasher.putInt(lineNo);
        }
        else if (type == Type.FIELD_ACCESS)
        {
            hasher.putBytes(clazz.getBytes());
            hasher.putBytes(fieldName.getBytes());
        }
        return hasher.hash().asInt();
    }

    @Override
    public boolean equals(Object o)
    {
        if (!(o instanceof TimeoutConfigStatement)) return false;
        TimeoutConfigStatement rhs = (TimeoutConfigStatement) o;
        return new EqualsBuilder().append(this.type, rhs.type).append(this.clazz, rhs.clazz).append(this.method, rhs.method).append(this.lineNo, rhs.lineNo)
                .append(this.fieldName, rhs.fieldName).isEquals();
    }

    public static JsonSerializer<TimeoutConfigStatement> jsonSerializer = new JsonSerializer<TimeoutConfigStatement>()
    {
        @Override
        public JsonElement serialize(TimeoutConfigStatement src, java.lang.reflect.Type typeOfSrc, JsonSerializationContext context)
        {
            return new JsonPrimitive(src.toString());
        }
    };

    @Override
    public int compareTo(TimeoutConfigStatement rhs)
    {
        return ComparisonChain.start().compare(this.type.ordinal(), rhs.type.ordinal()).compare(this.clazz, rhs.clazz)
                .compare(this.method, rhs.method, Ordering.arbitrary().nullsFirst()).compare(this.lineNo, rhs.lineNo, Ordering.arbitrary().nullsFirst())
                .compare(this.fieldName, rhs.fieldName, Ordering.arbitrary().nullsFirst()).result();
    }

    @Override 
    public String toString()
    {
        String r;
        if (type == Type.NORMAL_STATEMENT)
        {
            r = clazz + "." + method + " # L" + lineNo;
        }
        else
        {
            r = clazz + "." + fieldName;
        }
        if (expandedFrom != null)
        {
            String expandedFromStr = expandedFrom.toString();
            if (expandedFromStr.contains("|")) expandedFromStr = expandedFromStr.split("\\|")[0];
            r = r + "|" + expandedFromStr;
        };
        return r;
    }
}
