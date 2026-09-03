package io.github.xianynomial.sfmfactorystudio.client;

import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Lightweight localization entry for the addon, mirroring the small slice of
 * SFM's {@code LocalizationEntry} API that the migrated editor screens use.
 * <p>
 * The addon ships its own {@code lang/*.json}; when a key is missing from every
 * loaded language file the {@code fallback} (the Chinese default) is emitted
 * instead — showing the raw key to the player is never acceptable
 * (2026-09-02: issues_warn 键漏登语言文件，按钮直接显示了键名).
 */
public record Loc(String key, String fallback) {
    public MutableComponent getComponent() {
        return I18n.exists(key) ? Component.translatable(key) : Component.literal(fallback);
    }

    public String getString() {
        return I18n.exists(key) ? I18n.get(key) : fallback;
    }

    /** Convenience: translate a raw key to a display string. */
    public static String tr(String key) {
        return I18n.get(key);
    }

    /** Convenience: translate a raw key with args. */
    public static String tr(String key, Object... args) {
        return I18n.get(key, args);
    }
}
