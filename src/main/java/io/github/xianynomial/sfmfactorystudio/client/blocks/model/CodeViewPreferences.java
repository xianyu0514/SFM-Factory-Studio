package io.github.xianynomial.sfmfactorystudio.client.blocks.model;

import java.util.List;

/** Per-manager view preferences; the fourth value extends the 0.9.0 format. */
public record CodeViewPreferences(double fraction, boolean focused, boolean wrapped, boolean expanded) {
    public static final double DEFAULT_FRACTION = 0.4;
    public static CodeViewPreferences defaults() {
        return new CodeViewPreferences(DEFAULT_FRACTION, false, false, true);
    }
    public static CodeViewPreferences read(List<?> rows) {
        if (rows == null || rows.isEmpty() || !(rows.get(0) instanceof List<?> row) || row.size() < 3)
            return defaults();
        double fraction = row.get(0) instanceof Number n && Double.isFinite(n.doubleValue())
                ? Math.max(0, Math.min(1, n.doubleValue())) : DEFAULT_FRACTION;
        return new CodeViewPreferences(fraction, enabled(row.get(1)), enabled(row.get(2)),
                row.size() < 4 || !(row.get(3) instanceof Number) || enabled(row.get(3)));
    }
    private static boolean enabled(Object value) { return value instanceof Number n && n.intValue() != 0; }
    public List<List<Object>> write() {
        return List.of(List.of(fraction, focused ? 1 : 0, wrapped ? 1 : 0, expanded ? 1 : 0));
    }
    public boolean showCode(boolean splitFits) { return expanded && (focused || splitFits); }
}
