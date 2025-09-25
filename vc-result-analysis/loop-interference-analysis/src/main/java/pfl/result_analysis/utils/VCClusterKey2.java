package pfl.result_analysis.utils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.google.common.collect.HashMultiset;
import com.google.common.collect.Multiset;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;

public class VCClusterKey2 
{
    public Set<String> stringKeys;
    public List<LoopHash> loopHashKeys;
    public Set<Integer> intKeys;
    
    private VCClusterKey2()
    {
        stringKeys = new HashSet<>();
        loopHashKeys = new ArrayList<>();
        intKeys = new HashSet<>();
    }

    public static VCClusterKey2 build(ViciousCycleResult vcr, Map<LoopHash, LoopItem> loopMap, Map<LoopHash, Integer> loopCluster)
    {
        VCClusterKey2 key = new VCClusterKey2();

        boolean hasICFG = vcr.interferenceEdges.contains("ICFG");
        for (int i = 0; i < vcr.loops.size(); i++)
        {
            String interferenceEdgeType = vcr.interferenceEdges.get(i);
            int loopIdx1 = i;
            int loopIdx2 = i + 1;
            if (loopIdx2 >= vcr.loops.size()) loopIdx2 = 0;
            LoopItem loop1 = loopMap.get(vcr.loops.get(loopIdx1));
            LoopItem loop2 = loopMap.get(vcr.loops.get(loopIdx2));
            // key.intKeys.add(loopCluster.get(vcr.loops.get(loopIdx1)));
            if (interferenceEdgeType.contains("E(D)"))
            {
                key.intKeys.add(loopCluster.get(vcr.loops.get(loopIdx1)));
                // if (loopCluster.get(vcr.loops.get(loopIdx2)) == null) System.out.println(vcr.loops.get(loopIdx2).toString());
                // key.intKeys.add(loopCluster.get(vcr.loops.get(loopIdx2)));
            }
            else if (interferenceEdgeType.contains("S+(I)"))
            {
                key.intKeys.add(loopCluster.get(vcr.loops.get(loopIdx1)));
            }
            else if (interferenceEdgeType.equals("ICFG"))
            {
                key.intKeys.add(loopCluster.get(vcr.loops.get(loopIdx1)));
            }
        }
        // System.out.println(key.intKeys);
        return key;
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
        if (!(o instanceof VCClusterKey2)) return false;
        VCClusterKey2 rhs = (VCClusterKey2) o;
        return Objects.equals(this.stringKeys, rhs.stringKeys) && Objects.equals(this.loopHashKeys, rhs.loopHashKeys) && Objects.equals(this.intKeys, rhs.intKeys);
    }
}
