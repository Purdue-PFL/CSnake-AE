package pfl.analysis_util.exec_points;

import java.util.AbstractMap.SimpleEntry;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.lang3.tuple.ImmutablePair;

import com.google.common.collect.ComparisonChain;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IMethod;

import pfl.analysis_util.CtorConstructionStep;
import pfl.util.Utils;

public abstract class ExecPointBase
{
    public enum Type
    {
        THROW_EXCEPTION, NEGATE, BRANCH
    }

    public Type type;
    public IClass clazz;
    public IMethod method;
    public int lineNumber; // The line where injection happens.
    HashCode objHash = null;
    String objID = null;


    public Map<String, Object> toMap()
    {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("Type", this.type.toString());
        r.put("Class", Utils.getFullClassName(clazz));
        r.put("Method", Utils.getShortMethodName(method));
        r.put("LineNumber", lineNumber);
        _toMap_Impl(r);
        r.put("ExecPointID", this.getID());
        return r;
    }

    abstract public void _toMap_Impl(Map<String, Object> r);

    abstract public boolean equals(Object o);

    abstract public HashCode getHash();

    abstract public String getID();

    @Override
    public String toString()
    {
        StringBuilder sb = new StringBuilder();
        sb.append("Type: ");
        sb.append(this.type.toString());
        sb.append('\n');
        sb.append("Class: ");
        sb.append(Utils.getFullClassName(clazz));
        sb.append("\n");
        sb.append("Method: ");
        sb.append(Utils.getFullMethodName(method));
        sb.append("\n");
        sb.append("LineNumber: ");
        sb.append(lineNumber);
        sb.append("\n");
        return sb.toString();
    }

    public Hasher baseHash(Hasher hasher)
    {
        hasher.putInt(type.ordinal());
        hasher.putBytes(Utils.getFullClassName(clazz).getBytes());
        hasher.putBytes(Utils.getShortMethodName(method).getBytes());
        hasher.putInt(lineNumber);
        return hasher;
    }

    @Override
    public int hashCode()
    {
        return getHash().asInt();
    }

    public static <T extends ExecPointBase> LinkedHashMap<String, Map<String, Object>> getIDMapForJson(Collection<T> c)
    {
        return c.stream().map(e -> new SimpleEntry<>(e.getID().toString(), e.toMap()))
                .collect(Collectors.toMap(SimpleEntry::getKey, SimpleEntry::getValue, (key1, key2) -> key1, LinkedHashMap::new));
    }

}
