package io.github.xianynomial.sfmfactorystudio.client.blocks.tutorial;

import io.github.xianynomial.sfmfactorystudio.client.blocks.model.BProgram;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 新手引导的进度状态与完成判定。
 *
 * <p>职责刻意分成两类，因为只有这两类值得写单测：
 * <ul>
 *   <li>{@link #satisfiedBy}：纯函数，只读 {@code BProgram}，不碰任何 IO；</li>
 *   <li>{@link #toJson} / {@link #fromJson}：纯编解码，同样不碰 IO。</li>
 * </ul>
 * 真正落盘交给现成的 {@code EditorFileStore}（已有去抖 + 原子替换 + 后台线程），
 * 这里不另造一套文件写入。
 *
 * <p><b>为什么进度要单独存</b>：{@code layouts.json} 是"每个程序的布局"，
 * 引导进度是"每个玩家的一次性经历"。换程序不该重播引导，所以不能混在一起。
 */
public final class TutorialState {

    /** 格式版本。读到不认识的版本一律当成"全新"，避免用旧结构解释新字段。 */
    public static final int FORMAT_VERSION = 1;

    /**
     * 历史上新卡预填的占位标签。
     *
     * <p><b>2026-10-24 起已不再预填</b>（新卡标签为空），但旧存档里
     * 仍可能存着 {@code "a"} / {@code "b"} ——那是当时的默认值，不代表
     * 玩家真的选了一个叫 a 的容器。保留这张表，让旧存档也能被
     * 正确判定为「还没选」。
     *
     * <p>已知权衡：若玩家真把容器命名为单字母 {@code a}/{@code b}，
     * 引导会认为他还没选。单字母容器名极罕见，且引导可随时跳过，
     * 因此接受这个代价。
     *
     * <p>所以第 2、3 步的判定<b>绝不能</b>写成"labels 非空"——新卡一建出来就满足了，
     * 引导会直接跳过这两屏。必须判断"至少有一个标签不是占位符"。
     */
    private static final Set<String> PLACEHOLDER_LABELS = Set.of("a", "b");

    /** 已完成到第几步（ordinal）；-1 表示一步都没完成。 */
    private int reachedOrdinal = -1;
    private boolean skipped;
    /** 仅用于调试：记录上次打印布局日志的步骤。 */
    public TutorialStep lastLoggedStep;
    private boolean finished;

    public TutorialState() {
    }

    public int reachedOrdinal() {
        return reachedOrdinal;
    }

    public boolean skipped() {
        return skipped;
    }

    public boolean finished() {
        return finished;
    }

    /** 引导是否还需要显示（跳过或走完都不再显示）。 */
    public boolean active() {
        return !skipped && !finished;
    }

    /** 当前应显示的步骤。 */
    public TutorialStep current() {
        TutorialStep[] all = TutorialStep.values();
        int idx = Math.min(Math.max(reachedOrdinal + 1, 0), all.length - 1);
        return all[idx];
    }

    /**
     * 检查当前步骤是否已完成，完成则推进。
     *
     * <p>用 {@code while} 而不是 {@code if}：一次操作可能同时满足多步
     * （玩家改完标签顺手又改了侧面），停在中间那一屏会让他以为引导卡住了。
     * 手动屏（首尾）不参与自动推进。
     *
     * @return 是否发生了推进（供调用方决定要不要落盘）
     */
    public boolean advanceIfDone(BProgram program, boolean savedOnce) {
        if (!active()) return false;
        boolean moved = false;
        while (true) {
            TutorialStep cur = current();
            if (cur.manual) break;
            if (!satisfiedBy(cur, program, savedOnce)) break;
            reachedOrdinal = Math.max(reachedOrdinal, cur.ordinal());
            moved = true;
            if (cur.isLast()) {
                finished = true;
                break;
            }
        }
        return moved;
    }

    /** 首尾两屏：玩家点「开始 / 完成」推进。 */
    public void advanceManual() {
        TutorialStep cur = current();
        if (!cur.manual) return;
        reachedOrdinal = Math.max(reachedOrdinal, cur.ordinal());
        if (cur.isLast()) finished = true;
    }

    /**
     * 启动时快进：程序里已经满足的步骤直接标为完成。
     *
     * <p>否则玩家点「开始」后，第一帧 advanceIfDone 会把所有已满足的步骤
     * 一口气推完（实测：程序里已有配好的定时卡 ⇒ 直接跳到收尾屏）。
     *
     * <p>快进后如果一步都没推进，回到欢迎页 —— 新玩家该看到完整引导。
     */
    public void fastForward(BProgram program) {
        if (!active()) return;
        int saved = reachedOrdinal;
        // 临时跨过欢迎页（它是 manual，advanceIfDone 会直接 break）
        reachedOrdinal = TutorialStep.WELCOME.ordinal();
        boolean moved = false;
        while (advanceIfDone(program, false)) {
            moved = true;
        }
        if (!moved) reachedOrdinal = saved;
    }

    /** 玩家主动跳过整段引导。 */
    public void skip() {
        skipped = true;
    }

    /** 帮助面板里的「重新播放新手引导」。 */
    public void restart() {
        reachedOrdinal = -1;
        skipped = false;
        finished = false;
    }

    // ------------------------------------------------------------------ 判定

    /**
     * 纯函数：这一步在当前程序状态下是否已完成。
     *
     * <p>全部从模型状态推导，不依赖"用户点过哪个按钮"——事件会漏（撤销回退）、
     * 会重复计数，而且三个版本的输入事件签名并不相同。
     */
    public static boolean satisfiedBy(TutorialStep step, BProgram program, boolean savedOnce) {
        if (program == null) return false;
        return switch (step) {
            // 首尾两屏由点击推进，不参与自动判定（这里返回 true 也安全，因为 manual 已拦住）
            case WELCOME, DONE -> true;
            case ADD_CARD -> firstTimer(program) != null;
            case PICK_IN_LABEL -> hasRealLabel(firstInput(program));
            case PICK_OUT_LABEL -> hasRealLabel(firstOutput(program));
            case EACH_SIDE -> anyEachSide(program);
            case TO_FLUID -> hasKind(program, "fluid");
            case PICK_RESOURCE -> hasConcreteResource(program);
        };
    }

    /** 标签里至少有一个不是新卡占位符。 */
    private static boolean hasRealLabel(BProgram.Statement io) {
        BProgram.LabelAccess access = accessOf(io);
        // 只要有标签就算选了。
        // 不能再用「a/b 是占位符」来排除：新卡已经是空标签，
        // 而玩家用标签枪给容器起名叫 "a" 是完全合法的，
        // 排除它会让第 2/3 步永远无法完成（实测就是「选完不推进」）。
        return access != null && !access.labels.isEmpty();
    }

    private static boolean anyEachSide(BProgram program) {
        for (BProgram.Statement s : allStatements(program)) {
            BProgram.LabelAccess a = accessOf(s);
            if (a != null && a.eachSide) return true;
        }
        return false;
    }

    /**
     * 是否存在指定类型的资源引用。
     *
     * <p>注意：{@code ResourceLimit.resources} 为空时界面按隐式的 {@code item} 处理
     * （见 {@code BlockEditorScreen.firstResource}），所以流体只有被显式选中才会出现，
     * 这个判定不会误报。
     */
    private static boolean hasKind(BProgram program, String typeName) {
        for (BProgram.ResourceRef ref : allResources(program)) {
            if (ref != null && typeName.equals(ref.typeName)) return true;
        }
        return false;
    }

    /** 是否已选具体资源（namespace/name 不再是通配 {@code "*"}）。 */
    private static boolean hasConcreteResource(BProgram program) {
        for (BProgram.ResourceRef ref : allResources(program)) {
            if (ref == null) continue;
            if (!"*".equals(ref.namespace) || !"*".equals(ref.name)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------ 模型遍历

    private static BProgram.Trigger firstTimer(BProgram program) {
        for (BProgram.Trigger t : program.triggers) {
            if (t instanceof BProgram.TimerTrigger) return t;
        }
        return null;
    }

    private static BProgram.Statement firstInput(BProgram program) {
        for (BProgram.Statement s : allStatements(program)) {
            if (s instanceof BProgram.Statement.Input) return s;
        }
        return null;
    }

    private static BProgram.Statement firstOutput(BProgram program) {
        for (BProgram.Statement s : allStatements(program)) {
            if (s instanceof BProgram.Statement.Output) return s;
        }
        return null;
    }

    /** 取出/存入语句的 from|to 部分；其他语句返回 null。 */
    private static BProgram.LabelAccess accessOf(BProgram.Statement s) {
        if (s instanceof BProgram.Statement.Input in) return in.access;
        if (s instanceof BProgram.Statement.Output out) return out.access;
        return null;
    }

    /** 按显示顺序深度优先收集所有语句（含 If 内嵌套）。 */
    private static List<BProgram.Statement> allStatements(BProgram program) {
        List<BProgram.Statement> out = new ArrayList<>();
        for (BProgram.Trigger t : program.triggers) collectInto(t.body, out);
        return out;
    }

    private static void collectInto(List<BProgram.Statement> src, List<BProgram.Statement> out) {
        for (BProgram.Statement s : src) {
            out.add(s);
            if (s instanceof BProgram.Statement.If iff) {
                for (BProgram.Branch b : iff.branches) collectInto(b.body, out);
                collectInto(iff.elseBody, out);
            }
        }
    }

    /** 收集所有资源引用（limits 与 except）。 */
    private static List<BProgram.ResourceRef> allResources(BProgram program) {
        List<BProgram.ResourceRef> out = new ArrayList<>();
        for (BProgram.Statement s : allStatements(program)) {
            if (s instanceof BProgram.Statement.Input in) {
                for (BProgram.ResourceLimit l : in.limits) out.addAll(l.resources);
                out.addAll(in.except);
            } else if (s instanceof BProgram.Statement.Output o) {
                for (BProgram.ResourceLimit l : o.limits) out.addAll(l.resources);
                out.addAll(o.except);
            }
        }
        return out;
    }

    // -------------------------------------------------------------- 编解码

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("formatVersion", FORMAT_VERSION);
        o.addProperty("reachedOrdinal", reachedOrdinal);
        o.addProperty("skipped", skipped);
        o.addProperty("finished", finished);
        return o;
    }

    /**
     * 从文件内容还原。
     *
     * <p>任何异常（缺字段、类型不符、JSON 损坏、版本不认识）都回落到"全新状态"，
     * 绝不抛给调用方——引导坏掉不该让编辑器打不开。
     */
    public static TutorialState fromJson(String raw) {
        TutorialState s = new TutorialState();
        if (raw == null || raw.isBlank()) return s;
        try {
            JsonElement parsed = JsonParser.parseString(raw);
            if (!parsed.isJsonObject()) return s;
            JsonObject o = parsed.getAsJsonObject();
            if (o.has("formatVersion")
                    && o.get("formatVersion").getAsInt() != FORMAT_VERSION) {
                return s;
            }
            if (o.has("reachedOrdinal")) {
                s.reachedOrdinal = clampOrdinal(o.get("reachedOrdinal").getAsInt());
            }
            s.skipped = o.has("skipped") && o.get("skipped").getAsBoolean();
            s.finished = o.has("finished") && o.get("finished").getAsBoolean();
        } catch (RuntimeException corrupted) {
            return new TutorialState();
        }
        return s;
    }

    /** 手改文件/旧版本残留都可能让 ordinal 越界，夹进合法范围。 */
    private static int clampOrdinal(int value) {
        int max = TutorialStep.values().length - 1;
        return Math.min(Math.max(value, -1), max);
    }
}
