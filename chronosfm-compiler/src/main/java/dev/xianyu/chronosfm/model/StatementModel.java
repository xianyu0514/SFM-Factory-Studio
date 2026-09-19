package dev.xianyu.chronosfm.model;

import java.util.List;
import java.util.Objects;

public sealed interface StatementModel permits
        StatementModel.Transfer,
        StatementModel.Input,
        StatementModel.Output,
        StatementModel.Opaque {

    /**
     * Synthetic combined transfer used by compiler microbenchmarks and future
     * higher-level lowering passes. Real SFM AST initially lowers to Input/Output
     * operations so source ordering remains explicit.
     */
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

    record EndpointSelector(
            List<String> labels,
            List<String> sides,
            String slots,
            String roundRobinMode
    ) {
        public EndpointSelector {
            labels = List.copyOf(Objects.requireNonNull(labels, "labels"));
            sides = List.copyOf(Objects.requireNonNull(sides, "sides"));
            Objects.requireNonNull(slots, "slots");
            Objects.requireNonNull(roundRobinMode, "roundRobinMode");
        }
    }

    record ResourceSelector(
            List<String> resourceTypes,
            String exactSemanticSpec
    ) {
        public ResourceSelector {
            resourceTypes = List.copyOf(Objects.requireNonNull(resourceTypes, "resourceTypes"));
            Objects.requireNonNull(exactSemanticSpec, "exactSemanticSpec");
        }
    }

    record Input(
            EndpointSelector selector,
            ResourceSelector resources,
            boolean each
    ) implements StatementModel {
        public Input {
            Objects.requireNonNull(selector, "selector");
            Objects.requireNonNull(resources, "resources");
        }
    }

    record Output(
            EndpointSelector selector,
            ResourceSelector resources,
            boolean each,
            boolean emptySlotsOnly
    ) implements StatementModel {
        public Output {
            Objects.requireNonNull(selector, "selector");
            Objects.requireNonNull(resources, "resources");
        }
    }

    record Opaque(String reason) implements StatementModel {
        public Opaque {
            Objects.requireNonNull(reason, "reason");
        }
    }
}
