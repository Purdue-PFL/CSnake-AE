package pfl.result_analysis.utils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.UUID;

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
