package pfl.result_analysis.graph;

import java.io.Serializable;

public class RPCEdge extends LoopInterferenceEdge implements Serializable
{
    private static final long serialVersionUID = 1778164015373510270L;

    public RPCEdge()
    {
        this.type = Type.RPC;
    }
}
