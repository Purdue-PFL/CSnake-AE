package pfl.result_analysis.utils;

import java.util.HashMap;
import java.util.IntSummaryStatistics;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.google.common.collect.HashMultiset;
import com.google.common.collect.Multiset;

public class VCStatUtils 
{
    public static double getStatisticalDifference(List<Integer> sample1, List<Integer> sample2)
    {
        IntSummaryStatistics sample1Stat = sample1.stream().collect(Collectors.summarizingInt(e -> e));
        IntSummaryStatistics sample2Stat = sample2.stream().collect(Collectors.summarizingInt(e -> e));
        int min = Math.min(sample1Stat.getMin(), sample2Stat.getMin());
        int max = Math.max(sample1Stat.getMax(), sample2Stat.getMax());
        Multiset<Integer> sample1Dist = HashMultiset.create();
        sample1Dist.addAll(sample1);
        Multiset<Integer> sample2Dist = HashMultiset.create();
        sample2Dist.addAll(sample2);

        double sample1Count = sample1Stat.getCount();
        double sample2Count = sample2Stat.getCount();
        double statDiff = 0;
        for (int i = min; i <= max; i++)
        {
            double delta = Math.abs(sample1Dist.count(i) / sample1Count - sample2Dist.count(i) / sample2Count) * 0.5;
            statDiff += delta;
        }

        return statDiff;
    }    

    public static boolean isStatisticallyClose(List<Integer> sample1, List<Integer> sample2, double threshold)
    {
        return getStatisticalDifference(sample1, sample2) < threshold;
    }
}
