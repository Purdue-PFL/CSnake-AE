package pfl.result_analysis.bfs;

import java.util.Objects;

import com.google.common.hash.HashFunction;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;

import pfl.result_analysis.utils.VCLoopElement;

public class AlmostEqualVCBFSChain 
{
    public VCBFSChain chain;
    public AlmostEqualVCBFSChain(VCBFSChain chain)
    {
        this.chain = chain;
    }    

    private static HashFunction hashFunction = Hashing.murmur3_32_fixed();
    @Override 
    public int hashCode()
    {
        Hasher hasher = hashFunction.newHasher();
        for (VCLoopElement vle: chain.vles)
        {
            hasher.putInt(vle.loopID.hashCode());
            hasher.putInt(vle.interferenceType.ordinal());
        }
        hasher.putInt(chain.getLastVLE().affectedLoopID.hashCode());
        return hasher.hash().asInt();
    }

    // Make sure the loops involed and the edge types are the same
    @Override
    public boolean equals(Object o)
    {
        if (!(o instanceof AlmostEqualVCBFSChain)) return false;
        AlmostEqualVCBFSChain rhs = (AlmostEqualVCBFSChain) o;
        
        if (!this.chain.loops.equals(rhs.chain.loops)) return false;
        if (this.chain.vles.size() != rhs.chain.vles.size()) return false;
        for (int i = 0; i < this.chain.vles.size(); i++)
        {
            VCLoopElement vleL = this.chain.vles.get(i);
            VCLoopElement vleR = rhs.chain.vles.get(i);
            if (!(Objects.equals(vleL.loopID, vleR.loopID) && Objects.equals(vleL.affectedLoopID, vleR.affectedLoopID) && Objects.equals(vleL.interferenceType, vleR.interferenceType))) return false;
        }
        return true;
    }
}
