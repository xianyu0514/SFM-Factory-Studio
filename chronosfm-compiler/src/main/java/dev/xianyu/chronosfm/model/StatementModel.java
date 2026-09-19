package dev.xianyu.chronosfm.model;

import java.util.Objects;

public sealed interface StatementModel permits StatementModel.Transfer, StatementModel.Opaque {
    record Transfer(
            String sourceLabel,
            String destinationLabel,
            String resourceKey,
            boolean each,
            long maxQuantity,
            long retainQuantity
    ) implements StatementModel {
        public Transfer {
            Objects.requireNonNull(sourceLabel, "sourceLabel");
            Objects.requireNonNull(destinationLabel, "destinationLabel");
            Objects.requireNonNull(resourceKey, "resourceKey");
            if (maxQuantity < 0) throw new IllegalArgumentException("maxQuantity must be >= 0");
            if (retainQuantity < 0) throw new IllegalArgumentException("retainQuantity must be >= 0");
        }
    }

    record Opaque(String reason) implements StatementModel {
        public Opaque {
            Objects.requireNonNull(reason, "reason");
        }
    }
}
