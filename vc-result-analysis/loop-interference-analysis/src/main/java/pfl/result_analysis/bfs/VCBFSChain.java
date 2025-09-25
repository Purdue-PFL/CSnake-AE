package pfl.result_analysis.bfs;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.tuple.ImmutablePair;

import com.google.common.collect.ComparisonChain;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;

import pfl.result_analysis.dataset.LoopInterferenceDataset.InjectionType;
import pfl.result_analysis.dataset.LoopInterferenceDataset.InterferenceType;
import pfl.result_analysis.utils.LoopHash;
import pfl.result_analysis.utils.VCHashBytes;
import pfl.result_analysis.utils.VCLoopElement;

public class VCBFSChain implements Serializable, Comparable<VCBFSChain>
{
    private static final long serialVersionUID = 2564361614337159212L;
    public Set<LoopHash> loops = new HashSet<>();
    public List<VCLoopElement> vles = new ArrayList<>();
    public LoopHash firstLoop = null;
    public int errorCount = 0;
    public int delayCount = 0;
    public boolean isCycle = false;
    
    private VCBFSChain()
    {
    }

    private VCBFSChain(Set<LoopHash> loops, List<VCLoopElement> vles, LoopHash firstLoop, int errorCount, int delayCount, boolean isCycle)
    {
        this.loops = new HashSet<>(loops);
        this.vles = new ArrayList<>(vles);
        this.firstLoop = firstLoop;
        this.errorCount = errorCount;
        this.delayCount = delayCount;
        this.isCycle = isCycle;
    }

    // public static VCBFSChain build()
    // {   
    //     return new VCBFSChain();
    // }

    public static VCBFSChain build(VCLoopElement vle, InjectionType injectionType)
    {
        VCBFSChain r = new VCBFSChain();
        r.addVLE(vle, injectionType);
        return r;
    }

    public VCBFSChain copy()
    {
        return new VCBFSChain(loops, vles, firstLoop, errorCount, delayCount, isCycle);
    }

    private static Map<VCLoopElement, VCLoopElement> vlePool = new ConcurrentHashMap<>();
    public void addVLE(VCLoopElement vle, InjectionType injectionType)
    {
        VCLoopElement pooledVLE = vlePool.get(vle);
        if (pooledVLE == null) 
            vlePool.put(vle, vle);
        else 
            vle = pooledVLE;

        vles.add(vle);
        loops.add(vle.loopID);
        if (firstLoop == null) firstLoop = vle.loopID;
        if (injectionType == InjectionType.ERROR) 
            errorCount++;
        else if (injectionType == InjectionType.DELAY)
            delayCount++;
    }

    public VCLoopElement getLastVLE()
    {
        return Iterables.getLast(vles);
    }

    public boolean isCycle()
    {
        return isCycle;
    }

    public void setIsCycle()
    {
        this.isCycle = true;
    }

    @Override
    public int compareTo(VCBFSChain rhs)
    {
        return ComparisonChain.start()
                .compare(this.delayCount, rhs.delayCount)
                .compare(this.errorCount, rhs.errorCount)
                .compare(this.vles.size(), rhs.vles.size())
                .result();
    }

    // Reorder the cycle
    // 1. Find the loop with the smallest ID
    // 2. Start from that loop, find the first E(D) edge (if exists), and let the cycle start from that
    // 2B. If there is no E(D) edge, use the first S+(D) edge
    // 2C. If no S+(D) edge, use the first E(I) edge
    // 2D. If no E(I) edge, use the first S+(I) edge 
    public void canonicalize(Map<String, Map<String, Object>> injectionTestPlan)
    {
        if (!isCycle()) return; // Not a cycle, don't do anything
        LoopHash smallestIDLoop = null;
        int smallestIDLoopIdx = -1;
        int idx = 0;
        for (VCLoopElement vle: vles)
        {
            if (smallestIDLoopIdx == -1)
            {
                smallestIDLoop = vle.loopID;
                smallestIDLoopIdx = idx;
            }
            else if (vle.loopID.toString().compareTo(smallestIDLoop.toString()) < 0)
            {
                smallestIDLoop = vle.loopID;
                smallestIDLoopIdx = idx;
            }
            idx++;
        }

        int firstE_D = -1;
        int firstSP_D = -1;
        int firstE_I = -1;
        int firstSP_I = -1;
        for (int i = 0; i < vles.size(); i++)
        {
            int actualIdx = smallestIDLoopIdx + i;
            if (actualIdx >= vles.size()) actualIdx -= vles.size();
            VCLoopElement curVLE = vles.get(actualIdx);
            String curEdgeType = VCLoopElement.getEdgeType(injectionTestPlan.get(curVLE.injectionRunHash.toString()), curVLE.interferenceType);
            switch (curEdgeType)
            {
                case "E(D)":
                    if (firstE_D == -1) firstE_D = i;
                    break;
                case "S+(D)":
                    if (firstSP_D == -1) firstSP_D = i;
                    break;
                case "E(I)":
                    if (firstE_I == -1) firstE_I = i;
                    break;
                case "S+(I)":
                    if (firstSP_I == -1) firstSP_I = i;
                    break;
                default:
                    break;
            }
        }
        int rotateOffset = smallestIDLoopIdx;
        if (firstE_D != -1) 
            rotateOffset += firstE_D;
        else if (firstSP_D != -1)
            rotateOffset += firstSP_D;
        else if (firstE_I != -1)
            rotateOffset += firstE_I;
        else if (firstSP_I != -1)
            rotateOffset += firstSP_I;
        if (rotateOffset >= vles.size()) rotateOffset -= vles.size();
        Collections.rotate(vles, -rotateOffset);
        firstLoop = vles.get(0).loopID;
    }

    public Map<String, Object> toMap(Map<String, Map<String, Object>> injectionTestPlan)
    {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("Size", vles.size());
        r.put("Delay", delayCount);
        r.put("Error", errorCount);
        List<String> cycleRepr = new ArrayList<>();
        for (VCLoopElement vle: vles)
        {
            cycleRepr.add(vle.loopID.toString());
            String edgeRepr = "  ";
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
                    edgeRepr += "(D)  ";
            }
            edgeRepr = StringUtils.leftPad(edgeRepr, 6, ' ');
            edgeRepr += " | ID: " + vle.injectionID.toString() + " | Run: " + vle.injectionRunHash;
            cycleRepr.add(edgeRepr);
        }
        cycleRepr.add(getLastVLE().affectedLoopID.toString());
        r.put("CycleRepr", cycleRepr);

        return r;
    }

    public double chainScore = 0.5d;
    public void computeScore(Map<ImmutablePair<VCHashBytes, LoopHash>, Double> eSimScores, Map<ImmutablePair<VCHashBytes, LoopHash>, Double> sSimScores, 
        Map<String, Map<String, Object>> injectionTestPlan, double delayInjNegativeBias) // Returns [0, 1]
    {
        double sum = 0;
        int delayInjCount = 0;
        for (VCLoopElement vle: vles)
        {
            VCHashBytes injectionID = vle.injectionID;
            LoopHash injectionLoop = vle.loopID;
            ImmutablePair<VCHashBytes, LoopHash> injKey = ImmutablePair.of(injectionID, injectionLoop);
            double eScore = eSimScores.getOrDefault(injKey, 0.5d);
            double sScore = sSimScores.getOrDefault(injKey, 0.5d);
            sum += 0.75 * eScore + 0.25 * sScore; // ICFG and CFG edges will be given 0.5 due to default value

            Map<String, Object> runProf = injectionTestPlan.get(vle.injectionRunHash.toString());
            if (runProf != null)
            {
                String injectionType = (String) runProf.get("InjectionType");
                if (injectionType.equals("DELAY")) delayInjCount++;
            }
        }
        sum -= delayInjCount * delayInjNegativeBias;
        double avg = sum / vles.size();
        chainScore = Math.max(0, avg);
    }

    private static HashFunction hashFunction = Hashing.murmur3_32_fixed();
    @Override
    public int hashCode()
    {
        Hasher hasher = hashFunction.newHasher();
        vles.forEach(e -> hasher.putInt(e.hashCode()));
        hasher.putInt(loops.hashCode());
        return hasher.hash().asInt();
    }    

    @Override
    public boolean equals(Object o)
    {
        if (!(o instanceof VCBFSChain)) return false;
        VCBFSChain rhs = (VCBFSChain) o;
        return Objects.equals(loops, rhs.loops) && Objects.equals(vles, rhs.vles);
    }
}
