package pfl.bm;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collector;
import java.util.stream.Collectors;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

import com.google.common.hash.HashCode;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import pfl.bm.events.BranchEvent;
import pfl.bm.events.IterEventBase;

/* 
* Map<UUID, List<IterEventBase>> iterEventsMap
* Format:
* | 0x044832CB | 0x00000001 (Ver 1.0) | 64 bit Map length | key1: 128bit UUID | 64 bit Value List Length | value1: 8bit Type + 128bit ID + 32bit other | value2 ... |
* | key2: ...  |
*/
public class IterEventBinaryDecodeVerifier 
{
    public static void main(String[] args) throws IOException, ParseException
    {
        Options options = new Options();
        options.addOption("b", "bin", true, "BIN File");
        options.addOption("j", "json", true, "JSON File");
        CommandLineParser parser = new DefaultParser();
        CommandLine cmd = parser.parse(options, args);
        String jsonFile = cmd.getOptionValue("json");
        String binFile = cmd.getOptionValue("bin");
        
        Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        Type iterEventsType = new TypeToken<LinkedHashMap<String, ArrayList<HashMap<String, String>>>>()
                {
                }.getType();
        File iterEventsFile = new File(jsonFile);
        Reader iterEventsFileReader = new BufferedReader(new FileReader(iterEventsFile));
        Map<String, List<Map<String, String>>> iterEvents1 = gson.fromJson(iterEventsFileReader, iterEventsType);
        iterEventsFileReader.close();

        FileInputStream fis = new FileInputStream(binFile);
        BufferedInputStream bis = new BufferedInputStream(fis);
        byte[] bufInt = new byte[4];
        byte[] bufLong = new byte[8];
        byte[] buf128b = new byte[16];
        bis.read(bufInt);
        int magicNumber = Utils.byteArrayToInt(bufInt);
        if (magicNumber != 0x044832CB) System.out.println("Magic Number Error");
        bis.read(bufInt);
        int version = Utils.byteArrayToInt(bufInt);
        if (version != 0x00000001) System.out.println("Version Error");
        bis.read(bufLong);
        long iterEventLength = Utils.byteArrayToLong(bufLong);
        Map<String, List<Map<String, String>>> iterEvents2 = new HashMap<>();
        for (int i = 0; i < iterEventLength; i++)
        {
            bis.read(buf128b);
            String iterID = Utils.bytesAsUUID(buf128b).toString();
            if (!iterEvents1.containsKey(iterID)) System.out.println("Key Error: " + iterID);
            bis.read(bufLong);
            long valueLength = Utils.byteArrayToLong(bufLong);
            if (valueLength != iterEvents1.get(iterID).size()) System.out.println("Value Length Error, Key: " + iterID);
            List<Map<String, String>> iterEvents = new ArrayList<>();
            for (int j = 0; j < valueLength; j++)
            {
                byte eventTypeRaw = (byte) bis.read(); 
                String eventType = new String(new byte[] {eventTypeRaw}, StandardCharsets.UTF_8);
                Map<String, String> r = new LinkedHashMap<>();
                if (eventType.equals("B"))
                {
                    bis.read(buf128b);
                    HashCode branchInstIDHC = HashCode.fromBytes(buf128b);
                    bis.read(bufInt);
                    int realBranchID = Utils.byteArrayToInt(bufInt);
                    String branchID = branchInstIDHC.toString() + "-" + String.valueOf(realBranchID);
                    r.put("Type", IterEventBase.Type.BRANCH.toString());
                    r.put("BranchID", branchID);
                    iterEvents.add(r);
                }
                else if (eventType.equals("I"))
                {
                    bis.read(buf128b);
                    HashCode injectionIDHC = HashCode.fromBytes(buf128b);
                    bis.read(bufInt);
                    String injectionID = injectionIDHC.toString();
                    r.put("Type", IterEventBase.Type.INJECTION.toString());
                    r.put("InjectionID", injectionID);
                    iterEvents.add(r);
                }
                else if (eventType.equals("L"))
                {
                    bis.read(buf128b);
                    UUID childIterIDUUID = Utils.bytesAsUUID(buf128b);
                    bis.read(bufInt);
                    String childIterID = childIterIDUUID.toString();
                    r.put("Type", IterEventBase.Type.LINK.toString());
                    r.put("ChildIterID", childIterID);
                    iterEvents.add(r);
                }
                Map<String, String> ref = iterEvents1.get(iterID).get(j);
                if ((!r.keySet().equals(ref.keySet())) || (!r.values().stream().collect(Collectors.toSet()).equals(ref.values().stream().collect(Collectors.toSet()))))
                {
                    System.out.println("Value Error, Key: " + iterID + " Idx: " + j);
                    System.out.println("Ref:");
                    System.out.println(ref);
                    System.out.println("Cmp:");
                    System.out.println(r);
                }
            }
            iterEvents2.put(iterID, iterEvents);
        }
    }
    
}
