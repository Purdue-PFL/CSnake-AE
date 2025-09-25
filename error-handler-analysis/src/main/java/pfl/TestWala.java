package pfl;

import java.io.File;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

import org.eclipse.collections.impl.list.mutable.ListAdapter;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.core.util.config.AnalysisScopeReader;
import com.ibm.wala.ipa.callgraph.AnalysisScope;
import com.ibm.wala.ipa.cha.ClassHierarchy;
import com.ibm.wala.ipa.cha.ClassHierarchyFactory;

import pfl.util.Utils;

public class TestWala 
{
    public static void main(String[] args) throws Exception
    {
        String classPath = args[0];
        AnalysisScope scope = AnalysisScopeReader.instance.makeJavaBinaryAnalysisScope(classPath, new File("exclusion.txt"));
        ClassHierarchy cha = ClassHierarchyFactory.makeWithRoot(scope);

        for (IClass clazz: cha)
        {
            if (Utils.getFullClassName(clazz).contains("ReplicaInPipeline"))
            {
                IClass cur = clazz;
                while (cur.getSuperclass() != null)
                {
                    cur = cur.getSuperclass();
                    System.out.println(cur);
                }
                System.out.println(Utils.getFullClassName(clazz));
            }
        }
    }
}

