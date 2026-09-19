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
        return build(regions, List.of());
    }

    public static DependencyIndex build(
            List<TransferRegion> regions,
            List<ExactOperation> operations
    ) {
        Map<String, BitSet> labelBits = new HashMap<>();
        Map<String, BitSet> resourceBits = new HashMap<>();

        for (TransferRegion region : regions) {
            add(labelBits, region.sourceLabel(), region.regionId());
            add(labelBits, region.destinationLabel(), region.regionId());
            add(resourceBits, region.resourceKey(), region.regionId());
        }

        // Exact IO operations do not yet have persistent region ids. Their
        // exact-order ordinal is stable within a compiled plan and is therefore
        // used as the dependency key until TransferGraph lowering assigns regions.
        for (ExactOperation operation : operations) {
            int id = operation.exactOrderOrdinal();
            for (String label : operation.labels()) add(labelBits, label, id);
            for (String resource : operation.resourceTypes()) add(resourceBits, resource, id);
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
