package pfl.result_analysis.utils;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import com.google.common.base.MoreObjects;

import pfl.result_analysis.dataset.LoopInterferenceDataset.InterferenceType;

public class VCLoopElement implements Serializable
{
    private static final long serialVersionUID = 3200116394203496607L;
    public LoopHash loopID;
    public LoopHash affectedLoopID;
    public VCHashBytes injectionID;
    public VCHashBytes injectionRunHash;
    public VCHashBytes profileRunHash;
    public InterferenceType interferenceType;

    private VCLoopElement(LoopHash loopID, LoopHash affectedLoopID, VCHashBytes injectionID, VCHashBytes injectionRunHash, VCHashBytes profileRunHash, InterferenceType interferenceType)
    {
        this.loopID = loopID;
        this.affectedLoopID = affectedLoopID;
        this.injectionID = injectionID;
        this.injectionRunHash = injectionRunHash;
        this.profileRunHash = profileRunHash;
        this.interferenceType = interferenceType;
    }

    public static VCLoopElement build(LoopHash loopID, LoopHash affectedLoopID, VCHashBytes injectionID, VCHashBytes injectionRunHash, VCHashBytes profileRunHash, InterferenceType interferenceType)
    {
        return new VCLoopElement(loopID, affectedLoopID, injectionID, injectionRunHash, profileRunHash, interferenceType);
    }

    public VCLoopElement copy()
    {
        return new VCLoopElement(loopID, affectedLoopID, injectionID, injectionRunHash, profileRunHash, interferenceType);
    }

    public LoopHash getAffectedLoop()
    {
        return affectedLoopID;
    }

    public Map<String, String> toMap()
    {
        Map<String, String> r = new LinkedHashMap<>();
        r.put("LoopID", loopID.toString());
        r.put("AffectedLoop", affectedLoopID.toString());
        r.put("InjectionID", injectionID.toString());
        r.put("InterferenceType", interferenceType.name());
        r.put("InjectionRunHash", injectionRunHash.toString());
        r.put("ProfileRunHash", profileRunHash.toString());
        return r;
    }

    public static VCLoopElement fromMap(Map<String, String> map)
    {
        LoopHash loopID = LoopHash.wrap(map.get("LoopID"));
        LoopHash affectedLoop = LoopHash.wrap(map.get("AffectedLoop"));
        VCHashBytes injectionID = VCHashBytes.wrap(map.get("InjectionID"));
        InterferenceType interferenceType = InterferenceType.valueOf(map.get("InterferenceType"));
        VCHashBytes injectionRunHash = VCHashBytes.wrap(map.get("InjectionRunHash"));
        VCHashBytes profileRunHash = VCHashBytes.wrap(map.get("ProfileRunHash"));
        return VCLoopElement.build(loopID, affectedLoop, injectionID, injectionRunHash, profileRunHash, interferenceType);
    }

    @Override
    public String toString()
    {
        return MoreObjects.toStringHelper(this).add("LoopID", loopID.toString()).add("AffectedLoop", affectedLoopID.toString()).add("InjectionID", injectionID.toString())
                .add("InterferenceType", interferenceType.name())
                .add("InjectionRunHash", injectionRunHash.toString())
                .add("ProfileRunHash", profileRunHash.toString()).toString();
    }

    @Override
    public boolean equals(Object o)
    {
        if (!(o instanceof VCLoopElement)) return false;
        VCLoopElement rhs = (VCLoopElement) o;
        return Objects.equals(this.loopID, rhs.loopID) && Objects.equals(this.injectionID, rhs.injectionID) && Objects.equals(this.affectedLoopID, rhs.affectedLoopID) && Objects.equals(this.interferenceType, rhs.interferenceType);
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(loopID, injectionID, affectedLoopID, interferenceType);
    }

    public static String getEdgeType(Map<String, Object> runProf, InterferenceType interferenceType)
    {
        if (interferenceType == InterferenceType.CFG) return "CFG";
        if (interferenceType == InterferenceType.ICFG) return "ICFG";
        StringBuilder sb = new StringBuilder();
        if (interferenceType == InterferenceType.EXEC_SIG) sb.append('E');
        if (interferenceType == InterferenceType.ITER_COUNT) sb.append("S+");
        String injectionType = (String) runProf.get("InjectionType");
        if (injectionType.equals("THROW_EXCEPTION"))
            sb.append("(I)");
        else if (injectionType.equals("NEGATE"))
            sb.append("(I)");
        else if (injectionType.equals("DELAY"))
            sb.append("(D)");
        return sb.toString();
    }
}
