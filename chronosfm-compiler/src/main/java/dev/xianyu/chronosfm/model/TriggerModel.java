package dev.xianyu.chronosfm.model;

import java.util.List;
import java.util.Objects;

public sealed interface TriggerModel permits TriggerModel.Timer, TriggerModel.Redstone, TriggerModel.Opaque {
    List<StatementModel> statements();

    enum Alignment {
        LOCAL,
        GLOBAL
    }

    record Timer(
            int intervalTicks,
            Alignment alignment,
            int offset,
            List<StatementModel> statements
    ) implements TriggerModel {
        public Timer {
            if (intervalTicks <= 0) throw new IllegalArgumentException("intervalTicks must be > 0");
            Objects.requireNonNull(alignment, "alignment");
            Objects.requireNonNull(statements, "statements");
            statements = List.copyOf(statements);
            offset = Math.floorMod(offset, intervalTicks);
        }
    }

    record Redstone(List<StatementModel> statements) implements TriggerModel {
        public Redstone {
            Objects.requireNonNull(statements, "statements");
            statements = List.copyOf(statements);
        }
    }

    record Opaque(String reason, List<StatementModel> statements) implements TriggerModel {
        public Opaque {
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(statements, "statements");
            statements = List.copyOf(statements);
        }
    }
}
