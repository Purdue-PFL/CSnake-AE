package pfl.result_analysis.utils;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collector;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;

import com.google.common.collect.ComparisonChain;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import com.google.common.math.DoubleMath;

public class ViciousCycleResult implements Serializable, Comparable<ViciousCycleResult>
{
    private static final long serialVersionUID = -5382960360197556116L;
    public List<LoopHash> loops = new ArrayList<>();
    public List<String> interferenceEdges = new ArrayList<>();
    public List<VCHashBytes> injectionIDs = new ArrayList<>();
    public Map<LoopHash, List<ImmutablePair<LoopHash, String>>> additionalLoops = new LinkedHashMap<>();
    public Map<VCHashBytes, Set<LoopSignature>> matchedInjectionSignature = new HashMap<>();
    public List<VCHashBytes> runHashes = new ArrayList<>();
    public double score = -1;
    
    public ViciousCycleResult()
    {
    };

    public void setScore(double score)
    {
        this.score = score;
    }

    public void addLoop(LoopHash loopKey, String edge)
    {
        loops.add(loopKey);
        interferenceEdges.add(edge);
        hashCodeValue = 0;
    }

    public void addInjection(VCHashBytes injectionID)
    {
        injectionIDs.add(injectionID);
        hashCodeValue = 0;
    }

    public void addInjection(VCHashBytes injectionID, Set<LoopSignature> sig)
    {
        injectionIDs.add(injectionID);
        matchedInjectionSignature.put(injectionID, sig);
        hashCodeValue = 0;
    }

    public void addRunHash(VCHashBytes runHash)
    {
        runHashes.add(runHash);
        hashCodeValue = 0;
    }

    public void addAdditionalLoop(LoopHash loop1, LoopHash loop2, String edge)
    {
        additionalLoops.computeIfAbsent(loop1, k -> new ArrayList<>()).add(ImmutablePair.of(loop2, edge));
        hashCodeValue = 0;
    }

    public LoopHash getInjectionLoop()
    {
        for (int i = 0; i < interferenceEdges.size(); i++)
        {
            if (interferenceEdges.get(i).contains("S+(I)") || interferenceEdges.get(i).contains("E(I)"))
            {
                return loops.get(i);
            }
        }
        return LoopHash.wrap(StringUtils.repeat('0', 32));
    }

    public Map<String, Object> toMap()
    {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("Loops", this.loops.stream().map(e -> e.toString()).collect(Collectors.toList()));
        
        List<String> edges = new ArrayList<>();
        for (int i = 0; i < interferenceEdges.size(); i++)
        {
            StringBuilder sb = new StringBuilder();
            int loopIdx1 = i;
            int loopIdx2 = i + 1;
            if (loopIdx2 >= interferenceEdges.size()) loopIdx2 = 0;
            sb.append(StringUtils.rightPad(interferenceEdges.get(i), 5));
            sb.append(" ");
            sb.append(loops.get(loopIdx1).toString());
            sb.append(" -> ");
            sb.append(loops.get(loopIdx2).toString());
            edges.add(sb.toString());
        }        
        r.put("Edges", edges);

        r.put("InjectionID", this.injectionIDs.stream().map(e -> e.toString()).collect(Collectors.toList()));
        r.put("ExpID", this.runHashes.stream().map(e -> e.toString()).collect(Collectors.toList()));
        r.put("Score", this.score);

        r.put("RawEdges", this.interferenceEdges);
        
        return r;
    }

    private static HashFunction hashFunction = Hashing.murmur3_32_fixed();
    private volatile int hashCodeValue;
    @Override 
    public int hashCode()
    {
        int result = hashCodeValue;
        if (result == 0)
        {
            Hasher hasher = hashFunction.newHasher();
            loops.forEach(e -> hasher.putBytes(e.rawHashCode.asBytes()));
            interferenceEdges.forEach(e -> hasher.putBytes(e.getBytes()));
            injectionIDs.forEach(e -> hasher.putBytes(e.data));
            for (LoopHash addlLoopKey: additionalLoops.keySet())
            {
                hasher.putBytes(addlLoopKey.rawHashCode.asBytes());
                for (ImmutablePair<LoopHash, String> p: additionalLoops.get(addlLoopKey))
                {
                    hasher.putBytes(p.left.rawHashCode.asBytes());
                    hasher.putBytes(p.right.getBytes());
                }
            }
            result = hasher.hash().asInt();
            hashCodeValue = result;
        }
        return result;
    }

    @Override
    public boolean equals(Object o)
    {
        if (!(o instanceof ViciousCycleResult)) return false;
        ViciousCycleResult rhs = (ViciousCycleResult) o;
        return Objects.equals(this.loops, rhs.loops) && Objects.equals(this.interferenceEdges, rhs.interferenceEdges) && Objects.equals(this.additionalLoops, rhs.additionalLoops) && Objects.equals(this.injectionIDs, rhs.injectionIDs);
    }

    @Override
    public String toString()
    {
        StringBuilder sb = new StringBuilder();
        sb.append("Loops:\n");
        for (LoopHash loopKey: loops)
        {
            sb.append("  ");
            sb.append(loopKey.toString());
            sb.append('\n');
        }
        sb.append("Edges:\n");
        for (int i = 0; i < interferenceEdges.size(); i++)
        {
            sb.append("  ");
            int loopIdx1 = i;
            int loopIdx2 = i + 1;
            if (loopIdx2 >= interferenceEdges.size()) loopIdx2 = 0;
            sb.append(interferenceEdges.get(i));
            sb.append("\t");
            sb.append(loops.get(loopIdx1).toString());
            sb.append(" -> ");
            sb.append(loops.get(loopIdx2).toString());
            sb.append('\n');
        }
        if (injectionIDs.size() > 0)
        {
            sb.append("InjectionID:\n");
            for (VCHashBytes injectionID: injectionIDs)
            {
                sb.append("  ");
                sb.append(injectionID.toString());
                sb.append("\n");
            }
        }
        if (runHashes.size() > 0)
        {
            sb.append("ExpID:\n");
            for (VCHashBytes runHash: runHashes)
            {
                sb.append("  ");
                sb.append(runHash.toString());
                sb.append("\n");
            }
        }
        if (additionalLoops.size() > 0)
        {
            sb.append("Additional Loop:\n");
            for (LoopHash loopKey1: additionalLoops.keySet())
            {
                sb.append("  ");
                sb.append(loopKey1.toString());
                sb.append('\n');
                for (ImmutablePair<LoopHash, String> loop2: additionalLoops.get(loopKey1))
                {
                    LoopHash loopKey2 = loop2.getLeft();
                    String edgeType = loop2.getRight();
                    sb.append("    -> ");
                    sb.append(loopKey2.toString());
                    sb.append(" | ");
                    sb.append(edgeType);
                    sb.append('\n');
                }
            }
        }
        if (!DoubleMath.fuzzyEquals(score, -1, 0.0001d))
        {
            sb.append("Score: ");
            sb.append(score);
            sb.append('\n');
        }
        // if (matchedInjectionSignature.size() > 0)
        // {
        //     sb.append("Injection Matched Signature:\n");
        //     for (VCHashBytes injectionID: injectionIDs)
        //     {
        //         if (!matchedInjectionSignature.containsKey(injectionID)) continue;
        //         sb.append("  ");
        //         sb.append(injectionID.toString());
        //         sb.append('\n');
        //         for (LoopSignature sig: matchedInjectionSignature.get(injectionID))
        //         {
        //             for (VCHashBytes sigItem: sig.rawSignature)
        //             {
        //                 sb.append("    ");
        //                 sb.append(sigItem.toString());
        //                 sb.append("\n");
        //             }
        //             sb.append("    -----------\n");
        //         }
        //     }
        // }
        return sb.toString();
    }

    @Override
    public int compareTo(ViciousCycleResult rhs)
    {
        return ComparisonChain.start().compare(this.loops.size(), rhs.loops.size()).compare(this.injectionIDs.size(), rhs.injectionIDs.size()).compare(this.additionalLoops.size(), rhs.additionalLoops.size()).result();
    }
}
