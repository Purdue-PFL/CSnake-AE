package pfl.result_analysis.graph;

import org.jgrapht.graph.DefaultEdge;

public class WrappingDefaultEdge extends DefaultEdge
{
    private static final long serialVersionUID = 402957203003820031L;
    public LoopInterferenceEdge wrappedEdge;

    public WrappingDefaultEdge(LoopInterferenceEdge wrappedEdge)
    {
        this.wrappedEdge = wrappedEdge;
    }

    @Override
    public String toString()
    {
        return getSource().toString() + " -> " + getTarget().toString() + " | " + wrappedEdge.toString();
    }
}
