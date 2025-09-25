package pfl.result_analysis;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.ObjectOutputStream;
import java.lang.reflect.Type;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;
import org.apache.commons.lang3.tuple.ImmutableTriple;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import pfl.result_analysis.utils.BinaryResultLoader;
import pfl.result_analysis.utils.CustomUUID;
import pfl.result_analysis.utils.LoopSignature;
import pfl.result_analysis.utils.VCHashBytes;

public class BuildLoopSignatureLib 
{
    public static void main(String[] args) throws Exception
    {
        Options options = new Options();
        options.addOption("p", "path", true, "Path to the profile run result");
        options.addOption("o", "output", true, "Result output path");
        options.addOption("output_prefix", true, "Output suffix");
        options.addOption("profile_testplan", true, "path to profile run test plan");
        CommandLineParser parser = new DefaultParser();
        CommandLine cmd = parser.parse(options, args);

        String basePath = cmd.getOptionValue("path");
        String outputPath = cmd.getOptionValue("output");
        String outputPrefix = cmd.getOptionValue("output_prefix", "");
        outputPrefix = outputPrefix.equals("") ? outputPrefix : outputPrefix + "_";
        
        String profileTestPlanPath = cmd.getOptionValue("profile_testplan");
        Type profileTestPlanType = new TypeToken<ConcurrentHashMap<String, HashMap<String, Object>>>()
        {
        }.getType();
        Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        Map<String, Map<String, Object>> profileTestPlan;
        try (FileReader fr = new FileReader(profileTestPlanPath);
                BufferedReader br = new BufferedReader(fr);)
        {
            profileTestPlan = gson.fromJson(br, profileTestPlanType);
        }

        Map<VCHashBytes, Set<LoopSignature>> loopSignatureLib = new ConcurrentHashMap<>(); // LoopID: {[BranchID]}
        AtomicInteger progressIndicator = new AtomicInteger();
        Consumer<String> processor = profileRunHash -> // for (String profileRunHash: profileTestPlan.keySet())
        {
            int currentProgress = progressIndicator.getAndIncrement();
            if ((currentProgress < 5) || (currentProgress % 100 == 0)) System.out.println("At: " + currentProgress + '\t' + profileRunHash);
            try 
            {
                File loopIDIterIDMapFile = Paths.get(basePath, profileRunHash, "LoopIDIterIDMap.bin").toFile();
                File iterEventsFile = Paths.get(basePath, profileRunHash, "IterEvents.bin").toFile();
                if (!loopIDIterIDMapFile.exists() || !iterEventsFile.exists()) return;
    
                Map<VCHashBytes, List<CustomUUID>> loopIDIterIDMap = BinaryResultLoader.loadLoopIDIterIDMap(loopIDIterIDMapFile);
                ImmutableTriple<Map<CustomUUID, List<VCHashBytes>>, List<CustomUUID>, List<VCHashBytes>> tTriple = BinaryResultLoader
                        .load_IterEventsMap_InjectionIterList_ReachedInjectionList(iterEventsFile);
                Map<CustomUUID, List<VCHashBytes>> iterEvents = tTriple.getLeft();
                Map<VCHashBytes, Set<LoopSignature>> loopSignatures = BinaryResultLoader.buildLoopSignatureSingleIter(loopIDIterIDMap, iterEvents);
                loopSignatures.forEach((loopKey, loopSignature) -> loopSignatureLib.computeIfAbsent(loopKey, k -> ConcurrentHashMap.newKeySet()).addAll(loopSignature));
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
        };
        ExecutorService es = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() / 4);
        profileTestPlan.keySet().forEach(testHash -> es.submit(() -> processor.accept(testHash)));
        es.shutdown();
        es.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
        
        try (FileOutputStream fos = new FileOutputStream(Paths.get(outputPath, outputPrefix + "ProfileLoopSignatureLib.obj").toFile());
             BufferedOutputStream bos = new BufferedOutputStream(fos);
             ObjectOutputStream oos = new ObjectOutputStream(bos);)
        {
            oos.writeObject(loopSignatureLib);
            oos.flush();
            bos.flush();
            fos.flush();
        }
    }       
}
