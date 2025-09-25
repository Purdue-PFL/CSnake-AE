package pfl;

import java.io.FileWriter;
import java.io.PrintWriter;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;
import org.junit.platform.engine.FilterResult;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.discovery.ClassNameFilter;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.PostDiscoveryFilter;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;
import org.junit.platform.launcher.core.LauncherConfig;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;

import com.google.common.reflect.ClassPath;
import com.google.common.reflect.ClassPath.ClassInfo;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import pfl.util.Utils;

public class GetJunit5ParameterizedTest 
{
    public static void main(String[] args) throws Exception
    {
        Options options = new Options();
        options.addOption("static_tests_json", true, "Statically found test cases");
        options.addOption("o", "output", true, "Output Directory");
        CommandLineParser parser = new DefaultParser();
        CommandLine cmd = parser.parse(options, args);

        String outputDir = cmd.getOptionValue("output");

        List<String> staticTestsCasesFound = Utils.readJson(cmd.getOptionValue("static_tests_json"));
        Set<String> neededTestClasses = new HashSet<>();
        for (String testFullName: staticTestsCasesFound)
        {
            neededTestClasses.add(testFullName.split("\\#")[0]);
        }
        ClassPath classPath = ClassPath.from(ClassLoader.getSystemClassLoader());
        Set<ClassInfo> classes = classPath.getAllClasses();
        MyListener listener = new MyListener();
        for (ClassInfo ci: classes)
        {   
            if (!neededTestClasses.contains(ci.getName())) continue;
            LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request().selectors(DiscoverySelectors.selectClass(ci.getName())).build();
            Launcher launcher = LauncherFactory.create();
            launcher.execute(request, listener);
        }


        try (PrintWriter pw = new PrintWriter(new FileWriter(Paths.get(outputDir, "test_params.json").toFile())))
        {
            Collections.sort(listener.uniqueIds);
            Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
            gson.toJson(listener.uniqueIds, pw);
        }
        System.exit(0);

        // LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
        //     // .selectors(DiscoverySelectors.selectMethod(args[0]))
        //     // .selectors(DiscoverySelectors.selectClass(args[0]))
        //     .selectors(DiscoverySelectors.selectUniqueId("[engine:junit-vintage]/[runner:org.apache.hadoop.ozone.TestContainerOperations]/[test:testCreate(org.apache.hadoop.ozone.TestContainerOperations)]"))
        //     .filters(new MyFilter())
        //     .build();
        // Launcher launcher = LauncherFactory.create();
        // // launcher.discover(request);
        // launcher.execute(request, new MyListener());
    }   
}

class MyFilter implements PostDiscoveryFilter
{
    @Override
    public FilterResult apply(TestDescriptor desc)
    {
        System.out.println("||| " + desc.getDisplayName());
        System.out.println("+++ " + desc.getUniqueId());
        System.out.println();
        return FilterResult.included("A");
    }

}

class MyListener implements TestExecutionListener
{
    public List<String> uniqueIds = new ArrayList<>();
    @Override
    public void executionStarted(TestIdentifier id)
    {
        if (id.isTest())
        {
            uniqueIds.add(id.getUniqueId());
            System.out.println("++++ " + id.getUniqueId());
        }
    }
}