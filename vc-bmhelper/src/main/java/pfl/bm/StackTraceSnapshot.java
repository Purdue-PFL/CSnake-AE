package pfl.bm;

import java.io.Serializable;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class StackTraceSnapshot implements Serializable
{
    private static final long serialVersionUID = -6684603249235604871L;
    public StackTraceElement[] stack;
    private List<String> compareList = null;

    public StackTraceSnapshot(StackTraceElement[] stack)
    {
        this.stack = stack;
    }

    public List<String> toList()
    {
        return toCompareObject();
    }

    public List<String> toCompareObject()
    {
        if (compareList == null)
        {
            compareList = Arrays.stream(stack).map(e -> e.getClassName() + '.' + e.getMethodName() + "#L" + e.getLineNumber()).collect(Collectors.toList());
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
        if (!(o instanceof StackTraceSnapshot))
            return false;
        StackTraceSnapshot rhs = (StackTraceSnapshot) o;
        return this.toCompareObject().equals(rhs.toCompareObject());
    }

    @Override
    public String toString()
    {
        StringBuilder sb = new StringBuilder();
        sb.append("Stack:\n");
        toCompareObject().forEach(e -> sb.append(e + "\n"));
        return sb.toString();
    }

}
