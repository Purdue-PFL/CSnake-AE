package pfl.result_analysis;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream;

import com.github.luben.zstd.ZstdInputStream;
import com.github.luben.zstd.ZstdOutputStream;
import com.google.common.hash.HashCode;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import pfl.result_analysis.utils.BinaryUtils;
import pfl.result_analysis.utils.Utils;

public class ResultConvertToBinary 
{
    public static void main(String[] args) throws Exception
    {
        String profileResultRoot = Paths.get("/local1/qian151/vc-detect-workspace/loop-interference-result/profile").toString();
        String outputPath = "/local1/qian151/vc-detect-workspace/loop-interference-result/profile_binary";
        Set<String> finishedProfileRun = Files.lines(Paths.get(profileResultRoot, "progress.log")).map(e -> e.replaceAll("\\r|\\n", ""))
                .collect(Collectors.toCollection(ConcurrentHashMap::newKeySet));
        Type loopIDIterIDMapType = new TypeToken<ConcurrentHashMap<String, ArrayList<String>>>()
        {
        }.getType();
        Type iterEventsType = new TypeToken<ConcurrentHashMap<String, ArrayList<HashMap<String, String>>>>()
        {
        }.getType();

        AtomicInteger progressIndicator = new AtomicInteger();
        Consumer<String> processor = (runHash) ->
        {
            int currentProgress = progressIndicator.getAndIncrement();
            if ((currentProgress < 5) || (currentProgress % 100 == 0)) System.out.println("At: " + currentProgress + '\t' + runHash);
            try 
            {
                Path oldPathBase = Paths.get(profileResultRoot, runHash);
                Path newPathBase = Paths.get(outputPath, runHash);
                newPathBase.toFile().mkdirs();
                for (Path oldPath: Utils.toIterable(Files.walk(oldPathBase).iterator()))
                {
                    Path newPath = Paths.get(newPathBase.toString(), oldPathBase.relativize(oldPath).toString());
                    // System.out.println("Copying " + oldPath.toString() + " TO " + newPath.toString());
                    File f = oldPath.toFile();
                    if (f.isDirectory())
                    {
                        newPath.toFile().mkdirs();
                        continue;
                    }
                    if (oldPath.toString().endsWith("IterEvents.json") || oldPath.toString().endsWith("IterEvents.json.zst") || oldPath.toString().endsWith("IterEvents.json.xz"))
                    {
                        Map<String, List<Map<String, String>>> r = readJson(oldPath.toString(), iterEventsType);
                        String fn = oldPath.getFileName().toString().split("\\.")[0] + ".bin.zst";
                        // System.out.println("New Path: " + Paths.get(newPath.getParent().toString(), fn));
                        convertIterEvents(r, Paths.get(newPath.getParent().toString(), fn));
    
                    }
                    else if (oldPath.toString().endsWith("LoopIDIterIDMap.json") || oldPath.toString().endsWith("LoopIDIterIDMap.json.zst") || oldPath.toString().endsWith("LoopIDIterIDMap.json.xz"))
                    {
                        Map<String, List<String>> r = readJson(oldPath.toString(), loopIDIterIDMapType);
                        String fn = oldPath.getFileName().toString().split("\\.")[0] + ".bin";
                        // System.out.println("New Path: " + Paths.get(newPath.getParent().toString(), fn));
                        convertLoopIDIterIDMap(r, Paths.get(newPath.getParent().toString(), fn));
                    }
                    else 
                    {
                        Files.copy(oldPath, newPath, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            } catch (IOException e) 
            {
                System.out.println("Error with: " + runHash);
            }
        };
        ExecutorService es = Executors.newFixedThreadPool(16);
        finishedProfileRun.forEach(e -> es.submit(() -> processor.accept(e)));
        es.shutdown();
        es.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
    }

    public static void convertLoopIDIterIDMap(Map<String, List<String>> loopIDIterIDMap, Path outputPath)
    {
        try (FileOutputStream fos = new FileOutputStream(outputPath.toString()))
        {
            BufferedOutputStream bos = new BufferedOutputStream(fos);
            OutputStream os = bos;
            if (outputPath.toString().endsWith("zst"))
            {
                os = new ZstdOutputStream(bos, 19);
            }
            else if (outputPath.toString().endsWith("xz"))
            {
                os = new XZCompressorOutputStream(bos);
            }
            int magicNumber = 0x884832CB;
            os.write(BinaryUtils.intToByteArray(magicNumber));
            int version = 0x00000001;
            os.write(BinaryUtils.intToByteArray(version));
            os.write(BinaryUtils.longToByteArray(Long.valueOf(loopIDIterIDMap.size())));
            for (String loopKey: loopIDIterIDMap.keySet())
            {
                HashCode hc = HashCode.fromString(loopKey);
                os.write(hc.asBytes());
                os.write(BinaryUtils.longToByteArray(loopIDIterIDMap.get(loopKey).size()));
                for (String iterIDStr: loopIDIterIDMap.get(loopKey))
                {
                    UUID iterID = UUID.fromString(iterIDStr);
                    os.write(BinaryUtils.uuidAsBytes(iterID));
                }
            }
            os.close();
            if (outputPath.toString().endsWith("zst") || outputPath.toString().endsWith("xz")) bos.close();
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }
    }

    public static void convertIterEvents(Map<String, List<Map<String, String>>> iterEventsMap, Path outputPath)
    {
        try (FileOutputStream fos = new FileOutputStream(outputPath.toString()))
        {
            BufferedOutputStream bos = new BufferedOutputStream(fos);
            OutputStream os = bos;
            if (outputPath.toString().endsWith("zst"))
            {
                os = new ZstdOutputStream(bos, 12);
            }
            else if (outputPath.toString().endsWith("xz"))
            {
                os = new XZCompressorOutputStream(bos);
            }
            int magicNumber = 0x044832CB;
            os.write(BinaryUtils.intToByteArray(magicNumber));
            int version = 0x00000001;
            os.write(BinaryUtils.intToByteArray(version));
            os.write(BinaryUtils.longToByteArray(Long.valueOf(iterEventsMap.size())));
            for (String key: iterEventsMap.keySet())
            {
                UUID keyUUID = UUID.fromString(key);
                os.write(BinaryUtils.uuidAsBytes(keyUUID));
                os.write(BinaryUtils.longToByteArray(Long.valueOf(iterEventsMap.get(key).size())));
                for (Map<String, String> event: iterEventsMap.get(key))
                {
                    String typeStr = event.get("Type");
                    byte[] buf = new byte[21];      
                    if (typeStr.equals("BRANCH"))
                    {
                        String branchID = event.get("BranchID");
                        HashCode branchInstHashCode = HashCode.fromString(branchID.split("-")[0]);
                        int realBranchID = Integer.valueOf(branchID.split("-")[1]);
                        buf[0] = "B".getBytes(StandardCharsets.UTF_8)[0];
                        System.arraycopy(branchInstHashCode.asBytes(), 0, buf, 1, 16);
                        System.arraycopy(ByteBuffer.allocate(4).putInt(realBranchID).array(), 0, buf, 17, 4);
                    }
                    else if (typeStr.equals("INJECTION"))
                    {
                        String injectionID = event.get("InjectionID");
                        HashCode injectionIDHashCode = HashCode.fromString(injectionID);
                        buf[0] = "I".getBytes(StandardCharsets.UTF_8)[0];
                        System.arraycopy(injectionIDHashCode.asBytes(), 0, buf, 1, 16);
                    }
                    else if (typeStr.equals("LINK"))
                    {
                        String childIterID = event.get("ChildIterID");
                        UUID childIterUUID = new UUID(0, 0);
                        try 
                        {
                            UUID t = UUID.fromString(childIterID);
                            childIterUUID = t;
                        }
                        catch (IllegalArgumentException e)
                        {
                        }
                        buf[0] = "L".getBytes(StandardCharsets.UTF_8)[0];
                        System.arraycopy(BinaryUtils.uuidAsBytes(childIterUUID), 0, buf, 1, 16);
                    }
                    os.write(buf);
                }
            }
            os.close();
            if (outputPath.toString().endsWith("zst") || outputPath.toString().endsWith("xz")) bos.close();
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }
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
        return r;
    }
}
