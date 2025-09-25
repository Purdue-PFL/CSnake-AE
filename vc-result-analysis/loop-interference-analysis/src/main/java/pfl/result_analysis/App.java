package pfl.result_analysis;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.PrintWriter;
import java.io.Reader;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.jgrapht.Graph;
import org.jgrapht.alg.cycle.DirectedSimpleCycles;
import org.jgrapht.alg.cycle.JohnsonSimpleCycles;
import org.jgrapht.alg.cycle.TarjanSimpleCycles;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.jgrapht.graph.DefaultEdge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

public class App 
{
    public static void main( String[] args ) throws Exception
    {
        String inputPath = "/home/qian151/research/vc-detect/vc-result-analysis/loop-interference-analysis/LoopInterference.json.bak";
        Type type = new TypeToken<Map<String, List<String>>>()
        {
        }.getType();
        Map<String, List<String>> loopIterferences;
        Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        try (Reader reader = new BufferedReader(new FileReader(inputPath)))
        {
            
            loopIterferences = gson.fromJson(reader, type);
        }

        Graph<String, DefaultEdge> graph = new DefaultDirectedGraph<>(DefaultEdge.class);
        for (String loop1: loopIterferences.keySet())
        {
            graph.addVertex(loop1);
            for (String loop2: loopIterferences.get(loop1))
            {
                graph.addVertex(loop2);
                graph.addEdge(loop1, loop2);
            }
        }
        System.out.println("Vertex count: " + graph.vertexSet().size());
        System.out.println("Edge count: " + graph.edgeSet().size());
        
        DirectedSimpleCycles<String, DefaultEdge> cycleFinder = new TarjanSimpleCycles<>(graph);
        List<List<String>> cycles = cycleFinder.findSimpleCycles();
        System.out.println("Cycle count: " + cycles.size());
        try (PrintWriter pw = new PrintWriter("./cycle.json"))
        {
            gson.toJson(cycles, pw);
        }
    }
}
