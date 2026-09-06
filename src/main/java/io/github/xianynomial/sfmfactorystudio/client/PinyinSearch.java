package io.github.xianynomial.sfmfactorystudio.client;

import io.github.xianynomial.sfmfactorystudio.SFMGui;
import me.towdium.pinin.PinIn;

import java.util.Locale;

/**
 * Pinyin-aware search backed by the bundled PinIn library. Chinese item names can
 * be matched by full pinyin, initials, or mixed (e.g. "shitou" / "st" -> 石头).
 * Falls back to a plain case-insensitive substring match, and never throws.
 */
public final class PinyinSearch {
    private PinyinSearch() {
    }

    private static PinIn pinin;
    private static boolean failed = false;

    private static PinIn pinin() {
        if (pinin == null && !failed) {
            try {
                pinin = new PinIn();
            } catch (Throwable t) {
                failed = true;
                SFMGui.LOGGER.warn("PinIn init failed; pinyin search disabled", t);
            }
        }
        return pinin;
    }

    /**
     * Whether {@code source} matches {@code query}: plain substring OR pinyin.
     */
    public static boolean matches(String source, String query) {
        if (query == null || query.isEmpty()) {
            return true;
        }
        return matchesNormalized(source, query.toLowerCase(Locale.ROOT));
    }

    /** Same as {@link #matches(String, String)} when the query is already lowercase. */
    public static boolean matchesNormalized(String source, String normalizedQuery) {
        if (normalizedQuery == null || normalizedQuery.isEmpty()) return true;
        String s = source == null ? "" : source;
        if (s.toLowerCase(Locale.ROOT).contains(normalizedQuery)) {
            return true;
        }
        PinIn p = pinin();
        if (p != null) {
            try {
                // PinIn only matches lowercase pinyin tokens, so normalise the query
                // (uppercase letters would otherwise never match).
                return p.contains(s, normalizedQuery);
            } catch (Throwable ignored) {
            }
        }
        return false;
    }
}
