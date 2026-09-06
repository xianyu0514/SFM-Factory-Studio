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
 * <p>
 * 1.20.1 port notes:
 * <ul>
 *   <li>parameterized form: {@code fallback} may contain {@code %s} slots;
 *       the parameterless form must NOT rely on that.</li>
 *   <li>{@code I18n} touches the client language manager; in the headless unit
 *       test environment that is not initialized, so every call is wrapped and
 *       falls back (the unit tests then always see the Chinese defaults).</li>
 * </ul>
 */
public record Loc(String key, String fallback) {
    public MutableComponent getComponent() {
        return exists() ? Component.translatable(key) : Component.literal(fmt(fallback, NO_ARGS));
    }

    public String getString() {
        return exists() ? safeGet(NO_ARGS) : fmt(fallback, NO_ARGS);
    }

    /** Translate with arguments ({@code %s} slots in the lang entry/fallback). */
    public String getString(Object... args) {
        return exists() ? safeGet(args) : fmt(fallback, args);
    }

    public MutableComponent getComponent(Object... args) {
        return exists() ? Component.translatable(key, args) : Component.literal(fmt(fallback, args));
    }

    /** Convenience: translate a raw key to a display string (no fallback). */
    public static String tr(String key) {
        try {
            return I18n.get(key);
        } catch (Throwable t) {
            return key;
        }
    }

    /** Convenience: translate a raw key with args (no fallback). */
    public static String tr(String key, Object... args) {
        try {
            return I18n.get(key, args);
        } catch (Throwable t) {
            return key;
        }
    }

    private boolean exists() {
        try {
            return I18n.exists(key);
        } catch (Throwable t) {
            return false; // headless test JVM / language manager not initialized
        }
    }

    private String safeGet(Object... args) {
        try {
            return I18n.get(key, args);
        } catch (Throwable t) {
            return fmt(fallback, args);
        }
    }

    private static final Object[] NO_ARGS = new Object[0];

    private static String fmt(String pattern, Object... args) {
        if (args == null || args.length == 0) return pattern;
        try {
            return String.format(pattern, args);
        } catch (Throwable t) {
            return pattern;
        }
    }
}
