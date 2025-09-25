package pfl;

import java.io.PrintWriter;
import java.util.AbstractMap.SimpleEntry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;

import com.google.common.collect.Lists;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import pfl.analysis_util.exec_points.ExceptionInjectionPoint;
import pfl.patterns.AllThrowPoint;
import pfl.util.Utils;

public class ThrowPointMain
{
    public static void main(String[] args) throws Exception
    {
        // Debugger.setFlasg();
        Options options = new Options();
        options.addOption("cp", "classpath", true, "Classpath");
        options.addOption("o", "output", true, "Output Path");
        options.addOption("cp_prefix", true, "Classpath prefix for analysis scope, \"|\" splitted");
        options.addOption("cp_prefix_excl", true, "Excluded classpath prefix for analysis scope, \"|\" splitted");
        options.addOption("target_system", true, "HDFS292 | HBase-8389 | HBase260 | Cassandra500");
        CommandLineParser parser = new DefaultParser();
        CommandLine cmd = parser.parse(options, args);

        String classpath = cmd.getOptionValue("classpath");
        String outputPath = cmd.getOptionValue("output");
        String cpPrefix = cmd.getOptionValue("cp_prefix", "org.apache.hadoop.hdfs");
        String cpExclPrefix = cmd.getOptionValue("cp_prefix_excl", "org.apache.hadoop.hdfs.protocol.proto");
        String targetSystem = cmd.getOptionValue("target_system", "HDFS292");
        WalaModel model = new WalaModel(classpath);
        AllThrowPoint ifThrowAnalyzer = new AllThrowPoint(model);
        List<String> inclPrefix = cpPrefix.contains("|") ? Lists.newArrayList(cpPrefix.split("\\|")) : Lists.newArrayList(cpPrefix);
        List<String> exclPrefix = cpExclPrefix.contains("|") ? Lists.newArrayList(cpExclPrefix.split("\\|")) : Lists.newArrayList(cpExclPrefix);
        if (cpExclPrefix.length() == 0) exclPrefix = Lists.newArrayList();
        List<ExceptionInjectionPoint> ehs = ifThrowAnalyzer.analyze(inclPrefix, exclPrefix);
        // List<ErrorHandler> ehs = ifThrowAnalyzer.analyze(Lists.newArrayList("ThrowPointTest"), Lists.newArrayList("org", "javax"));
        ehs = filterErrorHandlers(ehs, targetSystem, exclPrefix);
        System.out.println("Useful Error Handler Count: " + ehs.size());

        Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        PrintWriter pw = new PrintWriter(outputPath);
        // Set<MethodIndexedErrorHandler> exportEhs = ehs.stream().map(e -> new MethodIndexedErrorHandler(e)).collect(Collectors.toSet());
        // // List<MethodIndexedErrorHandler> exportEhs = ehs.stream().map(e -> new MethodIndexedErrorHandler(e)).collect(Collectors.toList());
        // gson.toJson(exportEhs.stream().map(e -> e.toMap()).collect(Collectors.toList()), pw);
        gson.toJson(
                ehs.stream().map(e -> new SimpleEntry<>(e.getID(), e.toMap()))
                        .collect(Collectors.toMap(SimpleEntry::getKey, SimpleEntry::getValue, (loopKey1, loopKey2) -> loopKey1, LinkedHashMap::new)),
                LinkedHashMap.class, pw);
        pw.close();
    }

    public static List<ExceptionInjectionPoint> filterErrorHandlers(List<ExceptionInjectionPoint> ehs, String targetSystem, List<String> exclPrefix)
    {
        // Remove any error handlers which the exception type cannot be fully resolved
        ehs.removeIf(eh -> eh.throwableSteps.stream().anyMatch(step -> step.type.equals("java.lang.Object")));
        ehs.removeIf(eh -> eh.throwableSteps.stream().anyMatch(step -> step.type.equals("java.lang.InterruptedException")));
        ehs.removeIf(eh -> eh.throwableSteps.stream().anyMatch(step -> step.type.equals("java.lang.Throwable")));

        // Meaningless exceptions to inject
        ehs.removeIf(eh -> eh.throwableSteps.stream().anyMatch(step -> step.type.equals("java.lang.IllegalArgumentException")));
        ehs.removeIf(eh -> eh.throwableSteps.stream().anyMatch(step -> step.type.equals("java.lang.NumberFormatException")));
        ehs.removeIf(eh -> eh.throwableSteps.stream().anyMatch(step -> step.type.contains("InstantiationException")));
        ehs.removeIf(eh -> eh.throwableSteps.stream().anyMatch(step -> step.type.contains("NoSuchMethodException")));
        ehs.removeIf(eh -> eh.throwableSteps.stream().anyMatch(step -> step.type.contains("IllegalAccessException")));
        ehs.removeIf(eh -> eh.throwableSteps.stream().anyMatch(step -> step.type.contains("ClassNotFoundException")));
        ehs.removeIf(eh -> eh.throwableSteps.stream().anyMatch(step -> step.type.contains("NoSuchFieldException")));
        ehs.removeIf(eh -> eh.throwableSteps.stream().anyMatch(step -> step.type.contains("InvocationTargetException")));
        ehs.removeIf(eh -> eh.throwableSteps.stream().anyMatch(step -> step.type.contains("java.security")));
        ehs.removeIf(eh -> eh.throwableSteps.stream().anyMatch(step -> step.type.contains("java.lang.UnsupportedOperationException")));
        ehs.removeIf(eh -> eh.throwableSteps.stream().anyMatch(step -> step.type.contains("SecurityException")));
        ehs.removeIf(eh -> eh.throwableSteps.stream().anyMatch(step -> step.type.contains("javax.security")));

        ehs.removeIf(eh -> eh.clazz.isAbstract());
        ehs.removeIf(eh -> eh.throwableSteps.stream().anyMatch(step -> exclPrefix.stream().anyMatch(prefix -> step.type.startsWith(prefix))));

        if (targetSystem.equals("Cassandra500"))
        {
            ehs.removeIf(eh -> eh.throwableSteps.stream().anyMatch(step -> step.type.equals("org.apache.cassandra.exceptions.ConfigurationException")));
        }
        else if (targetSystem.equals("Flink120"))
        {
            ehs.removeIf(eh -> eh.throwableSteps.stream().anyMatch(step -> step.type.equals("java.lang.Error")));
        }
        else if (targetSystem.equals("OZone140"))
        {
            ehs.removeIf(eh -> eh.throwableSteps.stream().anyMatch(step -> step.type.contains("org.apache.hadoop.hdds.security.exception")));
        }
        else if (targetSystem.equals("HDFS341"))
        {
            ehs.removeIf(eh -> eh.throwableSteps.stream().anyMatch(step -> step.type.contains("org.apache.hadoop.security")));
            ehs.removeIf(eh -> eh.throwableSteps.stream().anyMatch(step -> step.type.equals("org.apache.hadoop.hdfs.server.namenode.UnsupportedActionException")));
            ehs.removeIf(eh -> eh.throwableSteps.stream().anyMatch(step -> step.type.equals("org.apache.hadoop.HadoopIllegalArgumentException")));
        }
        else if (targetSystem.equals("HDFS292"))
        {
            ehs.removeIf(eh -> eh.throwableSteps.stream().anyMatch(step -> step.type.equals("org.apache.hadoop.HadoopIllegalArgumentException")));
            ehs.removeIf(eh -> eh.throwableSteps.stream().anyMatch(step -> step.type.equals("org.apache.hadoop.hdfs.server.namenode.UnsupportedActionException")));
        }
        else if (targetSystem.equals("HBase260"))
        {
            ehs.removeIf(eh -> Utils.getShortMethodName(eh.method).contains("__jamon"));
        }
        ehs.removeIf(eh -> Utils.getShortMethodName(eh.method).contains("clinit"));

        return ehs;
    }
}
