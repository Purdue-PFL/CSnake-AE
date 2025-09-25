package pfl.result_analysis.dataset;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.SetMultimap;

import pfl.result_analysis.utils.LoopHash;
import pfl.result_analysis.utils.VCHashBytes;

public class LoopInterferenceDataset 
{
    public enum InterferenceType 
    {
        EXEC_SIG, ITER_COUNT, DELAY_PARENT, CFG, ICFG
    }    

    public enum InjectionType 
    {
        DELAY, ERROR, NONE
    }

    public Map<InjectionType, Map<InterferenceType, Map<VCHashBytes, Map<LoopHash, Set<LoopHash>>>>> runHashIndexedLoopInterference; // RunHash -> {InjectionLoopKey: [Interfered Loop]}
    public Map<InjectionType, Map<InterferenceType, Map<LoopHash, Collection<VCHashBytes>>>> loopIndexedInjectionRuns;  // Injection loop -> [RunHash]
    public SetMultimap<LoopHash, LoopHash> loopCFG;
    public SetMultimap<LoopHash, LoopHash> loopICFG;

    private LoopInterferenceDataset()
    {
        runHashIndexedLoopInterference = new ConcurrentHashMap<>();
        loopIndexedInjectionRuns = new ConcurrentHashMap<>();
        loopCFG = HashMultimap.create();
        loopICFG = HashMultimap.create();
    }

    public static LoopInterferenceDataset build()
    {
        return new LoopInterferenceDataset();
    }

    public Map<VCHashBytes, Map<LoopHash, Set<LoopHash>>> getRunHashIndexedLoopInterferenceMap(InjectionType injType, InterferenceType interferenceType)
    {
        return runHashIndexedLoopInterference.computeIfAbsent(injType, k -> new ConcurrentHashMap<>()).computeIfAbsent(interferenceType, k -> new ConcurrentHashMap<>());
    }

    public Map<LoopHash, Collection<VCHashBytes>> getLoopIndexedInjectionRunsMap(InjectionType injType, InterferenceType interferenceType)
    {
        return loopIndexedInjectionRuns.computeIfAbsent(injType, k -> new ConcurrentHashMap<>()).computeIfAbsent(interferenceType, k -> new ConcurrentHashMap<>());
    }

    public Collection<VCHashBytes> getInjectionRunsForLoop(LoopHash loopKey, InjectionType injType, InterferenceType interferenceType)
    {
        return loopIndexedInjectionRuns.get(injType).get(interferenceType).getOrDefault(loopKey, Collections.emptyList());
    }

    public void addCFGEdge(LoopHash loop1, LoopHash loop2)
    {
        loopCFG.put(loop1, loop2);
        loopICFG.put(loop2, loop1);
    }
}
