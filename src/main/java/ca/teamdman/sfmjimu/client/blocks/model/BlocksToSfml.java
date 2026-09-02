package ca.teamdman.sfmjimu.client.blocks.model;

import ca.teamdman.sfm.common.config.SFMConfig;
import ca.teamdman.sfmjimu.client.blocks.model.BProgram.Bool;
import ca.teamdman.sfmjimu.client.blocks.model.BProgram.Branch;
import ca.teamdman.sfmjimu.client.blocks.model.BProgram.LabelAccess;
import ca.teamdman.sfmjimu.client.blocks.model.BProgram.ResourceLimit;
import ca.teamdman.sfmjimu.client.blocks.model.BProgram.ResourceRef;
import ca.teamdman.sfmjimu.client.blocks.model.BProgram.Statement;
import ca.teamdman.sfmjimu.client.blocks.model.BProgram.TimerTrigger;
import ca.teamdman.sfmjimu.client.blocks.model.BProgram.Trigger;
import ca.teamdman.sfmjimu.client.blocks.model.BProgram.WithExpr;
import ca.teamdman.sfmjimu.client.blocks.model.BProgram.WithFilter;

import java.util.List;

/**
 * Serializes the block model back into canonical, pretty-printed SFML. The output
 * is plain text SFM already understands — the disk stores SFML, not blocks.
 */
public final class BlocksToSfml {
    private static final String INDENT = "    ";
    private static final List<String> KEYWORDS = List.of(
            "if", "then", "else", "has", "overall", "some", "every", "each", "one", "lone",
            "true", "false", "not", "and", "or", "gt", "lt", "eq", "le", "ge", "from", "to",
            "input", "output", "slots", "slot", "retain", "except", "forget", "empty", "in",
            "with", "without", "tag", "round", "robin", "by", "label", "block", "top",
            "bottom", "north", "east", "south", "west", "side", "left", "right", "front",
            "back", "null", "ticks", "tick", "seconds", "second", "global", "g", "redstone",
            "pulse", "do", "end", "name"
    );
    private static final List<String> RESOURCE_KEYWORDS_ALLOWED_AS_IDENTIFIERS = List.of(
            "redstone", "global", "second", "seconds", "top", "bottom", "left", "right", "front", "back"
    );

    private BlocksToSfml() {
    }

    /** Uses the live general server setting. Prefer {@link TimerRules} for a trigger. */
    public static long minimumTimerIntervalInTicks() {
        return TimerRules.normalMinimumTicks();
    }

    public static String toSfml(BProgram program) {
        StringBuilder sb = new StringBuilder();
        writeComments(sb, program.fileHeaderComments, 0);
        if (program.name != null && !program.name.isBlank()) {
            sb.append("name \"").append(program.name.replace("\"", "\\\"")).append("\"\n\n");
        }
        writeComments(sb, program.preambleComments, 0);
        if (!program.preambleComments.isEmpty() && !program.triggers.isEmpty()) sb.append('\n');
        for (Trigger t : program.triggers) {
            writeComments(sb, t.leadingComments, 0);
            writeTrigger(sb, t);
            sb.append('\n');
        }
        writeComments(sb, program.trailingComments, 0);
        return sb.toString();
    }

    private static void writeTrigger(StringBuilder sb, Trigger t) {
        if (t instanceof TimerTrigger tt) {
            // The number editor refuses an invalid commit. This final guard is
            // for imported/partially-mutated model states so every generated
            // program remains accepted by SFM's own compiler.
            sb.append("every ").append(Math.max(TimerRules.minimumCount(tt), tt.count));
            if (tt.global) sb.append(" global");
            if (tt.plus > 0) sb.append(" plus ").append(Math.max(0, tt.plus));
            sb.append(tt.unit == TimerTrigger.Unit.SECONDS ? " seconds" : " ticks");
            sb.append(" do\n");
        } else if (t instanceof BProgram.PulseTrigger) {
            sb.append("every redstone pulse do\n");
        }
        writeStatements(sb, t.body, 1);
        sb.append("end");
    }

    private static void writeStatements(StringBuilder sb, List<Statement> statements, int depth) {
        for (Statement s : statements) {
            writeStatement(sb, s, depth);
        }
    }

    private static void writeStatement(StringBuilder sb, Statement s, int depth) {
        String pad = INDENT.repeat(depth);
        if (s instanceof Statement.Input in) {
            sb.append(pad).append("input");
            writeLimits(sb, in.limits);
            writeExcept(sb, in.except);
            sb.append(" from");
            if (in.each) sb.append(" each");
            sb.append(' ').append(writeLabelAccess(in.access)).append('\n');
        } else if (s instanceof Statement.Output out) {
            sb.append(pad).append("output");
            writeLimits(sb, out.limits);
            writeExcept(sb, out.except);
            sb.append(" to");
            if (out.emptySlots) sb.append(" empty slots in");
            if (out.each) sb.append(" each");
            sb.append(' ').append(writeLabelAccess(out.access)).append('\n');
        } else if (s instanceof Statement.Forget f) {
            sb.append(pad).append("forget");
            for (int i = 0; i < f.labels.size(); i++) {
                sb.append(i == 0 ? ' ' : ", ").append(quoteLabelIfNeeded(f.labels.get(i)));
            }
            sb.append('\n');
        } else if (s instanceof Statement.If iff) {
            if (iff.branches.isEmpty()) {
                sb.append(pad).append("-- 空的判断积木已忽略\n");
                return;
            }
            for (int i = 0; i < iff.branches.size(); i++) {
                Branch b = iff.branches.get(i);
                sb.append(pad).append(i == 0 ? "if " : "else if ").append(writeBool(b.cond)).append(" then\n");
                writeStatements(sb, b.body, depth + 1);
            }
            if (iff.hasElse || !iff.elseBody.isEmpty()) {
                sb.append(pad).append("else\n");
                writeStatements(sb, iff.elseBody, depth + 1);
            }
            sb.append(pad).append("end\n");
        } else if (s instanceof Statement.Comment c) {
            String text = c.text.replace("\n", " ").replace("\r", " ").stripTrailing();
            sb.append(pad).append(text.isEmpty() ? "--" : "-- " + text).append('\n');
        } else if (s instanceof Statement.Raw raw) {
            if (validStatementFragment(raw.text)) {
                for (String line : raw.text.split("\n", -1)) {
                    sb.append(line.isBlank() ? "" : pad).append(line).append('\n');
                }
            } else {
                sb.append(pad).append("-- 无效兼容代码已停用；请在同屏代码编辑区修正\n");
            }
        }
    }

    private static void writeLimits(StringBuilder sb, List<ResourceLimit> limits) {
        boolean firstLimit = true;
        for (ResourceLimit rl : limits) {
            if (rl == null || rl.isEmpty()) continue;
            sb.append(firstLimit ? " " : ", ");
            firstLimit = false;
            boolean wrote = false;
            if (rl.quantity != null) {
                sb.append(Math.max(0, rl.quantity)).append(rl.quantityEach ? " each" : "");
                wrote = true;
            }
            if (rl.retain != null) {
                sb.append(wrote ? " " : "").append("retain ").append(Math.max(0, rl.retain)).append(rl.retainEach ? " each" : "");
                wrote = true;
            }
            List<ResourceRef> resources = rl.resources.stream().filter(java.util.Objects::nonNull).toList();
            if (!resources.isEmpty()) {
                sb.append(wrote ? " " : "");
                for (int i = 0; i < resources.size(); i++) {
                    ResourceRef resource = resources.get(i);
                    sb.append(i == 0 ? "" : " or ").append(resource.sfml());
                }
                wrote = true;
            }
            if (rl.with != null) {
                sb.append(wrote ? " " : "").append(writeWith(rl.with));
            }
        }
    }

    private static void writeComments(StringBuilder sb, List<String> comments, int depth) {
        String pad = INDENT.repeat(depth);
        for (String comment : comments) {
            String text = comment == null ? "" : comment.replace("\n", " ").replace("\r", " ").stripTrailing();
            sb.append(pad).append(text.isEmpty() ? "--" : "-- " + text).append('\n');
        }
    }

    private static void writeExcept(StringBuilder sb, List<ResourceRef> except) {
        List<ResourceRef> resources = except.stream().filter(java.util.Objects::nonNull).toList();
        if (resources.isEmpty()) return;
        sb.append(" except ");
        for (int i = 0; i < resources.size(); i++) {
            sb.append(i == 0 ? "" : ", ").append(resources.get(i).sfml());
        }
    }

    public static String writeLabelAccess(LabelAccess a) {
        StringBuilder sb = new StringBuilder();
        List<String> labels = a.labels.stream().filter(x -> x != null && !x.isBlank()).distinct().toList();
        if (labels.isEmpty()) {
            sb.append(quoteLabelIfNeeded("未设置标签"));
        }
        for (int i = 0; i < labels.size(); i++) {
            sb.append(i == 0 ? "" : ", ").append(quoteLabelIfNeeded(labels.get(i)));
        }
        if (a.roundRobin == BProgram.RoundRobinMode.LABEL || a.roundRobin == BProgram.RoundRobinMode.BLOCK) {
            sb.append(" round robin by ").append(a.roundRobin.name().toLowerCase(java.util.Locale.ROOT));
        }
        if (a.eachSide) {
            sb.append(" each side");
        } else if (!a.sides.isEmpty()) {
            sb.append(" ");
            List<BProgram.Side> sides = a.sides.stream().filter(java.util.Objects::nonNull).distinct().toList();
            for (int i = 0; i < sides.size(); i++) {
                sb.append(i == 0 ? "" : ", ").append(sides.get(i).sfml());
            }
            sb.append(" side");
        }
        if (!a.slots.isEmpty()) {
            sb.append(" slot").append(a.slots.size() > 1 ? "s" : "");
            for (int i = 0; i < a.slots.size(); i++) {
                sb.append(i == 0 ? " " : ", ").append(a.slots.get(i).sfml());
            }
        }
        return sb.toString();
    }

    public static String writeBool(Bool b) {
        if (b instanceof Bool.Has h) {
            StringBuilder sb = new StringBuilder();
            if (h.setMode != null && h.setMode != Bool.SetMode.DEFAULT) sb.append(h.setMode.sfml()).append(' ');
            sb.append(writeLabelAccess(h.access));
            Bool.Comparison comparison = h.comparison == null ? Bool.Comparison.GE : h.comparison;
            sb.append(" has ").append(comparison.symbol()).append(' ').append(Math.max(0, h.number));
            List<ResourceRef> resources = h.resources.stream().filter(java.util.Objects::nonNull).toList();
            if (!resources.isEmpty()) {
                for (int i = 0; i < resources.size(); i++) {
                    sb.append(i == 0 ? ' ' : " or ").append(resources.get(i).sfml());
                }
            }
            if (h.with != null) sb.append(' ').append(writeWith(h.with));
            writeExcept(sb, h.except);
            return sb.toString();
        } else if (b instanceof Bool.Redstone r) {
            return r.comparison == null
                    ? "redstone"
                    : "redstone " + r.comparison.symbol() + " " + Math.max(0, r.number);
        } else if (b instanceof Bool.And a) {
            return joinBool(a.parts, " and ");
        } else if (b instanceof Bool.Or o) {
            return joinBool(o.parts, " or ");
        } else if (b instanceof Bool.Not n) {
            return "not " + wrap(n.inner);
        } else if (b instanceof Bool.Const c) {
            return c.value ? "true" : "false";
        } else if (b instanceof Bool.RawBool r) {
            return validBoolFragment(r.text) ? r.text.trim() : "true";
        }
        return "true";
    }

    private static String joinBool(List<Bool> parts, String sep) {
        List<Bool> valid = parts.stream().filter(java.util.Objects::nonNull).toList();
        if (valid.isEmpty()) return "true";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < valid.size(); i++) {
            if (i > 0) sb.append(sep);
            sb.append(wrap(valid.get(i)));
        }
        return sb.toString();
    }

    public static String writeWith(WithFilter filter) {
        String prefix = filter.mode == WithFilter.Mode.WITHOUT ? "without " : "with ";
        return prefix + writeWithExpr(filter.expr);
    }

    private static String writeWithExpr(WithExpr expr) {
        if (expr instanceof WithExpr.Tag tag) {
            return "#" + normalizeTagMatcher(tag.matcher);
        }
        if (expr instanceof WithExpr.Not not) {
            return "not " + wrapWith(not.inner);
        }
        if (expr instanceof WithExpr.And and) {
            return joinWith(and.parts, " and ");
        }
        if (expr instanceof WithExpr.Or or) {
            return joinWith(or.parts, " or ");
        }
        return "#*";
    }

    private static String joinWith(List<WithExpr> parts, String separator) {
        List<WithExpr> valid = parts.stream().filter(java.util.Objects::nonNull).toList();
        if (valid.isEmpty()) return "#*";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < valid.size(); i++) {
            if (i > 0) sb.append(separator);
            sb.append(wrapWith(valid.get(i)));
        }
        return sb.toString();
    }

    private static String wrapWith(WithExpr expr) {
        String text = writeWithExpr(expr);
        return expr instanceof WithExpr.And || expr instanceof WithExpr.Or ? "(" + text + ")" : text;
    }

    private static String normalizeTagMatcher(String matcher) {
        String text = matcher == null ? "*" : matcher.trim();
        while (text.startsWith("#")) text = text.substring(1);
        return text.matches("[a-zA-Z_*][a-zA-Z0-9_*]*(?::[a-zA-Z_*][a-zA-Z0-9_*]*)?(?:/[a-zA-Z_*][a-zA-Z0-9_*]*)*")
                ? text
                : "*";
    }

    private static boolean validStatementFragment(String text) {
        return text != null && !text.isBlank()
                && SfmlToBlocks.parse("every 20 ticks do\n" + text + "\nend\n").ok();
    }

    private static boolean validBoolFragment(String text) {
        return text != null && !text.isBlank()
                && SfmlToBlocks.parse("every 20 ticks do\nif " + text + " then\nend\nend\n").ok();
    }

    /** Parenthesize nested and/or/not operands so precedence is explicit. */
    private static String wrap(Bool b) {
        String s = writeBool(b);
        boolean needsParens = b instanceof Bool.And || b instanceof Bool.Or || b instanceof Bool.Not;
        return needsParens ? "(" + s + ")" : s;
    }

    /**
     * Quote a label only when required: it contains characters outside the bare
     * identifier alphabet, or collides with an SFML keyword.
     */
    /**
     * Resource ids are stored unquoted in the model; when the value is a regex
     * or otherwise not a bare dotted identifier, emit it as a quoted string
     * resource (the SFML way to write patterns).
     */
    public static String quoteResourceIfNeeded(String id) {
        String value = id == null ? "*" : id.trim();
        if (isBareResource(value)) {
            return value;
        }
        return "\"" + value.replace("\"", "\\\"") + "\"";
    }

    private static boolean isBareResource(String value) {
        if (!value.matches("[a-zA-Z_*][a-zA-Z0-9_*]*(?::(?:[a-zA-Z_*][a-zA-Z0-9_*]*)?){0,3}")) {
            return false;
        }
        for (String segment : value.split(":", -1)) {
            String lower = segment.toLowerCase(java.util.Locale.ROOT);
            if (!segment.isEmpty() && KEYWORDS.contains(lower)
                    && !RESOURCE_KEYWORDS_ALLOWED_AS_IDENTIFIERS.contains(lower)) {
                return false;
            }
        }
        return true;
    }

public static String quoteLabelIfNeeded(String label) {
        if (label == null || label.isBlank()) label = "未设置标签";
        if (label.matches("[a-zA-Z_][a-zA-Z0-9_*]*") && !KEYWORDS.contains(label.toLowerCase(java.util.Locale.ROOT))) {
            return label;
        }
        return "\"" + label.replace("\"", "\\\"") + "\"";
    }
}
