package pfl;

import java.io.BufferedWriter;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;

import com.google.common.collect.Lists;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import pfl.analysis_util.UnitTestFinder;

public class GetAllUnitTests 
{
    public static void main(String[] args) throws Exception 
    {
        Options options = new Options();
        options.addOption("cp", "classpath", true, "Classpath");
        options.addOption("tcp", "testpath", true, "Test Classpath");
        options.addOption("otest", "testoutput", true, "Test Output Path");
        options.addOption("tcp_prefix", true, "Classpath prefix for unit test analysis scope, \"|\" splitted");
        options.addOption("target_system", true, "HDFS292 | HBase-8389 | HBase260");
        options.addOption("test_declaring_class_mapper_output", true, "Map the Junit Test class to the declaring class of the @Test method");
        CommandLineParser parser = new DefaultParser();
        CommandLine cmd = parser.parse(options, args);

        String classpath = cmd.getOptionValue("classpath");
        String tcpPrefix = cmd.getOptionValue("tcp_prefix", "org.apache.hadoop.hdfs");
        String targetSystem = cmd.getOptionValue("target_system", "HDFS292");

        Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        String testclassPath = cmd.getOptionValue("testpath");
        String testSearchCP = classpath + ":" + testclassPath;
        WalaModel model2 = new WalaModel(testSearchCP);
        UnitTestFinder testFinder = new UnitTestFinder(model2, targetSystem);
        List<String> tcpInclPrefix = tcpPrefix.contains("|") ? Lists.newArrayList(tcpPrefix.split("\\|")) : Lists.newArrayList(tcpPrefix);
        List<String> unitTests = testFinder.getTests(tcpInclPrefix, Lists.newArrayList());
        PrintWriter pw = new PrintWriter(cmd.getOptionValue("testoutput"));
        gson.toJson(unitTests, pw);
        pw.close();

        if (cmd.hasOption("test_declaring_class_mapper_output"))
        {
            try (BufferedWriter bw = Files.newBufferedWriter(Paths.get(cmd.getOptionValue("test_declaring_class_mapper_output"))))
            {
                gson.toJson(testFinder.testDeclaringClassMapper, bw);
            }
        }
    }    
}
