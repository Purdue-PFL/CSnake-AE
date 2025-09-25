package pfl.bm;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

// import sun.misc.SharedSecrets;

public class Utils 
{
    public static UUID bytesAsUUID(byte[] bytes)
    {
        ByteBuffer bb = ByteBuffer.wrap(bytes);
        long firstLong = bb.getLong();
        long secondLong = bb.getLong();
        return new UUID(firstLong, secondLong);
    }

    public static byte[] uuidAsBytes(UUID uuid)
    {
        if (uuid == null) return new byte[16];
        ByteBuffer bb = ByteBuffer.allocate(16);
        bb.putLong(uuid.getMostSignificantBits());
        bb.putLong(uuid.getLeastSignificantBits());
        return bb.array();
    }    

    public static byte[] longToByteArray(long n)
    {
        ByteBuffer buf = ByteBuffer.allocate(8);
        buf.putLong(n);
        return buf.array();
    }

    public static long byteArrayToLong(byte[] buf)
    {
        ByteBuffer bb = ByteBuffer.wrap(buf);
        return bb.getLong();
    }

    public static byte[] intToByteArray(int n)
    {
        ByteBuffer buf = ByteBuffer.allocate(4);
        buf.putInt(n);
        return buf.array();
    }

        public static int byteArrayToInt(byte[] buf)
    {
        ByteBuffer bb = ByteBuffer.wrap(buf);
        return bb.getInt();
    }

    public static byte[] intArrayToByteArray(int[] arr)
    {
        ByteBuffer buf = ByteBuffer.allocate(arr.length * 4);
        IntBuffer intBuf = buf.asIntBuffer();
        intBuf.put(arr);
        return buf.array();
    }

    // public static List<StackTraceElement> getStack(int startFrame, int frameCount)
    // {
    //     Exception e = new Exception();
    //     int requestedDepth = startFrame + frameCount - 1;
    //     int maxDepth = Math.min(requestedDepth, SharedSecrets.getJavaLangAccess().getStackTraceDepth(e));
    //     List<StackTraceElement> stack = new ArrayList<>();
        
    //     for (int frameNo = startFrame; frameNo <= maxDepth; frameNo++)
    //     {
    //         StackTraceElement elem = SharedSecrets.getJavaLangAccess().getStackTraceElement(e, frameNo);
    //         stack.add(elem);
    //     }

    //     return stack;
    // }

}
