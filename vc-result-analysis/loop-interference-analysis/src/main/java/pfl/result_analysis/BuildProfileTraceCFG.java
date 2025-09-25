package pfl.result_analysis;

import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.ObjectOutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.jgrapht.graph.DefaultEdge;

import pfl.graph.IMethodRawNameNode;
import pfl.result_analysis.utils.Utils;

public class BuildProfileTraceCFG 
{
    public static void main(String[] args) throws Exception
    {
        Options options = new Options();
        options.addOption("cfg_edges", true, "Json file containing the cfg edges");
        options.addOption("cfg_output_path", true, "loop cfg output path");
        DefaultParser parser = new DefaultParser();
        CommandLine cmd = parser.parse(options, args);

        Map<String, List<String>> cfgEdges = Utils.readJson(cmd.getOptionValue("cfg_edges")); // Key is caller

        DefaultDirectedGraph<IMethodRawNameNode, DefaultEdge> cg = new DefaultDirectedGraph<>(DefaultEdge.class);
        Map<String, IMethodRawNameNode> cgNodeMap = new HashMap<>();
        for (String methodSig: cfgEdges.keySet())
        {
            IMethodRawNameNode v = new IMethodRawNameNode(methodSig);
            cgNodeMap.put(methodSig, v);
            cg.addVertex(v);
            for (String methodSig2: cfgEdges.get(methodSig))
            {
                IMethodRawNameNode v2 = new IMethodRawNameNode(methodSig2);
                cgNodeMap.put(methodSig2, v2);
                cg.addVertex(v2);
            }
        }

        for (String methodSig: cfgEdges.keySet())
        {
            List<String> succs = cfgEdges.get(methodSig);
            IMethodRawNameNode srcV = cgNodeMap.get(methodSig);
            succs.forEach(methodSig2 -> cg.addEdge(srcV, cgNodeMap.get(methodSig2)));
        }

        try (FileOutputStream fos = new FileOutputStream(cmd.getOptionValue("cfg_output_path")); BufferedOutputStream bos = new BufferedOutputStream(fos); ObjectOutputStream oos = new ObjectOutputStream(bos);)
        {
            oos.writeObject(cg);
        }
    }    
}
