package pfl.result_analysis.ctx;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;
import org.apache.commons.lang3.tuple.ImmutableTriple;
import org.apache.commons.math3.util.Precision;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.gson.reflect.TypeToken;

import it.unimi.dsi.io.ByteBufferInputStream;
import pfl.result_analysis.utils.BinaryResultLoader;
import pfl.result_analysis.utils.BinaryUtils;
import pfl.result_analysis.utils.CustomUUID;
import pfl.result_analysis.utils.FileCorruptedExcpetion;
import pfl.result_analysis.utils.MagicNumberErrorException;
import pfl.result_analysis.utils.Utils;
import pfl.result_analysis.utils.VCHashBytes;

public class LoopInterferenceAnalysisContext 
{
    public Options options;
    public CommandLineParser parser;
    public CommandLine cmd;
    public String profileResultRoot;
    public String injectionResultRoot;
    public String outputPath;
    public String outputPrefix;
    public List<String> finishedInjectionRun;
    public Set<String> finishedProfileRun;
    public Map<String, Map<String, Object>> injectionTestPlan;
    public Map<String, Map<String, Object>> profileTestPlan;
    public String perInjectionResultPath;
    public int nThread;
    public boolean isContinue;
    public boolean ANVIL_RUN = Utils.isAnvilRun();
    public boolean useV2Result;
    public Map<Integer, String> execIDInverseMap;

    public LoopInterferenceAnalysisContext(String[] args) throws Exception
    {
        options = new Options();
        options.addOption("profile_run_path", true, "path to profile run result");
        options.addOption("injection_run_path", true, "path to injection run result");
        options.addOption("output_path", true, "output path");
        options.addOption("output_file_prefix", true, "prefix for outout files");
        options.addOption("profile_testplan", true, "profile_testplan_path");
        options.addOption("injection_testplan", true, "path to injection testplan");
        options.addOption("per_injection_result", true, "Loop Interference result per injection");
        options.addOption("nthread", true, "number of parallel analysis executor");
        options.addOption("continue", false, "Continue where we have left");
        options.addOption("v2_result", false, "Reading v2 result");
        options.addOption("exec_id_map", true, "Exec ID String to integer map");
        parser = new DefaultParser();
        cmd = parser.parse(options, args);

        profileResultRoot = cmd.getOptionValue("profile_run_path");
        injectionResultRoot = cmd.getOptionValue("injection_run_path");
        outputPath = cmd.getOptionValue("output_path");
        outputPrefix = cmd.getOptionValue("output_file_prefix", "");
        if (outputPrefix.length() > 0) outputPrefix = outputPrefix + "_";

        Predicate<String> injectionFilter = ANVIL_RUN ? runHash -> Files.exists(Paths.get(injectionResultRoot, runHash + "_loop_events.bin")) 
                                                      : runHash -> Files.exists(Paths.get(injectionResultRoot, runHash));
        finishedInjectionRun = Files.lines(Paths.get(injectionResultRoot, "progress.log")).parallel().map(e -> e.replaceAll("\\r|\\n", ""))
                .filter(injectionFilter).collect(Collectors.toList());
        Predicate<String> profileFilter = ANVIL_RUN ? runHash -> Files.exists(Paths.get(profileResultRoot, runHash + "_loop_events.bin")) 
                                                    : runHash -> Files.exists(Paths.get(profileResultRoot, runHash));
        finishedProfileRun = Files.lines(Paths.get(profileResultRoot, "progress.log")).parallel().map(e -> e.replaceAll("\\r|\\n", ""))
                .filter(profileFilter).collect(Collectors.toCollection(ConcurrentHashMap::newKeySet));

        Type testplanType = new TypeToken<ConcurrentHashMap<String, HashMap<String, Object>>>()
        {
        }.getType();
        injectionTestPlan = Utils.readJson(cmd.getOptionValue("injection_testplan"), testplanType);
        profileTestPlan = Utils.readJson(cmd.getOptionValue("profile_testplan"), testplanType);

        perInjectionResultPath = cmd.getOptionValue("per_injection_result", injectionResultRoot);
        Paths.get(perInjectionResultPath).toFile().mkdirs();
        nThread = Integer.parseInt(cmd.getOptionValue("nthread", "12"));
        isContinue = cmd.hasOption("continue");

        useV2Result = cmd.hasOption("v2_result");
        if (cmd.hasOption("v2_result"))
        {
            String execIDMapperPath = cmd.getOptionValue("exec_id_map");
            Map<String, Double> execIDMapper = Utils.readJson(execIDMapperPath);
            execIDInverseMap = execIDMapper.entrySet().stream().collect(Collectors.toConcurrentMap(e -> e.getValue().intValue(), e -> e.getKey()));
        }
        else
        {
            execIDInverseMap = null;
            System.out.println("WARNING: Use v1 result compatibility mode");
        }
    }   
    
    public Map<CustomUUID, int[]> loadIterIDStackMethodIdMap(String key) throws IOException 
    {
        if (!this.ANVIL_RUN)
        {
            File path;
            if (this.finishedProfileRun.contains(key))
                path = Paths.get(profileResultRoot, key, "IterIDStackMethodIdMap.bin").toFile();
            else
                path = Paths.get(this.injectionResultRoot, key, "IterIDStackMethodIdMap.bin").toFile();
                Map<CustomUUID, int[]> r = BinaryResultLoader.loadIterIDStackMethodIdMap(path);
            return r;
        }
        else 
        {
            Path path;
            if (this.finishedProfileRun.contains(key))
                path = Paths.get(this.profileResultRoot, key + "_loop_events.bin");
            else 
                path = Paths.get(this.injectionResultRoot, key + "_loop_events.bin");
            
            FileInputStream fis = new FileInputStream(path.toFile());
            InputStream bbis;
            long fileSize = Files.size(path);
            if (fileSize > 50 * 1024 * 1024)
            {
                FileChannel channel = fis.getChannel();
                bbis = ByteBufferInputStream.map(channel, FileChannel.MapMode.READ_ONLY);
            }
            else
            {
                bbis = fis;
            }
            long[] concatFileLengths = BinaryUtils.readLongArray(bbis, 3); // LoopIDIterIDMap, IterEvents, IterIDStackMethodIDMap
            Map<CustomUUID, int[]> r;
            try 
            {
                bbis.skip(concatFileLengths[0] + concatFileLengths[1]);
                r = BinaryResultLoader.loadIterIDStackMethodIdMap(bbis, concatFileLengths[2]);
            }
            catch (IOException e)
            {
                if (e instanceof MagicNumberErrorException)
                {
                    e = new IOException("Magic Number Error: " + path, e);
                }
                else if (e instanceof FileCorruptedExcpetion)
                {
                    e = new IOException("IterIDStackMethodIdMap corrupted: " + path, e);
                }
                throw e;
            }
            finally 
            {
                bbis.close();
                fis.close();
            }
            return r;
        }
    }

    public Map<Integer, String> loadMethodIdxMap(String key) throws IOException
    {
        Path path;
        if (!this.ANVIL_RUN)
        {
            if (this.finishedProfileRun.contains(key))
                path = Paths.get(this.profileResultRoot, key, "MethodIdx.json");
            else
                path = Paths.get(this.injectionResultRoot, key, "MethodIdx.json");
        }
        else 
        {
            if (this.finishedProfileRun.contains(key))
                path = Paths.get(this.profileResultRoot, key + "_MethodIdx.json");
            else
                path = Paths.get(this.injectionResultRoot, key + "_MethodIdx.json");
        }
        Type methodIdxType = new TypeToken<HashMap<String, String>>()
        {
        }.getType();
        Map<String, String> rT = Utils.readJson(path.toString(), methodIdxType);
        Map<Integer, String> r = rT.entrySet().stream().collect(Collectors.toMap(e -> Integer.valueOf(e.getKey()), e -> e.getValue()));
        return r;
    }

    public Map<VCHashBytes, List<CustomUUID>> loadLoopIDIterIDMap(String key) throws IOException
    {
        if (!this.ANVIL_RUN)
        {
            File path;
            if (this.finishedProfileRun.contains(key))
                path = Paths.get(this.profileResultRoot, key, "LoopIDIterIDMap.bin").toFile();
            else
                path = Paths.get(this.injectionResultRoot, key, "LoopIDIterIDMap.bin").toFile();
            return useV2Result ? BinaryResultLoader.loadLoopIDIterIDMap_v2(path, execIDInverseMap) : BinaryResultLoader.loadLoopIDIterIDMap(path);
        }
        else 
        {
            Path path;
            if (this.finishedProfileRun.contains(key))
                path = Paths.get(this.profileResultRoot, key + "_loop_events.bin");
            else 
                path = Paths.get(this.injectionResultRoot, key + "_loop_events.bin");
            
            FileInputStream fis = new FileInputStream(path.toFile());
            InputStream bbis;
            long fileSize = Files.size(path);
            if (fileSize > 50 * 1024 * 1024)
            {
                FileChannel channel = fis.getChannel();
                bbis = ByteBufferInputStream.map(channel, FileChannel.MapMode.READ_ONLY);
            }
            else
            {
                bbis = fis;
            }
            long[] concatFileLengths = BinaryUtils.readLongArray(bbis, 3); // LoopIDIterIDMap, IterEvents, IterIDStackMethodIDMap
            Map<VCHashBytes, List<CustomUUID>> r;
            try
            {
                r = useV2Result ? BinaryResultLoader.loadLoopIDIterIDMap_v2(bbis, concatFileLengths[0], execIDInverseMap) : BinaryResultLoader.loadLoopIDIterIDMap(bbis, concatFileLengths[0]);
            }
            catch (IOException e)
            {
                if (e instanceof MagicNumberErrorException)
                {
                    e = new IOException("Magic Number Error: " + path, e);
                }
                else if (e instanceof FileCorruptedExcpetion)
                {
                    e = new IOException("LoopIDIterIDMap corrupted: " + path, e);
                }
                throw e;
            }
            finally 
            {
                bbis.close();
                fis.close();
            }
            return r;
        }
    }

    public Map<CustomUUID, List<VCHashBytes>> loadIterEventsMap(String key) throws IOException, ExecutionException
    {
        if (!this.ANVIL_RUN)
        {
            File path;
            if (this.finishedProfileRun.contains(key))
                path = Paths.get(this.profileResultRoot, key, "IterEvents.bin").toFile();
            else
                path = Paths.get(this.injectionResultRoot, key, "IterEvents.bin").toFile();
            ImmutableTriple<Map<CustomUUID, List<VCHashBytes>>, List<CustomUUID>, List<VCHashBytes>> r = useV2Result
                    ? BinaryResultLoader.load_IterEventsMap_InjectionIterList_ReachedInjectionList_v2_simple(path, execIDInverseMap)
                    : BinaryResultLoader.load_IterEventsMap_InjectionIterList_ReachedInjectionList(path);
            return r.getLeft();
        }
        else 
        {
            Path path;
            if (this.finishedProfileRun.contains(key))
                path = Paths.get(this.profileResultRoot, key + "_loop_events.bin");
            else 
                path = Paths.get(this.injectionResultRoot, key + "_loop_events.bin");
            
            FileInputStream fis = new FileInputStream(path.toFile());
            InputStream bbis;
            long fileSize = Files.size(path);
            if (fileSize > 50 * 1024 * 1024)
            {
                FileChannel channel = fis.getChannel();
                bbis = ByteBufferInputStream.map(channel, FileChannel.MapMode.READ_ONLY);
            }
            else
            {
                bbis = fis;
            }
            long[] concatFileLengths = BinaryUtils.readLongArray(bbis, 3); // LoopIDIterIDMap, IterEvents, IterIDStackMethodIDMap
            ImmutableTriple<Map<CustomUUID, List<VCHashBytes>>, List<CustomUUID>, List<VCHashBytes>> r;
            try 
            {
                bbis.skip(concatFileLengths[0]);
                r = useV2Result
                    ? BinaryResultLoader.load_IterEventsMap_InjectionIterList_ReachedInjectionList_v2_simple(bbis, concatFileLengths[1], execIDInverseMap)
                    : BinaryResultLoader.load_IterEventsMap_InjectionIterList_ReachedInjectionList(bbis, concatFileLengths[1]);
            }
            catch (IOException e)
            {
                if (e instanceof MagicNumberErrorException)
                {
                    e = new IOException("Magic Number Error: " + path, e);
                }
                else if (e instanceof FileCorruptedExcpetion)
                {
                    e = new IOException("IterEvents corrupted: " + path, e);
                }
                throw e;
            }
            finally 
            {
                bbis.close();
                fis.close();
            }
            return r.getLeft();
        }
    }

    public Map<CustomUUID, List<VCHashBytes>> loadIterEventsMap_v2(String key, Map<Integer, String> execIDInverseMap, 
        Map<CustomUUID, CustomUUID> out_parentIterIDMap, Map<CustomUUID, Long> out_iterIDTIDMap) throws IOException, ExecutionException
    {
        if (!this.ANVIL_RUN)
        {
            File path;
            if (this.finishedProfileRun.contains(key))
                path = Paths.get(this.profileResultRoot, key, "IterEvents.bin").toFile();
            else
                path = Paths.get(this.injectionResultRoot, key, "IterEvents.bin").toFile();
            ImmutableTriple<Map<CustomUUID, List<VCHashBytes>>, List<CustomUUID>, List<VCHashBytes>> r = BinaryResultLoader.load_IterEventsMap_InjectionIterList_ReachedInjectionList_v2(path, execIDInverseMap, out_parentIterIDMap, out_iterIDTIDMap);
            return r.getLeft();
        }
        else 
        {
            Path path;
            if (this.finishedProfileRun.contains(key))
                path = Paths.get(this.profileResultRoot, key + "_loop_events.bin");
            else 
                path = Paths.get(this.injectionResultRoot, key + "_loop_events.bin");
            
            FileInputStream fis = new FileInputStream(path.toFile());
            InputStream bbis;
            long fileSize = Files.size(path);
            if (fileSize > 50 * 1024 * 1024)
            {
                FileChannel channel = fis.getChannel();
                bbis = ByteBufferInputStream.map(channel, FileChannel.MapMode.READ_ONLY);
            }
            else
            {
                bbis = fis;
            }
            long[] concatFileLengths = BinaryUtils.readLongArray(bbis, 3); // LoopIDIterIDMap, IterEvents, IterIDStackMethodIDMap
            ImmutableTriple<Map<CustomUUID, List<VCHashBytes>>, List<CustomUUID>, List<VCHashBytes>> r;
            try 
            {
                bbis.skip(concatFileLengths[0]);
                r = BinaryResultLoader.load_IterEventsMap_InjectionIterList_ReachedInjectionList_v2(bbis, concatFileLengths[1], execIDInverseMap, out_parentIterIDMap, out_iterIDTIDMap);
            }
            catch (IOException e)
            {
                if (e instanceof MagicNumberErrorException)
                {
                    e = new IOException("Magic Number Error: " + path, e);
                }
                else if (e instanceof FileCorruptedExcpetion)
                {
                    e = new IOException("IterEvents corrupted: " + path, e);
                }
                throw e;
            }
            finally 
            {
                bbis.close();
                fis.close();
            }
            return r.getLeft();
        }
    }
}
