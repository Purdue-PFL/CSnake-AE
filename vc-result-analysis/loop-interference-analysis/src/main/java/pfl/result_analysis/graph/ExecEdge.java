package pfl.result_analysis.graph;

import java.io.Serializable;

public class ExecEdge extends LoopInterferenceEdge implements Serializable
{
    private static final long serialVersionUID = 258908722416052152L;

    public ExecEdge()
    {
        this.type = Type.EXEC_INTERFERENCE;
    }
}
