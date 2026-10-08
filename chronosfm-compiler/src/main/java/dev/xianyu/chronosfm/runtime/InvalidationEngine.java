package dev.xianyu.chronosfm.runtime;

import dev.xianyu.chronosfm.ir.DependencyIndex;

import java.util.BitSet;
import java.util.Objects;

public final class InvalidationEngine {
    private final DependencyIndex dependencies;
    private final BitSet dirtyRegions = new BitSet();

    public InvalidationEngine(DependencyIndex dependencies) {
        this.dependencies = Objects.requireNonNull(dependencies, "dependencies");
    }

    public void invalidateLabel(String label) {
        mark(dependencies.regionsForLabel(label));
    }

    public void invalidateResource(String resourceKey) {
        mark(dependencies.regionsForResource(resourceKey));
    }

    public void invalidateRegion(int regionId) {
        if (regionId < 0) throw new IllegalArgumentException("regionId must be >= 0");
        dirtyRegions.set(regionId);
    }

    public boolean isDirty(int regionId) {
        return dirtyRegions.get(regionId);
    }

    public int dirtyCount() {
        return dirtyRegions.cardinality();
    }

    public int[] drainDirtyRegions() {
        int[] result = dirtyRegions.stream().toArray();
        dirtyRegions.clear();
        return result;
    }

    private void mark(int[] ids) {
        for (int id : ids) dirtyRegions.set(id);
    }
}
