package pfl.result_analysis;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;
import org.jgrapht.Graph;
import org.jgrapht.Graphs;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.jgrapht.graph.DefaultEdge;

import pfl.result_analysis.utils.LoopHash;
import pfl.result_analysis.utils.LoopItem;
import pfl.result_analysis.utils.Utils;

public class BuildDynamicLoopCFG 
{
    public static void main(String[] args) throws Exception
    {
        Options options = new Options();
        options.addOption("dynamic_callsite_path", true, "JSON file containing dynamic loop callsites");
        options.addOption("loops", true, "json file for loops");
        options.addOption("graph_output_path", true, "loop cfg output path");
        DefaultParser parser = new DefaultParser();
        CommandLine cmd = parser.parse(options, args);

        Map<String, Map<String, Object>> loopMapT = Utils.readJson(cmd.getOptionValue("loops"));
        Map<LoopHash, LoopItem> loopMap = loopMapT.entrySet().stream().collect(Collectors.toMap(e -> LoopHash.wrap(e.getKey()), e -> LoopItem.mapToLoopItem(e.getValue())));

        Map<String, List<List<String>>> callSiteMapT = Utils.readJson(cmd.getOptionValue("dynamic_callsite_path"));
        Map<LoopHash, List<List<String>>> callSiteMap = callSiteMapT.entrySet().stream().collect(Collectors.toMap(e -> LoopHash.wrap(e.getKey()), e -> e.getValue()));
        
        Map<String, Set<LoopHash>> methodIndexedLoops = new HashMap<>();
        for (LoopHash loopKey: loopMap.keySet())
        {
            LoopItem loopItem = loopMap.get(loopKey);
            String fullMethodName = loopItem.clazz + "." + loopItem.func;
            methodIndexedLoops.computeIfAbsent(fullMethodName, k -> new HashSet<>()).add(loopKey);
        }
        Graph<LoopHash, DefaultEdge> loopCFG = new DefaultDirectedGraph<>(DefaultEdge.class); 
        for (LoopHash loopKey: callSiteMap.keySet())
        {
            loopCFG.addVertex(loopKey);
            for (List<String> callSite: callSiteMap.get(loopKey))
            {
                for (String stackMethodFullName: callSite)
                {
                    Set<LoopHash> predLoops = methodIndexedLoops.get(stackMethodFullName);
                    if (predLoops == null) continue;
                    predLoops.forEach(l -> 
                    {
                        loopCFG.addVertex(l);
                        loopCFG.addEdge(l, loopKey);
                    });
                }
            }
        }

        // Reduce loopCFG by function name
        for (LoopHash loopKey: loopCFG.vertexSet())
        {
            LoopItem loopItem = loopMap.get(loopKey);
            String fullMethodName = loopItem.clazz + "." + loopItem.func;
            Set<LoopHash> loopsToMerge = methodIndexedLoops.get(fullMethodName);
            for (LoopHash loopToMerge: loopsToMerge)
            {
                List<LoopHash> succs = Graphs.successorListOf(loopCFG, loopToMerge);
                succs.forEach(succ -> loopCFG.addEdge(loopKey, succ));
            }
        }

        Utils.dumpObj(loopCFG, cmd.getOptionValue("graph_output_path"));
    }    
}
