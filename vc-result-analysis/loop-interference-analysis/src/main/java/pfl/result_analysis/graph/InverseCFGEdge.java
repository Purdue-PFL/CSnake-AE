package pfl.result_analysis.graph;

public class InverseCFGEdge extends LoopInterferenceEdge
{
    private static final long serialVersionUID = -6163696005097306975L;

    public InverseCFGEdge()
    {
        this.type = Type.INVERSE_CFG;
    }
}
