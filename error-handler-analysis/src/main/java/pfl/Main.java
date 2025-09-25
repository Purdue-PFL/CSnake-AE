package pfl;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;

import com.google.common.collect.Lists;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.PrintWriter;

import pfl.analysis_util.ErrorHandler;
import pfl.analysis_util.MethodIndexedErrorHandler;
import pfl.analysis_util.TestPlanGenerator;
import pfl.analysis_util.UnitTestFinder;
import pfl.analysis_util.UnitTestMatcher;
import pfl.patterns.IfThrow;
import pfl.util.Debugger;

public class Main
{
    public static void main(String[] args) throws Exception
    {
        // // Debugger.setFlag();
        // Options options = new Options();
        // options.addOption("cp", "classpath", true, "Classpath");
        // options.addOption("tcp", "testpath", true, "Test Classpath");
        // CommandLineParser parser = new DefaultParser();
        // CommandLine cmd = parser.parse(options, args);

        // String classpath = cmd.getOptionValue("classpath");
        // // System.out.println(classpath);
        // WalaModel model = new WalaModel(classpath);
        // IfThrow ifThrowAnalyzer = new IfThrow(model);
        // List<ErrorHandler> ehs = ifThrowAnalyzer.analyze(Lists.newArrayList("org.apache.hadoop.hdfs"), Lists.newArrayList("org.apache.hadoop.hdfs.protocol.proto"));
        // // Remove any error handlers which the exception type cannot be fully resolved
        // ehs.removeIf(eh -> eh.throwableSteps.stream().anyMatch(step -> step.type.equals("java.lang.Object")));
        // ehs.removeIf(eh -> eh.throwableSteps.stream().anyMatch(step -> step.type.equals("java.lang.InterruptedException")));

        // Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        // PrintWriter pw = new PrintWriter("./result/testplans/if-throw-handlers.json");
        // Set<MethodIndexedErrorHandler> exportEhs = ehs.stream().map(e -> new MethodIndexedErrorHandler(e)).collect(Collectors.toSet()); 
        // gson.toJson(exportEhs.stream().map(e -> e.toMap()).collect(Collectors.toList()), pw);
        // pw.close();
        // // Map EH with unit tests
        // if (cmd.hasOption("testpath"))
        // {
        //     String testclassPath = cmd.getOptionValue("testpath");
        //     String testSearchCP = classpath + ":" + testclassPath;
        //     WalaModel model2 = new WalaModel(testSearchCP);
        //     UnitTestFinder testFinder = new UnitTestFinder(model2);
        //     List<String> unitTests = testFinder.getTests();
        //     pw = new PrintWriter("./result/testplans/if-throw-unittests.json");
        //     gson.toJson(unitTests, pw);
        //     pw.close();
        // }


        // // Generate test plan
        // // pw = new PrintWriter("./result/if-throw-plan-2.json");
        // // gson.toJson(TestPlanGenerator.generate(ehs), pw);
        // // pw.close();

        // // PrintWriter pw = new PrintWriter("if-throw.txt");
        // // int index = 0;
        // // for (ErrorHandler eh: ehs)
        // // {
        // // pw.println("Error Handler " + index);
        // // pw.println(eh.toString());
        // // pw.flush();
        // // index++;
        // // }
        // // pw.close();
        // Debugger.close();
    }
}
