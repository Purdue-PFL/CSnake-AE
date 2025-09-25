package pfl.result_analysis;

import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;

import com.google.common.hash.HashCode;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import pfl.result_analysis.jfr.JdkJfrParser;
import pfl.result_analysis.utils.Utils;
import pfl.result_analysis.utils.WrappedString;

public class ProfileRunJfrToCFGEdges
{
    public static void main(String[] args) throws Exception
    {
        Options options = new Options();
        options.addOption("profile_run_path", true, "path to profile run result");
        options.addOption("output_path", true, "output path");
        options.addOption("output_file_prefix", true, "prefix for outout files");
        options.addOption("profile_testplan", true, "profile_testplan_path");
        options.addOption("nthread", true, "number of parallel analysis executor");
        CommandLineParser parser = new DefaultParser();
        CommandLine cmd = parser.parse(options, args);

        Map<String, Map<String, Object>> profileTestplan = Utils.readJson(cmd.getOptionValue("profile_testplan"));
        String profileRunPathRoot = cmd.getOptionValue("profile_run_path");
        Map<WrappedString, Set<WrappedString>> cfgEdges = new ConcurrentHashMap<>();
        AtomicInteger progressIndicator = new AtomicInteger();
        Consumer<String> processor = runHash ->
        {
            int currentProgress = progressIndicator.getAndIncrement();
            if ((currentProgress < 5) || (currentProgress % 100 == 0)) System.out.println("At: " + currentProgress + '\t' + runHash);
            try
            {
                Path jfrPath = Paths.get(profileRunPathRoot, runHash, "trace.jfr");
                Map<WrappedString, Set<WrappedString>> oneCFGEdges = JdkJfrParser.convertOne(jfrPath.toString());
                for (WrappedString caller : oneCFGEdges.keySet())
                {
                    cfgEdges.computeIfAbsent(caller, k -> ConcurrentHashMap.newKeySet()).addAll(oneCFGEdges.get(caller));
                }
            }
            catch (Exception e)
            {
                System.out.println("Exception at: " + runHash);
                e.printStackTrace();
            }
        };

        ExecutorService es = Executors.newFixedThreadPool(Integer.valueOf(cmd.getOptionValue("nthread")));
        profileTestplan.keySet().forEach(e -> es.submit(() -> processor.accept(e)));
        es.shutdown();
        es.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);

        Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        Path outputPath = Paths.get(cmd.getOptionValue("output_path"), cmd.getOptionValue("output_file_prefix") + "_CFG_Edges.json");
        try (BufferedWriter bw = Files.newBufferedWriter(outputPath))
        {
            Map<String, Set<String>> cfgEdgesT = cfgEdges.entrySet().stream()
                    .collect(Collectors.toMap(e -> e.getKey().toString(), e -> e.getValue().stream().map(e2 -> e2.toString()).collect(Collectors.toSet())));
            gson.toJson(cfgEdgesT, bw);
            bw.flush();
        }
    }
}
