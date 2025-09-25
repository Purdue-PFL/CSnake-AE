package pfl.result_analysis.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;

public class VCClusterKey1 
{
    public List<String> stringKeys;
    public List<LoopHash> loopHashKeys;
    
    private VCClusterKey1()
    {
        stringKeys = new ArrayList<>();
        loopHashKeys = new ArrayList<>();
    }

    public static VCClusterKey1 build(ViciousCycleResult vcr, Map<LoopHash, LoopItem> loopMap)
    {
        VCClusterKey1 key = new VCClusterKey1();

        for (int i = 0; i < vcr.loops.size(); i++)
        {
            String interferenceEdgeType = vcr.interferenceEdges.get(i);
            int loopIdx1 = i;
            int loopIdx2 = i + 1;
            if (loopIdx2 >= vcr.loops.size()) loopIdx2 = 0;
            LoopItem loop1 = loopMap.get(vcr.loops.get(loopIdx1));
            LoopItem loop2 = loopMap.get(vcr.loops.get(loopIdx2));
            if (interferenceEdgeType.contains("E(D)"))
            {
                key.stringKeys.add(loop1.clazz);
                key.loopHashKeys.add(vcr.loops.get(loopIdx2));
            }
            else if (interferenceEdgeType.contains("S+(I)"))
            {
                key.loopHashKeys.add(vcr.loops.get(loopIdx1));
            }
            else if (interferenceEdgeType.equals("ICFG"))
            {
                key.stringKeys.add(loop1.clazz);
            }
        }
        return key;
    }

    private static HashFunction hashFunction = Hashing.murmur3_32_fixed();
    @Override
    public int hashCode()
    {
        Hasher hasher = hashFunction.newHasher();
        stringKeys.forEach(s -> hasher.putBytes(s.getBytes()));
        loopHashKeys.forEach(l -> hasher.putBytes(l.rawHashCode.asBytes()));
        return hasher.hash().asInt();
    }

    @Override
    public boolean equals(Object o)
    {
        if (!(o instanceof VCClusterKey1)) return false;
        VCClusterKey1 rhs = (VCClusterKey1) o;
        return Objects.equals(this.stringKeys, rhs.stringKeys) && Objects.equals(this.loopHashKeys, rhs.loopHashKeys);
    }
}
