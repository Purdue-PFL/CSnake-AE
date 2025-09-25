package pfl.test_execute.driver;

import java.io.PrintStream;

import org.junit.internal.TextListener;
import org.junit.runner.JUnitCore;
import org.junit.runner.Request;
import org.junit.runner.Result;

public class RunSingleTest 
{   
    public static void main(String[] args) throws Throwable
    {
        String tn = args[0];
        String[] testNameSplit = tn.split("#");
        String testClass = testNameSplit[0];
        String testName = testNameSplit[1];

        JUnitCore runner = new JUnitCore();
        TextListener listener = new TextListener(System.out);
        runner.addListener(listener);
        // try (PrintStream ps = new PrintStream("./test.log"))
        // {
        //     System.setOut(ps);
        Request test = Request.method(Class.forName(testClass), testName);
        Result r = runner.run(test);
        System.out.println(r.wasSuccessful());
        System.out.println(r.getFailures());
        System.out.println(r.getIgnoreCount());
        System.out.println(r.getRunCount());
        // }

    }
}
