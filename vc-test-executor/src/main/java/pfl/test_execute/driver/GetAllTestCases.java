package pfl.test_execute.driver;

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

import org.junit.runner.Description;
import org.junit.runner.JUnitCore;
import org.junit.runner.Request;
import org.junit.runner.manipulation.Filter;
import org.junit.runner.notification.RunNotifier;

import com.google.common.reflect.ClassPath;
import com.google.common.reflect.ClassPath.ClassInfo;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import pfl.test_execute.Utils;

public class GetAllTestCases 
{
    public static void main(String[] args) throws Exception
    {
        List<String> staticTestsCasesFound = Utils.readJson("/home/qian151/research/vc-detect/error-handler-analysis/result/hbase260/tests.json");
        Set<String> neededTestClasses = new HashSet<>();
        for (String testFullName: staticTestsCasesFound)
        {
            neededTestClasses.add(testFullName.split("\\#")[0]);
        }
        ClassPath classPath = ClassPath.from(RunSingleTest.class.getClassLoader());
        Set<ClassInfo> classes = classPath.getAllClasses();
        List<String> testClasses = new ArrayList<>();
        for (ClassInfo ci: classes)
        {   
            String classSimpleName = ci.getSimpleName();
            // Rules from maven-surefire-plugin 
            if (classSimpleName.startsWith("Test") || classSimpleName.endsWith("Test") || classSimpleName.endsWith("Tests") || classSimpleName.endsWith("TestCase"))
            {
                if (neededTestClasses.contains(ci.getName()))
                {
                    testClasses.add(ci.getName());
                }
            }
        }

        Set<String> processed = new HashSet<>();
        Path progressPath = Paths.get("./progress.log");
        boolean append = false;
        if (Files.exists(progressPath))
        {
            Files.lines(progressPath).forEach(e -> processed.add(e));
            append = true;
        }
        try (PrintWriter pw = new PrintWriter(new FileWriter("./tests_raw.txt", append)))
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
                catch (ClassNotFoundException e)
                {}
            });
        }
        System.out.println("Finished");
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