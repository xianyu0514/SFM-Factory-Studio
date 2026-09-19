package dev.xianyu.chronosfm.ir;

import java.util.*;

public final class DependencyIndex {
    private final Map<String, int[]> byLabel;
    private final Map<String, int[]> byResource;

    private DependencyIndex(Map<String, int[]> byLabel, Map<String, int[]> byResource) {
        this.byLabel = Map.copyOf(byLabel);
        this.byResource = Map.copyOf(byResource);
    }

    public static DependencyIndex build(List<TransferRegion> regions) {
        Map<String, BitSet> labelBits = new HashMap<>();
        Map<String, BitSet> resourceBits = new HashMap<>();

        for (TransferRegion region : regions) {
            add(labelBits, region.sourceLabel(), region.regionId());
            add(labelBits, region.destinationLabel(), region.regionId());
            add(resourceBits, region.resourceKey(), region.regionId());
        }

        return new DependencyIndex(freeze(labelBits), freeze(resourceBits));
    }

    public int[] regionsForLabel(String label) {
        return byLabel.getOrDefault(label, new int[0]).clone();
    }

    public int[] regionsForResource(String resourceKey) {
        return byResource.getOrDefault(resourceKey, new int[0]).clone();
    }

    private static void add(Map<String, BitSet> map, String key, int regionId) {
        map.computeIfAbsent(key, ignored -> new BitSet()).set(regionId);
    }

    private static Map<String, int[]> freeze(Map<String, BitSet> source) {
        Map<String, int[]> result = new HashMap<>();
        source.forEach((key, bits) -> result.put(key, bits.stream().toArray()));
        return result;
    }
}
