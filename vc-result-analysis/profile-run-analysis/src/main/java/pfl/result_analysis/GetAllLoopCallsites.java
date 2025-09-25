package pfl.result_analysis;

import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import com.google.common.collect.ImmutableList;
import com.google.common.hash.HashCode;
import com.google.common.primitives.Ints;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import pfl.result_analysis.ctx.LoopInterferenceAnalysisContext;
import pfl.result_analysis.utils.CustomUUID;
import pfl.result_analysis.utils.VCHashBytes;

public class GetAllLoopCallsites 
{
    public static LoopInterferenceAnalysisContext ctx;
    public static void main(String[] args) throws Exception
    {
        ctx = new LoopInterferenceAnalysisContext(args);
        Map<VCHashBytes, Set<ImmutableList<String>>> callSiteMap = new ConcurrentHashMap<>();
        AtomicInteger progressIndicator = new AtomicInteger();
        Consumer<String> processor = runHash -> 
        {
            int currentProgress = progressIndicator.getAndIncrement();
            if ((currentProgress < 5) || (currentProgress % 100 == 0)) System.out.println("At: " + currentProgress + '\t' + runHash);
            try
            {
                Map<VCHashBytes, List<CustomUUID>> loopIDIterIDMap = ctx.loadLoopIDIterIDMap(runHash);
                Map<CustomUUID, int[]> iterIDStackMethodIdMap = ctx.loadIterIDStackMethodIdMap(runHash);
                Map<Integer, String> methodIdxMap = ctx.loadMethodIdxMap(runHash);

                Map<CustomUUID, VCHashBytes> iterIDLoopIDMap = new HashMap<>();
                for (VCHashBytes loopKey: loopIDIterIDMap.keySet())
                {
                    loopIDIterIDMap.get(loopKey).forEach(iterID -> iterIDLoopIDMap.put(iterID, loopKey));
                }

                Map<List<Integer>, ImmutableList<String>> callSiteNameCache = new HashMap<>();
                for (CustomUUID iterID: iterIDStackMethodIdMap.keySet())
                {
                    VCHashBytes loopKey = iterIDLoopIDMap.get(iterID);
                    if (loopKey == null) continue;
                    List<Integer> stackMethodIDs = Ints.asList(iterIDStackMethodIdMap.get(iterID));
                    ImmutableList<String> callSite = callSiteNameCache.computeIfAbsent(stackMethodIDs, k -> 
                        stackMethodIDs.stream().map(e -> methodIdxMap.getOrDefault(e, "NONE")).collect(ImmutableList.toImmutableList()));
                    callSiteMap.computeIfAbsent(loopKey, k -> ConcurrentHashMap.newKeySet()).add(callSite);
                }
                callSiteNameCache.clear();;
            }
            catch (Exception e)
            {
                System.out.println("Exception at: " + runHash);
                System.out.println(e);
            }
        };

        ExecutorService es = Executors.newFixedThreadPool(ctx.nThread);
        for (String profileRunHash: ctx.finishedProfileRun)
        {
            es.submit(() -> processor.accept(profileRunHash));
        }
        for (String injectionRunHash : ctx.finishedInjectionRun)
        {
            es.submit(() -> processor.accept(injectionRunHash));
        }
        es.shutdown();
        es.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);

        Map<String, Set<ImmutableList<String>>> callSiteMapT = callSiteMap.entrySet().stream().collect(Collectors.toMap(e -> e.getKey().toString(), e -> e.getValue()));
        Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        Path loopCallsitePath = Paths.get(ctx.outputPath, ctx.outputPrefix + "AllLoopCallsites.json");
        try (BufferedWriter bw = Files.newBufferedWriter(loopCallsitePath))
        {
            gson.toJson(callSiteMapT, bw);
            bw.flush();
        }
    }

}
