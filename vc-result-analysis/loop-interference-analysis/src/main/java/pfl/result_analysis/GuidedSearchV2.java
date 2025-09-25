package pfl.result_analysis;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.ObjectOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.DoubleSummaryStatistics;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;

import com.google.common.collect.ComparisonChain;
import com.google.common.collect.ConcurrentHashMultiset;
import com.google.common.collect.HashMultiset;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Multiset;
import com.google.common.collect.Sets;
import com.google.common.io.ByteStreams;
import com.google.gson.reflect.TypeToken;

import pfl.result_analysis.dataset.LoopInterferenceDataset.InjectionType;
import pfl.result_analysis.dataset.LoopInterferenceDataset.InterferenceType;
import pfl.result_analysis.utils.LoopHash;
import pfl.result_analysis.utils.LoopItem;
import pfl.result_analysis.utils.LoopSignature;
import pfl.result_analysis.utils.Utils;
import pfl.result_analysis.utils.VCHashBytes;
import pfl.result_analysis.utils.VCLoopElement;
import pfl.result_analysis.utils.ViciousCycleResult;

public class GuidedSearchV2 extends FindErrorInjectChain
{
    public static void main(String[] args) throws Exception
    {
        Options options = new Options();
        options.addOption("delay_injection_testplan", true, "Injection Testplan");
        options.addOption("injection_result_path", true, "Per-experiment interference result path (JSON)");
        options.addOption("output_path", true, "result_output_path");
        options.addOption("negate_injection", true, "Details about the NEGATE injection");
        options.addOption("throw_injection", true, "Details about the throw injection");
        options.addOption("throw_branch_position", true, "The branch position for each if-throw pattern");
        options.addOption("candidate_error_chain", true, "Candidate VC Error chains");
        options.addOption("loops", true, "json file for loops");
        DefaultParser parser = new DefaultParser();
        CommandLine cmd = parser.parse(options, args);

        //#region Load supporting data structures

        loadResultCache();

        List<List<VCLoopElement>> candidateChains = new ArrayList<>();
        List<List<Map<String, String>>> candidateChainsT = Utils.readJson(cmd.getOptionValue("candidate_error_chain"));
        for (List<Map<String, String>> chain: candidateChainsT)
        {
            candidateChains.add(chain.stream().map(e -> VCLoopElement.fromMap(e)).collect(Collectors.toList()));
        }
        candidateChainsT.clear();

        Type testplanType = new TypeToken<ConcurrentHashMap<String, HashMap<String, Object>>>()
        {
        }.getType();
        Map<String, Map<String, Object>> injectionTestPlan = Utils.readJson(cmd.getOptionValue("delay_injection_testplan"), testplanType);
        Map<String, List<String>> injectionRepeatedRuns = new ConcurrentHashMap<>();
        for (String injectionRunHash : injectionTestPlan.keySet())
        {
            String aggregateExpKey = (String) injectionTestPlan.get(injectionRunHash).get("AggregateExpKey");
            injectionRepeatedRuns.computeIfAbsent(aggregateExpKey, k -> new ArrayList<>()).add(injectionRunHash);
        }
                
        Map<String, Map<String, Object>> negateInjectionT = Utils.readJson(cmd.getOptionValue("negate_injection"));
        negateInjection = negateInjectionT.entrySet().stream().collect(Collectors.toConcurrentMap(e -> VCHashBytes.wrap(e.getKey()), e -> e.getValue()));
        negateInjectionT.clear();
        Map<String, Map<String, Object>> throwInjectionT = Utils.readJson(cmd.getOptionValue("throw_injection"));
        throwInjection = throwInjectionT.entrySet().stream().collect(Collectors.toConcurrentMap(e -> VCHashBytes.wrap(e.getKey()), e -> e.getValue()));
        throwInjectionT.clear();

        Map<String, List<String>> throwBranchPosT = Utils.readJson(cmd.getOptionValue("throw_branch_position"));
        throwBranchPos = throwBranchPosT.entrySet().stream()
                .collect(Collectors.toMap(e -> VCHashBytes.wrap(e.getKey()), e -> e.getValue().stream().map(e2 -> VCHashBytes.wrap(e2)).collect(Collectors.toSet())));
        throwBranchPosT.clear();
        
        injectionResultPathRoot = cmd.getOptionValue("injection_result_path");
        iterCountResultPathRoot = cmd.getOptionValue("iter_count_result_path", injectionResultPathRoot);

        Map<String, Map<String, Object>> loopMapT = Utils.readJson(cmd.getOptionValue("loops"));
        loopMap = loopMapT.entrySet().stream().collect(Collectors.toMap(e -> LoopHash.wrap(e.getKey()), e -> LoopItem.mapToLoopItem(e.getValue())));

        //#endregion Load supporting data structures

        // #region Load per-experiment

        // Try connecting with E(D) edge
        Map<LoopHash, Set<String>> delayLoopIndexedInjectionRuns = new ConcurrentHashMap<>();
        for (String injectionRunHash: injectionRepeatedRuns.keySet())
        {
            Map<String, Object> runProfile = injectionTestPlan.get(injectionRunHash);
            LoopHash injectionLoop = LoopHash.wrap((String) runProfile.get("Injection Loop"));
            delayLoopIndexedInjectionRuns.computeIfAbsent(injectionLoop, k -> ConcurrentHashMap.newKeySet()).add(injectionRunHash);
        }
        Set<ImmutableList<VCLoopElement>> vcResult = ConcurrentHashMap.newKeySet();
        Set<ViciousCycleResult> vcResult2 = ConcurrentHashMap.newKeySet();
        // System.setErr(new PrintStream(ByteStreams.nullOutputStream()));
        candidateChains.parallelStream().forEach(candidateChain ->          // for (List<VCLoopElement> candidateChain: candidateChains)
        {
            VCLoopElement lastVLE = Iterables.getLast(candidateChain);
            VCLoopElement firstVLE = candidateChain.get(0);
            LoopHash delayInjectionLoop = lastVLE.affectedLoopID;
            LoopHash targetDelayAffectedLoop = firstVLE.loopID;
            Set<String> injectionRunHashes = delayLoopIndexedInjectionRuns.get(delayInjectionLoop);
            if (injectionRunHashes == null) return;
            // for (String injectionRunHashStr: injectionRunHashes)
            injectionRunHashes.parallelStream().forEach(injectionRunHashStr -> 
            {
                VCHashBytes injectionRunHash = VCHashBytes.wrap(injectionRunHashStr);
                Set<LoopSignature> matchingSignature = getMatchingInjectionSignature(injectionRunHash, delayInjectionLoop, firstVLE.injectionID, firstVLE.injectionRunHash, firstVLE.loopID);
                if (matchingSignature.isEmpty()) return;

                VCHashBytes profileRunHash = VCHashBytes.wrap((String) injectionTestPlan.get(injectionRunHashStr).get("ProfileRunID"));
                ImmutableList.Builder<VCLoopElement> builder = ImmutableList.builder();
                builder.addAll(candidateChain);
                VCLoopElement delayVLE = VCLoopElement.build(delayInjectionLoop, targetDelayAffectedLoop, Utils.toVCHashBytes(delayInjectionLoop), injectionRunHash, profileRunHash, InterferenceType.EXEC_SIG);
                builder.add(delayVLE);
                vcResult.add(builder.build());

                ViciousCycleResult vcr = new ViciousCycleResult();
                vcr.addLoop(delayInjectionLoop, "E(D)");
                vcr.addInjection(Utils.toVCHashBytes(delayInjectionLoop));
                vcr.addRunHash(injectionRunHash);
                for (VCLoopElement vle: candidateChain)
                {
                    String edgeName;
                    switch (vle.interferenceType) 
                    {
                        case EXEC_SIG:
                            edgeName = "E(I)";
                            break;
                        case ITER_COUNT:
                            edgeName = "S+(I)";
                            break;
                        default:
                            edgeName = vle.interferenceType.name();
                            break;
                    }
                    vcr.addLoop(vle.loopID, edgeName);
                    if (!vle.injectionID.equals(VCHashBytes.nullSafeValue())) vcr.addInjection(vle.injectionID);
                    if (!vle.injectionRunHash.equals(VCHashBytes.nullSafeValue())) vcr.addRunHash(vle.injectionRunHash);
                }
                vcResult2.add(vcr);
            });
        });

        //#endregion Load per-experiment

        Files.createDirectories(Paths.get(cmd.getOptionValue("output_path")));
        try (ObjectOutputStream oos = new ObjectOutputStream(new BufferedOutputStream(Files.newOutputStream(Paths.get(cmd.getOptionValue("output_path"), "Raw_VCs.obj")))))
        {
            oos.writeObject(vcResult2);
        }
        System.out.println("Total: " + vcResult2.size());
    }
}
