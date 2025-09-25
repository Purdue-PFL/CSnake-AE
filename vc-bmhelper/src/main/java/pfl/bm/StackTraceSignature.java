package pfl.bm;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

import org.eclipse.collections.impl.list.mutable.ListAdapter;

public class StackTraceSignature implements Serializable 
{
    private static final long serialVersionUID = -4656413224230013433L;
    public List<StackTraceSnapshot> stacks;
    private List<String> compareList = null;

    public StackTraceSignature(List<StackTraceSnapshot> stacks)
    {
        this.stacks = stacks;
    }

    public List<List<String>> toList()
    {
        return stacks.stream().map(sts -> sts.toList()).collect(Collectors.toList());
    }

    public List<String> toCompareObject()
    {
        if (compareList == null)
        {
            compareList = stacks.stream().map(st -> st.toCompareObject()).flatMap(Collection::stream).collect(Collectors.toList());
        }
        return compareList;
    }

    @Override
    public int hashCode()
    {
        return toCompareObject().hashCode();
    }

    @Override
    public boolean equals(Object o)
    {
        if (!(o instanceof StackTraceSignature))
            return false;
        StackTraceSignature rhs = (StackTraceSignature) o;
        return this.toCompareObject().equals(rhs.toCompareObject());
    }

    @Override
    public String toString()
    {
        StringBuilder sb = new StringBuilder();
        sb.append("----------------- SIGNATURE -----------------------\n");
        ListAdapter.adapt(stacks).zipWithIndex().forEach(e ->
        {
            sb.append("Level ");
            sb.append(e.getTwo());
            sb.append('\t');
            sb.append(e.getOne().toString());
        });
        sb.append("--------------------------------------------------\n");
        return sb.toString();
    }

}