package pfl.bm.events;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import com.google.common.hash.HashCode;

public class InjectionEvent extends IterEventBase 
{
    private static final long serialVersionUID = -6289860354817303448L;
    public int injectionID;

    public InjectionEvent(int injectionID)
    {
        this.type = Type.INJECTION;
        this.injectionID = injectionID;
    }

    @Override
    public Map<String, Object> toMap()
    {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("Type", this.type.toString());
        r.put("InjectionID", injectionID);
        return r;
    }

    @Override
    public byte[] toByteArray()
    {
        byte[] r = new byte[5];
        ByteBuffer rBuf = ByteBuffer.wrap(r);
        rBuf.put("I".getBytes(StandardCharsets.UTF_8)[0]);
        rBuf.putInt(injectionID);
        return r;
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(type, injectionID);
    }

    @Override
    public boolean equals(Object o)
    {
        if (!(o instanceof InjectionEvent)) return false;
        InjectionEvent rhs = (InjectionEvent) o;
        return this.injectionID == rhs.injectionID;
    }
}
