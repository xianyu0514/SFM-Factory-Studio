package io.github.xianynomial.sfmfactorystudio.client.blocks.model;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * The block-program data model: a Scratch-style tree that maps 1:1 onto the SFML
 * grammar. Everything the editor shows is stored here; {@code BlocksToSfml} turns
 * it into text and {@code SfmlToBlocks} parses every current SFML grammar shape
 * back into typed nodes. Raw nodes remain only as a read-only migration guard for
 * content created by older or future versions.
 */
public final class BProgram {
    /**
     * Session-stable ids for editor bookkeeping (layout caches, severity
     * overlays). Assigned monotonically at construction, never persisted —
     * SFML and the json files stay id-free, and every re-parse produces fresh
     * ids, so stale cache entries can never alias a new block.
     */
    private static final java.util.concurrent.atomic.AtomicLong NEXT_ID =
            new java.util.concurrent.atomic.AtomicLong(1);

    public String name = "";                 // SFML `name "..."` line; may be empty
    /** Comments before the optional NAME declaration. */
    public final List<String> fileHeaderComments = new ArrayList<>();
    /** Comments after NAME and before the first trigger. */
    public final List<String> preambleComments = new ArrayList<>();
    /** Comments after the final trigger. */
    public final List<String> trailingComments = new ArrayList<>();
    public final List<Trigger> triggers = new ArrayList<>();

    /** Collect every distinct label referenced anywhere, in first-seen order. */
    public List<String> collectLabels() {
        Set<String> out = new LinkedHashSet<>();
        for (Trigger t : triggers) {
            for (Statement s : t.body) collectFromStatement(s, out);
        }
        return new ArrayList<>(out);
    }

    private static void collectFromStatement(Statement s, Set<String> out) {
        if (s instanceof Statement.Input in) {
            in.access.collectLabels(out);
            for (ResourceLimit rl : in.limits) rl.collectLabels(out);
        } else if (s instanceof Statement.Output out2) {
            out2.access.collectLabels(out);
            for (ResourceLimit rl : out2.limits) rl.collectLabels(out);
        } else if (s instanceof Statement.Forget f) {
            out.addAll(f.labels);
        } else if (s instanceof Statement.If i) {
            for (Branch b : i.branches) {
                b.cond.collectLabels(out);
                for (Statement bs : b.body) collectFromStatement(bs, out);
            }
            for (Statement bs : i.elseBody) collectFromStatement(bs, out);
        }
    }

    /** Deep copy via re-parse would need codegen; this is a structural copy used for undo. */
    public BProgram copy() {
        BProgram p = new BProgram();
        p.name = this.name;
        p.fileHeaderComments.addAll(this.fileHeaderComments);
        p.preambleComments.addAll(this.preambleComments);
        p.trailingComments.addAll(this.trailingComments);
        for (Trigger t : triggers) p.triggers.add(t.copy());
        return p;
    }

    // ---------------------------------------------------------------- trigger

    public static abstract class Trigger {
        /** Session-stable id; see the NEXT_ID counter in {@link BProgram}. */
        public final long id = NEXT_ID.getAndIncrement();
        /** Comments between the previous trigger and this trigger. */
        public final List<String> leadingComments = new ArrayList<>();
        public final List<Statement> body = new ArrayList<>();

        public abstract Trigger copy();

        protected void copyBody(Trigger into) {
            into.leadingComments.addAll(leadingComments);
            for (Statement s : body) into.body.add(s.copy());
        }
    }

    /** `every <count> <unit> do ... end`; supports the global/plus grammar extras. */
    public static final class TimerTrigger extends Trigger {
        public long count = 20;
        public Unit unit = Unit.TICKS;
        public boolean global = false;
        public long plus = 0;               // `plus N ticks` offset; 0 = none

        public enum Unit {TICKS, SECONDS}

        @Override
        public TimerTrigger copy() {
            TimerTrigger t = new TimerTrigger();
            t.count = count;
            t.unit = unit;
            t.global = global;
            t.plus = plus;
            copyBody(t);
            return t;
        }
    }

    /** `every redstone pulse do ... end`. */
    public static final class PulseTrigger extends Trigger {
        @Override
        public PulseTrigger copy() {
            PulseTrigger t = new PulseTrigger();
            copyBody(t);
            return t;
        }
    }

    // -------------------------------------------------------------- statement

    public static abstract class Statement {
        /** Session-stable id; see the NEXT_ID counter in {@link BProgram}. */
        public final long id = NEXT_ID.getAndIncrement();

        public abstract Statement copy();

        public static final class Input extends Statement {
            public final List<ResourceLimit> limits = new ArrayList<>();
            public final List<ResourceRef> except = new ArrayList<>();
            public boolean each = false;                            // FROM EACH x
            public final LabelAccess access = new LabelAccess();

            @Override
            public Input copy() {
                Input s = new Input();
                copyLimits(s.limits, limits);
                for (ResourceRef resource : except) s.except.add(resource.copy());
                s.each = each;
                s.access.copyFrom(access);
                return s;
            }
        }

        public static final class Output extends Statement {
            public final List<ResourceLimit> limits = new ArrayList<>();
            public final List<ResourceRef> except = new ArrayList<>();
            public boolean each = false;                            // TO EACH x
            public boolean emptySlots = false;                      // TO EMPTY SLOTS IN
            public final LabelAccess access = new LabelAccess();

            @Override
            public Output copy() {
                Output s = new Output();
                copyLimits(s.limits, limits);
                for (ResourceRef resource : except) s.except.add(resource.copy());
                s.each = each;
                s.emptySlots = emptySlots;
                s.access.copyFrom(access);
                return s;
            }
        }

        /** `forget [labels...]`; empty list = plain `forget` (all tracked resources). */
        public static final class Forget extends Statement {
            public final List<String> labels = new ArrayList<>();

            @Override
            public Forget copy() {
                Forget s = new Forget();
                s.labels.addAll(labels);
                return s;
            }
        }

        /** `if ... then ... [else if ...] [else ...] end`. */
        public static final class If extends Statement {
            public final List<Branch> branches = new ArrayList<>();
            public boolean hasElse = false;
            public final List<Statement> elseBody = new ArrayList<>();

            @Override
            public If copy() {
                If s = new If();
                for (Branch b : branches) s.branches.add(b.copy());
                s.hasElse = hasElse;
                for (Statement bs : elseBody) s.elseBody.add(bs.copy());
                return s;
            }
        }

        /** `-- text` note block; imported comments remain editable and survive round trips. */
        public static final class Comment extends Statement {
            public String text = "";

            public Comment() {
            }

            public Comment(String text) {
                this.text = text;
            }

            @Override
            public Comment copy() {
                return new Comment(text);
            }
        }

        /** Read-only migration guard for syntax from a newer or older build. */
        public static final class Raw extends Statement {
            public String text = "";

            public Raw() {
            }

            public Raw(String text) {
                this.text = text;
            }

            @Override
            public Raw copy() {
                return new Raw(text);
            }
        }

        private static void copyLimits(List<ResourceLimit> dst, List<ResourceLimit> src) {
            for (ResourceLimit rl : src) dst.add(rl.copy());
        }
    }

    public static final class Branch {
        public Bool cond = new Bool.Const(true);
        public final List<Statement> body = new ArrayList<>();

        public Branch copy() {
            Branch b = new Branch();
            b.cond = cond.copy();
            for (Statement s : body) b.body.add(s.copy());
            return b;
        }
    }

    // ------------------------------------------------------------ io options

    /**
     * One clause of a resource limit list: `[limit] <ids> [with ...]`.
     * Any part may be unset; at least one must be present to be meaningful.
     */
    public static final class ResourceLimit {
        public Long quantity = null;
        public boolean quantityEach = false;
        public Long retain = null;
        public boolean retainEach = false;
        public final List<ResourceRef> resources = new ArrayList<>();
        public WithFilter with = null;

        public ResourceLimit copy() {
            ResourceLimit rl = new ResourceLimit();
            rl.quantity = quantity;
            rl.quantityEach = quantityEach;
            rl.retain = retain;
            rl.retainEach = retainEach;
            for (ResourceRef resource : resources) rl.resources.add(resource.copy());
            rl.with = with == null ? null : with.copy();
            return rl;
        }

        public void collectLabels(Set<String> out) {
            // resource limits never reference labels
        }

        /** True when this clause carries no information beyond resource ids. */
        public boolean isPlain() {
            return quantity == null && retain == null && with == null && resources.size() <= 1;
        }

        /** An entirely empty editor row is omitted instead of producing a dangling comma. */
        public boolean isEmpty() {
            return quantity == null && retain == null && with == null && resources.isEmpty();
        }
    }

    // ---------------------------------------------------------- resource ids

    /** Resource category shown separately from the concrete resource selector. */
    public enum ResourceKind {
        ITEM("item", "物品"),
        FLUID("fluid", "流体"),
        CHEMICAL("chemical", "化学品"),
        GAS("gas", "气体"),
        SLURRY("slurry", "矿浆"),
        PIGMENT("pigment", "颜料"),
        REDSTONE("redstone", "红石信号"),
        INFUSION("infusion", "灌注材料"),
        FORGE_ENERGY("forge_energy", "能量"),
        CUSTOM("custom", "其他类别");

        public final String sfmlName;
        public final String chineseName;

        ResourceKind(String sfmlName, String chineseName) {
            this.sfmlName = sfmlName;
            this.chineseName = chineseName;
        }

        public static ResourceKind fromTypeName(String typeName) {
            String value = typeName == null ? "item" : typeName.toLowerCase(Locale.ROOT);
            if (List.of("fe", "rf", "energy", "power", "forge_energy").contains(value)) {
                return FORGE_ENERGY;
            }
            for (ResourceKind kind : values()) {
                if (kind != CUSTOM && kind.sfmlName.equals(value)) return kind;
            }
            return CUSTOM;
        }
    }

    /**
     * Typed form of SFM's four-part resource identifier:
     * {@code type namespace:type name:resource namespace:resource name}.
     * The editor renders the first pair as a category control and the second as
     * a separate resource slot. Item output intentionally uses SFM's shorthand.
     */
    public static final class ResourceRef {
        public String typeNamespace = "sfm";
        public String typeName = "item";
        public String namespace = "*";
        public String name = "*";

        public static ResourceRef forKind(ResourceKind kind) {
            ResourceRef ref = new ResourceRef();
            ResourceKind selected = kind == null || kind == ResourceKind.CUSTOM ? ResourceKind.ITEM : kind;
            ref.typeName = selected.sfmlName;
            return ref;
        }

        public static ResourceRef parse(String sfml) {
            String value = sfml == null ? "*" : sfml.trim();
            if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1).replace("\\\"", "\"");
            }
            String[] parts = value.split(":", -1);
            ResourceRef ref = new ResourceRef();
            switch (parts.length) {
                case 1 -> ref.name = wildcard(parts[0]);
                case 2 -> {
                    ref.namespace = wildcard(parts[0]);
                    ref.name = wildcard(parts[1]);
                }
                case 3 -> {
                    ref.typeName = canonicalType(parts[0]);
                    ref.namespace = wildcard(parts[1]);
                    ref.name = wildcard(parts[2]);
                }
                case 4 -> {
                    ref.typeNamespace = wildcard(parts[0]);
                    ref.typeName = canonicalType(parts[1]);
                    ref.namespace = wildcard(parts[2]);
                    ref.name = wildcard(parts[3]);
                }
                default -> throw new IllegalArgumentException("资源名称最多只能有四段");
            }
            return ref;
        }

        private static String wildcard(String value) {
            return value == null || value.isBlank() ? "*" : value;
        }

        private static String canonicalType(String value) {
            String type = wildcard(value).toLowerCase(Locale.ROOT);
            return List.of("fe", "rf", "energy", "power").contains(type) ? "forge_energy" : type;
        }

        public ResourceKind kind() {
            return "sfm".equalsIgnoreCase(typeNamespace)
                    ? ResourceKind.fromTypeName(typeName)
                    : ResourceKind.CUSTOM;
        }

        public boolean isItem() {
            return kind() == ResourceKind.ITEM;
        }

        public boolean isWildcard() {
            return isWildcardPart(namespace) && isWildcardPart(name);
        }

        private static boolean isWildcardPart(String value) {
            return value == null || value.isBlank() || value.equals("*") || value.equals(".*");
        }

        /** Resource-only portion displayed in the slot editor. Empty means all. */
        public String resourcePart() {
            if (isWildcard()) return "";
            if (isWildcardPart(namespace)) return name;
            return namespace + ":" + name;
        }

        public ResourceRef withResourcePart(String value) {
            ResourceRef copy = copy();
            String text = value == null ? "" : value.trim();
            if (text.isBlank()) {
                copy.namespace = "*";
                copy.name = "*";
                return copy;
            }
            String[] parts = text.split(":", -1);
            if (parts.length == 1) {
                copy.namespace = "*";
                copy.name = wildcard(parts[0]);
            } else if (parts.length == 2) {
                copy.namespace = wildcard(parts[0]);
                copy.name = wildcard(parts[1]);
            } else {
                throw new IllegalArgumentException("这里只填写资源名称，例如 water 或 minecraft:water");
            }
            return copy;
        }

        /** Canonical SFML: item omits its category; all other kinds always include it. */
        public String sfml() {
            String raw;
            if (isItem()) {
                if (isWildcardPart(namespace) && isWildcardPart(name)) raw = "*";
                else if (isWildcardPart(namespace)) raw = name;
                else raw = namespace + ":" + name;
            } else {
                String type = "sfm".equalsIgnoreCase(typeNamespace)
                        ? typeName
                        : typeNamespace + ":" + typeName;
                raw = type + ":"
                        + (isWildcardPart(namespace) ? "" : namespace)
                        + ":"
                        + (isWildcardPart(name) ? "" : name);
            }
            return BlocksToSfml.quoteResourceIfNeeded(raw);
        }

        public ResourceRef copy() {
            ResourceRef ref = new ResourceRef();
            ref.typeNamespace = typeNamespace;
            ref.typeName = typeName;
            ref.namespace = namespace;
            ref.name = name;
            return ref;
        }

        @Override
        public String toString() {
            return sfml();
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof ResourceRef ref)) return false;
            return Objects.equals(typeNamespace, ref.typeNamespace)
                    && Objects.equals(typeName, ref.typeName)
                    && Objects.equals(namespace, ref.namespace)
                    && Objects.equals(name, ref.name);
        }

        @Override
        public int hashCode() {
            return Objects.hash(typeNamespace, typeName, namespace, name);
        }
    }

    // ------------------------------------------------------- resource filters

    /** A structured `with|without` resource-component filter. */
    public static final class WithFilter {
        public Mode mode = Mode.WITH;
        public WithExpr expr = new WithExpr.Tag("*");

        public enum Mode {WITH, WITHOUT}

        public WithFilter copy() {
            WithFilter f = new WithFilter();
            f.mode = mode;
            f.expr = expr.copy();
            return f;
        }
    }

    /** The boolean tree that follows a SFML `with` or `without` keyword. */
    public static abstract class WithExpr {
        public abstract WithExpr copy();

        public static final class Tag extends WithExpr {
            public String matcher;

            public Tag(String matcher) {
                this.matcher = matcher == null || matcher.isBlank() ? "*" : matcher.trim();
            }

            @Override
            public WithExpr copy() {
                return new Tag(matcher);
            }
        }

        public static final class Not extends WithExpr {
            public WithExpr inner = new Tag("*");

            @Override
            public WithExpr copy() {
                Not n = new Not();
                n.inner = inner.copy();
                return n;
            }
        }

        public static final class And extends WithExpr {
            public final List<WithExpr> parts = new ArrayList<>();

            @Override
            public WithExpr copy() {
                And a = new And();
                for (WithExpr part : parts) a.parts.add(part.copy());
                return a;
            }
        }

        public static final class Or extends WithExpr {
            public final List<WithExpr> parts = new ArrayList<>();

            @Override
            public WithExpr copy() {
                Or o = new Or();
                for (WithExpr part : parts) o.parts.add(part.copy());
                return o;
            }
        }
    }

    public enum RoundRobinMode {
        NONE, LABEL, BLOCK;

        public static RoundRobinMode fromSfml(String text) {
            if (text == null) return NONE;
            return switch (text.toLowerCase(java.util.Locale.ROOT)) {
                case "label" -> LABEL;
                case "block" -> BLOCK;
                default -> NONE;
            };
        }
    }

    public enum Side {
        TOP, BOTTOM, NORTH, EAST, SOUTH, WEST, LEFT, RIGHT, FRONT, BACK, NULL;

        public String sfml() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }

        public static Side fromSfml(String text) {
            return valueOf(text.toUpperCase(java.util.Locale.ROOT));
        }
    }

    /** A validated slot number or inclusive slot range. */
    public record SlotRange(long first, long last) {
        public SlotRange {
            if (first < 0 || last < first) {
                throw new IllegalArgumentException("槽位必须是非负数，且范围终点不能小于起点");
            }
        }

        /**
         * 宽松解析：`3`、`3-10`、`-10`（从头到 10）、`40-`（40 到末尾，需容器总槽数）、
         * `10-3` 自动交换顺序。total=-1 表示容器槽数未知（开区间终点无法补全）。
         */
        public static SlotRange parseLenient(String text, int total) {
            String value = text == null ? "" : text.trim();
            if (value.matches("[0-9]+")) {
                long n = Long.parseLong(value);
                return new SlotRange(n, n);
            }
            if (value.matches("[0-9]+-[0-9]+")) {
                int dash = value.indexOf('-');
                long a = Long.parseLong(value.substring(0, dash));
                long b = Long.parseLong(value.substring(dash + 1));
                return a <= b ? new SlotRange(a, b) : new SlotRange(b, a); // 自动交换
            }
            if (value.matches("-[0-9]+")) {          // -10 = 0..10
                long end = Long.parseLong(value.substring(1));
                return new SlotRange(0, end);
            }
            if (value.matches("[0-9]+-")) {          // 40- = 40..末尾（需 total）
                long start = Long.parseLong(value.substring(0, value.length() - 1));
                long end = total >= 0 ? Math.max(total - 1, start) : start;
                return new SlotRange(start, end);
            }
            throw new IllegalArgumentException("槽位请输入数字，如 3 或 3-10");
        }
        public static SlotRange parse(String text) {
            String value = text == null ? "" : text.trim();
            if (!value.matches("[0-9]+(?:-[0-9]+)?")) {
                throw new IllegalArgumentException("槽位应写成 0 或 0-5");
            }
            int dash = value.indexOf('-');
            long first = Long.parseLong(dash < 0 ? value : value.substring(0, dash));
            long last = Long.parseLong(dash < 0 ? value : value.substring(dash + 1));
            return new SlotRange(first, last);
        }

        public String sfml() {
            return first == last ? Long.toString(first) : first + "-" + last;
        }

        @Override
        public String toString() {
            return sfml();
        }
    }

    /** The `from|to <labels> [round robin][sides][slots]` part of an IO statement. */
    public static final class LabelAccess {
        public final List<String> labels = new ArrayList<>();
        public RoundRobinMode roundRobin = RoundRobinMode.NONE;
        public boolean eachSide = false;
        public final List<Side> sides = new ArrayList<>();
        public final List<SlotRange> slots = new ArrayList<>();

        public void copyFrom(LabelAccess o) {
            labels.addAll(o.labels);
            roundRobin = o.roundRobin;
            eachSide = o.eachSide;
            sides.addAll(o.sides);
            slots.addAll(o.slots);
        }

        public void collectLabels(Set<String> out) {
            out.addAll(labels);
        }

        public boolean isEmpty() {
            return labels.isEmpty();
        }
    }

    // ------------------------------------------------------------- booleans

    public static abstract class Bool {
        public abstract Bool copy();

        public void collectLabels(Set<String> out) {
        }

        /** `[<setop>] <labels> has <op> <n> [ids]` — the everyday condition. */
        public static final class Has extends Bool {
            public SetMode setMode = SetMode.DEFAULT;
            public final LabelAccess access = new LabelAccess();
            public Comparison comparison = Comparison.GE;
            public long number = 0;
            public final List<ResourceRef> resources = new ArrayList<>();
            public WithFilter with = null;
            public final List<ResourceRef> except = new ArrayList<>();

            @Override
            public Has copy() {
                Has b = new Has();
                b.setMode = setMode;
                b.access.copyFrom(access);
                b.comparison = comparison;
                b.number = number;
                for (ResourceRef resource : resources) b.resources.add(resource.copy());
                b.with = with == null ? null : with.copy();
                for (ResourceRef resource : except) b.except.add(resource.copy());
                return b;
            }

            @Override
            public void collectLabels(Set<String> out) {
                access.collectLabels(out);
            }
        }

        /** `redstone [op n]` — bare form means "any redstone". */
        public static final class Redstone extends Bool {
            public Comparison comparison = null; // null means any redstone signal
            public long number = 0;

            @Override
            public Redstone copy() {
                Redstone b = new Redstone();
                b.comparison = comparison;
                b.number = number;
                return b;
            }
        }

        public enum SetMode {
            DEFAULT, OVERALL, SOME, EVERY, ONE, LONE;

            public static SetMode fromSfml(String text) {
                if (text == null || text.isBlank()) return DEFAULT;
                String normalized = text.equalsIgnoreCase("each") ? "every" : text;
                return valueOf(normalized.toUpperCase(java.util.Locale.ROOT));
            }

            public String sfml() {
                return this == DEFAULT ? "" : name().toLowerCase(java.util.Locale.ROOT);
            }
        }

        public enum Comparison {
            GT(">"), GE(">="), EQ("="), LE("<="), LT("<");

            private final String symbol;

            Comparison(String symbol) {
                this.symbol = symbol;
            }

            public String symbol() {
                return symbol;
            }

            public static Comparison fromSfml(String text) {
                return switch (text.toLowerCase(java.util.Locale.ROOT)) {
                    case ">", "gt" -> GT;
                    case ">=", "ge" -> GE;
                    case "=", "eq" -> EQ;
                    case "<=", "le" -> LE;
                    case "<", "lt" -> LT;
                    default -> throw new IllegalArgumentException("不支持的比较符号: " + text);
                };
            }
        }

        public static final class And extends Bool {
            public final List<Bool> parts = new ArrayList<>();

            @Override
            public And copy() {
                And b = new And();
                for (Bool p : parts) b.parts.add(p.copy());
                return b;
            }

            @Override
            public void collectLabels(Set<String> out) {
                for (Bool p : parts) p.collectLabels(out);
            }
        }

        public static final class Or extends Bool {
            public final List<Bool> parts = new ArrayList<>();

            @Override
            public Or copy() {
                Or b = new Or();
                for (Bool p : parts) b.parts.add(p.copy());
                return b;
            }

            @Override
            public void collectLabels(Set<String> out) {
                for (Bool p : parts) p.collectLabels(out);
            }
        }

        public static final class Not extends Bool {
            public Bool inner = new Const(true);

            @Override
            public Not copy() {
                Not b = new Not();
                b.inner = inner.copy();
                return b;
            }

            @Override
            public void collectLabels(Set<String> out) {
                inner.collectLabels(out);
            }
        }

        public static final class Const extends Bool {
            public boolean value;

            public Const(boolean value) {
                this.value = value;
            }

            @Override
            public Const copy() {
                return new Const(value);
            }
        }

        /** Read-only migration guard for a condition unknown to this build. */
        public static final class RawBool extends Bool {
            public String text = "";

            public RawBool() {
            }

            public RawBool(String text) {
                this.text = text;
            }

            @Override
            public RawBool copy() {
                return new RawBool(text);
            }
        }
    }
}
