package pfl.result_analysis.jfr;

import java.io.BufferedInputStream;
import java.io.BufferedWriter;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.AbstractMap.SimpleEntry;
import java.util.stream.Collector;
import java.util.stream.Collectors;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedMethod;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordingFile;
import one.convert.Arguments;
import one.jfr.JfrReader;
import pfl.result_analysis.utils.WrappedString;

public class JdkJfrParser 
{
    public static void main(String[] args) throws Exception
    {
        Map<WrappedString, Set<WrappedString>> cfgEdges = convertOne(args[0]);
        Path outputPath = Paths.get("./", "jfr_cfg.json");
        Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        try (BufferedWriter bw = Files.newBufferedWriter(outputPath))
        {
            gson.toJson(cfgEdges, bw);
            bw.flush();
        }
    }

    public static Map<WrappedString, Set<WrappedString>> convertOne(String jfrPathStr) throws IOException
    {
        Map<WrappedString, Set<WrappedString>> cfgEdges = new HashMap<>(); // Key is caller
        Path jfrPath = Paths.get(jfrPathStr);
        try (RecordingFile f = new RecordingFile(jfrPath))
        {
            while (f.hasMoreEvents())
            {
                RecordedEvent event = f.readEvent();
                if (event.getEventType().getName().contains("ExecutionSample")) 
                {
                    RecordedStackTrace s = event.getStackTrace();
                    if (s != null)
                    {
                        List<RecordedFrame> frames = s.getFrames();
                        frames.removeIf(frame -> !frame.isJavaFrame());
                        List<WrappedString> frameName = frames.stream().map(frame -> 
                        {
                            RecordedMethod method = frame.getMethod();
                            return WrappedString.wrap(method.getType().getName() + "." + method.getName() + method.getDescriptor());
                        }).collect(Collectors.toList());
                        Collections.reverse(frameName);
                        for (int i = 1; i < frameName.size(); i++)
                        {
                            WrappedString methodName1 = frameName.get(i - 1);
                            WrappedString methodName2 = frameName.get(i);
                            cfgEdges.computeIfAbsent(methodName1, k -> new HashSet<>()).add(methodName2);
                        }
                    }
                }
            }
        }
        cfgEdges.keySet().removeIf(callerMethod -> callerMethod.startsWith("pfl.bm"));
        cfgEdges.values().forEach(e -> e.removeIf(calleeMethod -> calleeMethod.startsWith("pfl.bm")));
        return cfgEdges;
    }
}
