package pfl.result_analysis.graph;

import java.io.Serializable;

public class CFGEdge extends LoopInterferenceEdge implements Serializable
{
    private static final long serialVersionUID = -673752784012336024L;
    public CFGEdge()
    {
        this.type = Type.CFG;
    }
}
