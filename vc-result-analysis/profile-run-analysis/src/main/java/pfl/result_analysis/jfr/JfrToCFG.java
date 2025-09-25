package pfl.result_analysis.jfr;

import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import one.convert.Arguments;
import one.convert.Frame;
import one.convert.JfrConverter;
import one.jfr.JfrReader;
import one.jfr.StackTrace;
import one.jfr.event.AllocationSample;
import one.jfr.event.Event;
import one.jfr.event.EventAggregator;

public class JfrToCFG extends JfrConverter
{
    public Map<String, Set<String>> edges = new HashMap<>(); // Key is caller
    public JfrToCFG(JfrReader jfr, Arguments args)
    {
        super(jfr, args);
    }

    @Override
    protected void convertChunk() throws IOException
    {
        collectEvents().forEach(new EventAggregator.Visitor() 
        {
            CallStack stack = new CallStack();

            @Override
            public void visit(Event event, long value)
            {
                StackTrace stackTrace = jfr.stackTraces.get(event.stackTraceId);
                if (stackTrace == null) return;
                
                long[] methods = stackTrace.methods;
                byte[] types = stackTrace.types;
                int[] locations = stackTrace.locations;

                for (int i = methods.length; --i >= 0; ) {
                    String methodName = getMethodName(methods[i], types[i]);
                    int location;
                    if (args.lines && (location = locations[i] >>> 16) != 0) {
                        methodName += ":" + location;
                    } else if (args.bci && (location = locations[i] & 0xffff) != 0) {
                        methodName += "@" + location;
                    }
                    stack.push(methodName, types[i]);
                }
                long classId = event.classId();
                if (classId != 0) {
                    stack.push(getClassName(classId), (event instanceof AllocationSample)
                            && ((AllocationSample) event).tlabSize == 0 ? Frame.TYPE_KERNEL : Frame.TYPE_INLINED);
                }

                for (int i = 1; i < stack.names.length; i++)
                {
                    String name1 = stack.names[i - 1];
                    String name2 = stack.names[i];
                    edges.computeIfAbsent(name1, k -> new HashSet<>()).add(name2);
                }
                stack.clear();
            }
        });
    }

    public static Map<String, Set<String>> convertOne(String jfrPath) throws IOException
    {
        String[] converterArgsArr = new String[] {"--cpu", "--dot", "--norm"};
        JfrToCFG converter;
        Arguments converterArgs = new Arguments(converterArgsArr);
        try (JfrReader jfr = new JfrReader(jfrPath))
        {
            converter = new JfrToCFG(jfr, converterArgs);
            converter.convert();
        }
        converter.edges.remove(null);
        converter.edges.values().forEach(e -> e.removeIf(Objects::isNull));
        converter.edges.keySet().removeIf(callerMethod -> callerMethod.startsWith("pfl.bm"));
        converter.edges.values().forEach(e -> e.removeIf(calleeMethod -> calleeMethod.startsWith("pfl.bm")));
        return converter.edges;
    }
    
    public static void main(String[] args) throws IOException
    {
        String[] converterArgsArr = new String[] {"--cpu", "--dot", "--norm"};
        JfrToCFG converter;
        Arguments converterArgs = new Arguments(converterArgsArr);
        try (JfrReader jfr = new JfrReader(args[0]))
        {
            converter = new JfrToCFG(jfr, converterArgs);
            converter.convert();;
        }
        Path outputPath = Paths.get("./", "jfr_cfg.json");
        Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        try (BufferedWriter bw = Files.newBufferedWriter(outputPath))
        {
            gson.toJson(converter.edges, bw);
            bw.flush();
        }
    }
}
