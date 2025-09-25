package pfl.analysis_util;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class TestPlanGenerator
{
    public static List<Map<String, Object>> generate(List<ErrorHandler> ehs)
    {
        Map<String, Set<MethodIndexedErrorHandler>> testPlan = new HashMap<>(); // TestMethod -> [ErrorHandler]
        for (ErrorHandler eh : ehs)
        {
            MethodIndexedErrorHandler mieh = new MethodIndexedErrorHandler(eh);
            for (String testClass : eh.unitTests.keySet())
            {
                for (String testMethod : eh.unitTests.get(testClass))
                {
                    String testKey = testClass + "#" + testMethod;
                    testPlan.computeIfAbsent(testKey, k -> new HashSet<>()).add(mieh);
                }
            }
        }
        List<Map<String, Object>> testJson = testPlan.entrySet().stream().map(e -> 
        {
            Map<String, Object> r = new HashMap<>();
            r.put("UnitTest", e.getKey());
            r.put("ErrorHandlers", e.getValue().stream().map(handler -> handler.toMap()).collect(Collectors.toList()));
            return r;
        }).collect(Collectors.toList());
        return testJson;
    }
}
