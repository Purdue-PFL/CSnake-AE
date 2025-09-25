package pfl.test_execute;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Enumeration;
import java.util.Map;

import com.google.common.graph.Network;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

public class Utils
{
    @SuppressWarnings("unchecked")
    public static void modifyAnnotationValue(Annotation annotation, String key, Object value)
    {
        if (annotation == null) return;
        // System.out.println(annotation);
        Object handler = Proxy.getInvocationHandler(annotation);
        Field f;
        try
        {
            f = handler.getClass().getDeclaredField("memberValues");
        }
        catch (NoSuchFieldException | SecurityException e)
        {
            throw new IllegalStateException(e);
        }
        f.setAccessible(true);
        Map<String, Object> memberValues;
        try
        {
            memberValues = (Map<String, Object>) f.get(handler);
            // System.out.println(memberValues);
        }
        catch (IllegalArgumentException | IllegalAccessException e)
        {
            throw new IllegalStateException(e);
        }
        // System.out.println("Key: " + key + " Value: " + value);
        memberValues.put(key, value);
        // System.out.println(memberValues);
        // System.out.println(annotation);
    }

    public static String getLocalIPAddr()
    {
        try (Socket socket = new Socket())
        {
            socket.connect(new InetSocketAddress("google.com", 80));
            return socket.getLocalAddress().getHostAddress();
        }
        catch (IOException e)
        {
        }
        return "0.0.0.0";
    }

    public static String getDocketHostIPAddr() 
    {
        NetworkInterface iface;
        try
        {
            iface = NetworkInterface.getByName("eth0");
            Enumeration<InetAddress> inetAddress = iface.getInetAddresses();
            InetAddress currentAddress;
            currentAddress = inetAddress.nextElement();
            while(inetAddress.hasMoreElements())
            {
                currentAddress = inetAddress.nextElement();
                if(currentAddress instanceof Inet4Address && !currentAddress.isLoopbackAddress())
                {
                    break;
                }
            }
            String localIPStr = currentAddress.getHostAddress();
            String[] ipSegs = localIPStr.split("\\.");
            ipSegs[3] = "1";
            return String.join(".", ipSegs);
        }
        catch (SocketException e)
        {
            return "127.0.0.1";
        }

    }

    public static <T> T readJson(String path) throws IOException
    {
        Type type = new TypeToken<T>(){}.getType();
        Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        try (BufferedReader r = Files.newBufferedReader(Paths.get(path)))
        {
            return gson.fromJson(r, type);
        }
    }

}
