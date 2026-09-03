package io.github.xianynomial.sfmfactorystudio.client.blocks.model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Structural and everyday-use checks for the block model. Grammar errors are
 * prevented by typed fields and the serializer; these diagnostics explain
 * incomplete or ineffective programs in player-facing Chinese before save.
 *
 * <p>Every issue carries an optional {@code block} reference (a
 * {@link BProgram.Trigger} or {@link BProgram.Statement}) so the editor can
 * highlight the offending block and jump the camera to it, plus an optional
 * one-click fix that mutates the model. The screen applies fixes through its
 * normal edit path (undo snapshot + invalidation), so they stay revertible.
 *
 * <p>Semantics notes verified against SFM 4.34 source:
 * <ul>
 *   <li>{@code input} with no resource limits compiles to
 *       {@code ResourceLimit.TAKE_ALL_LEAVE_NONE} — it moves everything.</li>
 *   <li>Unbound labels compile fine and silently do nothing at runtime.</li>
 *   <li>Except lists are plain resource sets; a superset exclusion starves a
 *       rule without any compile error.</li>
 * </ul>
 */
public final class ProgramDiagnostics {
    private ProgramDiagnostics() {
    }

    public enum Severity {ERROR, WARNING}

    /**
     * One finding. {@code block} is the trigger or statement the camera should
     * focus on; {@code fix} mutates the model in place when a safe, obvious
     * correction exists. {@code blockId} is the stable session id of
     * {@code block} (-1 when null), so UI overlays can key by id instead of
     * holding object references across model rebuilds.
     */
    public record Issue(
            Severity severity, String path, String message, Object block,
            String fixLabel, Runnable fix, long blockId
    ) {
    }

    /** External facts the pure checks cannot derive: label bindings + registries. */
    public static final class Context {
        public final Map<String, Integer> labelCounts;
        public final ResourceOracle oracle;

        /**
         * @param labelCounts label name → blocks actually bound on this disk;
         *                    labels absent from the map are treated as unknown
         *                    (never reported). An empty map disables the check.
         * @param oracle      resolves concrete ids to the kinds that contain
         *                    them; null disables id checks (unit tests).
         */
        public Context(Map<String, Integer> labelCounts, ResourceOracle oracle) {
            this.labelCounts = labelCounts == null ? Map.of() : labelCounts;
            this.oracle = oracle;
        }

        public static final Context EMPTY = new Context(Map.of(), null);
    }

    /** Which resource kinds contain a concrete {@code namespace:path} id. */
    public interface ResourceOracle {
        Set<BProgram.ResourceKind> kindsOf(String namespace, String name);
    }

    public static List<Issue> check(BProgram program) {
        return check(program, Context.EMPTY);
    }

    public static List<Issue> check(BProgram program, Context ctx) {
        List<Issue> issues = new ArrayList<>();
        for (int i = 0; i < program.triggers.size(); i++) {
            BProgram.Trigger trigger = program.triggers.get(i);
            String path = "第 " + (i + 1) + " 个开始条件";
            if (trigger instanceof BProgram.TimerTrigger timer) {
                long minimum = TimerRules.minimumCount(timer);
                if (timer.count < minimum) {
                    long finalMinimum = minimum;
                    error(issues, path, "执行间隔必须至少为 " + minimum
                                    + (timer.unit == BProgram.TimerTrigger.Unit.TICKS ? " 刻" : " 秒")
                                    + (TimerRules.usesOnlyEnergyIO(timer.body)
                                    ? "（纯能量传输规则）" : "（服务器普通传输规则）"),
                            timer, "调整为 " + finalMinimum + (timer.unit == BProgram.TimerTrigger.Unit.TICKS ? " 刻" : " 秒"),
                            () -> timer.count = finalMinimum);
                }
                if (timer.plus < 0) error(issues, path, "计时偏移不能是负数", timer, null, null);
            }
            if (trigger.body.isEmpty()) {
                // 空触发器体在 SFM 里合法（官方 timer_triggers 示例即是），
                // 新拖入的卡片不该被当成错误 —— 只提醒。
                warning(issues, path, "下面还没有要执行的操作", trigger, null, null);
            }
            // SFM 按整刻解析（先收集 INPUT 再由 OUTPUT 分发），语句顺序无关；
            // 是否存在取出按整个触发器预先扫描。
            checkStatements(trigger.body, path, trigger, ctx, anyInput(trigger.body), issues);
        }
        checkUnboundLabels(program, ctx, issues);
        return issues;
    }

    /**
     * “标签没有绑定任何方块”的汇总报告：同一标签无论被多少积木引用，
     * 都只出一条提醒，定位指向第一次用到它的积木（用户 2026-09-02 拍板）。
     */
    private static void checkUnboundLabels(BProgram program, Context ctx, List<Issue> issues) {
        if (ctx == null || ctx.labelCounts.isEmpty()) return;
        Map<String, Object> firstUse = new LinkedHashMap<>();
        collectFirstLabelUses(program.triggers, firstUse);
        for (var entry : firstUse.entrySet()) {
            String label = entry.getKey();
            Integer count = ctx.labelCounts.get(label);
            if (count != null && count == 0) {
                warning(issues, "标签", "标签「" + label + "」还没有绑定方块", entry.getValue(), null, null);
            }
        }
    }

    /** 首个引用各标签的积木（Trigger/Statement），供定位高亮。 */
    private static void collectFirstLabelUses(List<BProgram.Trigger> triggers, Map<String, Object> out) {
        for (BProgram.Trigger trigger : triggers) {
            collectFirstLabelUsesInBody(trigger.body, trigger, out);
        }
    }

    private static void collectFirstLabelUsesInBody(List<BProgram.Statement> statements, Object block, Map<String, Object> out) {
        for (BProgram.Statement statement : statements) {
            Object where = statement;
            if (statement instanceof BProgram.Statement.Input in) {
                collectLabelsOf(in.access, where, out);
            } else if (statement instanceof BProgram.Statement.Output output) {
                collectLabelsOf(output.access, where, out);
            } else if (statement instanceof BProgram.Statement.If iff) {
                for (BProgram.Branch branch : iff.branches) {
                    if (branch.cond instanceof BProgram.Bool.Has has) {
                        collectLabelsOf(has.access, where, out);
                    }
                    collectFirstLabelUsesInBody(branch.body, where, out);
                }
                collectFirstLabelUsesInBody(iff.elseBody, where, out);
            }
        }
    }

    private static void collectLabelsOf(BProgram.LabelAccess access, Object block, Map<String, Object> out) {
        for (String label : access.labels) {
            if (label != null && !label.isBlank()) out.putIfAbsent(label, block);
        }
    }

    /** True when any input statement exists anywhere in the body, including branches. */
    private static boolean anyInput(List<BProgram.Statement> statements) {
        for (BProgram.Statement s : statements) {
            if (s instanceof BProgram.Statement.Input) return true;
            if (s instanceof BProgram.Statement.If iff) {
                for (BProgram.Branch branch : iff.branches) {
                    if (anyInput(branch.body)) return true;
                }
                if (anyInput(iff.elseBody)) return true;
            }
        }
        return false;
    }

    public static List<String> errorMessages(BProgram program) {
        return check(program).stream()
                .filter(issue -> issue.severity == Severity.ERROR)
                .map(issue -> issue.path + "：" + issue.message)
                .toList();
    }

    public static List<String> warningMessages(BProgram program) {
        return check(program).stream()
                .filter(issue -> issue.severity == Severity.WARNING)
                .map(issue -> issue.path + "：" + issue.message)
                .toList();
    }

    private static void checkStatements(List<BProgram.Statement> statements, String parent,
                                        Object scope, Context ctx, boolean triggerHasInput,
                                        List<Issue> issues) {
        for (int i = 0; i < statements.size(); i++) {
            BProgram.Statement statement = statements.get(i);
            String path = parent + " / 第 " + (i + 1) + " 块";
            if (statement instanceof BProgram.Statement.Input input) {
                checkAccess(input.access, path, true, statement, issues);
                checkLimits(input.limits, path, statement, ctx, issues);
                checkResources(input.except, path, "排除资源", statement, ctx, issues);
                checkCoveredExcept(input.limits, input.except, path, statement, issues);
                // 注意：不带数量/资源的 input（取出全部）是 SFM 的合法用法（TAKE_ALL），
                // 不做提醒——玩家清空容器就是常见意图（2026-09-02 用户拍板，勿加回）。
                // 同样，取出和存入使用同一个标签（分拣、重新分配的常见写法）也完全
                // 合法，不做任何提醒（2026-09-02 用户拍板，勿加回）。
            } else if (statement instanceof BProgram.Statement.Output output) {
                checkAccess(output.access, path, true, statement, issues);
                checkLimits(output.limits, path, statement, ctx, issues);
                checkResources(output.except, path, "排除资源", statement, ctx, issues);
                checkCoveredExcept(output.limits, output.except, path, statement, issues);
                // SFM 按整刻解析：同触发器里任何位置有取出即可；完全没有取出时程序
                // 仍能编译（只是不会有任何东西可放），所以提醒而不是报错。
                if (!triggerHasInput) {
                    warning(issues, path, "这个开始条件里没有任何「取出资源」，不会存入任何东西", statement, null, null);
                }
            } else if (statement instanceof BProgram.Statement.Forget forget) {
                if (!forget.labels.isEmpty()
                        && forget.labels.stream().anyMatch(label -> label == null || label.isBlank())) {
                    error(issues, path, "要清空的方块标签中有空白项", statement, null, null);
                }
            } else if (statement instanceof BProgram.Statement.If iff) {
                if (iff.branches.isEmpty()) {
                    error(issues, path, "判断至少需要一个条件", statement, null, null);
                    continue;
                }
                for (int branchIndex = 0; branchIndex < iff.branches.size(); branchIndex++) {
                    BProgram.Branch branch = iff.branches.get(branchIndex);
                    String branchPath = path + (branchIndex == 0 ? " / 如果" : " / 否则如果 " + (branchIndex + 1));
                    checkBool(branch.cond, branchPath, statement, ctx, issues);
                    if (branch.cond instanceof BProgram.Bool.Const c && !c.value) {
                        warning(issues, branchPath, "条件是固定的「否」，这个分支永远不会执行", statement, null, null);
                    }
                    if (branch.body.isEmpty()) warning(issues, branchPath, "条件成立后没有要执行的操作", statement, null, null);
                    checkStatements(branch.body, branchPath, statement, ctx, triggerHasInput, issues);
                }
                if (iff.hasElse || !iff.elseBody.isEmpty()) {
                    if (iff.elseBody.isEmpty()) warning(issues, path + " / 否则", "没有要执行的操作", statement, null, null);
                    checkStatements(iff.elseBody, path + " / 否则", statement, ctx, triggerHasInput, issues);
                }
            } else if (statement instanceof BProgram.Statement.Raw raw) {
                if (!validStatementFragment(raw.text)) {
                    error(issues, path, "兼容代码不是有效的 SFM 操作，请在同屏代码编辑区修正", statement, null, null);
                }
            }
        }
    }

    private static void checkAccess(BProgram.LabelAccess access, String path, boolean required,
                                    Object block, List<Issue> issues) {
        List<String> labels = access.labels.stream().filter(x -> x != null && !x.isBlank()).toList();
        if (required && labels.isEmpty()) error(issues, path, "必须选择至少一个方块标签", block, null, null);
        if (access.labels.stream().anyMatch(x -> x == null || x.isBlank())) {
            error(issues, path, "方块标签中有空白项", block, null, null);
        }
        if (new HashSet<>(labels).size() != labels.size()) warning(issues, path, "方块标签有重复项", block, null, null);
        // 未绑定标签不在这里逐积木报告：同一标签可能被多处引用，
        // 汇总到 check() 末尾每个标签只出一条（见 checkUnboundLabels）。
        if (access.eachSide && !access.sides.isEmpty()) {
            error(issues, path, "“每个侧面”和指定侧面不能同时使用", block, null, null);
        }
        if (access.roundRobin == BProgram.RoundRobinMode.LABEL && labels.size() < 2) {
            warning(issues, path, "只有一个标签时，按标签轮流没有效果", block, null, null);
        }
        for (int i = 0; i < access.slots.size(); i++) {
            BProgram.SlotRange a = access.slots.get(i);
            for (int j = i + 1; j < access.slots.size(); j++) {
                BProgram.SlotRange b = access.slots.get(j);
                if (a.first() <= b.last() && b.first() <= a.last()) {
                    warning(issues, path, "槽位范围有重复，可合并为一个范围", block, null, null);
                    return;
                }
            }
        }
    }

    private static void checkLimits(List<BProgram.ResourceLimit> limits, String path, Object block,
                                    Context ctx, List<Issue> issues) {
        int meaningful = 0;
        for (BProgram.ResourceLimit limit : limits) {
            if (limit == null || limit.isEmpty()) continue;
            meaningful++;
            if (limit.quantity != null && limit.quantity < 0) error(issues, path, "搬运数量不能是负数", block, null, null);
            if (limit.retain != null && limit.retain < 0) error(issues, path, "保留数量不能是负数", block, null, null);
            checkResources(limit.resources, path, "资源", block, ctx, issues);
            if (limit.with != null) checkWith(limit.with.expr, path, issues);
        }
        if (meaningful > 1) {
            Set<String> rendered = new HashSet<>();
            for (BProgram.ResourceLimit limit : limits) {
                if (limit != null && !limit.isEmpty()) {
                    String value = limit.resources + ":" + limit.quantity + ":" + limit.retain;
                    if (!rendered.add(value)) warning(issues, path, "资源扩展组有重复项", block, null, null);
                }
            }
        }
    }

    private static void checkResources(List<BProgram.ResourceRef> resources, String path, String label,
                                       Object block, Context ctx, List<Issue> issues) {
        if (resources.stream().anyMatch(Objects::isNull)) {
            error(issues, path, label + "中有无法识别的项", block, null, null);
        }
        for (BProgram.ResourceRef resource : resources) {
            if (resource == null) continue;
            if (resource.typeNamespace == null || resource.typeNamespace.isBlank()
                    || resource.typeName == null || resource.typeName.isBlank()) {
                error(issues, path, label + "缺少资源类别", block, null, null);
            }
            checkResourceKindMatch(resource, path, label, block, ctx, issues);
        }
        if (new HashSet<>(resources).size() != resources.size()) {
            warning(issues, path, label + "有重复项", block, null, null);
        }
    }

    /**
     * “资源类型与资源 ID 不一致”：a concrete id that exists in other registries
     * but not in the declared one is almost always a silent no-op at runtime.
     * Only fires with an oracle (client session with a built resource index).
     */
    private static void checkResourceKindMatch(BProgram.ResourceRef resource, String path, String label,
                                                Object block, Context ctx, List<Issue> issues) {
        if (ctx == null || ctx.oracle == null) return;
        BProgram.ResourceKind kind = resource.kind();
        if (kind == BProgram.ResourceKind.CUSTOM) return;
        // 只对“纯注册表 id”做存在性/类别核对。SFM 支持 *ingot*、".*ingot.*" 这类
        // 模糊匹配写法（见官方 filtering 示例），它们不是具体 id，查注册表必然
        // “找不到”——核对它们就是误报。连 '.' 也一并排除（既是正则通配又罕见于 id，
        // 宁可少查不可错怪）。
        if (!isPlainRegistryId(resource.name)) return;
        String namespace = resource.namespace == null || resource.namespace.isBlank()
                || "*".equals(resource.namespace) || ".*".equals(resource.namespace)
                ? null : resource.namespace;
        if (namespace != null && !isPlainRegistryId(namespace)) return;
        Set<BProgram.ResourceKind> found = ctx.oracle.kindsOf(namespace, resource.name);
        if (found.contains(kind)) return;
        String id = (namespace == null ? "*" : namespace) + ":" + resource.name;
        if (found.isEmpty()) {
            warning(issues, path, label + "「" + id + "」在游戏里找不到，可能是拼写错误或来自未安装的模组",
                    block, null, null);
            return;
        }
        if (found.size() == 1) {
            BProgram.ResourceKind actual = found.iterator().next();
            warning(issues, path, label + "「" + id + "」不是" + kind.chineseName
                            + "，而是" + actual.chineseName,
                    block, "改为" + actual.chineseName, () -> {
                        resource.typeNamespace = "sfm";
                        resource.typeName = actual.sfmlName;
                    });
        } else {
            StringBuilder names = new StringBuilder();
            for (BProgram.ResourceKind k : found) {
                if (names.length() > 0) names.append("、");
                names.append(k.chineseName);
            }
            warning(issues, path, label + "「" + id + "」不是" + kind.chineseName
                    + "（同时匹配：" + names + "）", block, null, null);
        }
    }

    /** 注册表 id 的保守子集：字母数字 _ / -（不含点与星号，见上方注释）。 */
    private static boolean isPlainRegistryId(String value) {
        return value != null && !value.isBlank() && value.matches("[a-zA-Z0-9/_-]+");
    }

    /** “排除规则把所有资源全部排除”：starves the rule without a compile error. */
    private static void checkCoveredExcept(List<BProgram.ResourceLimit> limits, List<BProgram.ResourceRef> except,
                                           String path, Object block, List<Issue> issues) {
        if (except == null || except.isEmpty()) return;
        if (except.stream().filter(Objects::nonNull).anyMatch(BProgram.ResourceRef::isWildcard)) {
            warning(issues, path, "排除条件里包含「全部资源」，这条规则永远匹配不到任何东西", block,
                    "移除「全部」排除", () -> except.removeIf(r -> r != null && r.isWildcard()));
            return;
        }
        Set<BProgram.ResourceRef> exceptSet = new HashSet<>();
        for (BProgram.ResourceRef ref : except) {
            if (ref != null) exceptSet.add(ref);
        }
        for (BProgram.ResourceLimit limit : limits) {
            if (limit == null || limit.resources.isEmpty()) continue;
            Set<BProgram.ResourceRef> resourceSet = new HashSet<>(limit.resources);
            if (!resourceSet.isEmpty() && exceptSet.containsAll(resourceSet)) {
                warning(issues, path, "排除条件把这组资源全部排除了，这条规则不会搬运任何东西", block,
                        "删除冲突的排除条件", () -> except.removeIf(exceptSet::contains));
                return;
            }
        }
    }

    private static void checkBool(BProgram.Bool bool, String path, Object block, Context ctx, List<Issue> issues) {
        if (bool == null) {
            error(issues, path, "缺少判断条件", block, null, null);
        } else if (bool instanceof BProgram.Bool.Has has) {
            checkAccess(has.access, path, true, block, issues);
            if (has.comparison == null) error(issues, path, "缺少数量比较方式", block, null, null);
            if (has.number < 0) error(issues, path, "比较数量不能是负数", block, null, null);
            if (has.comparison == BProgram.Bool.Comparison.LT && has.number == 0) {
                warning(issues, path, "「少于 0」永远不会成立，这个条件永远是假", block, null, null);
            }
            checkResources(has.resources, path, "判断资源", block, ctx, issues);
            checkResources(has.except, path, "排除资源", block, ctx, issues);
            checkCoveredExcept(has.resources.isEmpty() ? List.of() : List.of(limitOf(has)), has.except, path, block, issues);
            if (has.with != null) checkWith(has.with.expr, path, issues);
        } else if (bool instanceof BProgram.Bool.Redstone redstone) {
            if (redstone.number < 0) error(issues, path, "红石信号数值不能是负数", block, null, null);
        } else if (bool instanceof BProgram.Bool.And and) {
            if (and.parts.size() < 2) error(issues, path, "“同时满足”至少需要两个条件", block, null, null);
            checkContradictions(and.parts, true, path, block, issues);
            for (BProgram.Bool part : and.parts) checkBool(part, path, block, ctx, issues);
        } else if (bool instanceof BProgram.Bool.Or or) {
            if (or.parts.size() < 2) error(issues, path, "“满足任意一项”至少需要两个条件", block, null, null);
            checkContradictions(or.parts, false, path, block, issues);
            for (BProgram.Bool part : or.parts) checkBool(part, path, block, ctx, issues);
        } else if (bool instanceof BProgram.Bool.Not not) {
            checkBool(not.inner, path, block, ctx, issues);
        } else if (bool instanceof BProgram.Bool.RawBool raw && !validBoolFragment(raw.text)) {
            error(issues, path, "兼容条件不是有效的 SFM 判断", block, null, null);
        }
    }

    private static BProgram.ResourceLimit limitOf(BProgram.Bool.Has has) {
        BProgram.ResourceLimit rl = new BProgram.ResourceLimit();
        rl.resources.addAll(has.resources);
        return rl;
    }

    /**
     * “AND / OR 条件永远无法成立”：structural duplicates and direct negations.
     * Catches the common hand-built mistakes; deep semantic analysis is out of
     * scope on purpose — false positives cost more than missed positives here.
     */
    private static void checkContradictions(List<BProgram.Bool> parts, boolean conjunction,
                                            String path, Object block, List<Issue> issues) {
        for (int i = 0; i < parts.size(); i++) {
            for (int j = i + 1; j < parts.size(); j++) {
                BProgram.Bool a = parts.get(i);
                BProgram.Bool b = parts.get(j);
                if (a == null || b == null) continue;
                if (sameBool(a, b)) {
                    String joiner = conjunction ? "且" : "或";
                    int duplicateIndex = j;
                    warning(issues, path, "「" + joiner + "」里有两个完全相同的条件，可以删掉一个", block,
                            "删除重复条件", () -> parts.remove(duplicateIndex));
                    return;
                }
                BProgram.Bool innerB = b instanceof BProgram.Bool.Not not ? not.inner : null;
                BProgram.Bool innerA = a instanceof BProgram.Bool.Not not ? not.inner : null;
                if (innerB != null && sameBool(a, innerB)) {
                    reportNegationPair(parts, j, conjunction, path, block, issues);
                    return;
                }
                if (innerA != null && sameBool(innerA, b)) {
                    reportNegationPair(parts, i, conjunction, path, block, issues);
                    return;
                }
            }
        }
    }

    private static void reportNegationPair(List<BProgram.Bool> parts, int notIndex, boolean conjunction,
                                           String path, Object block, List<Issue> issues) {
        if (conjunction) {
            warning(issues, path, "一个条件和它自己的相反同时出现，这个「且」永远不成立", block,
                    "删除相反的条件", () -> parts.remove(notIndex));
        } else {
            warning(issues, path, "一个条件和它自己的相反同时出现，这个「或」永远为真", block,
                    "删除相反的条件", () -> parts.remove(notIndex));
        }
    }

    // ---- structural equality (no equals() on the model; keep it local & typed) ----

    static boolean sameBool(BProgram.Bool a, BProgram.Bool b) {
        if (a instanceof BProgram.Bool.Has ha && b instanceof BProgram.Bool.Has hb) {
            return ha.setMode == hb.setMode
                    && sameAccess(ha.access, hb.access)
                    && ha.comparison == hb.comparison
                    && ha.number == hb.number
                    && new HashSet<>(ha.resources).equals(new HashSet<>(hb.resources))
                    && new HashSet<>(ha.except).equals(new HashSet<>(hb.except))
                    && (ha.with == null ? hb.with == null : hb.with != null && sameWith(ha.with.expr, hb.with.expr));
        }
        if (a instanceof BProgram.Bool.Redstone ra && b instanceof BProgram.Bool.Redstone rb) {
            return ra.comparison == rb.comparison && ra.number == rb.number;
        }
        if (a instanceof BProgram.Bool.Const ca && b instanceof BProgram.Bool.Const cb) {
            return ca.value == cb.value;
        }
        if (a instanceof BProgram.Bool.Not na && b instanceof BProgram.Bool.Not nb) {
            return sameBool(na.inner, nb.inner);
        }
        if (a instanceof BProgram.Bool.And aa && b instanceof BProgram.Bool.And ab) {
            return sameBoolList(aa.parts, ab.parts);
        }
        if (a instanceof BProgram.Bool.Or oa && b instanceof BProgram.Bool.Or ob) {
            return sameBoolList(oa.parts, ob.parts);
        }
        return false;
    }

    private static boolean sameBoolList(List<BProgram.Bool> a, List<BProgram.Bool> b) {
        if (a.size() != b.size()) return false;
        Set<BProgram.Bool> used = new HashSet<>();
        for (BProgram.Bool x : a) {
            boolean matched = false;
            for (int i = 0; i < b.size(); i++) {
                if (used.contains(b.get(i))) continue;
                if (sameBool(x, b.get(i))) {
                    used.add(b.get(i));
                    matched = true;
                    break;
                }
            }
            if (!matched) return false;
        }
        return true;
    }

    private static boolean sameAccess(BProgram.LabelAccess a, BProgram.LabelAccess b) {
        return new HashSet<>(a.labels).equals(new HashSet<>(b.labels))
                && a.roundRobin == b.roundRobin
                && a.eachSide == b.eachSide
                && new HashSet<>(a.sides).equals(new HashSet<>(b.sides))
                && new HashSet<>(a.slots).equals(new HashSet<>(b.slots));
    }

    static boolean sameWith(BProgram.WithExpr a, BProgram.WithExpr b) {
        if (a instanceof BProgram.WithExpr.Tag ta && b instanceof BProgram.WithExpr.Tag tb) {
            return Objects.equals(ta.matcher, tb.matcher);
        }
        if (a instanceof BProgram.WithExpr.Not na && b instanceof BProgram.WithExpr.Not nb) {
            return sameWith(na.inner, nb.inner);
        }
        if (a instanceof BProgram.WithExpr.And aa && b instanceof BProgram.WithExpr.And ab) {
            return sameWithList(aa.parts, ab.parts);
        }
        if (a instanceof BProgram.WithExpr.Or oa && b instanceof BProgram.WithExpr.Or ob) {
            return sameWithList(oa.parts, ob.parts);
        }
        return false;
    }

    /** Order-insensitive matching; a part of {@code a} is consumed at most once. */
    private static boolean sameWithList(List<BProgram.WithExpr> a, List<BProgram.WithExpr> b) {
        if (a.size() != b.size()) return false;
        Set<BProgram.WithExpr> used = new HashSet<>();
        for (BProgram.WithExpr x : a) {
            boolean matched = false;
            for (int i = 0; i < b.size(); i++) {
                if (used.contains(b.get(i))) continue;
                if (sameWith(x, b.get(i))) {
                    used.add(b.get(i));
                    matched = true;
                    break;
                }
            }
            if (!matched) return false;
        }
        return true;
    }

    private static void checkWith(BProgram.WithExpr expr, String path, List<Issue> issues) {
        if (expr == null) {
            error(issues, path, "缺少资源特征条件", null, null, null);
        } else if (expr instanceof BProgram.WithExpr.Tag tag) {
            String matcher = tag.matcher == null ? "" : tag.matcher.trim().replaceFirst("^#+", "");
            if (!matcher.matches("[a-zA-Z_*][a-zA-Z0-9_*]*(?::[a-zA-Z_*][a-zA-Z0-9_*]*)?(?:/[a-zA-Z_*][a-zA-Z0-9_*]*)*")) {
                error(issues, path, "资源特征标签格式不正确", null, null, null);
            }
        } else if (expr instanceof BProgram.WithExpr.Not not) {
            checkWith(not.inner, path, issues);
        } else if (expr instanceof BProgram.WithExpr.And and) {
            if (and.parts.size() < 2) error(issues, path, "资源特征的“且”至少需要两项", null, null, null);
            for (BProgram.WithExpr part : and.parts) checkWith(part, path, issues);
        } else if (expr instanceof BProgram.WithExpr.Or or) {
            if (or.parts.size() < 2) error(issues, path, "资源特征的“或”至少需要两项", null, null, null);
            for (BProgram.WithExpr part : or.parts) checkWith(part, path, issues);
        }
    }

    private static boolean validStatementFragment(String text) {
        if (text == null || text.isBlank()) return false;
        return SfmlToBlocks.parse("every 20 ticks do\n" + text + "\nend\n").ok();
    }

    private static boolean validBoolFragment(String text) {
        if (text == null || text.isBlank()) return false;
        return SfmlToBlocks.parse("every 20 ticks do\nif " + text + " then\nend\nend\n").ok();
    }

    private static void error(List<Issue> issues, String path, String message) {
        issues.add(new Issue(Severity.ERROR, path, message, null, null, null, -1));
    }

    private static void error(List<Issue> issues, String path, String message,
                              Object block, String fixLabel, Runnable fix) {
        issues.add(new Issue(Severity.ERROR, path, message, block, fixLabel, fix, blockIdOf(block)));
    }

    private static void warning(List<Issue> issues, String path, String message,
                                Object block, String fixLabel, Runnable fix) {
        issues.add(new Issue(Severity.WARNING, path, message, block, fixLabel, fix, blockIdOf(block)));
    }

    /** Stable session id of the block an issue points at (-1 when null). */
    public static long blockIdOf(Object block) {
        if (block instanceof BProgram.Statement s) return s.id;
        if (block instanceof BProgram.Trigger t) return t.id;
        return -1;
    }
}
