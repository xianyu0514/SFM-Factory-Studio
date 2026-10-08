package dev.xianyu.chronosfm.model;

import java.util.List;
import java.util.Objects;

public record ProgramModel(List<TriggerModel> triggers) {
    public ProgramModel {
        Objects.requireNonNull(triggers, "triggers");
        triggers = List.copyOf(triggers);
    }
}
