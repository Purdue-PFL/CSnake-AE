package pfl.result_analysis.bfs;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.google.common.hash.HashFunction;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;

import pfl.result_analysis.utils.LoopHash;
import pfl.result_analysis.utils.VCLoopElement;

public class V3ResultKey1
{
    public Set<String> stringKeys;
    public List<LoopHash> loopHashKeys;
    public Set<Integer> intKeys;

    public static String clusteringMode = "";

    private V3ResultKey1()
    {
        stringKeys = new HashSet<>();
        loopHashKeys = new ArrayList<>();
        intKeys = new HashSet<>();
    } 

    public static void setClusteringMode(String system)
    {
        clusteringMode = system;
    }

    public static V3ResultKey1 build(VCBFSChain chain, Map<String, Map<String, Object>> injectionTestPlan, Map<LoopHash, Integer> loopCluster)
    {
        V3ResultKey1 key = new V3ResultKey1();
        for (VCLoopElement vle: chain.vles)
        {
            String edgeType = getEdgeType(vle, injectionTestPlan);
            if (edgeType.contains("E(D)"))
            {
                key.intKeys.add(loopCluster.get(vle.loopID));
            }
            if (!clusteringMode.contains("HDFS3") && !clusteringMode.contains("Flink"))
            {
                if (edgeType.contains("S+(I)"))
                {
                    key.intKeys.add(loopCluster.get(vle.loopID));
                }
                else if (edgeType.equals("ICFG"))
                {
                    key.intKeys.add(loopCluster.get(vle.loopID));
                }
            }
        }
        return key;
    }

    public static String getEdgeType(VCLoopElement vle, Map<String, Map<String, Object>> injectionTestPlan)
    {
        String edgeRepr = "";
        switch (vle.interferenceType) {
            case EXEC_SIG:
                edgeRepr += "E";
                break;
            case ITER_COUNT:
                edgeRepr += "S+";
                break;
            case ICFG:
                edgeRepr += "ICFG";
                break;
            case CFG:
                edgeRepr += "CFG";
                break;
            default:
                break;
        }
        Map<String, Object> runProf = injectionTestPlan.get(vle.injectionRunHash.toString());
        if (runProf != null) // Not ICFG and CFG
        {
            String injectionType = (String) runProf.get("InjectionType");
            if (injectionType.equals("THROW_EXCEPTION"))
                edgeRepr += "(I)_E";
            else if (injectionType.equals("NEGATE"))
                edgeRepr += "(I)_N";
            else if (injectionType.equals("DELAY"))
                edgeRepr += "(D)";
        }

        return edgeRepr;
    }

    private static HashFunction hashFunction = Hashing.murmur3_32_fixed();
    @Override
    public int hashCode()
    {
        Hasher hasher = hashFunction.newHasher();
        stringKeys.forEach(s -> hasher.putBytes(s.getBytes()));
        loopHashKeys.forEach(l -> hasher.putBytes(l.rawHashCode.asBytes()));
        intKeys.forEach(i -> hasher.putInt(i));
        return hasher.hash().asInt();
    }

    @Override
    public boolean equals(Object o)
    {
        if (!(o instanceof V3ResultKey1)) return false;
        V3ResultKey1 rhs = (V3ResultKey1) o;
        return Objects.equals(this.stringKeys, rhs.stringKeys) && Objects.equals(this.loopHashKeys, rhs.loopHashKeys) && Objects.equals(this.intKeys, rhs.intKeys);
    }
}
