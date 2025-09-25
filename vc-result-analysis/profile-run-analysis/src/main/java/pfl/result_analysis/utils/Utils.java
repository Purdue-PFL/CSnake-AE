package pfl.result_analysis.utils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;

import com.github.luben.zstd.ZstdInputStream;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

public class Utils 
{
    public static <T> Iterable<T> toIterable(Iterator<T> it) 
    {
        return () -> it;
    }

    public static <T> T readJson(String path, Type ty) throws IOException
    {
        File jsonFile = new File(path);
        Reader jsonReader = null;
        if (path.endsWith(".json"))
        {
            jsonReader = new BufferedReader(new FileReader(jsonFile));
        }
        else if (path.endsWith(".json.zst"))
        {
            jsonReader = new BufferedReader(new InputStreamReader(new ZstdInputStream(new FileInputStream(jsonFile))));
        }
        else if (path.endsWith(".json.xz"))
        {
            jsonReader = new BufferedReader(new InputStreamReader(new XZCompressorInputStream(new FileInputStream(jsonFile))));
        }
        Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        T r = gson.fromJson(jsonReader, ty);
        jsonReader.close();
        return r;
    }

    public static <T> T readJson(String path) throws FileNotFoundException, IOException
    {
        Type retType = new TypeToken<T>()
        {
        }.getType();
        return readJson(path, retType);
    }    

    public static void commonPoolSyncRun(Runnable r) throws InterruptedException, ExecutionException
    {
        ForkJoinPool.commonPool().submit(r).get();
    }

    public static <T> T commonPoolSyncRun(Callable<T> r) throws InterruptedException, ExecutionException
    {
        return ForkJoinPool.commonPool().submit(r).get();
    }

    public static double[] truncateArray(double[] longerArray, double[] shorterArray)
    {
        assert longerArray.length > shorterArray.length;
        int length = shorterArray.length;
        return Arrays.copyOfRange(longerArray, 0, length);
    }

    public static boolean isAnvilRun()
    {
        try 
        {
            Process proc = Runtime.getRuntime().exec("whoami");
            BufferedReader stdin = new BufferedReader(new InputStreamReader(proc.getInputStream()));
            String username = stdin.readLine();
            return username.equals("x-sqian");
        }
        catch (IOException e)
        {
            return false;
        }
    }

    public static <T> void parallelRunTasks(Collection<T> tasks, Consumer<T> processor, int nThread) throws InterruptedException
    {
        ExecutorService es = Executors.newFixedThreadPool(nThread);
        tasks.forEach(e -> es.submit(() -> processor.accept(e)));
        es.shutdown();
        es.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
    }
}
