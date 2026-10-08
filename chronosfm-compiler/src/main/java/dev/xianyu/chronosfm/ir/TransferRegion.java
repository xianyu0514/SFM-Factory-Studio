package dev.xianyu.chronosfm.ir;

import java.util.Objects;

public record TransferRegion(
        int regionId,
        int triggerIndex,
        int statementIndex,
        int exactOrderOrdinal,
        String sourceLabel,
        String destinationLabel,
        String resourceKey,
        boolean each,
        long maxQuantity,
        long retainQuantity
) {
    public TransferRegion {
        if (regionId < 0 || triggerIndex < 0 || statementIndex < 0 || exactOrderOrdinal < 0) {
            throw new IllegalArgumentException("ids and order must be >= 0");
        }
        Objects.requireNonNull(sourceLabel, "sourceLabel");
        Objects.requireNonNull(destinationLabel, "destinationLabel");
        Objects.requireNonNull(resourceKey, "resourceKey");
    }
}
