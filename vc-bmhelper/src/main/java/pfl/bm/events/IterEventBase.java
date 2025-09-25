package pfl.bm.events;

import java.io.Serializable;
import java.util.Map;
import java.util.UUID;

import com.google.common.hash.HashCode;

public abstract class IterEventBase implements Serializable
{
    private static final long serialVersionUID = 3898153549879897752L;

    public enum Type 
    {
        BRANCH, LINK, INJECTION 
    }    
    public Type type;

    abstract public Map<String, Object> toMap();

    // | 1Byte Type | Variable Length Content  | 
    // e.g. |B|32bit Mapped Branch ID          |
    //      |L|128bit child uuid               |
    //      |I|32bit Mapped Injection ID       |
    abstract public byte[] toByteArray();

}
