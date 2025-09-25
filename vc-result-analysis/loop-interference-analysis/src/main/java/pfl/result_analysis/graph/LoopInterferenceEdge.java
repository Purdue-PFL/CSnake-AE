package pfl.result_analysis.graph;

import java.io.Serializable;

import org.apache.commons.lang3.builder.EqualsBuilder;
import org.jgrapht.graph.DefaultEdge;

import com.google.common.collect.ComparisonChain;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;

public abstract class LoopInterferenceEdge extends DefaultEdge implements Serializable 
{
    private static final long serialVersionUID = -2480816578175318417L;

    // Sorted Based on preference in the graph
    public enum Type implements Serializable
    {
        RPC, CFG, EXEC_INTERFERENCE, ITER_COUNT_INTERFERENCE, INVERSE_CFG, ITER_COUNT_CORRELATION
    }
    public Type type;

    @Override
    public String toString()
    {
        return "(" + type.toString() + " | " + getSource() + " : " + getTarget() + ")";
    }

    @Override
    public int hashCode()
    {
        Hasher hasher = Hashing.murmur3_32_fixed().newHasher();
        hasher.putInt(getSource().hashCode());
        hasher.putInt(getTarget().hashCode());
        hasher.putInt(type.ordinal());
        return hasher.hash().asInt();
    }

    @Override
    public boolean equals(Object o)
    {
        if (!(o instanceof LoopInterferenceEdge)) return false;
        LoopInterferenceEdge rhs = (LoopInterferenceEdge) o;
        return this.getSource().equals(rhs.getSource()) && this.getTarget().equals(rhs.getTarget()) && this.type.equals(rhs.type);
    }
}
