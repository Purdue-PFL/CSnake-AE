package pfl.inspector;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.ObjectInputStream;
import java.util.stream.Collectors;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;
import org.jgrapht.Graph;
import org.jgrapht.GraphPath;
import org.jgrapht.alg.interfaces.ShortestPathAlgorithm;
import org.jgrapht.alg.shortestpath.DijkstraShortestPath;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.jgrapht.graph.DefaultEdge;

import pfl.graph.IMethodRawNameNode;

public class MethodCFGConnectivityInspector 
{
    public static void main(String[] args) throws Exception
    {
        Options options = new Options();
        options.addOption("saved_graph", true, "Serialized JGraphT graph");
        options.addOption("start_func", true, null);
        options.addOption("end_func", true, null);
        CommandLineParser parser = new DefaultParser();
        CommandLine cmd = parser.parse(options, args);
        String savedGraphPath = cmd.getOptionValue("saved_graph");
        String startFunc = cmd.getOptionValue("start_func");
        String endFunc = cmd.getOptionValue("end_func");
        Graph<IMethodRawNameNode, DefaultEdge> cgT;
        File savedGraphFile = new File(savedGraphPath);
        try (FileInputStream fis = new FileInputStream(savedGraphFile); BufferedInputStream bis = new BufferedInputStream(fis); ObjectInputStream ois = new ObjectInputStream(bis);)
        {
            cgT = (DefaultDirectedGraph<IMethodRawNameNode, DefaultEdge>) ois.readObject();
        }
        System.out.println("Graph Loaded");
        // cgT.removeAllVertices(cgT.vertexSet().stream().filter(v -> v.toString().startsWith("java.lang") && !v.toString().startsWith("java.lang.reflect")).collect(Collectors.toSet()));
        // cgT.removeAllVertices(cgT.vertexSet().stream().filter(v -> v.toString().contains("toString") | v.toString().contains("javax") | v.toString().contains("security") | v.toString().contains("org.apache.hadoop.hdfs.web")).collect(Collectors.toSet()));

        IMethodRawNameNode startNode = cgT.vertexSet().stream().filter(v -> v.toString().startsWith(startFunc + '(')).findAny().get();
        IMethodRawNameNode endNode = cgT.vertexSet().stream().filter(v -> v.toString().startsWith(endFunc + '(')).findAny().get();
        ShortestPathAlgorithm<IMethodRawNameNode, DefaultEdge> shortestPathAlgo = new DijkstraShortestPath<>(cgT);
        GraphPath<IMethodRawNameNode, DefaultEdge> path = shortestPathAlgo.getPath(startNode, endNode);
        if (path == null)
        {
            System.out.println("Not Connected");
        }
        else 
        {
            System.out.println("Connected");
            for (IMethodRawNameNode node: path.getVertexList())
            {
                System.out.println(node);
            }
        }
    }
}
