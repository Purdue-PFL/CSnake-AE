package pfl.result_analysis.test;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import pfl.result_analysis.utils.BinaryResultLoader;
import pfl.result_analysis.utils.CustomUUID;
import pfl.result_analysis.utils.Utils;
import pfl.result_analysis.utils.VCHashBytes;

public class TestBinaryResultLoader
{
    public static void main(String[] args) throws IOException
    {
        String iterIDStackMethodIdMapPath = "/mnt/asset_cs/qian151/vc-detect-workspace/loop-interference-result/hdfs292_injection/4a842804bb5e3b2fe90dac81993e5267/IterIDStackMethodIdMap.bin";
        Map<CustomUUID, int[]> iterIDStackMethodIdMap = BinaryResultLoader.loadIterIDStackMethodIdMap(new File(iterIDStackMethodIdMapPath));
        Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

        String methodIdxPath = "/mnt/asset_cs/qian151/vc-detect-workspace/loop-interference-result/hdfs292_injection/4a842804bb5e3b2fe90dac81993e5267/MethodIdx.json";
        Type methodIdxTType = new TypeToken<HashMap<Double, String>>()
        {
        }.getType();
        Map<Double, String> methodIdxT = Utils.readJson(methodIdxPath, methodIdxTType);
        Map<Integer, String> methodIdx = new HashMap<>();
        for (double key : methodIdxT.keySet())
        {
            int intKey = (int) Math.round(key);
            methodIdx.put(intKey, methodIdxT.get(key));
        }

        Map<VCHashBytes, List<CustomUUID>> loopIDIterIDMap = BinaryResultLoader.loadLoopIDIterIDMap(new File("/mnt/asset_cs/qian151/vc-detect-workspace/loop-interference-result/hdfs292_injection/4a842804bb5e3b2fe90dac81993e5267/LoopIDIterIDMap.bin"));
        Map<CustomUUID, VCHashBytes> iterIDLoopIDMap = new HashMap<>();
        for (VCHashBytes loopKey: loopIDIterIDMap.keySet())
        {
            loopIDIterIDMap.get(loopKey).forEach(e -> iterIDLoopIDMap.put(e, loopKey));
        }

        BinaryResultLoader.load_IterEventsMap_InjectionIterList_ReachedInjectionList(new File("/mnt/asset_cs/qian151/vc-detect-workspace/loop-interference-result/hdfs292_injection/4a842804bb5e3b2fe90dac81993e5267/IterEvents.bin"));

        Map<String, List<String>> tOut = iterIDStackMethodIdMap.entrySet().stream()
                .collect(Collectors.toMap(e -> Objects.toString(iterIDLoopIDMap.get(e.getKey())), e -> Arrays.stream(e.getValue()).boxed().map(idx -> methodIdx.get(idx)).collect(Collectors.toList()), (k1, k2) -> k1));
        try (PrintWriter pw = new PrintWriter("test.json"))
        {
            gson.toJson(tOut, pw);
        }
    }
}
