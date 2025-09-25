package pfl;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOError;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;
import org.junit.runner.Description;
import org.junit.runner.JUnitCore;
import org.junit.runner.Request;
import org.junit.runner.manipulation.Filter;
import org.junit.runner.notification.RunNotifier;

import com.google.common.reflect.ClassPath;
import com.google.common.reflect.ClassPath.ClassInfo;

import pfl.util.Utils;

public class GetAllParameterizedTest 
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
        List<String> testClasses = new ArrayList<>();
        for (ClassInfo ci: classes)
        {   
            String classSimpleName = ci.getSimpleName();
            // Rules from maven-surefire-plugin 
            if (classSimpleName.startsWith("Test") || classSimpleName.endsWith("Test") || classSimpleName.endsWith("Tests") || classSimpleName.endsWith("TestCase") || classSimpleName.endsWith("TCase"))
            {
                if (neededTestClasses.contains(ci.getName()))
                {
                    testClasses.add(ci.getName());
                }
            }
        }

        Set<String> processed = new HashSet<>();
        Path progressPath = Paths.get(outputDir, "progress.log");
        boolean append = false;
        // if (Files.exists(progressPath))
        // {
        //     Files.lines(progressPath).forEach(e -> processed.add(e));
        //     append = true;
        // }
        try (PrintWriter pw = new PrintWriter(new FileWriter(Paths.get(outputDir, "tests_with_param.txt").toFile(), append)))
        {
            testClasses.stream().forEach(testClassName -> 
            {
                if (processed.contains(testClassName)) return;
                System.out.println("At: " + testClassName);
                try 
                {
                    InterceptFilter filter = new InterceptFilter();
                    Class<?> testClass = Class.forName(testClassName);
                    Request request = Request.aClass(testClass);
                    JUnitCore junit = new JUnitCore();

                    TimerTask interruptTimerTask = new TimerTask()
                    {
                        @Override
                        public void run()
                        {
                            try 
                            {
                                Field field = JUnitCore.class.getDeclaredField("fNotifier");
                                field.setAccessible(true);
                                RunNotifier runNotifier = (RunNotifier) field.get(junit);
                                runNotifier.pleaseStop();
                                System.out.println("Stopped: " + testClassName);
                            }
                            catch (Exception e)
                            {
                                e.printStackTrace();
                            }
                        }
                    };
                    Timer interruptTimer = new Timer();
                    interruptTimer.schedule(interruptTimerTask, 120000);
                    junit.run(request.filterWith(filter));
                    interruptTimer.cancel();
                    
                    filter.displayNames.forEach(pw::println);
                    pw.flush();

                    processed.add(testClassName);
                    try (PrintWriter pw2 = new PrintWriter(Files.newBufferedWriter(progressPath)))
                    {
                        processed.forEach(e -> pw2.println(e));
                        pw2.flush();
                    }
                    catch (IOException e)
                    {
                        e.printStackTrace();
                    }
                }
                catch (Throwable e)
                {
                    e.printStackTrace();
                    return;
                }
            });
        }
        System.out.println("Finished");
        System.exit(0);
    }
}

class InterceptFilter extends Filter
{
    public List<String> classAndMethodNames = new ArrayList<>();
    public List<String> displayNames = new ArrayList<>();

    @Override
    public boolean shouldRun(Description description)
    {
        BlockingQueue<Description> descs = new LinkedBlockingQueue<>();
        descs.add(description);
        Description curr;
        do 
        {
            curr = descs.poll();
            addDescription(curr);
            if (curr.getChildren().size() <= 0) break;
            descs.addAll(curr.getChildren());
        } while (!descs.isEmpty());

        return true;
    }

    private void addDescription(Description description)
    {
        String classAndMethodName = description.getClassName() + "#" + description.getMethodName();
        String displayName = description.getDisplayName();
        classAndMethodNames.add(classAndMethodName);
        displayNames.add(displayName);
    }

    @Override
    public String describe()
    {
        return "MyFilter";
    }
    
}