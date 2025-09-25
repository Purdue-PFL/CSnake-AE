package pfl.result_analysis.graph;

import java.io.Serializable;

public class IterCountEdge extends LoopInterferenceEdge implements Serializable
{
    private static final long serialVersionUID = 1025255059436645302L;

    public IterCountEdge()
    {
        this.type = Type.ITER_COUNT_INTERFERENCE;
    }
}
