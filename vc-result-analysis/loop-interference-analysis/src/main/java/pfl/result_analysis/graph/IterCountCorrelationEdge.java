package pfl.result_analysis.graph;

import java.io.Serializable;

public class IterCountCorrelationEdge extends LoopInterferenceEdge implements Serializable
{
    private static final long serialVersionUID = 7039181538583970842L;

    public IterCountCorrelationEdge()
    {
        this.type = Type.ITER_COUNT_CORRELATION;
    }
    
}
