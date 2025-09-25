package pfl.result_analysis;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;

import com.github.luben.zstd.ZstdInputStream;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

public class TestZstd 
{
    public static void main(String[] args) throws Exception
    {
        Options options = new Options();
        options.addOption("i", "input", true, "Path to input");
        CommandLineParser parser = new DefaultParser();
        CommandLine cmd = parser.parse(options, args);

        String inputFile = cmd.getOptionValue("input");
        Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        Type loopIDIterIDMapType = new TypeToken<HashMap<String, List<String>>>(){}.getType();
        Reader reader = new BufferedReader(new InputStreamReader(new ZstdInputStream(new FileInputStream(inputFile))));
        Map<String, List<String>> loopIDIterIDMap = gson.fromJson(reader, loopIDIterIDMapType);
        try (PrintWriter pw = new PrintWriter("test.json"))
        {
            gson.toJson(loopIDIterIDMap, pw);
        }
    }
}

