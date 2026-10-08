package dev.xianyu.chronosfm.ir;

import dev.xianyu.chronosfm.model.StatementModel;

import java.util.List;
import java.util.Objects;

/**
 * Exact source-order IO/control IR.
 *
 * This layer deliberately does not fuse, reorder or solve flow. It is the
 * correctness boundary used before structural optimizations are enabled.
 */
public sealed interface ExactOperation permits
        ExactOperation.InputOp,
        ExactOperation.OutputOp,
        ExactOperation.LegacyBarrier {

    int triggerIndex();
    int statementIndex();
    int exactOrderOrdinal();

    default List<String> labels() {
        return List.of();
    }

    default List<String> resourceTypes() {
        return List.of();
    }

    record InputOp(
            int triggerIndex,
            int statementIndex,
            int exactOrderOrdinal,
            StatementModel.EndpointSelector selector,
            StatementModel.ResourceSelector resources,
            boolean each
    ) implements ExactOperation {
        public InputOp {
            validateIds(triggerIndex, statementIndex, exactOrderOrdinal);
            Objects.requireNonNull(selector, "selector");
            Objects.requireNonNull(resources, "resources");
        }

        @Override
        public List<String> labels() {
            return selector.labels();
        }

        @Override
        public List<String> resourceTypes() {
            return resources.resourceTypes();
        }
    }

    record OutputOp(
            int triggerIndex,
            int statementIndex,
            int exactOrderOrdinal,
            StatementModel.EndpointSelector selector,
            StatementModel.ResourceSelector resources,
            boolean each,
            boolean emptySlotsOnly
    ) implements ExactOperation {
        public OutputOp {
            validateIds(triggerIndex, statementIndex, exactOrderOrdinal);
            Objects.requireNonNull(selector, "selector");
            Objects.requireNonNull(resources, "resources");
        }

        @Override
        public List<String> labels() {
            return selector.labels();
        }

        @Override
        public List<String> resourceTypes() {
            return resources.resourceTypes();
        }
    }

    /**
     * No optimizer may move work across this barrier until an equivalence proof
     * exists for the underlying SFM statement/control-flow construct.
     */
    record LegacyBarrier(
            int triggerIndex,
            int statementIndex,
            int exactOrderOrdinal,
            String reason
    ) implements ExactOperation {
        public LegacyBarrier {
            validateIds(triggerIndex, statementIndex, exactOrderOrdinal);
            Objects.requireNonNull(reason, "reason");
        }
    }

    private static void validateIds(int triggerIndex, int statementIndex, int order) {
        if (triggerIndex < 0 || statementIndex < 0 || order < 0) {
            throw new IllegalArgumentException("trigger/statement/order must be >= 0");
        }
    }
}
