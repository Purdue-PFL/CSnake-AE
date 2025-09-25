package pfl.test_execute;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.lang.annotation.Annotation;
import java.lang.instrument.ClassFileTransformer;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.MessageFormat;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.apache.commons.io.FileUtils;
import org.apache.log4j.FileAppender;
import org.apache.log4j.Level;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;
import org.apache.log4j.PatternLayout;
import org.jboss.byteman.agent.Retransformer;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.engine.reporting.ReportEntry;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;

import com.google.common.collect.Lists;
import com.google.protobuf.ByteString;
import com.redhat.jigawatts.Jigawatts;

import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import one.profiler.AsyncProfiler;
import pfl.bm.VCLoopInterferenceHelper;
import pfl.test_execute.TestExecutionGrpc.TestExecutionBlockingStub;
import pfl.test_execute.TestExecutionService.ClientID;
import pfl.test_execute.TestExecutionService.Task;
import pfl.test_execute.TestExecutionService.TaskResult;
import xdean.jex.util.reflect.AnnotationUtil;

public class JUnit5Run 
{
    public static Retransformer retransformer = null;
    public static PrintWriter mainLogger;

    public static boolean shouldCheckpoint = false;
    public static boolean isCriuRecoveredRun = false;
    public static boolean shouldTrace = false;

    public static void main(String[] args) throws IOException
    {
        ManagedChannel channel = NettyChannelBuilder.forAddress(Config.GRPC_SERVER_ADDR, Config.GRPC_PORT).usePlaintext().maxInboundMessageSize(Integer.MAX_VALUE).build();
        TestExecutionBlockingStub stub = TestExecutionGrpc.newBlockingStub(channel);
        UUID clientUUID = UUID.randomUUID();
        ClientID clientID = ClientID.newBuilder().setIpAddr(Utils.getLocalIPAddr()).setUuid(clientUUID.toString()).build();

        String checkpointPath = "/criu";
        if (args.length >= 1)
        {
            if (args[0].equals("checkpoint")) shouldCheckpoint = true;
            if (Arrays.stream(args).anyMatch("trace"::equals)) shouldTrace = true;
        }

        Field loadedTransformerField;
        try
        {
            loadedTransformerField = org.jboss.byteman.agent.Main.class.getField("loadedTransformer");
            ClassFileTransformer loadedTransformer = (ClassFileTransformer) loadedTransformerField.get(null);
            if (loadedTransformer instanceof Retransformer) retransformer = (Retransformer) loadedTransformer;
        }
        catch (NoSuchFieldException | SecurityException | IllegalArgumentException | IllegalAccessException e)
        {
            System.out.println("Cannot Access Byteman Retransformer");
            System.exit(-1);
        }

        while (true)
        {
            try
            {
                FileUtils.deleteDirectory(new File("./target/data"));
            }
            catch (IOException e)
            {
                e.printStackTrace();
            }

            Task task = stub.getTask(clientID);
            // Unpack task
            String expKey = task.getExpKey();
            String btmStr = task.getBtmStr();
            String unitTestFullName = task.getUnittestFunc();
            String junit5UniqueID = unitTestFullName.split("\\|")[0];
            String junitDisplayName = unitTestFullName.split("\\|")[1];
            String unittestClass = junitDisplayName.split("\\#")[0];
            String unittestMethod = junitDisplayName.split("\\#")[1];
            long injectionTimeMs = task.getInjectionTimeMs();
            long unittestTimeoutMs = task.getUnittestTimeoutMs();
            try
            {
                // Run task and log
                Path outputDir = Files.createTempDirectory(Paths.get("/tmp"), "vc_" + expKey);
                System.out.println("Testing: " + unittestClass + "#" + unittestMethod + "\tOutputDir: " + outputDir.toString());
                try (PrintWriter bytemanLogger = new PrintWriter(Paths.get(outputDir.toString(), "byteman.log").toFile());
                        PrintStream junitPrintStream = new PrintStream(Paths.get(outputDir.toString(), "junit.log").toFile());
                        PrintWriter btmWriter = new PrintWriter(Paths.get(outputDir.toString(), "rule.btm").toFile());)
                {
                    btmWriter.println(btmStr);
                    btmWriter.flush();
                    String testOutputFile = Paths.get(outputDir.toString(), "stdout.log").toString();
                    AsyncProfiler profiler = AsyncProfiler.getInstance();
                    String tracePath = Paths.get(outputDir.toString(), "trace.jfr").toString();
                    if (shouldTrace)
                    {
                        profiler.execute(String.format("start,jfr,event=cpu,interval=250000,cstack=no,dot,lib,norm,sig,file=%s", tracePath));
                    }
                    runTest(junit5UniqueID, unittestClass, unittestMethod, outputDir.toString(), injectionTimeMs, unittestTimeoutMs, btmStr, expKey + ".btm", testOutputFile, junitPrintStream,
                            bytemanLogger);
                    if (shouldTrace)
                    {
                        profiler.execute(String.format("stop,file=%s", tracePath));
                    }
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }
                // Report
                Path compressDir = Files.createTempDirectory(Paths.get("/tmp"), expKey + "_r");
                String outputFN = Paths.get(compressDir.toString(), "r.tar.zstd").toString();
                String cmdLine = MessageFormat.format("tar --zstd -cf {0} {1}", outputFN, outputDir.toString());
                String[] cmd = org.apache.commons.exec.CommandLine.parse(cmdLine).toStrings();
                Process p = Runtime.getRuntime().exec(cmd);
                p.waitFor();
                try (InputStream resultIS = new BufferedInputStream(new FileInputStream(outputFN));)
                {
                    TaskResult.Builder resultBuilder = TaskResult.newBuilder();
                    resultBuilder.setClientID(clientID);
                    resultBuilder.setExpKey(expKey);
                    resultBuilder.setTestOutput(ByteString.readFrom(resultIS));
                    stub.taskFinish(resultBuilder.build());
                }

                FileUtils.deleteDirectory(outputDir.toFile());
                FileUtils.deleteDirectory(compressDir.toFile());
                if (isCriuRecoveredRun) System.exit(0);
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
            System.gc();
            if (shouldCheckpoint)
            {
                channel.shutdownNow();
                try
                {
                    channel.awaitTermination(5, TimeUnit.SECONDS);
                }
                catch (InterruptedException e)
                {
                    e.printStackTrace();
                }
                isCriuRecoveredRun = true;
                shouldCheckpoint = false;
                Jigawatts.saveTheWorld(checkpointPath, false);
            }
            if (isCriuRecoveredRun)
            {
                channel = NettyChannelBuilder.forAddress(Config.GRPC_SERVER_ADDR, Config.GRPC_PORT).usePlaintext().maxInboundMessageSize(Integer.MAX_VALUE).build();
                stub = TestExecutionGrpc.newBlockingStub(channel);
                clientID = ClientID.newBuilder().setIpAddr(Utils.getLocalIPAddr()).setUuid(clientUUID.toString()).build();
            }
        }
    }

    public static void clearHelperEnvironment()
    {
        for (Thread t : Thread.getAllStackTraces().keySet())
        {
            if (t.getName().startsWith("org.apache"))
            {
                t.interrupt();
            }
            else if (Arrays.stream(t.getStackTrace()).anyMatch(e -> e.getClassName().contains("org.apache")))
            {
                t.interrupt();
            }
        }
        VCLoopInterferenceHelper.clearEnvironment();
    }

    public static void runTest(String junit5UniqueID, String className, String methodName, String outputPath, long injectionTimeMs, long testTimeoutMs, String btmStr, String btmName,
            String testOutputFile, PrintStream junitOutputStream, PrintWriter bytemanLogger) throws Exception
    {
        // Prepare Environment
        PrintStream oldSysOutput = System.out;
        System.setOut(junitOutputStream);
        System.setProperty("org.jboss.byteman.expName", "test");
        System.setProperty("org.jboss.byteman.vcWriterPath", outputPath);
        System.setProperty("org.jboss.byteman.injectionTime", Long.toString(injectionTimeMs));
        System.out.println("Environment Prepared");
        clearHelperEnvironment();
        retransformer.installScript(Lists.newArrayList(btmStr), Lists.newArrayList(btmName), bytemanLogger);

        String PATTERN = "%d{ISO8601} [%t] %-5p %c{2} (%F:%M(%L)) - %m%n";
        FileAppender appender = new FileAppender();
        appender.setName(testOutputFile);
        appender.setFile(testOutputFile);
        appender.setAppend(false);
        appender.setLayout(new PatternLayout(PATTERN));
        appender.setThreshold(Level.DEBUG);
        appender.activateOptions();
        Logger.getRootLogger().getLoggerRepository().resetConfiguration();
        Logger.getRootLogger().addAppender(appender);

        // Set timeout value for this test
        if (testTimeoutMs == 0) testTimeoutMs = Config.DEFAULT_TIMEOUT_MS;
        String realMethodName = methodName.contains("[") ? methodName.split("\\[")[0] : methodName;
        Method testMethod = null;
        for (Method m : Class.forName(className).getMethods())
        {
            if (m.getName().equals(realMethodName))
            {
                testMethod = m;
                break;
            }
        }
        if (testMethod != null) 
        {
            // Test Junit4 Annotation
            Annotation junit4Annotation = testMethod.getAnnotation(org.junit.Test.class);
            if (junit4Annotation != null)
            {
                Utils.modifyAnnotationValue(junit4Annotation, "timeout", testTimeoutMs);
            }
            else // Junit 5 Annotation
            {
                Map<String, Object> annoMap = new HashMap<>();
                annoMap.put("value", testTimeoutMs);
                annoMap.put("unit", TimeUnit.MILLISECONDS);
                org.junit.jupiter.api.Timeout timeoutAnno = AnnotationUtil.createAnnotationFromMap(org.junit.jupiter.api.Timeout.class, annoMap);
                AnnotationUtil.addAnnotation(testMethod, timeoutAnno);
            }
        }

        TextListener listener = new TextListener(junitOutputStream);
        Thread testExecThread = new Thread(() ->
        {
            LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                .selectors(DiscoverySelectors.selectUniqueId(junit5UniqueID))
                .configurationParameter("junit.platform.output.capture.stdout", "true")
                .configurationParameter("junit.platform.output.capture.stderr", "true")
                .build();
            Launcher launcher = LauncherFactory.create();
            launcher.execute(request, listener);
        });
        testExecThread.start();
        junitOutputStream.println("+++ TestExecThread started");
        junitOutputStream.flush();
        TimerTask interruptTimerTask = new TimerTask()
        {
            @Override
            public void run()
            {
                junitOutputStream.println("+++ InterruptTimer fired 1");
                junitOutputStream.flush();
                for (int i = 0; i < 20; i++)
                {
                    if (testExecThread.isAlive())
                    {
                        testExecThread.interrupt();
                    }
                }
                junitOutputStream.println("+++ InterruptTimer fired 2");
            }
        };
        Timer interruptTimer = new Timer();
        interruptTimer.schedule(interruptTimerTask, testTimeoutMs + 60000);
        junitOutputStream.println("+++ InterruptTimer scheduled");
        junitOutputStream.flush();
        testExecThread.join(testTimeoutMs + 80000);
        if (isCriuRecoveredRun)
        {
            junitOutputStream.println("+++ CRIU Recovered Run: Returning from runTest()");
            junitOutputStream.flush();
            interruptTimer.cancel();
            System.setOut(oldSysOutput);
            return;
        }
        junitOutputStream.println("+++ TestExecThread joined");
        junitOutputStream.flush();
        interruptTimer.cancel();

        // Clean up
        LogManager.shutdown();
        Thread.sleep(5000);
        clearHelperEnvironment();
        junitOutputStream.println("+++ Helper status reset");
        junitOutputStream.flush();
        retransformer.removeScripts(Lists.newArrayList(btmStr), bytemanLogger);
        junitOutputStream.println("+++ Remove script done");
        junitOutputStream.flush();
        System.setOut(oldSysOutput);
        junitOutputStream.println("+++ Test clean up done");
        junitOutputStream.flush();
    }

}

class TextListener implements TestExecutionListener
{
    public PrintStream os;
    public TextListener(PrintStream os)
    {
        this.os = os;
    }

    @Override
    public void reportingEntryPublished(TestIdentifier testIdentifier, ReportEntry entry) 
    {
        Map<String, String> entryMap = entry.getKeyValuePairs();
        if(testIdentifier.isTest()) 
        {
            os.println(entryMap.get("stdout").trim());
            os.println(entryMap.get("stderr").trim());
        }
    }
}
