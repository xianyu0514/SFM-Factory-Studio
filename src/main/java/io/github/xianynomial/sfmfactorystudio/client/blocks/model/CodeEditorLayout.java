package io.github.xianynomial.sfmfactorystudio.client.blocks.model;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/** Source offsets stay unchanged when a logical line occupies multiple display rows. */
public final class CodeEditorLayout {
    private CodeEditorLayout() {}
    public record Row(int start, int end, int line, boolean continuation) {}
    public static List<Row> rows(String source, int width, boolean wrap, ToIntFunction<String> fit) {
        List<Row> rows = new ArrayList<>();
        int start = 0, line = 0;
        while (start <= source.length()) {
            int end = source.indexOf('\n', start);
            if (end < 0) end = source.length();
            int p = start;
            do {
                int n = wrap ? Math.max(1, fit.applyAsInt(source.substring(p, end))) : end - p;
                int next = Math.min(end, p + n);
                // Never divide a UTF-16 surrogate pair between display rows.
                if (next < end && next > p && Character.isHighSurrogate(source.charAt(next - 1))
                        && Character.isLowSurrogate(source.charAt(next))) {
                    next = next - p == 1 ? next + 1 : next - 1;
                }
                rows.add(new Row(p, next, line, p != start));
                p = next;
            } while (p < end);
            if (end == source.length()) break;
            start = end + 1;
            line++;
        }
        return List.copyOf(rows);
    }
    public static int rowAt(List<Row> rows, int offset) {
        int lo = 0, hi = rows.size() - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (rows.get(mid).start() <= offset) lo = mid + 1;
            else hi = mid - 1;
        }
        return Math.max(0, hi);
    }
    public static int maxScroll(int extent, int viewport) {
        return Math.max(0, extent - Math.max(1, viewport));
    }
    public static int find(String source, String query, int cursor, int anchor, boolean backwards) {
        if (query.isEmpty()) return -1;
        if (backwards) {
            int before = Math.min(cursor, anchor) - 1;
            int hit = before < 0 ? -1 : source.lastIndexOf(query, before);
            return hit >= 0 ? hit : source.lastIndexOf(query);
        }
        int hit = source.indexOf(query, Math.max(cursor, anchor));
        return hit >= 0 ? hit : source.indexOf(query);
    }
    public static int occurrences(String source, String query) {
        if (query.isEmpty()) return 0;
        int count = 0;
        for (int p = 0; (p = source.indexOf(query, p)) >= 0; p += query.length()) count++;
        return count;
    }
    public record Replacement(String source, int count, int caret) {}
    /** Literal replacements, never regex; all changes form a single editor transaction. */
    public static Replacement replace(String source, String query, String replacement,
                                      int cursor, int anchor, boolean all, int limit) {
        if (query.isEmpty()) return new Replacement(source, 0, cursor);
        int start = Math.min(cursor, anchor), end = Math.max(cursor, anchor);
        int hit = source.substring(start, end).equals(query) ? start : find(source, query, cursor, anchor, false);
        int count = all ? occurrences(source, query) : hit >= 0 ? 1 : 0;
        if (count == 0) return new Replacement(source, 0, cursor);
        long length = source.length() + (long) count * (replacement.length() - query.length());
        if (length > limit) return new Replacement(source, -1, cursor);
        if (all) hit = source.indexOf(query);
        String changed = all ? source.replace(query, replacement)
                : source.substring(0, hit) + replacement + source.substring(hit + query.length());
        return new Replacement(changed, count, hit + replacement.length());
    }
    public record ErrorPosition(int line, int column) {}
    private static final java.util.regex.Pattern ERROR_POSITION = java.util.regex.Pattern.compile("\\bline (\\d+):(\\d+)\\b");
    /** SFM's ListErrorListener reports one-based lines and zero-based columns. */
    public static ErrorPosition errorPosition(String raw) {
        var match = ERROR_POSITION.matcher(raw);
        if (!match.find()) return null;
        try {
            int line = Integer.parseInt(match.group(1)), column = Integer.parseInt(match.group(2));
            return line > 0 ? new ErrorPosition(line, column) : null;
        } catch (NumberFormatException ignored) { return null; }
    }
}
