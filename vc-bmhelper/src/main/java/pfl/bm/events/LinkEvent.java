package pfl.bm.events;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.google.common.hash.HashCode;

import pfl.bm.StackTraceSnapshot;
import pfl.bm.Utils;

public class LinkEvent extends IterEventBase
{
    private static final long serialVersionUID = 146562461426499002L;
    // public StackTraceSnapshot callstack;
    public int childRunnableHash;
    public UUID childIterID;

    // public LinkEvent(StackTraceSnapshot callstack, int childRunnableHash, UUID childIterID)
    // {
    //     this.type = Type.LINK;
    //     this.callstack = callstack;
    //     this.childRunnableHash = childRunnableHash;
    //     this.childIterID = childIterID;
    // }

    public LinkEvent(int childRunnableHash, UUID childIterID)
    {
        this.type = Type.LINK;
        this.childRunnableHash = childRunnableHash;
        this.childIterID = childIterID;
    }

    @Override
    public Map<String, Object> toMap()
    {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("Type", this.type.toString());
        r.put("ChildIterID", String.valueOf(childIterID));
        // r.put("Callstack", callstack.toList());
        // r.put("ChildRunnableHash", childRunnableHash);
        return r;
    }

    @Override
    public byte[] toByteArray()
    {
        byte[] r = new byte[17];
        ByteBuffer rBuf = ByteBuffer.wrap(r);
        rBuf.put("L".getBytes(StandardCharsets.UTF_8)[0]);
        rBuf.put(Utils.uuidAsBytes(childIterID));
        return r;
    }
}
