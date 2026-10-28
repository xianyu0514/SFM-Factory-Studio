package io.github.xianynomial.sfmfactorystudio.client;

import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Lightweight localization entry for the addon, mirroring the small slice of
 * SFM's {@code LocalizationEntry} API that the migrated editor screens use.
 * <p>
 * The addon ships its own {@code lang/*.json}. Resolution chain: selected
 * language → en_us (vanilla merges en_us under every language) → the Chinese
 * {@code fallback} as the last resort — showing the raw key to the player is
 * never acceptable (2026-09-02: issues_warn 键漏登语言文件，按钮直接显示了键名).
 * Missing keys are logged once (debug) so translation gaps surface before
 * release instead of silently degrading to Chinese.
 * <p>
 * 1.21.1 sync: parameterized form added (matches 1.20.1's Loc).
 */
public record Loc(String key, String fallback) {
    private static final java.util.Set<String> MISSING = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public MutableComponent getComponent() {
        return exists() ? Component.translatable(key) : Component.literal(fmt(fallback, NO_ARGS));
    }

    public String getString() {
        if (!exists()) {
            noteMissing();
            return fmt(fallback, NO_ARGS);
        }
        return safeGet(NO_ARGS);
    }

    /** Translate with arguments ({@code %s} slots in the lang entry/fallback). */
    public String getString(Object... args) {
        if (!exists()) {
            noteMissing();
            return fmt(fallback, args);
        }
        return safeGet(args);
    }

    public MutableComponent getComponent(Object... args) {
        return exists() ? Component.translatable(key, args) : Component.literal(fmt(fallback, args));
    }


    private boolean exists() {
        try {
            return I18n.exists(key);
        } catch (Throwable t) {
            return false; // headless test JVM / language manager not initialized
        }
    }

    private void noteMissing() {
        if (MISSING.add(key)) {
            com.mojang.logging.LogUtils.getLogger()
                    .debug("[sfmfactorystudio] missing lang key '{}' (using Chinese fallback)", key);
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
