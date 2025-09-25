package pfl.result_analysis;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;
import org.apache.commons.io.input.UnsynchronizedBufferedInputStream;

import com.google.common.hash.HashCode;
import com.google.common.io.CountingInputStream;

import it.unimi.dsi.io.ByteBufferInputStream;
import pfl.result_analysis.utils.BinaryResultLoader;
import pfl.result_analysis.utils.BinaryUtils;
import pfl.result_analysis.utils.CustomUUID;
import pfl.result_analysis.utils.LoopSignature;
import pfl.result_analysis.utils.Utils;
import pfl.result_analysis.utils.VCHashBytes;

public class QuickResultInspector 
{
    public static void main(String[] args) throws Exception
    {
        Options options = new Options();
        options.addOption("input", true, "Input file");
        options.addOption("loop", true, "target loop");
        options.addOption("event", true, "target event");
        options.addOption("v2_result", false, "Reading v2 result");
        options.addOption("exec_id_map", true, "Exec ID String to integer map");
        CommandLineParser parser = new DefaultParser();
        CommandLine cmd = parser.parse(options, args);

        Map<String, Double> execIDMapper;
        Map<Integer, String> execIDInverseMap;
        boolean useV2Result = cmd.hasOption("v2_result");
        if (cmd.hasOption("v2_result"))
        {
            String execIDMapperPath = cmd.getOptionValue("exec_id_map");
            execIDMapper = Utils.readJson(execIDMapperPath);
            execIDInverseMap = execIDMapper.entrySet().stream().collect(Collectors.toConcurrentMap(e -> e.getValue().intValue(), e -> e.getKey()));
        }
        else
        {
            execIDInverseMap = null;
            System.out.println("WARNING: Use v1 result compatibility mode");
        }

        String inputPath = cmd.getOptionValue("input");
        String targetLoop = cmd.getOptionValue("loop");
        if (inputPath.endsWith("LoopIDIterIDMap.bin"))
        {
            File inputFile = new File(inputPath);
            Map<VCHashBytes, List<CustomUUID>> loopIDIterIDMap = BinaryResultLoader.loadLoopIDIterIDMap_v2(inputFile, execIDInverseMap);
            VCHashBytes loopKey = VCHashBytes.wrap(HashCode.fromString(targetLoop).asBytes());
            boolean containsLoop = loopIDIterIDMap.containsKey(loopKey);
            System.out.println(containsLoop);
            if (containsLoop)
            {
                System.out.println(loopIDIterIDMap.get(loopKey).size());
            }
            // for (VCHashBytes key: loopIDIterIDMap.keySet())
            // {
            //     System.out.println(key);
            // }
        }
        else 
        {
            File loopIDIterIDMapFile = Paths.get(inputPath, "LoopIDIterIDMap.bin").toFile();
            File iterEventsMapFile = Paths.get(inputPath, "IterEvents.bin").toFile();
            Map<VCHashBytes, List<CustomUUID>> loopIDIterIDMap = BinaryResultLoader.loadLoopIDIterIDMap_v2(loopIDIterIDMapFile, execIDInverseMap);
            Map<CustomUUID, List<VCHashBytes>> iterEventsMap = BinaryResultLoader.load_IterEventsMap_InjectionIterList_ReachedInjectionList_v2_simple(iterEventsMapFile, execIDInverseMap).getLeft();
            Map<VCHashBytes, Set<LoopSignature>> loopSignatures = BinaryResultLoader.buildLoopSignatureSingleIter(loopIDIterIDMap, iterEventsMap);
            // String targetEvent = cmd.getOptionValue("event");
            VCHashBytes targetLoopKey = VCHashBytes.wrap(HashCode.fromString(targetLoop).asBytes());
            // for (CustomUUID iterID: loopIDIterIDMap.get(targetLoopKey))
            // {
            //     System.out.println(iterEventsMap.get(iterID));
            // }
            for (LoopSignature sig: loopSignatures.get(VCHashBytes.wrap(HashCode.fromString(targetLoop).asBytes())))
            {
                for (VCHashBytes eventID: sig.rawSignature)
                {
                    System.out.println(eventID.toString());
                    // if (eventID.toString().equals(targetEvent)) System.out.println("Found");
                }
                int count = 0;
                for (CustomUUID iterID: loopIDIterIDMap.get(VCHashBytes.wrap(HashCode.fromString(targetLoop).asBytes())))
                {
                    if (iterEventsMap.get(iterID).equals(sig.rawSignature)) count++;
                }
                System.out.println("Count: " + count);
                System.out.println();
            }
            System.out.println(loopSignatures.get(VCHashBytes.wrap(HashCode.fromString(targetLoop).asBytes())));
        }
    }    

}
