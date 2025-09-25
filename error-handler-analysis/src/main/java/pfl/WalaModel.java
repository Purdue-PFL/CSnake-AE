package pfl;

import com.google.common.collect.Iterables;
import com.ibm.wala.classLoader.Language;
import com.ibm.wala.core.util.config.AnalysisScopeReader;
import com.ibm.wala.ipa.callgraph.AnalysisCacheImpl;
import com.ibm.wala.ipa.callgraph.AnalysisOptions;
import com.ibm.wala.ipa.callgraph.AnalysisScope;
import com.ibm.wala.ipa.callgraph.CallGraph;
import com.ibm.wala.ipa.callgraph.CallGraphBuilderCancelException;
import com.ibm.wala.ipa.callgraph.Entrypoint;
import com.ibm.wala.ipa.callgraph.impl.AllApplicationEntrypoints;
import com.ibm.wala.ipa.callgraph.propagation.SSAPropagationCallGraphBuilder;
import com.ibm.wala.ipa.cha.ClassHierarchy;
import com.ibm.wala.ipa.cha.ClassHierarchyFactory;
import com.ibm.wala.util.WalaException;

import java.io.File;
import java.io.IOException;

public class WalaModel
{
    private AnalysisScope scope;
    private ClassHierarchy cha;
    private String classPath;
    private CallGraph cg = null;

    public WalaModel(String classPath) throws IOException, WalaException, IllegalArgumentException
    {
        this.classPath = classPath;
        this.scope = AnalysisScopeReader.instance.makeJavaBinaryAnalysisScope(classPath, new File("exclusion.txt"));
        this.cha = ClassHierarchyFactory.makeWithRoot(this.scope);
    }

    public ClassHierarchy getCha()
    {
        return cha;
    }

    public AnalysisScope getScope()
    {
        return scope;
    }

    public void setCallGraph(CallGraph cg)
    {
        this.cg = cg;
    }

    public CallGraph getCallGraph() throws CallGraphBuilderCancelException
    {
        if (cg == null)
        {
            // Get a call graph
            // Iterable<Entrypoint> ep = com.ibm.wala.ipa.callgraph.impl.Util.makeMainEntrypoints(scope, cha);
            // // Some jar does not have a main function
            // if (Iterables.size(ep) == 0)
            //     ep = new AllApplicationEntrypoints(scope, cha);
            Iterable<Entrypoint> ep = new AllApplicationEntrypoints(scope, cha);
            AnalysisOptions o = new AnalysisOptions(scope, ep);
            o.setReflectionOptions(AnalysisOptions.ReflectionOptions.APPLICATION_GET_METHOD);
            SSAPropagationCallGraphBuilder builder = com.ibm.wala.ipa.callgraph.impl.Util.makeZeroCFABuilder(Language.JAVA, o, new AnalysisCacheImpl(),cha,scope);
            SSAPropagationCallGraphBuilder builder2 = com.ibm.wala.ipa.callgraph.impl.Util.makeZeroOneCFABuilder(Language.JAVA, o, new AnalysisCacheImpl(), cha, scope);
            this.cg = builder2.makeCallGraph(o, null);
            System.out.println("CallGraph created");
        }
        return cg;
    }

}
