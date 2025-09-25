package pfl.result_analysis;

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

}
