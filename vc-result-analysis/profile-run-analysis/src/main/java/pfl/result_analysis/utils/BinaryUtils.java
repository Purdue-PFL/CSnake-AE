package pfl.result_analysis.utils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.UUID;

import org.apache.commons.compress.utils.ByteUtils.InputStreamByteSupplier;

public class BinaryUtils 
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

    // Zero-Indexed
    public static int byteArrayToInt(byte[] buf, int index)
    {
        ByteBuffer bb = ByteBuffer.wrap(buf);
        return bb.getInt(index * 4);
    }

    public static int[] byteArrayToIntArray(byte[] buf)
    {
        ByteBuffer bb = ByteBuffer.wrap(buf);
        int[] r = new int[buf.length / 4];
        for (int i = 0; i < buf.length / 4; i++)
        {
            r[i] = bb.getInt();
        }
        return r;
    }

    public static long[] byteArrayToLongArray(byte[] buf)
    {
        ByteBuffer bb = ByteBuffer.wrap(buf);
        long[] r = new long[buf.length / 8];
        for (int i = 0; i < buf.length / 8; i++)
        {
            r[i] = bb.getLong();
        }
        return r;
    }

    public static int readInt(InputStream is) throws IOException
    {
        return byteArrayToInt(readBytes(is, 4));
    }

    public static long readLong(InputStream is) throws IOException
    {
        return byteArrayToLong(readBytes(is, 8));
    }

    public static UUID readUUID(InputStream is) throws IOException
    {
        return bytesAsUUID(readBytes(is, 16));
    }

    public static CustomUUID readCustomUUID(InputStream is) throws IOException
    {
        return CustomUUID.wrap(readBytes(is, 16));
    }

    public static int[] readyIntArray(InputStream is, int count) throws IOException
    {
        return byteArrayToIntArray(readBytes(is, count * 4));
    }

    public static long[] readLongArray(InputStream is, int count) throws IOException 
    {
        return byteArrayToLongArray(readBytes(is, count * 8));
    }

    public static int readInt(ByteBuffer bb) throws IOException
    {
        return bb.getInt();
    }

    public static long readLong(ByteBuffer bb) throws IOException
    {
        return bb.getLong();
    }

    public static UUID readUUID(ByteBuffer bb) throws IOException
    {
        return bytesAsUUID(readBytes(bb, 16));
    }

    public static CustomUUID readCustomUUID(ByteBuffer bb) throws IOException
    {
        return CustomUUID.wrap(readBytes(bb, 16));
    }

    public static byte[] readBytes(ByteBuffer bb, int bytesLen)
    {
        byte[] buf = new byte[bytesLen];
        bb.get(buf);
        return buf;
    }

    public static byte[] readBytes(InputStream is, int bytesLen) throws IOException
    {
        byte[] buf = new byte[bytesLen];
        is.read(buf);
        return buf;
    }

}
