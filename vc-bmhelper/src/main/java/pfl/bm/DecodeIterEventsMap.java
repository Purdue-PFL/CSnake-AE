// package pfl.bm;

// import java.io.FileInputStream;
// import java.io.FileOutputStream;
// import java.io.ObjectInputStream;
// import java.io.ObjectOutputStream;
// import java.io.PrintWriter;
// import java.util.Collection;
// import java.util.List;
// import java.util.Map;
// import java.util.UUID;
// import java.util.concurrent.ConcurrentHashMap;
// import java.util.stream.Collectors;
// import java.util.zip.GZIPInputStream;
// import java.util.zip.GZIPOutputStream;

// import org.apache.commons.cli.CommandLine;
// import org.apache.commons.cli.CommandLineParser;
// import org.apache.commons.cli.DefaultParser;
// import org.apache.commons.cli.Options;
// import org.apache.fury.Fury;
// import org.apache.fury.config.Language;
// import org.apache.fury.io.FuryInputStream;

// import com.google.gson.Gson;
// import com.google.gson.GsonBuilder;

// import pfl.bm.events.IterEventBase;

// public class DecodeIterEventsMap
// {
//     public static void main(String[] args) throws Exception
//     {
//         Options options = new Options();
//         options.addOption("i", "input", true, "Input .obj path");
//         options.addOption("o", "output", true, "Output .json path");
//         CommandLineParser parser = new DefaultParser();
//         CommandLine cmd = parser.parse(options, args);
//         String inputPath = cmd.getOptionValue("input");
//         String outputPath = cmd.getOptionValue("output");

//         Fury fury = Fury.builder().withLanguage(Language.XLANG).withRefTracking(true).requireClassRegistration(false).build();
//         FileInputStream fis = new FileInputStream(inputPath + "/LoopIDIterIDMap.obj");
//         FuryInputStream ffis = new FuryInputStream(fis);
//         Map<String, Collection<UUID>> loopIDIterIDMap = (Map<String, Collection<UUID>>) fury.deserialize(ffis);

//     }
// }
