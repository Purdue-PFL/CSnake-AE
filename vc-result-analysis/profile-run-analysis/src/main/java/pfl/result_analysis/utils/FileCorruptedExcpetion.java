package pfl.result_analysis.utils;

import java.io.IOException;

public class FileCorruptedExcpetion extends IOException 
{
    private static final long serialVersionUID = -8769436895805773833L;

    public FileCorruptedExcpetion()
    {
        super();
    }

    public FileCorruptedExcpetion(String msg)
    {
        super(msg);
    }
}
