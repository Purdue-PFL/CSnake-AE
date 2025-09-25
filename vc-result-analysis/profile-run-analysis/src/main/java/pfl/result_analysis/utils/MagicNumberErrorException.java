package pfl.result_analysis.utils;

import java.io.IOException;

public class MagicNumberErrorException extends IOException  
{
    private static final long serialVersionUID = -692484427888437183L;

    public MagicNumberErrorException()
    {
        super();
    }

    public MagicNumberErrorException(String msg)
    {
        super(msg);
    }

    public void setMsg(String msg)
    {
        
    }
}
