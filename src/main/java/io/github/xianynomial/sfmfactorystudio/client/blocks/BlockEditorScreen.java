package io.github.xianynomial.sfmfactorystudio.client.blocks;

import ca.teamdman.sfm.common.containermenu.ManagerContainerMenu;
import ca.teamdman.sfm.common.config.SFMConfig;
import ca.teamdman.sfm.common.label.LabelPositionHolder;
import ca.teamdman.sfm.common.net.ServerboundManagerProgramPacket;
import ca.teamdman.sfm.common.registry.registration.SFMPackets;
import io.github.xianynomial.sfmfactorystudio.client.ResourceIndex;
import io.github.xianynomial.sfmfactorystudio.client.ResourceTagIndex;
import io.github.xianynomial.sfmfactorystudio.client.blocks.model.BProgram;
import io.github.xianynomial.sfmfactorystudio.client.blocks.model.BlockTemplates;
import io.github.xianynomial.sfmfactorystudio.client.blocks.model.BlocksToSfml;
import io.github.xianynomial.sfmfactorystudio.client.blocks.model.CardLayouts;
import io.github.xianynomial.sfmfactorystudio.client.blocks.model.EditorLayout;
import io.github.xianynomial.sfmfactorystudio.client.blocks.model.EditorLayout.BodyRef;
import io.github.xianynomial.sfmfactorystudio.client.blocks.model.EditorLayout.CardL;
import io.github.xianynomial.sfmfactorystudio.client.blocks.model.EditorLayout.Gap;
import io.github.xianynomial.sfmfactorystudio.client.blocks.model.ProgramDiagnostics;
import io.github.xianynomial.sfmfactorystudio.client.blocks.model.SfmlToBlocks;
import io.github.xianynomial.sfmfactorystudio.client.blocks.model.SfmlValidate;
import io.github.xianynomial.sfmfactorystudio.client.blocks.model.TimerRules;
import io.github.xianynomial.sfmfactorystudio.net.SFMGuiNetwork;
import io.github.xianynomial.sfmfactorystudio.net.SfmCaps;
import io.github.xianynomial.sfmfactorystudio.net.UpdateLabelsPayload;
import ca.teamdman.sfml.intellisense.IntellisenseContext;
import ca.teamdman.sfml.intellisense.SFMLIntellisense;
import ca.teamdman.sfml.program_builder.ProgramBuilder;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.loading.FMLPaths;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static io.github.xianynomial.sfmfactorystudio.client.blocks.BlockTexts.*;
import static io.github.xianynomial.sfmfactorystudio.client.blocks.BlockTheme.*;

/**
 * Scratch-style block editor for SFM programs — frosted-glass light theme in a
 * centered window (not fullscreen), with a deterministic two-phase layout
 * (measure, then draw) so blocks never overlap or drift, mouse-wheel zoom +
 * drag panning, rubber-band selection with group drag / copy / paste / save as
 * template, vanilla-style semi-transparent resource slots with item icons and
 * Chinese type capsules for "all items / all fluids / ..." entries.
 *
 * Coordinate spaces:
 *   screen  — raw MC pixels
 *   panel   — the editor window (inset, centered)
 *   content — logical layout pixels, drawn through translate+scale (zoom/pan)
 */
public class BlockEditorScreen extends Screen {


    // ---- metrics (content px) — geometry 的唯一出处是 EditorLayout -----------
    private static final int BAR_H = EditorLayout.BAR_H;
    private static final int OPT_H = EditorLayout.OPT_H;
    private static final int ROW_GAP = EditorLayout.ROW_GAP;
    private static final int INDENT = EditorLayout.INDENT;
    private static final int HEAD_H = EditorLayout.HEAD_H;
    private static final int FOOT_H = EditorLayout.FOOT_H;
    private static final int ADD_H = EditorLayout.ADD_H;
    private static final int CARD_W = EditorLayout.CARD_W;
    private static final int CARD_INNER = EditorLayout.CARD_INNER;
    // 界面（非内容）度量在 BlockTheme
    private static final int PALETTE_W = BlockTheme.PALETTE_W;
    private static final int TOOLBAR_H = BlockTheme.TOOLBAR_H;
    private static final int ISSUES_W = BlockTheme.ISSUES_W;

    // ---- screen state ----------------------------------------------------------
    private final ManagerContainerMenu menu;
    private final @Nullable Runnable onCloseRequest;
    private boolean importFailed;
    private String savedProgramText;
    private BProgram program;
    private String statusText = "";
    private int statusColor = C_TEXT_SUB;
    private int statusTicks = 0;
    private boolean dirty = false;
    private static final int DRAFT_DELAY_TICKS = 40;
    /** 匹配预览最多列多少件物品；只防卡死，不再把结果切掉一截。 */
    private static final int PREVIEW_LIMIT = 300;
    private int draftSaveDelay = -1;
    private String lastDraftText = "";
    private boolean draftPromptChecked = false;
    private boolean closingWithoutPrompt = false;

    private boolean previewMode = false; // 同屏源码编辑区是否展开（旧字段名保留，避免布局存档迁移）
    private float zoom = 1.0f;
    private int viewX = 0, viewY = 0;
    private boolean fitted = false;

    // ---- 诊断面板（问题检查）--------------------------------------------------
    // 每 5 tick 重算一次（含未绑定标签、类别不符等运行期静默失败项），
    // 结果驱动工具栏按钮计数、积木上的红/黄角标和右侧问题面板。
    private boolean issuesOpen = false;
    private boolean issuesPanelVisible = false;   // 窄窗口时自动隐藏（不挤垮画布）
    private int issuesScroll = 0;
    private int issueCursor = -1;   // 上一个定位的问题（‹ › / F8 循环）
    private int issueRefreshCountdown = 0;
    // 版本门控：modelVersion 在任何诊断可见的变化（程序编辑/标签同步）时自增；
    // tick 里的定时刷新只在版本变化时才真正重算，空闲时零成本。
    private long modelVersion = 0;
    private long issuesVersion = -1;
    private int issuesRefreshCount = 0;
    private List<ProgramDiagnostics.Issue> issuesCache = List.of();
    // 派生数据随 issuesCache 一起缓存：计数在工具栏/面板两处每帧使用；
    // 折行是逐字符字体测量（O(len²)），绝不能留在渲染循环里。
    private long issueErrCount = 0;
    private long issueWarnCount = 0;
    private boolean issueMissingLabels = false;
    private @Nullable List<List<String>> issuesWrapped = null;
    private int issuesWrappedWidth = -1;
    private final java.util.HashMap<Long, ProgramDiagnostics.Severity> blockSeverity =
            new java.util.HashMap<>();
    private @Nullable Object locateTarget = null;   // Trigger 或 Statement
    private int locateTicks = 0;
    private String generatedCache = "";

    private EditBox nameBox;
    private @Nullable SfmlCodeEditor codeEditor;
    private boolean codeTextEdited = false;
    private boolean codeAwaitingValidation = false;
    // 积木侧编辑（含选择器回调）改过模型、代码窗尚未跟上。init() 重跑会刷新
    // lastModelSfml，光靠"模型变了"检测不到这类编辑，需要显式标志兜底。
    private boolean blocksNewerThanCode = false;
    private boolean settingCodeFromModel = false;
    private int codeValidateDelay = -1;
    private int codeSuggestDelay = -1;
    private String codeStatusText = "积木与代码已同步";
    private int codeStatusColor = 0xFF18794E;
    private String lastModelSfml = "";
    private @Nullable Popup popup;

    private final List<String> knownLabels = new ArrayList<>();
    private final Map<String, Integer> knownLabelCounts = new LinkedHashMap<>();
    private final ArrayDeque<String> undoStack = new ArrayDeque<>();
    private final ArrayDeque<String> redoStack = new ArrayDeque<>();

    private static final List<String> SERVER_LABELS = new ArrayList<>();
    private static final Map<String, Integer> SERVER_LABEL_COUNTS = new LinkedHashMap<>();

    public static void acceptLabels(List<UpdateLabelsPayload.LabelInfo> labels) {
        SERVER_LABELS.clear();
        SERVER_LABEL_COUNTS.clear();
        for (UpdateLabelsPayload.LabelInfo label : labels) {
            if (label.name() == null || label.name().isBlank()) continue;
            if (!SERVER_LABELS.contains(label.name())) SERVER_LABELS.add(label.name());
            SERVER_LABEL_COUNTS.merge(label.name(), Math.max(0, label.blockCount()), Math::max);
        }

        // The reply is asynchronous. Previously it only updated this static
        // cache, so the already-open first editor kept its old empty copy until
        // the player closed and reopened it.
        Screen current = Minecraft.getInstance().screen;
        if (current instanceof BlockEditorScreen editor) editor.acceptLiveLabels(labels);
    }

    private void acceptLiveLabels(List<UpdateLabelsPayload.LabelInfo> labels) {
        int before = knownLabels.size();
        knownLabels.clear();
        knownLabelCounts.clear();
        for (UpdateLabelsPayload.LabelInfo label : labels) {
            if (label.name() == null || label.name().isBlank()) continue;
            if (!knownLabels.contains(label.name())) knownLabels.add(label.name());
            knownLabelCounts.merge(label.name(), Math.max(0, label.blockCount()), Math::max);
        }
        mergeLabels(knownLabels, program.collectLabels());
        for (String label : knownLabels) knownLabelCounts.putIfAbsent(label, 0);
        modelVersion++; // 标签绑定数变化会改变诊断结果
        refreshIssues(); // 绑定数刚变，未绑定标签诊断立即更新
        if (popup instanceof Popup.LabelPopup labelPopup) {
            labelPopup.replaceKnownLabels(knownLabels, knownLabelCounts);
        }
        if (knownLabels.size() > before) {
            long bound = knownLabelCounts.values().stream().filter(count -> count > 0).count();
            showStatus("✔ 已同步 " + knownLabels.size() + " 个标签，其中 " + bound + " 个已绑定方块", 0xFF198754);
        }
    }

    private static void mergeLabels(List<String> target, Iterable<String> source) {
        for (String label : source) {
            if (label != null && !label.isBlank() && !target.contains(label)) target.add(label);
        }
    }

    // ---- panel / canvas rects (screen px) --------------------------------------
    // Non-zero defaults: JEI queries these the moment the screen opens (before
    // init/render), and zero-size properties are rejected as invalid, which
    // hides the JEI overlay for the whole session.
    private int panelX = 8, panelY = 8, panelW = 800, panelH = 600;

    /** Screen size that is valid even before init() runs (JEI queries early). */
    public int safeScreenWidth() {
        var w = Minecraft.getInstance().getWindow();
        int sw = w != null ? w.getGuiScaledWidth() : 0;
        return sw > 0 ? sw : this.width > 0 ? this.width : 800;
    }

    public int safeScreenHeight() {
        var w = Minecraft.getInstance().getWindow();
        int sh = w != null ? w.getGuiScaledHeight() : 0;
        return sh > 0 ? sh : this.height > 0 ? this.height : 600;
    }
    private int canvasX, canvasY, canvasW, canvasH;
    private static final int CANVAS_PAD = BlockTheme.CANVAS_PAD;

    // ---- deterministic layout ---------------------------------------------------
    // 几何全部由 EditorLayout 计算（按稳定 id 每卡缓存）：编辑只重排脏卡，
    // 拖动只平移被拖卡的缓存行，屏幕类只保留“是否需要重排”这个脏标记。
    private boolean layoutDirty = true;
    private final EditorLayout layout = new EditorLayout();
    /** 展开了扩展面板的 Input/Output 积木（按稳定 id）。 */
    private final Set<Long> expandedIds = new LinkedHashSet<>();
    /** 折叠的卡片 / If（按稳定 id；卡片折叠随 layouts.json 持久化）。 */
    private final Set<Long> collapsedCards = new LinkedHashSet<>();
    private final Set<Long> collapsedIfs = new LinkedHashSet<>();
    /** LOD：低于该缩放只画卡片标题与摘要，不画正文行。 */
    private static final float LOD_ZOOM = 0.25f;

    // ---- 三色工作区 -------------------------------------------------------------
    // 内容坐标矩形 + 名称，纯客户端数据（存 layouts.json 的 ":zones" 键）。
    // 点击区名 = 聚焦该区（其他区的卡片整体隐藏）；再点一次恢复。
    private record Zone(String name, int color, int x, int y, int w, int h) {
    }

    private static final int[] ZONE_FILLS = {0x142F6FED, 0x1410B981, 0x14B45309};
    private static final int[] ZONE_BORDERS = {0xFF2F6FED, 0xFF10B981, 0xFFB45309};
    private final List<Zone> zones = new ArrayList<>();
    private @Nullable Zone focusedZone = null;
    private boolean zoneDrawing = false;   // 工具栏「分区」按钮开启：下一次画布拖拽画分区
    private boolean zoneDrag = false;
    private int zoneX1, zoneY1, zoneX2, zoneY2;

    // 左侧积木栏滚动（滚轮 + 滚动条），模板再多也能滚到
    private int paletteScroll = 0;
    private int paletteContentH = 0;

    // ---- 积木连线（纯视觉备注，不进 SFML）------------------------------------------
    // 端点 = 积木/卡的稳定 id（Trigger 与 Statement 共用同一 id 计数器，天然全局唯一）。
    // 持久化按 触发头指纹 + 块在卡内的路径，随 layouts.json 存取；撤销/代码同步走同一迁移。
    private record BlockLink(long a, long b) {
    }

    private record SavedEndpoint(String key, String path) {
    }

    private final List<BlockLink> links = new ArrayList<>();
    private long linkDragFrom = -1;   // 正在拖出的连线起点（-1 无）
    private double linkDragX, linkDragY;

    /**
     * 可复用命中框：对象池跨帧复用（render 开头只重置游标），消除每帧几十到
     * 几百次的记录分配。弹窗等低频场景用 {@link #of} 独立分配。
     */
    private static final class Hit {
        int x, y, w, h, kind;
        Object data;
        Runnable onClick;

        static Hit of(int x, int y, int w, int h, int kind, Object data, Runnable onClick) {
            Hit hit = new Hit();
            hit.x = x;
            hit.y = y;
            hit.w = w;
            hit.h = h;
            hit.kind = kind;
            hit.data = data;
            hit.onClick = onClick;
            return hit;
        }
    }

    private static final int K_CLICK = 0, K_GRIP = 1, K_PALETTE = 2, K_BODY_SEL = 3, K_AB = 4, K_HEAD = 5,
            K_RCLICK = 6;   // 仅响应右键的命中（资源槽复制/粘贴等）
    /** 命中框可视化配色（Ctrl+Shift+D）：按下标对应上面的 K_* 常量。 */
    private static final int[] DBG = {0xFFFF3B30, 0xFF34C759, 0xFFFF9500, 0xFF5859D6, 0xFFAF52DE, 0xFF007AFF};

    // floating action bar shown at the mouse after a band selection
    private boolean actionBarVisible = false;
    private int abX, abY;

    private final List<Hit> hits = new ArrayList<>();   // content coords
    private final List<Hit> uiHits = new ArrayList<>(); // screen coords
    private final List<Hit> hitPool = new ArrayList<>();
    private int hitPoolCursor = 0;

    /** 从对象池取一个命中框并注册到当前列表；游标在每帧 render 开头重置。 */
    private Hit hit(int x, int y, int w, int h, int kind, Object data, Runnable onClick) {
        Hit hit;
        if (hitPoolCursor < hitPool.size()) {
            hit = hitPool.get(hitPoolCursor);
        } else {
            hit = new Hit();
            hitPool.add(hit);
        }
        hitPoolCursor++;
        hit.x = x;
        hit.y = y;
        hit.w = w;
        hit.h = h;
        hit.kind = kind;
        hit.data = data;
        hit.onClick = onClick;
        return hit;
    }
    private final IdentityHashMap<BProgram.ResourceRef, ResourceIndex.Entry> resourceEntryCache = new IdentityHashMap<>();
    /** 最近点击的 body（“+ 放入积木”的目标）；直接持有列表引用，模型重建时清空。 */
    private @Nullable List<BProgram.Statement> selectedBody;

    // ---- selection / clipboard / drag -------------------------------------------
    private final LinkedHashSet<BProgram.Statement> selection = new LinkedHashSet<>();
    private final LinkedHashSet<BProgram.Trigger> selectedTriggers = new LinkedHashSet<>();
    private boolean bandSelecting = false;
    private int bandX1, bandY1, bandX2, bandY2;
    private record GroupDrag(List<BProgram.Statement> all, List<BProgram.Statement> firstList, int firstIndex, String label, int accent) {
    }
    private @Nullable GroupDrag dragGroup;
    private @Nullable Gap dropGap;

    // ---- 卡片自由坐标（方案 A）-----------------------------------------------------
    // 卡片不再硬编码成单列：每张卡有自己的内容坐标，可拖动到任意位置、多卡并排。
    // 坐标只活在客户端（写进 SFML 会让 SFM 编译器拒绝保存），另存 layouts.json。
    // 坐标本体由 EditorLayout 持有（按触发器稳定 id 键控）。
    private @Nullable BProgram.Trigger dragTrigger;      // 正在拖动的卡
    private @Nullable BProgram.Trigger keepPosTrigger;   // 落位后保护这张卡的坐标不被避让推走
    private final List<BProgram.Trigger> dragCards = new ArrayList<>();        // 多选时整组移动
    private final IdentityHashMap<BProgram.Trigger, int[]> dragStart = new IdentityHashMap<>();
    private int dragGrabDX, dragGrabDY;   // 光标相对被抓卡片左上角的偏移（内容坐标）
    private boolean dragMoved = false;    // 是否已越过拖动阈值
    private boolean dragUndoPushed = false;
    private int autoNextY = 0;            // 自动排布的下一个 y
    private static final int DRAG_THRESHOLD = 4;

    private @Nullable String dragPaletteKind;
    private double mouseX, mouseY;      // content coords
    private boolean panning = false;
    private boolean panMoved = false;   // 右键原地点击=菜单，拖动=平移
    private double pressX, pressY;      // screen coords
    private int panStartViewX, panStartViewY;
    private @Nullable String pendingPalette;

    // ---- 紧贴对齐（拖卡吸附）------------------------------------------------
    // 横向：完全贴平（左缘贴对方右缘/右缘贴对方左缘）或同列对齐；
    // 纵向：正下方/正上方（标准节奏间距）或顶对齐。吸附时贴合边显示指示线。
    private static final int SNAP_PX = 16;
    private int snapGuideX = -1, snapGuideY1, snapGuideY2;   // 垂直贴合线（x 吸附）
    private int snapGuideY = -1, snapGuideXL, snapGuideXR;   // 水平贴合线（y 吸附）

    /** 命中框可视化（Ctrl+Shift+D）：排查"点不中 / 拖不动"时打开。 */
    private boolean debugHits = false;
    /** 性能浮层（Ctrl+Shift+P）：布局耗时 / 命中框数量 / 诊断次数。 */
    private boolean debugPerf = false;
    private long perfLayoutNanos = 0;

    private static String clipboardSfml = null;
    /** 资源槽右键复制的资源（跨卡片可用，会话内保留）。 */
    private BProgram.ResourceRef copiedResource = null;
    /** 标签药丸右键复制的标签组（跨卡片可用）。 */
    private List<String> copiedLabels = null;
    private boolean clipboardTriggers = false; // last copy was whole triggers

    public BlockEditorScreen(ManagerContainerMenu menu, String programText,
                             @Nullable Runnable onCloseRequest) {
        super(Component.translatable("gui.sfmfactorystudio.blocks.title"));
        this.menu = menu;
        this.onCloseRequest = onCloseRequest;
        this.savedProgramText = programText == null ? "" : programText;
        SfmlToBlocks.Result r = SfmlToBlocks.parse(savedProgramText);
        this.importFailed = !r.ok();
        this.program = r.ok() && r.program() != null ? r.program() : new BProgram();
        if (!r.ok()) {
            previewMode = true;
            statusText = "⚠ 原程序语法不正确；已展开代码编辑，修正后会自动恢复积木";
            statusColor = 0xFFB45309;
            statusTicks = 200;
        }
        lastModelSfml = BlocksToSfml.toSfml(this.program);
        layout.setProgram(this.program);
        layout.setExpandedIds(expandedIds);
        loadLayouts();
        warmupKindOracle();
    }

    private static volatile boolean oracleWarmupStarted = false;

    /** 后台预热资源类别索引（只读注册表），消除首次诊断的单帧卡顿。 */
    private static void warmupKindOracle() {
        if (oracleWarmupStarted) return;
        oracleWarmupStarted = true;
        Thread warmup = new Thread(() -> {
            try {
                resourceOracle();
            } catch (Throwable ignored) {
            }
        }, "sfmfactorystudio-oracle-warmup");
        warmup.setDaemon(true);
        warmup.start();
    }

    // ================================================================== lifecycle

    @Override
    protected void init() {
        // 回显（从标签/NBT 选择器返回会重跑 init）：代码窗没有未提交的编辑时，
        // 模型才是最新事实。若沿用窗里的旧文本，选择器刚写入模型的条件会被旧
        // 代码"抢走所有权"，随后保存/校验会把整个模型重解析回没有条件的版本。
        String carriedCode = codeEditor == null
                ? (savedProgramText.isBlank() && !importFailed ? generated() : savedProgramText)
                : (codeTextEdited ? codeEditor.value() : generated());
        // Keep a valid disk program byte-for-byte until the first block edit;
        // comments and the player's formatting should not change just because
        // the source pane was opened.
        boolean carriedOwnership = codeTextEdited || importFailed || !Objects.equals(carriedCode, generated());
        knownLabels.clear();
        knownLabels.addAll(SERVER_LABELS);
        knownLabelCounts.clear();
        knownLabelCounts.putAll(SERVER_LABEL_COUNTS);
        SFMGuiNetwork.sendToServerBestEffort(
                new io.github.xianynomial.sfmfactorystudio.net.RequestLabelsPayload(menu.MANAGER_POSITION));
        layoutDirty = true;
        // 注意不要重置 fitted：选择器切屏返回会重跑 init()，重置会导致视角被抢去自动适配
        computePanel();
        nameBox = new EditBox(this.font, namePillX() + 6, panelY + 7, 108, 15, Component.empty());
        nameBox.setMaxLength(64);
        nameBox.setBordered(false);
        nameBox.setTextColor(0xFF1B2432);
        nameBox.setTextShadow(false); // EditBox 默认带阴影，浅色胶囊背景上就是"字体重影"
        nameBox.setValue(program.name);
        nameBox.setResponder(s -> {
            if (program.name.equals(s)) return;
            program.name = s;
            dirty = true;
            draftSaveDelay = DRAFT_DELAY_TICKS;
            generatedCache = "";
        });
        this.addRenderableWidget(nameBox);

        // Always keep one live editor instance. Hiding it only collapses the
        // source pane; it avoids losing the caret/undo history when users switch
        // repeatedly between blocks and code.
        int codeH = Math.max(64, panelH / 3 - 30);
        codeEditor = new SfmlCodeEditor(this.font, panelX + PALETTE_W + 22,
                panelY + panelH - codeH - 10, Math.max(120, panelW - PALETTE_W - 38), codeH,
                this::onCodeTextChanged);
        settingCodeFromModel = true;
        codeEditor.setValueFromModel(carriedCode);
        settingCodeFromModel = false;
        codeEditor.visible = previewMode;
        codeTextEdited = carriedOwnership;
        codeAwaitingValidation = importFailed;
        lastModelSfml = generated();
        this.addRenderableWidget(codeEditor);
        offerDraftRestore();
    }

    public int getPanelX() {
        return panelW <= 1 ? (safeScreenWidth() - Math.round(safeScreenWidth() * 74f / 100f)) / 2 : panelX;
    }

    public int getPanelY() {
        return panelH <= 1 ? (safeScreenHeight() - Math.round(safeScreenHeight() * 86f / 100f)) / 2 : panelY;
    }

    public int getPanelW() {
        return panelW <= 1 ? Math.round(safeScreenWidth() * 74f / 100f) : panelW;
    }

    public int getPanelH() {
        return panelH <= 1 ? Math.round(safeScreenHeight() * 86f / 100f) : panelH;
    }

    private void computePanel() {
        // Uniform shrink on all sides (aspect preserved, centered). With JEI the
        // panel is smaller so its ingredient list and bookmarks fit on both sides.
        boolean jei = io.github.xianynomial.sfmfactorystudio.client.JeiCompat.isAvailable();
        float wf = jei ? 74f / 100f : 94f / 100f;
        float hf = jei ? 86f / 100f : 94f / 100f;
        panelW = Math.round(width * wf);
        panelH = Math.round(height * hf);
        panelX = (width - panelW) / 2;
        panelY = (height - panelH) / 2;
    }

    @Override
    public void tick() {
        if (codeEditor != null) codeEditor.tick();
        if (statusTicks > 0) statusTicks--;
        if (locateTicks > 0) locateTicks--;
        if (--issueRefreshCountdown <= 0) {
            issueRefreshCountdown = 5;
            // 5 tick 只是“变化后多久刷新”的延迟上限；程序与标签都没变就不重算
            if (modelVersion != issuesVersion) refreshIssues();
        }
        if (draftSaveDelay > 0 && --draftSaveDelay == 0 && dirty) {
            saveDraft();
        }
        if (codeValidateDelay > 0 && --codeValidateDelay == 0) {
            applyCodeText(false);
        }
        if (codeSuggestDelay > 0 && --codeSuggestDelay == 0) {
            refreshCodeSuggestions();
        }

        // A block edit happens after pushUndo() invalidates the generated cache.
        // Detect the completed model change here and update source once, keeping
        // the code caret stable while the user is typing. blocksNewerThanCode
        // also covers edits that landed while a picker screen was showing,
        // where init() already refreshed lastModelSfml.
        if (codeEditor != null && codeValidateDelay <= 0 && !codeAwaitingValidation) {
            String modelNow = generated();
            if (blocksNewerThanCode || !modelNow.equals(lastModelSfml)) {
                lastModelSfml = modelNow;
                blocksNewerThanCode = false;
                settingCodeFromModel = true;
                codeEditor.setValueFromModel(modelNow);
                settingCodeFromModel = false;
                codeTextEdited = false;
                codeStatusText = "积木修改已同步到代码";
                codeStatusColor = 0xFF18794E;
                codeSuggestDelay = codeEditor.isFocused() ? 3 : -1;
            }
        }
    }

    // ================================================================ model edits

    private void pushUndo() {
        // 撤销快照与每触发器内容哈希共用一次序列化遍历；哈希交给布局做差分，
        // 普通字段编辑不再触发全程序重排（大程序每次按键 O(全部卡) → O(改动卡)）。
        io.github.xianynomial.sfmfactorystudio.client.blocks.model.BlocksToSfml.Snapshot snap =
                io.github.xianynomial.sfmfactorystudio.client.blocks.model.BlocksToSfml.snapshot(program);
        undoStack.push(snap.sfml());
        while (undoStack.size() > 50) undoStack.removeLast();
        redoStack.clear(); // 新编辑使重做历史失效
        dirty = true;
        draftSaveDelay = DRAFT_DELAY_TICKS;
        generatedCache = "";
        blocksNewerThanCode = true;
        layoutDirty = true;
        modelVersion++;
        layout.markModelEdited(snap.triggerHashes());
    }

    private void undo() {
        if (undoStack.isEmpty()) {
            showStatus("没有可撤销的操作", C_TEXT_SUB);
            return;
        }
        String sfml = undoStack.pop();
        SfmlToBlocks.Result r = SfmlToBlocks.parse(sfml);
        if (r.ok() && r.program() != null) {
            redoStack.push(BlocksToSfml.toSfml(program));
            while (redoStack.size() > 50) redoStack.removeLast();
            replaceProgramPreservingLayout(r, "已撤销上一步操作");
        } else {
            showStatus("撤销失败：历史内容无法解析", 0xFFD13438);
        }
    }

    private void redo() {
        if (redoStack.isEmpty()) {
            showStatus("没有可重做的操作", C_TEXT_SUB);
            return;
        }
        String sfml = redoStack.pop();
        SfmlToBlocks.Result r = SfmlToBlocks.parse(sfml);
        if (r.ok() && r.program() != null) {
            undoStack.push(BlocksToSfml.toSfml(program));
            while (undoStack.size() > 50) undoStack.removeLast();
            replaceProgramPreservingLayout(r, "已重做");
        } else {
            showStatus("重做失败：历史内容无法解析", 0xFFD13438);
        }
    }

    /**
     * 用解析结果整体替换程序（撤销/重做共用）。重载会生成全新的 Trigger
     * 对象，先按指纹快照旧卡片坐标、重载后搬回新对象——否则一次撤销/重做
     * 就把用户摆好的自由布局塌回单列。
     */
    private void replaceProgramPreservingLayout(SfmlToBlocks.Result r, String okMessage) {
        List<List<String>> savedLinks = snapshotLinks();
        List<String> haveKeys = new ArrayList<>();
        List<int[]> havePos = new ArrayList<>();
        for (BProgram.Trigger t : program.triggers) {
            int[] p = layout.cardPosOf(t.id);
            if (p == null) continue;
            haveKeys.add(CardLayouts.triggerKey(t));
            havePos.add(new int[]{p[0], p[1]});
        }
        program = r.program();
        layout.setProgram(program);
        layout.setExpandedIds(expandedIds);
        resourceEntryCache.clear();
        if (!haveKeys.isEmpty()) {
            int[] m = CardLayouts.matchByKeys(CardLayouts.keysOf(program.triggers), haveKeys);
            for (int i = 0; i < m.length; i++) {
                if (m[i] >= 0) {
                    layout.setCardPos(program.triggers.get(i).id,
                            havePos.get(m[i])[0], havePos.get(m[i])[1]);
                }
            }
        }
        modelVersion++;
        restoreLinks(savedLinks);
        selectedBody = null;
        nameBox.setValue(program.name);
        generatedCache = "";
        blocksNewerThanCode = true; // 撤销/重做也改了模型，代码窗需要跟上
        selection.clear();
        selectedTriggers.clear();
        actionBarVisible = false;
        layoutDirty = true;
        dirty = true;
        draftSaveDelay = DRAFT_DELAY_TICKS;
        showStatus(okMessage, C_SELECT);
    }

    private BProgram.Statement.Input newInput() {
        var s = new BProgram.Statement.Input();
        s.access.labels.add(knownLabels.isEmpty() ? "a" : knownLabels.get(0));
        s.limits.add(new BProgram.ResourceLimit());
        return s;
    }

    private BProgram.Statement.Output newOutput() {
        var s = new BProgram.Statement.Output();
        s.access.labels.add(knownLabels.isEmpty() ? "b" : knownLabels.get(0));
        s.limits.add(new BProgram.ResourceLimit());
        return s;
    }

    private BProgram.Statement.If newIf() {
        var s = new BProgram.Statement.If();
        var b = new BProgram.Branch();
        b.cond = newConditionHas();
        s.branches.add(b);
        return s;
    }

    private BProgram.TimerTrigger newEnergyTrigger() {
        String source = knownLabels.isEmpty() ? "供能端" : knownLabels.get(0);
        String target = knownLabels.size() > 1 ? knownLabels.get(1) : "用能端";
        BProgram.TimerTrigger trigger = new BProgram.TimerTrigger();
        trigger.count = TimerRules.energyMinimumTicks();
        trigger.unit = BProgram.TimerTrigger.Unit.TICKS;
        trigger.body.addAll(BlockTemplates.energyTransfer(source, target));
        return trigger;
    }

    private BProgram.Bool.Has newConditionHas() {
        var has = new BProgram.Bool.Has();
        has.access.labels.add(knownLabels.isEmpty() ? "a" : knownLabels.get(0));
        return has;
    }

    private List<BProgram.Statement> targetBody() {
        if (selectedBody != null) {
            return selectedBody;
        }
        if (program.triggers.isEmpty()) {
            program.triggers.add(new BProgram.TimerTrigger());
        }
        return program.triggers.get(0).body;
    }

    // ===================================================================== save

    private String generated() {
        if (generatedCache.isEmpty()) {
            generatedCache = BlocksToSfml.toSfml(program);
        }
        return generatedCache;
    }

    /** The source pane is canonical after a text edit; otherwise blocks are. */
    private String currentSource() {
        return codeTextEdited && codeEditor != null ? codeEditor.value() : generated();
    }

    private void onCodeTextChanged(String ignored) {
        if (settingCodeFromModel || codeEditor == null) return;
        codeTextEdited = true;
        codeAwaitingValidation = true;
        codeValidateDelay = 8; // debounce: do not rebuild the block tree on every keystroke
        codeSuggestDelay = 4;
        dirty = true;
        draftSaveDelay = DRAFT_DELAY_TICKS;
        codeStatusText = "正在检查代码…";
        codeStatusColor = C_TEXT_SUB;
    }

    private void refreshCodeSuggestions() {
        if (codeEditor == null || !previewMode || !codeEditor.isFocused()) return;
        try {
            var build = new ProgramBuilder(codeEditor.value()).build();
            IntellisenseContext context = new IntellisenseContext(
                    build,
                    codeEditor.cursorPosition(),
                    codeEditor.selectionCursorPosition(),
                    LabelPositionHolder.from(menu.getDisk()),
                    SFMConfig.getOrDefault(SFMConfig.CLIENT_TEXT_EDITOR_CONFIG.intellisenseLevel));
            codeEditor.setSuggestions(SFMLIntellisense.getSuggestions(context), context);
        } catch (Throwable ignored) {
            codeEditor.clearSuggestions();
        }
    }

    /**
     * Parse source into a fresh block tree. Invalid, half-typed source stays in
     * the editor and draft, while the last valid blocks remain untouched.
     */
    private boolean applyCodeText(boolean announce) {
        if (codeEditor == null) return !importFailed;
        String source = codeEditor.value();
        if (source.length() > ca.teamdman.sfml.ast.Program.MAX_PROGRAM_LENGTH) {
            codeStatusText = "代码过长，未同步到积木";
            codeStatusColor = C_ERR;
            if (announce) showStatus("✖ 程序太长，请删减后再保存", C_ERR);
            return false;
        }
        List<String> compilerErrors = SfmlValidate.check(source);
        SfmlToBlocks.Result parsed = compilerErrors.isEmpty() ? SfmlToBlocks.parse(source) : null;
        if (!compilerErrors.isEmpty() || parsed == null || !parsed.ok() || parsed.program() == null) {
            List<String> errors = !compilerErrors.isEmpty() ? compilerErrors
                    : parsed == null ? List.of("无法读取代码") : parsed.errors();
            String first = errors.isEmpty() ? "代码尚未完整" : errors.get(0);
            codeStatusText = "未同步：" + this.font.plainSubstrByWidth(first, Math.max(100, canvasW - 150));
            codeStatusColor = C_ERR;
            if (announce) showStatus("✖ " + first + "；已保留代码和原有积木", C_ERR);
            return false;
        }

        String before = generated();
        if (!before.equals(BlocksToSfml.toSfml(parsed.program()))) {
            undoStack.push(before);
            while (undoStack.size() > 50) undoStack.removeLast();
        }
        redoStack.clear(); // 代码同步也是一次编辑，重做历史失效

        // Preserve card positions for matching triggers when text is edited.
        List<String> oldKeys = new ArrayList<>();
        List<int[]> oldPositions = new ArrayList<>();
        for (BProgram.Trigger trigger : program.triggers) {
            int[] pos = layout.cardPosOf(trigger.id);
            if (pos == null) continue;
            oldKeys.add(CardLayouts.triggerKey(trigger));
            oldPositions.add(new int[]{pos[0], pos[1]});
        }
        List<List<String>> savedLinks = snapshotLinks();
        program = parsed.program();
        layout.setProgram(program);
        layout.clearCardPos();
        if (!oldKeys.isEmpty()) {
            int[] matches = CardLayouts.matchByKeys(CardLayouts.keysOf(program.triggers), oldKeys);
            for (int i = 0; i < matches.length; i++) {
                if (matches[i] >= 0) {
                    layout.setCardPos(program.triggers.get(i).id,
                            oldPositions.get(matches[i])[0], oldPositions.get(matches[i])[1]);
                }
            }
        }
        restoreLinks(savedLinks);
        importFailed = false;
        codeAwaitingValidation = false;
        blocksNewerThanCode = false; // 代码刚刚成为权威，模型同步标志随之消解
        resourceEntryCache.clear();
        selection.clear();
        selectedTriggers.clear();
        expandedIds.clear();
        layout.setExpandedIds(expandedIds);
        selectedBody = null;
        actionBarVisible = false;
        modelVersion++;
        generatedCache = "";
        layoutDirty = true;
        lastModelSfml = generated();
        if (nameBox != null) nameBox.setValue(program.name);
        dirty = !Objects.equals(source, savedProgramText);
        draftSaveDelay = dirty ? DRAFT_DELAY_TICKS : -1;
        codeStatusText = "代码正确，已同步为积木";
        codeStatusColor = 0xFF18794E;
        if (announce) showStatus("✔ 代码正确，积木已更新", 0xFF0C8F58);
        return true;
    }

    private List<String> lint() {
        return new ArrayList<>(ProgramDiagnostics.warningMessages(program));
    }

    private boolean save() {
        if (!SfmCaps.withComponent() && programHasNbtMatcher()) {
            showStatus("✖ 此服务器未安装 NBT 区分支持；请先移除资源标签里的组件条件再保存", 0xFFD13438);
            return false;
        }
        if (codeTextEdited && !applyCodeText(true)) {
            return false;
        }
        if (importFailed) {
            previewMode = true;
            if (codeEditor != null) codeEditor.visible = true;
            showStatus("✖ 请先在下方代码编辑区修正语法；原程序和积木都没有被覆盖", 0xFFD13438);
            return false;
        }
        List<String> modelErrors = ProgramDiagnostics.errorMessages(program);
        if (!modelErrors.isEmpty()) {
            showStatus("✖ " + String.join("；", modelErrors), 0xFFD13438);
            return false;
        }
        String sfml = currentSource();
        if (sfml.length() > ca.teamdman.sfml.ast.Program.MAX_PROGRAM_LENGTH) {
            showStatus("✖ 程序太长，请拆分内容后再保存；为防止语法损坏，本次没有截断", 0xFFD13438);
            return false;
        }
        var errors = SfmlValidate.check(sfml);
        if (!errors.isEmpty()) {
            showStatus("✖ " + String.join("; ", errors), 0xFFD13438);
            return false;
        }
        var hints = lint();
        if (!hints.isEmpty()) {
            // 全部拼成一行会超出状态栏；只展示第一条，其余指向问题面板
            String shown = hints.get(0)
                    + (hints.size() > 1
                    ? "（" + T_ISSUES_MORE.getString().replace("{n}", String.valueOf(hints.size() - 1)) + "）"
                    : "");
            showStatus("⚠ " + shown, 0xFFB45309);
        }
        SFMPackets.sendToServer(new ServerboundManagerProgramPacket(
                menu.containerId, menu.MANAGER_POSITION, sfml));
        menu.program = sfml;
        savedProgramText = sfml;
        dirty = false;
        codeTextEdited = codeEditor != null && !codeEditor.value().equals(generated());
        lastModelSfml = generated();
        draftSaveDelay = -1;
        clearDraft();
        saveLayouts();
        showStatus(T_SAVED_OK.getString(), 0xFF0C8F58);
        return true;
    }

    // ---- 卡片坐标持久化（layouts.json）------------------------------------------
    // 坐标只活在客户端：写进 SFML 会让 SFM 编译器直接拒绝保存。按管理器方块坐标
    // 分键。条目携带"卡片指纹"（触发头：类型/数值/单位/全局/偏移），加载时按
    // 指纹匹配而不是按顺序索引 —— 用 ◀/▶ 重排或增删卡片后位置不会串到别的卡上；
    // 正文（积木块）编辑不影响指纹，位置照样保留。指纹相同的卡（如多个相同定时
    // 触发）按出现顺序一一对应；匹配不上的卡回自动排布（宁可位置错，不叠卡）。
    // 兼容：旧格式条目 [x, y] 仍可读（数量对得上才按位置套用）。

    /** 一条已保存的卡片位置：key 为触发头指纹，旧格式条目 key 为 null。 */
    private record SavedCard(@Nullable String key, int x, int y, boolean collapsed) {
    }

    private String layoutKey() {
        var p = menu.MANAGER_POSITION;
        return p.getX() + "," + p.getY() + "," + p.getZ();
    }

    private @Nullable Path layoutFile() {
        try {
            Path dir = FMLPaths.CONFIGDIR.get().resolve("sfmfactorystudio");
            Files.createDirectories(dir);
            return dir.resolve("layouts.json");
        } catch (IOException e) {
            return null;
        }
    }

    /** 解析一份 layouts.json；读不到或格式不对返回 null，绝不抛出。 */
    @SuppressWarnings("unchecked")
    private @Nullable Map<String, List<List<Object>>> readLayoutFile() {
        Path file = layoutFile();
        if (file == null || !Files.exists(file)) return null;
        try {
            var raw = GSON.fromJson(Files.readString(file),
                    new TypeToken<Map<String, List<List<Object>>>>() {
                    }.getType());
            return raw instanceof Map<?, ?> map && !map.isEmpty()
                    ? (Map<String, List<List<Object>>>) map : null;
        } catch (Exception e) {
            return null;
        }
    }

    private void loadLayouts() {
        Map<String, List<List<Object>>> all = readLayoutFile();
        if (all == null) return;
        List<?> cards = all.get(layoutKey());
        if (cards == null) return;
        List<SavedCard> entries = new ArrayList<>();
        boolean sawNewFormat = false;
        for (Object c : cards) {
            if (!(c instanceof List<?> xy)) continue;
            if (xy.size() >= 3 && xy.get(0) instanceof String k
                    && xy.get(1) instanceof Number nx && xy.get(2) instanceof Number ny) {
                boolean collapsed = xy.size() >= 4 && xy.get(3) instanceof Number nc && nc.intValue() != 0;
                entries.add(new SavedCard(k, CardLayouts.snap(nx.intValue()), CardLayouts.snap(ny.intValue()), collapsed));
                sawNewFormat = true;
            } else if (xy.size() >= 2 && xy.get(0) instanceof Number a && xy.get(1) instanceof Number b) {
                entries.add(new SavedCard(null, CardLayouts.snap(a.intValue()), CardLayouts.snap(b.intValue()), false));
            }
        }
        if (entries.isEmpty()) return;
        if (!sawNewFormat) {
            // 旧格式（纯 [x, y] 列表）：只有数量严丝合缝才按位置套用
            if (entries.size() != program.triggers.size()) return;
            for (int i = 0; i < entries.size(); i++) {
                layout.setCardPos(program.triggers.get(i).id, entries.get(i).x(), entries.get(i).y());
            }
            return;
        }
        // 新格式：按指纹匹配（CardLayouts.matchByKeys），同指纹按出现顺序一一对应
        List<String> haveKeys = new ArrayList<>();
        List<int[]> havePos = new ArrayList<>();
        List<Boolean> haveCollapsed = new ArrayList<>();
        for (SavedCard e : entries) {
            if (e.key() != null) {
                haveKeys.add(e.key());
                havePos.add(new int[]{e.x(), e.y()});
                haveCollapsed.add(e.collapsed());
            }
        }
        int[] m = CardLayouts.matchByKeys(CardLayouts.keysOf(program.triggers), haveKeys);
        for (int i = 0; i < m.length; i++) {
            if (m[i] >= 0) {
                layout.setCardPos(program.triggers.get(i).id, havePos.get(m[i])[0], havePos.get(m[i])[1]);
                if (haveCollapsed.get(m[i])) collapsedCards.add(program.triggers.get(i).id);
            }
        }
        layout.setCollapsedCards(collapsedCards);
        loadZones(all);
        loadLinks(all);
    }

    /** 工作区存在 layouts.json 的 "<管理器>:zones" 键下；读不到就保持为空。 */
    private void loadZones(Map<String, List<List<Object>>> all) {
        zones.clear();
        focusedZone = null;
        List<?> rows = all.get(layoutKey() + ":zones");
        if (rows == null) return;
        for (Object rowObj : rows) {
            if (rowObj instanceof List<?> row && row.size() >= 6
                    && row.get(1) instanceof Number c && row.get(2) instanceof Number x
                    && row.get(3) instanceof Number y && row.get(4) instanceof Number w
                    && row.get(5) instanceof Number h) {
                zones.add(new Zone(String.valueOf(row.get(0)),
                        Math.max(0, Math.min(2, c.intValue())),
                        x.intValue(), y.intValue(), w.intValue(), h.intValue()));
            }
        }
    }

    // ---- 连线持久化：触发头指纹 + 块在卡内的路径（"c"=整卡），匹配不上静默丢弃 --

    private void loadLinks(Map<String, List<List<Object>>> all) {
        links.clear();
        List<?> rows = all.get(layoutKey() + ":links");
        if (rows == null) return;
        for (Object rowObj : rows) {
            if (!(rowObj instanceof List<?> row) || row.size() < 4) continue;
            long a = resolveEndpoint(String.valueOf(row.get(0)), String.valueOf(row.get(1)));
            long b = resolveEndpoint(String.valueOf(row.get(2)), String.valueOf(row.get(3)));
            if (a >= 0 && b >= 0 && a != b) links.add(new BlockLink(a, b));
        }
    }

    private long resolveEndpoint(String key, String path) {
        for (BProgram.Trigger t : program.triggers) {
            if (!CardLayouts.triggerKey(t).equals(key)) continue;
            if ("c".equals(path)) return t.id;
            List<Integer> p = new ArrayList<>();
            for (String s : path.split("-")) {
                try {
                    p.add(Integer.parseInt(s));
                } catch (NumberFormatException ignored) {
                    return -1;
                }
            }
            BProgram.Statement st = stmtAtPath(t, p);
            return st != null ? st.id : -1;
        }
        return -1;
    }

    /** 端点 id → (指纹, 路径)；找不到主人（刚被删）返回 null。 */
    private @Nullable SavedEndpoint encodeEndpoint(long id) {
        for (BProgram.Trigger t : program.triggers) {
            if (t.id == id) return new SavedEndpoint(CardLayouts.triggerKey(t), "c");
            List<Integer> path = pathToStmt(t.body, id);
            if (path != null) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < path.size(); i++) {
                    if (i > 0) sb.append('-');
                    sb.append(path.get(i));
                }
                return new SavedEndpoint(CardLayouts.triggerKey(t), sb.toString());
            }
        }
        return null;
    }

    /** 深度优先找语句：路径元素交替为 [语句下标, 分支下标(-1=否则), 语句下标, …]。 */
    private static @Nullable List<Integer> pathToStmt(List<BProgram.Statement> body, long id) {
        for (int i = 0; i < body.size(); i++) {
            BProgram.Statement s = body.get(i);
            if (s.id == id) return new ArrayList<>(List.of(i));
            if (s instanceof BProgram.Statement.If iff) {
                for (int b = 0; b < iff.branches.size(); b++) {
                    List<Integer> sub = pathToStmt(iff.branches.get(b).body, id);
                    if (sub != null) {
                        sub.add(0, b);
                        sub.add(0, i);
                        return sub;
                    }
                }
                List<Integer> sub = pathToStmt(iff.elseBody, id);
                if (sub != null) {
                    sub.add(0, -1);
                    sub.add(0, i);
                    return sub;
                }
            }
        }
        return null;
    }

    private static @Nullable BProgram.Statement stmtAtPath(BProgram.Trigger t, List<Integer> path) {
        if (path.isEmpty()) return null;
        List<BProgram.Statement> body = t.body;
        BProgram.Statement found = null;
        for (int k = 0; k < path.size(); k++) {
            int idx = path.get(k);
            if (idx < 0 || idx >= body.size()) return null;
            found = body.get(idx);
            if (found instanceof BProgram.Statement.If iff && k + 1 < path.size()) {
                int branch = path.get(++k);
                body = branch == -1 ? iff.elseBody
                        : branch < iff.branches.size() ? iff.branches.get(branch).body : null;
                if (body == null) return null;
            } else if (k + 1 < path.size()) {
                return null;
            }
        }
        return found;
    }

    /** 模型重建前快照连线；重建后按指纹+路径找回，找不到的静默丢弃。 */
    private List<List<String>> snapshotLinks() {
        List<List<String>> saved = new ArrayList<>();
        for (BlockLink l : links) {
            SavedEndpoint ea = encodeEndpoint(l.a());
            SavedEndpoint eb = encodeEndpoint(l.b());
            if (ea != null && eb != null) {
                saved.add(List.of(ea.key(), ea.path(), eb.key(), eb.path()));
            }
        }
        return saved;
    }

    private void restoreLinks(List<List<String>> saved) {
        links.clear();
        for (List<String> row : saved) {
            long a = resolveEndpoint(row.get(0), row.get(1));
            long b = resolveEndpoint(row.get(2), row.get(3));
            if (a >= 0 && b >= 0 && a != b) links.add(new BlockLink(a, b));
        }
    }

    private void saveLayouts() {
        Path file = layoutFile();
        if (file == null) return;
        try {
            Map<String, List<List<Object>>> all = new LinkedHashMap<>();
            Map<String, List<List<Object>>> prev = readLayoutFile();
            if (prev != null) {
                for (Map.Entry<String, List<List<Object>>> e : prev.entrySet()) {
                    // 其他管理器的条目原样保留；旧格式 [x, y] 也照抄，不偷偷升级
                    if (e.getKey().equals(layoutKey())) continue;
                    all.put(e.getKey(), e.getValue());
                }
            }
            List<List<Object>> mine = new ArrayList<>();
            for (BProgram.Trigger t : program.triggers) {
                int[] p = layout.cardPosOf(t.id);
                Object x = p == null ? 0 : p[0];
                Object y = p == null ? 0 : p[1];
                mine.add(collapsedCards.contains(t.id)
                        ? List.of(CardLayouts.triggerKey(t), x, y, 1)
                        : List.of(CardLayouts.triggerKey(t), x, y));
            }
            all.put(layoutKey(), mine);
            List<List<Object>> zoneRows = new ArrayList<>();
            for (Zone z : zones) {
                zoneRows.add(List.of(z.name(), z.color(), z.x(), z.y(), z.w(), z.h()));
            }
            if (!zoneRows.isEmpty()) all.put(layoutKey() + ":zones", zoneRows);
            List<List<Object>> linkRows = new ArrayList<>();
            for (BlockLink l : links) {
                SavedEndpoint ea = encodeEndpoint(l.a());
                SavedEndpoint eb = encodeEndpoint(l.b());
                if (ea != null && eb != null) {
                    linkRows.add(List.of(ea.key(), ea.path(), eb.key(), eb.path()));
                }
            }
            if (!linkRows.isEmpty()) all.put(layoutKey() + ":links", linkRows);
            Files.writeString(file, GSON.toJson(all));
        } catch (Exception ignored) {
        }
    }

    private void showStatus(String text, int color) {
        statusText = text;
        statusColor = color;
        statusTicks = 160;
    }

    // ============================================================== 诊断（问题检查）

    /** 重算全部诊断：标签绑定数来自服务器，资源类别来自已建好的资源索引。 */
    private void refreshIssues() {
        List<ProgramDiagnostics.Issue> fresh =
                ProgramDiagnostics.check(program, new ProgramDiagnostics.Context(knownLabelCounts, resourceOracle()));
        // 面板里错误在前、提醒在后，同级别保持程序顺序
        fresh.sort((a, b) -> {
            if (a.severity() != b.severity()) {
                return a.severity() == ProgramDiagnostics.Severity.ERROR ? -1 : 1;
            }
            return 0;
        });
        issuesCache = fresh;
        issuesRefreshCount++;
        issueErrCount = fresh.stream().filter(i -> i.severity() == ProgramDiagnostics.Severity.ERROR).count();
        issueWarnCount = fresh.size() - issueErrCount;
        issueMissingLabels = fresh.stream().anyMatch(i -> i.message().contains("还没有绑定方块"));
        issuesWrapped = null; // 折行随缓存一起失效
        issuesVersion = modelVersion;
        blockSeverity.clear();
        for (ProgramDiagnostics.Issue issue : fresh) {
            if (issue.blockId() < 0) continue;
            blockSeverity.merge(issue.blockId(), issue.severity(), (oldV, newV) ->
                    newV == ProgramDiagnostics.Severity.ERROR ? newV : oldV);
        }
    }

    /** trigger → 卡片矩形；statement → 积木行矩形。布局尚未跑或对象已被替换时返回 null。 */
    private int @Nullable [] contentRectOf(Object target) {
        if (target instanceof BProgram.Statement s) {
            return layout.rowRectOf(s.id);
        }
        if (target instanceof BProgram.Trigger t) {
            return layout.cardRectOf(t.id);
        }
        return null;
    }

    /** 按稳定 id 反查矩形（诊断角标用；statement 优先，其次触发器卡）。 */
    private int @Nullable [] contentRectOfId(long id) {
        int[] r = layout.rowRectOf(id);
        return r != null ? r : layout.cardRectOf(id);
    }

    /** 把相机居中到问题积木并高亮 2 秒；矩形找不到时先强制重排再试一次。 */
    private void locateIssue(ProgramDiagnostics.Issue issue) {
        if (issue.block() == null) return;
        locateBlock(issue.block());
    }

    /** 相机居中 + 2 秒呼吸边框（问题定位 / 搜索定位共用）。 */
    private void locateBlock(Object block) {
        int[] r = contentRectOf(block);
        if (r == null) {
            layoutDirty = true;
            relayout();
            r = contentRectOf(block);
        }
        if (r == null) {
            showStatus("找不到这块积木了，它可能刚被删除", 0xFFB45309);
            return;
        }
        float safeZoom = Math.max(0.6f, zoom);
        zoom = safeZoom;
        viewX = Math.round(r[0] + r[2] / 2f - canvasW / (2f * zoom));
        viewY = Math.round(r[1] + r[3] / 2f - canvasH / (2f * zoom));
        locateTarget = block;
        locateTicks = 40;
    }


    /** 修复动作走正常编辑路径：先压撤销快照，再改模型，立即刷新诊断。 */
    private void applyIssueFix(ProgramDiagnostics.Issue issue) {
        if (issue.fix() == null) return;
        pushUndo();
        issue.fix().run();
        refreshIssues();
        showStatus("✔ 已修复：" + issue.fixLabel(), 0xFF0C8F58);
    }

    /** 循环定位下一个/上一个问题（‹ › 按钮 + F8）。 */
    private void stepIssue(int dir) {
        if (issuesCache.isEmpty()) return;
        int size = issuesCache.size();
        if (issueCursor < 0 || issueCursor >= size) {
            issueCursor = dir > 0 ? 0 : size - 1;
        } else {
            issueCursor = (issueCursor + dir + size) % size;
        }
        locateIssue(issuesCache.get(issueCursor));
    }

    /** ResourceIndex 条目 → (完整 id / 资源名) → 类别集合，会话内只建一次。 */
    private static volatile java.util.Map<String, java.util.Set<BProgram.ResourceKind>> KIND_BY_FULL = null;
    private static volatile java.util.Map<String, java.util.Set<BProgram.ResourceKind>> KIND_BY_PATH = null;

    private static @Nullable ProgramDiagnostics.ResourceOracle resourceOracle() {
        try {
            if (KIND_BY_FULL == null) buildKindOracle();
            var full = KIND_BY_FULL;
            var path = KIND_BY_PATH;
            if (full == null || path == null) return null;
            return (namespace, name) -> {
                if (namespace != null) {
                    var exact = full.get(namespace + ":" + name);
                    if (exact != null && !exact.isEmpty()) return exact;
                }
                var byPath = path.get(name);
                return byPath == null ? java.util.Set.of() : byPath;
            };
        } catch (Throwable t) {
            return null; // 索引不可用（注册表未就绪等）时静默禁用类别检查
        }
    }

    private static synchronized void buildKindOracle() {
        if (KIND_BY_FULL != null) return;
        java.util.Map<String, java.util.Set<BProgram.ResourceKind>> full = new java.util.HashMap<>();
        java.util.Map<String, java.util.Set<BProgram.ResourceKind>> byPath = new java.util.HashMap<>();
        for (ResourceIndex.Entry entry : ResourceIndex.all()) {
            try {
                BProgram.ResourceRef ref = BProgram.ResourceRef.parse(entry.sfmlId());
                BProgram.ResourceKind kind = ref.kind();
                if (kind == BProgram.ResourceKind.CUSTOM) continue;
                if (ref.namespace == null || ref.name == null) continue;
                if (ref.namespace.isBlank() || ref.name.isBlank()
                        || "*".equals(ref.namespace) || "*".equals(ref.name)) continue;
                full.computeIfAbsent(ref.namespace + ":" + ref.name,
                        k -> java.util.EnumSet.noneOf(BProgram.ResourceKind.class)).add(kind);
                byPath.computeIfAbsent(ref.name,
                        k -> java.util.EnumSet.noneOf(BProgram.ResourceKind.class)).add(kind);
            } catch (IllegalArgumentException ignored) {
                // 索引里的怪异 id 直接跳过，不影响其余条目
            }
        }
        KIND_BY_FULL = full;
        KIND_BY_PATH = byPath;
    }

    /** 中文按字符宽度折行；一行都放不下时也保证有输出。 */
    private List<String> wrapText(String text, int maxWidth) {
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (this.font.width(current.toString() + c) > maxWidth && !current.isEmpty()) {
                lines.add(current.toString());
                current.setLength(0);
            }
            current.append(c);
        }
        if (!current.isEmpty()) lines.add(current.toString());
        return lines;
    }

    /** 右侧问题面板：列出全部错误/提醒，支持定位、修复、推送缺失标签。 */
    private void renderIssues(GuiGraphics g, int mx, int my) {
        int x = canvasX + canvasW + 10;
        int y = canvasY;
        int w = ISSUES_W;
        int h = canvasH;
        if (w <= 40 || h <= 40) return;

        // 面板背景吞掉点击，避免穿透到画布起框选
        uiHits.add(hit(x, y, w, h, K_CLICK, null, () -> { }));

        rounded(g, x + 2, y + 3, w, h, 8, G_SHADOW);
        rounded(g, x, y, w, h, 8, G_CARD_TRANS);
        border(g, x, y, w, h, G_BORDER);

        long errors = issueErrCount;
        long warnings = issueWarnCount;

        // 标题行 + 关闭按钮
        int ty = y + 8;
        text(g, T_ISSUES_TITLE.getString(), x + 10, ty, C_TEXT);
        String count = errors > 0
                ? "错误 " + errors + (warnings > 0 ? " · 提醒 " + warnings : "")
                : warnings > 0 ? "提醒 " + warnings : "";
        if (!count.isEmpty()) {
            String counter = "  " + count;
            text(g, counter, x + 10 + this.font.width(T_ISSUES_TITLE.getString()), ty, errors > 0 ? C_ERR : C_WARN);
        }
        int closeW = 18;
        int closeX = x + w - closeW - 4;
        button(g, closeX, y + 3, closeW, 16, "✕", 0xCC5B6472, 0xCC49525E, () -> issuesOpen = false, mx, my);
        if (!issuesCache.isEmpty()) {
            button(g, closeX - 46, y + 3, 21, 16, "◀", 0xCC5B6472, 0xCC49525E, () -> stepIssue(-1), mx, my);
            button(g, closeX - 23, y + 3, 21, 16, "▶", 0xCC5B6472, 0xCC49525E, () -> stepIssue(1), mx, my);
        }
        ty += 18;

        int innerX = x + 8;
        int innerW = w - 16;

        // 有未绑定标签问题时，提供一键推送到标签枪（复用现有网络包）
        boolean missingLabels = issueMissingLabels;
        if (missingLabels) {
            String label = T_ISSUES_PUSH_LABELS.getString();
            int bw = Math.min(innerW, this.font.width(label) + 12);
            button(g, innerX, ty, bw, 16, label, 0xCC2563EB, 0xCC1D4ED8, () ->
                    SFMGuiNetwork.sendToServerBestEffort(
                            new io.github.xianynomial.sfmfactorystudio.net.PullLabelsPayload(menu.MANAGER_POSITION)), mx, my);
            ty += 20;
        }

        if (issuesCache.isEmpty()) {
            text(g, T_ISSUES_NONE.getString(), innerX, ty + 4, 0xFF0C8F58);
            return;
        }

        // 滚动区（折行只在问题列表或面板宽度变化时重算，其余帧直接复用）
        int listTop = ty;
        int listBottom = y + h - 6;
        if (issuesWrapped == null || issuesWrappedWidth != innerW - 14) {
            List<List<String>> computed = new ArrayList<>(issuesCache.size());
            for (ProgramDiagnostics.Issue issue : issuesCache) {
                computed.add(wrapText(issue.message(), innerW - 14));
            }
            issuesWrapped = computed;
            issuesWrappedWidth = innerW - 14;
        }
        List<List<String>> wrapped = issuesWrapped;
        int contentH = 0;
        for (List<String> lines : wrapped) {
            contentH += 6 + lines.size() * 10 + 18 + 6;
        }
        int maxScroll = Math.max(0, contentH - (listBottom - listTop));
        issuesScroll = Math.max(0, Math.min(issuesScroll, maxScroll));

        g.enableScissor(x + 1, listTop - 2, x + w - 1, listBottom);
        try {
            int cy = listTop - issuesScroll;
            for (int i = 0; i < issuesCache.size(); i++) {
                ProgramDiagnostics.Issue issue = issuesCache.get(i);
                List<String> lines = wrapped.get(i);
                int rowH = 6 + lines.size() * 10 + 18 + 6;
                if (cy + rowH > listTop - 6 && cy < listBottom) {
                    boolean error = issue.severity() == ProgramDiagnostics.Severity.ERROR;
                    int color = error ? C_ERR : C_WARN;
                    rounded(g, innerX - 2, cy, innerW + 4, rowH - 6, 5, error ? 0x14D13438 : 0x14B45309);
                    // 严重度圆点
                    g.fill(innerX, cy + 6, innerX + 6, cy + 12, color);
                    int ly = cy + 5;
                    for (String line : lines) {
                        text(g, line, innerX + 12, ly, C_TEXT);
                        ly += 10;
                    }
                    int btnY = cy + 5 + lines.size() * 10 + 2;
                    boolean canLocate = issue.block() != null;
                    // 行背景命中先注册（后注册的按钮命中在分发时优先），整行点击 = 定位
                    uiHits.add(hit(innerX - 2, cy, innerW + 4, rowH - 6, K_CLICK, null,
                            canLocate ? () -> locateIssue(issue) : () -> { }));
                    if (canLocate) {
                        button(g, innerX + 12, btnY, 36, 14, T_ISSUES_LOCATE.getString(), 0xCC5B6472,
                                0xCC49525E, () -> locateIssue(issue), mx, my);
                    }
                    if (issue.fix() != null) {
                        button(g, innerX + 12 + (canLocate ? 40 : 0), btnY, 36, 14, T_ISSUES_FIX.getString(),
                                0xE60FA968, 0xE60C8F58, () -> applyIssueFix(issue), mx, my);
                    }
                }
                cy += rowH;
            }
        } finally {
            g.disableScissor();
        }
        if (contentH > listBottom - listTop) {
            int barH = Math.max(18, (listBottom - listTop) * (listBottom - listTop) / contentH);
            int barY = listTop + (listBottom - listTop - barH) * issuesScroll / Math.max(1, maxScroll);
            g.fill(x + w - 4, barY, x + w - 2, barY + barH, 0x662F6FED);
        }
    }

    private void closeEditor() {
        requestClose();
    }

    private void requestClose() {
        if (!dirty) {
            performClose();
            return;
        }
        showStatus("有未保存的修改，请先选择如何退出", 0xFFB45309);
        setPopup(new Popup.DecisionPopup(
                panelX + panelW / 2 - 160,
                panelY + panelH / 2 - 34,
                320,
                "有未保存的修改",
                "要先保存到管理器吗？本地草稿也会自动保留。",
                List.of("save_exit", "discard_exit", "continue"),
                List.of("保存并退出", "不保存退出", "继续编辑"),
                action -> {
                    switch (action) {
                        case "save_exit" -> {
                            if (save()) performClose();
                        }
                        case "discard_exit" -> {
                            dirty = false;
                            draftSaveDelay = -1;
                            clearDraft();
                            performClose();
                        }
                        default -> showStatus("已继续编辑；未保存内容会保留为本地草稿", C_SELECT);
                    }
                }));
    }

    private void performClose() {
        closingWithoutPrompt = true;
        saveLayouts();
        if (onCloseRequest != null) {
            onCloseRequest.run();
        } else {
            Minecraft.getInstance().setScreen(null);
        }
    }

    // ==================================================================== layout

    /**
     * 布局委托给 {@link EditorLayout}（按稳定 id 每卡缓存）：脏卡重排、
     * 挪过的卡整体平移缓存行、避让只在非拖动状态下对卡级矩形求解。
     */
    private void relayout() {
        long t0 = debugPerf ? System.nanoTime() : 0L;
        layout.setCollapsedCards(collapsedCards);
        layout.setCollapsedIfs(collapsedIfs);
        layout.relayout(dragTrigger != null, keepPosTrigger == null ? -1 : keepPosTrigger.id);
        if (debugPerf) perfLayoutNanos = System.nanoTime() - t0;
        layoutDirty = false;
    }

    // ---- content <-> screen transforms ------------------------------------------

    private int sX(int contentX) {
        return canvasX + CANVAS_PAD + Math.round((contentX - viewX) * zoom);
    }

    private int sY(int contentY) {
        return canvasY + CANVAS_PAD + Math.round((contentY - viewY) * zoom);
    }

    private double ctX(double screenX) {
        return (screenX - canvasX - CANVAS_PAD) / zoom + viewX;
    }

    private double ctY(double screenY) {
        return (screenY - canvasY - CANVAS_PAD) / zoom + viewY;
    }

    /**
     * 注册一个 JEI ghost-drag 落点区域（入参是 content 坐标）。
     *
     * 两处关键处理，缺一个就会出现"JEI 物品图层浮在最上面、盖住别的东西"：
     * 1) 尺寸要乘 zoom —— 资源框在屏幕上的实际大小是 size*zoom。此前直接把
     *    content 尺寸当屏幕尺寸传给 JEI，落点判定区跟资源框不重合：缩小画布
     *    时判定区比资源框大一圈，吸附高亮框就压在旁边的元素上；放大时又比
     *    资源框小，拖到框边缘反而吸不上。
     * 2) 区域裁进画布可视矩形 —— 卡片折叠、平移出视野、缩到 LOD 以下时资源
     *    框并不在屏幕上，此时不该留落点，否则 overlay 会盖住折叠后的卡片摘要。
     */
    private void addGhostZone(int contentX, int contentY, int contentW, int contentH,
                              String current, Consumer<String> setter) {
        int sw = Math.max(1, Math.round(contentW * zoom));
        int sh = Math.max(1, Math.round(contentH * zoom));
        addGhostZoneScreen(sX(contentX), sY(contentY), sw, sh, current, setter);
    }

    /**
     * 屏幕坐标版：弹窗内的 pill 用（它们本来就在屏幕坐标系里，没有 zoom 变换）。
     * 同样裁进画布，并且被裁到几乎不可见时直接不注册——折叠/收起后不留残影。
     */
    private void addGhostZoneScreen(int sx, int sy, int sw, int sh,
                                    String current, Consumer<String> setter) {
        if (canvasW <= 0 || canvasH <= 0) return;
        int x0 = Math.max(sx, canvasX);
        int y0 = Math.max(sy, canvasY);
        int x1 = Math.min(sx + sw, canvasX + canvasW);
        int y1 = Math.min(sy + sh, canvasY + canvasH);
        if (x1 - x0 < 4 || y1 - y0 < 4) return; // 看不见就不占落点
        JeiGhostDrops.add(new Rect2i(x0, y0, x1 - x0, y1 - y0), current, setter);
    }

    private void fitContent() {
        float zx = (canvasW - 16f) / Math.max(1, layout.contentW());
        float zy = (canvasH - 16f) / Math.max(1, layout.contentH());
        zoom = Math.max(0.1f, Math.min(1.25f, Math.min(zx, zy)));
        viewX = Math.round(layout.contentMinX() + layout.contentW() / 2f - canvasW / (2f * zoom));
        viewY = Math.round(layout.contentMinY() + layout.contentH() / 2f - canvasH / (2f * zoom));
    }

    /** Conservative viewport test in logical content coordinates. */
    private boolean contentVisible(int x, int y, int w, int h) {
        double safeZoom = Math.max(0.01f, zoom);
        double margin = 32.0 / safeZoom;
        double left = viewX - margin;
        double top = viewY - margin;
        double right = viewX + canvasW / safeZoom + margin;
        double bottom = viewY + canvasH / safeZoom + margin;
        return x < right && x + w > left && y < bottom && y + h > top;
    }

    // ==================================================================== input

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (popup != null) {
            // Popup actions may replace or directly close themselves. Inspect
            // the instance that actually received the click so a replacement
            // is preserved and a null replacement cannot crash this handler.
            Popup clickedPopup = popup;
            boolean consumed = clickedPopup.mouseClicked(mx, my, button);
            if (popup == clickedPopup && !clickedPopup.keepOpen) popup = null;
            if (consumed) return true;
        }
        if (!inPanel(mx, my)) return true; // clicks outside the window do nothing

        if (codeEditor != null && codeEditor.isFocused()
                && (mx < codeEditor.getX() || mx >= codeEditor.getX() + codeEditor.getWidth()
                || my < codeEditor.getY() || my >= codeEditor.getY() + codeEditor.getHeight())) {
            codeEditor.setFocused(false);
            codeEditor.clearSuggestions();
        }

        if (super.mouseClicked(mx, my, button)) {
            if (codeEditor != null && codeEditor.isFocused()) codeSuggestDelay = 2;
            return true;
        }

        // Palette / toolbar (screen coords) — last registered wins (topmost).
        // Every registered UI target with an action is a real button regardless
        // of its visual/debug kind. Restricting this to K_CLICK made all K_AB
        // selection-toolbar actions silently fall through into the canvas.
        for (int i = uiHits.size() - 1; i >= 0; i--) {
            Hit h = uiHits.get(i);
            if (in(h, mx, my)) {
                if (h.kind == K_PALETTE) {
                    pendingPalette = (String) h.data;
                    pressX = mx;
                    pressY = my;
                    return true;
                }
                if (h.onClick != null) h.onClick.run();
                // Registered UI owns its pixels even when intentionally inert;
                // never start a canvas selection from the same mouse press.
                return true;
            }
        }

        double cx = ctX(mx), cy = ctY(my);

        // Ctrl+左键：在任意位置（包括积木上）起框，不被命中区抢走
        boolean overCanvas = mx >= canvasX && mx < canvasX + canvasW
                && my >= canvasY && my < canvasY + canvasH;
        // 中键或右键可从画布任意位置开始平移，即使光标正位于积木上。
        if (overCanvas && button != 0) {
            startPanning(mx, my);
            return true;
        }
        if (button == 0 && overCanvas && hasControlDown()) {
            bandSelecting = true;
            bandX1 = bandX2 = (int) cx;
            bandY1 = bandY2 = (int) cy;
            pressX = mx;
            pressY = my;
            return true;
        }

        // canvas hits — reverse order so fields beat their row's grip.
        // A press on a block row always drags, never rubber-bands. A press on
        // a card's empty body or its header targets that body AND arms the
        // rubber band, so selection still works when cards fill the canvas.
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit h = hits.get(i);
            if (in(h, cx, cy)) {
                if (h.kind == K_RCLICK) {
                    if (button == 2) {
                        h.onClick.run();
                        return true;
                    }
                    continue; // 左键/中键穿透到普通命中
                }
                if (h.kind == K_GRIP && button == 0) {
                    if (hasShiftDown()) {
                        // Shift+点击行：加选/减选，不进入拖动
                        BProgram.Statement row = ((DragRef) h.data).statement();
                        if (!selection.remove(row)) selection.add(row);
                        return true;
                    }
                    startBlockDrag(h.data, cx, cy);
                    return true;
                }
                if (h.kind == K_BODY_SEL) {
                    // 卡片内永不框选（用户拍板 2026-09-02）：点击只记住"放入积木"的目标
                    selectedBody = (List<BProgram.Statement>) h.data;
                    return true;
                }
                if (h.kind == K_HEAD) {
                    BProgram.Trigger t = (BProgram.Trigger) h.data;
                    selectedBody = t.body;
                    if (button == 0) {
                        if (hasShiftDown()) {
                            // Shift+点击头部：加选 / 减选，不进入拖动
                            if (selectedTriggers.contains(t)) selectedTriggers.remove(t);
                            else selectedTriggers.add(t);
                            return true;
                        }
                        pressX = mx;
                        pressY = my;
                        startTriggerDrag(t, cx, cy);
                    }
                    return true;
                }
                if (h.onClick != null) {
                    h.onClick.run();
                    return true;
                }
            }
        }

        // empty canvas: arm rubber band (left) or pan (any button drag)
        if (mx >= canvasX && mx < canvasX + canvasW && my >= canvasY && my < canvasY + canvasH) {
            if (button == 0 && zoneDrawing) {
                zoneDrag = true;
                zoneX1 = zoneX2 = (int) cx;
                zoneY1 = zoneY2 = (int) cy;
                pressX = mx;
                pressY = my;
                return true;
            }
            if (button == 0) {
                bandSelecting = true;
                bandX1 = bandX2 = (int) cx;
                bandY1 = bandY2 = (int) cy;
            } else {
                startPanning(mx, my);
            }
            pressX = mx;
            pressY = my;
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    private boolean inPanel(double mx, double my) {
        return mx >= panelX && mx < panelX + panelW && my >= panelY && my < panelY + panelH;
    }

    private static boolean in(Hit h, double x, double y) {
        return x >= h.x && x < h.x + h.w && y >= h.y && y < h.y + h.h;
    }

    private void startBlockDrag(Object data, double cx, double cy) {
        if (data instanceof DragRef d) {
            List<BProgram.Statement> group = new ArrayList<>();
            if (selection.contains(d.statement())) {
                group.addAll(orderedSelection());
            } else {
                selection.clear();
                selection.add(d.statement());
                group.add(d.statement());
            }
            pushUndo(); // one undo entry per drag
            for (BProgram.Statement s : group) {
                removeFromAll(s); // detach right away: the block travels with the cursor
            }
            dragGroup = new GroupDrag(group, d.list(), d.index(), d.label(), d.accent());
            mouseX = cx;
            mouseY = cy;
            dropGap = layout.nearestGap(cx, cy);
            insertGroupAt(dropGap); // place it immediately
        }
    }

    /**
     * 开始拖动触发器卡片（方案 A：卡片有自由坐标，可拖到画布任意位置）。
     * 只置状态、不 pushUndo —— 要等光标越过 DRAG_THRESHOLD 才真正记一次撤销点，
     * 否则点一下卡片就多出一条无意义的撤销记录。
     */
    private void startTriggerDrag(BProgram.Trigger t, double cx, double cy) {
        dragCards.clear();
        dragStart.clear();
        // 选中了多张卡时抓任意一张，整组跟着走（Scratch 语义）
        if (selectedTriggers.size() > 1 && selectedTriggers.contains(t)) {
            dragCards.addAll(selectedTriggers);
        } else {
            selectedTriggers.clear();
            selectedTriggers.add(t);
            dragCards.add(t);
        }
        int[] p = layout.cardPosOf(t.id);
        int px = p == null ? 0 : p[0];
        int py = p == null ? 0 : p[1];
        dragGrabDX = (int) Math.round(cx) - px;
        dragGrabDY = (int) Math.round(cy) - py;
        for (BProgram.Trigger d : dragCards) {
            int[] q = layout.cardPosOf(d.id);
            dragStart.put(d, new int[]{q == null ? 0 : q[0], q == null ? 0 : q[1]});
        }
        dragMoved = false;
        dragUndoPushed = false;
        dragTrigger = t;
        keepPosTrigger = t;   // 拖动中不被避让逻辑推走
    }

    /**
     * Moves the dragged group into the gap, removing it from any previous spot.
     * 拖拽帧热路径：只把被抽出/插入的 body 标脏，其余卡的布局缓存原样复用。
     */
    private void insertGroupAt(@Nullable Gap g) {
        if (g == null || dragGroup == null) return;
        List<List<BProgram.Statement>> touched = new ArrayList<>(2);
        for (BProgram.Statement s : dragGroup.all()) {
            List<BProgram.Statement> from = removeFromAll(s); // idempotent before the first insert
            if (from != null && !touched.contains(from)) touched.add(from);
        }
        List<BProgram.Statement> dst = g.body().list();
        int idx = Math.max(0, Math.min(g.index(), dst.size()));
        for (BProgram.Statement s : dragGroup.all()) {
            dst.add(idx, s);
            idx++;
        }
        for (List<BProgram.Statement> body : touched) layout.markBodyDirty(body);
        layout.markBodyDirty(dst);
        layoutDirty = true;
    }

    private record DragRef(List<BProgram.Statement> list, BProgram.Statement statement, int index, String label, int accent) {
    }

    /** Selection in document order; skips statements nested under a selected If. */
    private List<BProgram.Statement> orderedSelection() {
        List<BProgram.Statement> out = new ArrayList<>();
        for (BProgram.Trigger t : program.triggers) {
            collectSelected(t.body, out);
        }
        return out;
    }

    private void collectSelected(List<BProgram.Statement> list, List<BProgram.Statement> out) {
        for (BProgram.Statement s : list) {
            if (selection.contains(s)) {
                out.add(s);
                if (s instanceof BProgram.Statement.If iff) {
                    for (BProgram.Branch b : iff.branches) selection.removeAll(b.body);
                    selection.removeAll(iff.elseBody);
                }
            } else if (s instanceof BProgram.Statement.If iff) {
                for (BProgram.Branch b : iff.branches) collectSelected(b.body, out);
                collectSelected(iff.elseBody, out);
            }
        }
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (pendingPalette != null && dragPaletteKind == null
                && Math.abs(mx - pressX) + Math.abs(my - pressY) > 6) {
            dragPaletteKind = pendingPalette;
            pendingPalette = null;
        }
        double cx = ctX(mx), cy = ctY(my);
        if (zoneDrag) {
            zoneX2 = (int) cx;
            zoneY2 = (int) cy;
            return true;
        }
        if (linkDragFrom >= 0) {
            linkDragX = cx;
            linkDragY = cy;
            return true;
        }
        if (dragGroup != null) {
            mouseX = cx;
            mouseY = cy;
            Gap g = layout.nearestGap(cx, cy);
            if (g != dropGap) {
                dropGap = g;
                insertGroupAt(g); // the block really travels through the list live
            }
            return true;
        }
        if (dragPaletteKind != null) {
            mouseX = cx;
            mouseY = cy;
            dropGap = layout.nearestGap(cx, cy);
            return true;
        }
        if (dragTrigger != null) {
            // 方案 A：卡片按自由坐标跟着光标走，不再做"越过中心线才换序"的判定
            // （旧逻辑在只有一张卡时 idx 恒等于 cur，所以完全拖不动）。
            if (!dragMoved) {
                if (Math.abs(mx - pressX) + Math.abs(my - pressY) < DRAG_THRESHOLD) return true;
                dragMoved = true;
            }
            if (!dragUndoPushed) {
                pushUndo();   // 一次拖动 = 一条撤销记录
                dragUndoPushed = true;
            }
            int[] base = dragStart.get(dragTrigger);
            if (base == null) return true;
            int nx = CardLayouts.snap((int) Math.round(cx) - dragGrabDX);
            int ny = CardLayouts.snap((int) Math.round(cy) - dragGrabDY);
            int[] self = layout.cardRectOf(dragTrigger.id);
            int[] snapped = snapCardPos(dragTrigger, nx, ny, self == null ? 120 : self[3]);
            nx = snapped[0];
            ny = snapped[1];
            int mdx = nx - base[0];
            int mdy = ny - base[1];
            for (BProgram.Trigger d : dragCards) {
                int[] s = dragStart.get(d);
                if (s == null) continue;
                layout.setCardPos(d.id, s[0] + mdx, s[1] + mdy);
            }
            layoutDirty = true;
            return true;
        }
        if (bandSelecting) {
            bandX2 = (int) cx;
            bandY2 = (int) cy;
            liveBandSelect(); // selection updates in real time while dragging
            return true;
        }
        if (panning) {
            if (Math.abs(mx - pressX) + Math.abs(my - pressY) > DRAG_THRESHOLD) panMoved = true;
            viewX = panStartViewX - (int) Math.round((mx - pressX) / zoom);
            viewY = panStartViewY - (int) Math.round((my - pressY) / zoom);
            return true;
        }
        boolean handled = super.mouseDragged(mx, my, button, dx, dy);
        if (handled && codeEditor != null && codeEditor.isFocused()) codeSuggestDelay = 2;
        return handled;
    }

    private void startPanning(double mx, double my) {
        panning = true;
        panMoved = false;
        pressX = mx;
        pressY = my;
        panStartViewX = viewX;
        panStartViewY = viewY;
    }

    /**
     * 紧贴对齐：在阈值内选择距离最近的吸附点。完全贴平（x）与正上/正下方（y）
     * 记录贴合指示线；同列/顶对齐是无指示线的轻吸附。返回 [x, y]。
     */
    private int[] snapCardPos(BProgram.Trigger grabbed, int nx, int ny, int selfH) {
        snapGuideX = -1;
        snapGuideY = -1;
        int bestX = nx, bestXDist = SNAP_PX + 1;
        int bestY = ny, bestYDist = SNAP_PX + 1;
        CardL partnerX = null, partnerY = null;   // 完全贴平的伙伴卡（轻对齐为 null）
        for (CardL c : layout.cards()) {
            if (dragCards.contains(c.trigger())) continue;
            int d;
            d = c.x() + c.w() - nx;                       // 我的左缘贴对方右缘
            if (Math.abs(d) < Math.abs(bestXDist)) { bestXDist = d; bestX = nx + d; partnerX = c; }
            d = c.x() - CARD_W - nx;                      // 我的右缘贴对方左缘
            if (Math.abs(d) < Math.abs(bestXDist)) { bestXDist = d; bestX = nx + d; partnerX = c; }
            d = c.x() - nx;                               // 同列对齐（轻）
            if (Math.abs(d) < Math.abs(bestXDist)) { bestXDist = d; bestX = nx + d; partnerX = null; }
            d = c.y() + c.h() - ny;                        // 我紧贴对方正下方
            if (Math.abs(d) < Math.abs(bestYDist)) { bestYDist = d; bestY = ny + d; partnerY = c; }
            d = c.y() - selfH - ny;                        // 我紧贴对方正上方
            if (Math.abs(d) < Math.abs(bestYDist)) { bestYDist = d; bestY = ny + d; partnerY = c; }
            d = c.y() - ny;                               // 顶对齐（轻）
            if (Math.abs(d) < Math.abs(bestYDist)) { bestYDist = d; bestY = ny + d; partnerY = null; }
        }
        if (Math.abs(bestXDist) > SNAP_PX) { bestX = nx; partnerX = null; }
        if (Math.abs(bestYDist) > SNAP_PX) { bestY = ny; partnerY = null; }
        if (partnerX != null) {
            snapGuideX = bestX == partnerX.x() + partnerX.w() ? bestX : bestX + CARD_W;
            snapGuideY1 = Math.min(bestY, partnerX.y()) - 4;
            snapGuideY2 = Math.max(bestY + selfH, partnerX.y() + partnerX.h()) + 4;
        }
        if (partnerY != null) {
            snapGuideY = bestY == partnerY.y() + partnerY.h() ? bestY : bestY + selfH;
            snapGuideXL = Math.min(bestX, partnerY.x());
            snapGuideXR = Math.max(bestX + CARD_W, partnerY.x() + partnerY.w());
        }
        return new int[]{bestX, bestY};
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        double cx = ctX(mx), cy = ctY(my);

        if (zoneDrag) {
            zoneDrag = false;
            zoneDrawing = false;
            int x1 = Math.min(zoneX1, zoneX2), x2 = Math.max(zoneX1, zoneX2);
            int y1 = Math.min(zoneY1, zoneY2), y2 = Math.max(zoneY1, zoneY2);
            if (x2 - x1 >= 80 && y2 - y1 >= 56) {
                // 松手即生成：默认名 + 自动轮换颜色，零弹窗（用户拍板 2026-09-02）
                String name = "工作区 " + (zones.size() + 1);
                zones.add(new Zone(name, zones.size() % 3, x1, y1, x2 - x1, y2 - y1));
                saveLayouts();
                showStatus("✔ 分区「" + name + "」已创建：点标题改名 · 点顶栏聚焦 · 色块换色", 0xFF0C8F58);
            } else {
                showStatus("区域太小，已取消创建分区", 0xFFB45309);
            }
            return true;
        }
        if (linkDragFrom >= 0) {
            long from = linkDragFrom;
            linkDragFrom = -1;
            // 落到任意一张其他卡上即连接（自动吸附最近的一面），落空取消
            for (CardL c : layout.cards()) {
                if (c.trigger().id == from) continue;
                if (cx >= c.x() && cx < c.x() + c.w() && cy >= c.y() && cy < c.y() + c.h()) {
                    links.add(new BlockLink(from, c.trigger().id));
                    saveLayouts();
                    showStatus("已连接（仅备注，不影响程序运行）；点连线中点 ✕ 删除", C_SELECT);
                    return true;
                }
            }
            return true;
        }
        if (dragGroup != null) {
            if (dropGap == null) {
                // released over empty space: put the group back where it was
                // grabbed from — never append via targetBody(), which would
                // park it in the first trigger and duplicate it across lists
                List<BProgram.Statement> dst = dragGroup.firstList();
                int idx = dragGroup.firstIndex();
                for (BProgram.Statement s : dragGroup.all()) removeFromAll(s);
                int i = Math.max(0, Math.min(idx, dst.size()));
                for (BProgram.Statement s : dragGroup.all()) {
                    dst.add(i, s);
                    i++;
                }
                layout.markBodyDirty(dst);
                layoutDirty = true;
            }
            dragGroup = null;
            dropGap = null;
            return true;
        }
        if (dragTrigger != null) {
            if (dragMoved) {
                keepPosTrigger = dragTrigger;  // 落位后由别的卡让路，别把刚放好的推走
            } else {
                keepPosTrigger = null;         // 只是点了一下：恢复避让，让重叠自动分开
            }
            layoutDirty = true;
            dragTrigger = null;
            dragCards.clear();
            dragStart.clear();
            dragMoved = false;
            dragUndoPushed = false;
            return true;
        }
        if (dragPaletteKind != null) {
            dropPalette(dragPaletteKind, cx, cy);
            dragPaletteKind = null;
            dropGap = null;
            return true;
        }
        if (bandSelecting) {
            bandSelecting = false;
            applyBandSelection();
            if (!selection.isEmpty() || !selectedTriggers.isEmpty()) {
                showActionBar((int) mx, (int) my);
            }
            return true;
        }
        if (pendingPalette != null) {
            String kind = pendingPalette;
            pendingPalette = null;
            clickAdd(kind);
            return true;
        }
        if (panning) {
            panning = false;
            if (!panMoved) openContextMenu(mx, my);
            return true; // 平移/右键菜单的收尾不冒泡给控件
        }
        return super.mouseReleased(mx, my, button);
    }

    /**
     * 右键原地点击：卡片内=复制/粘贴/删除（自动选中该卡）；卡片外=仅粘贴。
     * 不放撤销/重做（工具栏已有，用户拍板 2026-09-02）。
     */
    private void openContextMenu(double mx, double my) {
        double ccx = ctX(mx), ccy = ctY(my);
        BProgram.Trigger over = null;
        for (CardL c : layout.cards()) {
            if (ccx >= c.x() && ccx < c.x() + c.w() && ccy >= c.y() && ccy < c.y() + c.h()) {
                over = c.trigger();
                break;
            }
        }
        List<String> values = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        boolean hasClipboard = clipboardSfml != null && !clipboardSfml.isBlank();
        if (hasClipboard) {
            values.add("paste");
            labels.add("粘贴");
        }
        if (over == null) {
            values.add("new_timer");
            labels.add("新建定时触发器");
            values.add("new_pulse");
            labels.add("新建脉冲触发器");
        }
        if (over != null) {
            if (!selectedTriggers.contains(over)) {
                selection.clear();
                selectedTriggers.clear();
                selectedTriggers.add(over);
            }
            values.add("copy");
            labels.add("复制");
            values.add("delete");
            labels.add("删除");
        }
        if (values.isEmpty()) return;
        setPopup(new Popup.ChoicePopup((int) mx - 30, (int) my - 10, 116, values, labels, "", action -> {
            switch (action) {
                case "paste" -> pasteClipboard();
                case "copy" -> copySelection();
                case "delete" -> deleteSelection();
                case "new_timer" -> createCardAt("timer", ccx, ccy);
                case "new_pulse" -> createCardAt("pulse", ccx, ccy);
            }
        }));
    }

    private @Nullable List<BProgram.Statement> removeFromAll(BProgram.Statement s) {
        for (BProgram.Trigger t : program.triggers) {
            List<BProgram.Statement> from = removeFromBody(t.body, s);
            if (from != null) return from;
        }
        return null;
    }

    private @Nullable List<BProgram.Statement> removeFromBody(List<BProgram.Statement> list, BProgram.Statement s) {
        for (int i = 0; i < list.size(); i++) {
            BProgram.Statement cur = list.get(i);
            if (cur == s) {
                list.remove(i);
                return list;
            }
            if (cur instanceof BProgram.Statement.If iff) {
                for (BProgram.Branch b : iff.branches) {
                    List<BProgram.Statement> from = removeFromBody(b.body, s);
                    if (from != null) return from;
                }
                List<BProgram.Statement> from = removeFromBody(iff.elseBody, s);
                if (from != null) return from;
            }
        }
        return null;
    }

    private void applyBandSelection() {
        int x1 = Math.min(bandX1, bandX2), x2 = Math.max(bandX1, bandX2);
        int y1 = Math.min(bandY1, bandY2), y2 = Math.max(bandY1, bandY2);
        if (x2 - x1 < 4 && y2 - y1 < 4) {
            selection.clear(); // plain click on empty space deselects
            selectedTriggers.clear();
            focusedZone = null; // 退出聚焦，恢复显示全部卡片
            return;
        }
        liveBandSelect();
    }

    /** Recomputes the selection from the current band rectangle (called live). */
    private void liveBandSelect() {
        int x1 = Math.min(bandX1, bandX2), x2 = Math.max(bandX1, bandX2);
        int y1 = Math.min(bandY1, bandY2), y2 = Math.max(bandY1, bandY2);
        selection.clear();
        selectedTriggers.clear();
        // 空间过滤：行矩形都在卡片矩形内，卡不交叠 ⇒ 行必不交叠，
        // 只需遍历与框选矩形相交的卡（帽子语义：碰到卡的任意部分=整卡入选）。
        for (CardL c : layout.cards()) {
            if (c.x() < x2 && c.x() + c.w() > x1 && c.y() < y2 && c.y() + c.h() > y1) {
                selectInBody(c.trigger().body, x1, y1, x2, y2);
                selectedTriggers.add(c.trigger());
            }
        }
        for (BProgram.Trigger t : selectedTriggers) {
            stripStatements(t.body);
        }
    }

    private void stripStatements(List<BProgram.Statement> list) {
        for (BProgram.Statement s : list) {
            selection.remove(s);
            if (s instanceof BProgram.Statement.If iff) {
                for (BProgram.Branch b : iff.branches) stripStatements(b.body);
                stripStatements(iff.elseBody);
            }
        }
    }

    private void selectInBody(List<BProgram.Statement> list, int x1, int y1, int x2, int y2) {
        for (BProgram.Statement s : list) {
            int[] r = layout.rowRectOf(s.id);
            if (r == null) continue;
            if (r[0] < x2 && r[0] + r[2] > x1 && r[1] < y2 && r[1] + r[3] > y1) {
                selection.add(s);
                if (s instanceof BProgram.Statement.If) continue; // whole if only
            }
            if (s instanceof BProgram.Statement.If iff) {
                for (BProgram.Branch b : iff.branches) selectInBody(b.body, x1, y1, x2, y2);
                selectInBody(iff.elseBody, x1, y1, x2, y2);
            }
        }
    }

    // ---- palette actions --------------------------------------------------------

    private void clickAdd(String kind) {
        pushUndo();
        switch (kind) {
            case "timer" -> program.triggers.add(newTriggerCard("timer"));
            case "pulse" -> program.triggers.add(newTriggerCard("pulse"));
            case "input" -> addInheriting(targetBody(), newInput());
            case "output" -> addInheriting(targetBody(), newOutput());
            case "energy" -> program.triggers.add(newTriggerCard("energy"));
            case "forget" -> targetBody().add(new BProgram.Statement.Forget());
            case "if" -> targetBody().add(newIf());
            case "comment" -> targetBody().add(new BProgram.Statement.Comment("备注"));
            case "tpl_smelt" -> templateSmelt();
            case "tpl_sort" -> templateSort();
            case "tpl_even" -> templateEven();
            case "tpl_fast" -> templateFast();
            default -> {
                if (kind.startsWith("mytpl:")) {
                    insertTemplate(kind.substring(6));
                }
            }
        }
    }

    private void dropPalette(String kind, double cx, double cy) {
        if (kind.equals("timer") || kind.equals("pulse") || kind.equals("energy")) {
            pushUndo();
            BProgram.Trigger t = newTriggerCard(kind);
            program.triggers.add(t);
            // 新卡落在鼠标位置，不再自动堆到最下方
            layout.setCardPos(t.id, CardLayouts.snap((int) cx), CardLayouts.snap((int) cy));
            layoutDirty = true;
            return;
        }
        if (kind.startsWith("mytpl:")) {
            // templates always append to the target body
            clickAdd(kind);
            return;
        }
        Gap g = layout.nearestGap(cx, cy);
        if (g != null) {
            pushUndo();
            List<BProgram.Statement> list = g.body().list();
            BProgram.Statement built = buildBlock(kind);
            int at = Math.max(0, Math.min(g.index(), list.size()));
            inheritAccess(list, at, built);
            list.add(at, built);
            layoutDirty = true;
        } else if (inCanvas(cx, cy)) {
            // 空白画布：在鼠标位置新建一张卡装这块积木（不再随机塞进别的卡）
            pushUndo();
            BProgram.TimerTrigger t = new BProgram.TimerTrigger();
            t.body.add(buildBlock(kind));
            program.triggers.add(t);
            layout.setCardPos(t.id, CardLayouts.snap((int) cx), CardLayouts.snap((int) cy));
            layoutDirty = true;
        }
    }

    /** 新建触发器卡：定时/脉冲自动带「从方块取出 + 放入方块」骨架；能量模板自带结构。 */
    private BProgram.Trigger newTriggerCard(String kind) {
        if (kind.equals("energy")) return newEnergyTrigger();
        BProgram.Trigger t = kind.equals("pulse") ? new BProgram.PulseTrigger() : new BProgram.TimerTrigger();
        t.body.add(newInput());
        t.body.add(newOutput());
        return t;
    }

    /** 插入语句并继承同列表里上一条取出/存入的标签与侧面。 */
    private void addInheriting(List<BProgram.Statement> list, BProgram.Statement stmt) {
        inheritAccess(list, list.size(), stmt);
        list.add(stmt);
    }

    /**
     * 新放的取出/存入自动继承同一列表中上一条取出/存入的 标签+侧面+逐面
     * （流水线搭建最常见的重复设置）；找不到就不动。不继承 槽位/轮流/分别处理。
     */
    private void inheritAccess(List<BProgram.Statement> body, int index, BProgram.Statement inserted) {
        if (!(inserted instanceof BProgram.Statement.Input) && !(inserted instanceof BProgram.Statement.Output)) return;
        for (int i = index - 1; i >= 0; i--) {
            BProgram.LabelAccess src;
            BProgram.Statement prev = body.get(i);
            if (prev instanceof BProgram.Statement.Input p) src = p.access;
            else if (prev instanceof BProgram.Statement.Output p) src = p.access;
            else continue;
            BProgram.LabelAccess dst = inserted instanceof BProgram.Statement.Input in ? in.access
                    : ((BProgram.Statement.Output) inserted).access;
            dst.labels.clear();
            dst.labels.addAll(src.labels);
            dst.sides.clear();
            dst.sides.addAll(src.sides);
            dst.eachSide = src.eachSide;
            return;
        }
    }

    private BProgram.Statement buildBlock(String kind) {
        return switch (kind) {
            case "input" -> newInput();
            case "output" -> newOutput();
            case "forget" -> new BProgram.Statement.Forget();
            case "if" -> newIf();
            case "comment" -> new BProgram.Statement.Comment("备注");
            default -> new BProgram.Statement.Comment("备注");
        };
    }

    private boolean inCanvas(double cx, double cy) {
        double sx = canvasX + CANVAS_PAD + (cx - viewX) * zoom;
        double sy = canvasY + CANVAS_PAD + (cy - viewY) * zoom;
        return sx >= canvasX && sx < canvasX + canvasW && sy >= canvasY && sy < canvasY + canvasH;
    }

    // ---- one-click templates ----------------------------------------------------

    private void templateSmelt() {
        targetBody().addAll(BlockTemplates.smeltingLine());
    }

    private void templateSort() {
        targetBody().add(BlockTemplates.fullStackSort());
    }

    private void templateEven() {
        targetBody().addAll(BlockTemplates.balancedDistribution());
    }

    private void templateFast() {
        program.triggers.addAll(BlockTemplates.parallelTransfers());
    }

    // ---- clipboard & user templates ----------------------------------------------

    private static String bodySfml(String text) {
        int start = text.indexOf("do\n");
        int end = text.lastIndexOf("end");
        if (start < 0 || end <= start) return null;
        return text.substring(start + 3, end);
    }

    private String serializeSelection() {
        List<BProgram.Statement> sel = orderedSelection();
        if (sel.isEmpty()) return null;
        BProgram tmp = new BProgram();
        var tt = new BProgram.TimerTrigger();
        tmp.triggers.add(tt);
        for (BProgram.Statement s : sel) tt.body.add(s.copy());
        return bodySfml(BlocksToSfml.toSfml(tmp));
    }

    /** Whole triggers as body text (for templates): their statements in order. */
    private String serializeTriggersAsBody() {
        List<BProgram.Statement> out = new ArrayList<>();
        for (BProgram.Trigger t : selectedTriggers) {
            collectStatements(t.body, out);
        }
        if (out.isEmpty()) return null;
        BProgram tmp = new BProgram();
        var tt = new BProgram.TimerTrigger();
        tmp.triggers.add(tt);
        for (BProgram.Statement s : out) tt.body.add(s.copy());
        return bodySfml(BlocksToSfml.toSfml(tmp));
    }

    private void collectStatements(List<BProgram.Statement> list, List<BProgram.Statement> out) {
        for (BProgram.Statement s : list) {
            out.add(s);
            if (s instanceof BProgram.Statement.If iff) {
                for (BProgram.Branch b : iff.branches) collectStatements(b.body, out);
                collectStatements(iff.elseBody, out);
            }
        }
    }

    private void copySelection() {
        if (selectedTriggers.isEmpty() && selection.isEmpty()) {
            showStatus("没有可复制的积木", 0xFFB45309);
            return;
        }
        if (!selectedTriggers.isEmpty()) {
            BProgram tmp = new BProgram();
            for (BProgram.Trigger t : selectedTriggers) {
                tmp.triggers.add(copyTrigger(t));
            }
            clipboardSfml = BlocksToSfml.toSfml(tmp);
            clipboardTriggers = true;
            showStatus("已复制 " + selectedTriggers.size() + " 个触发器（Ctrl+V 粘贴）", C_SELECT);
            return;
        }
        String sfml = serializeSelection();
        if (sfml != null && !sfml.isBlank()) {
            clipboardSfml = sfml;
            clipboardTriggers = false;
            showStatus("已复制 " + selection.size() + " 个积木（Ctrl+V 粘贴）", C_SELECT);
        } else {
            showStatus("复制失败：所选积木无法转换为代码", 0xFFD13438);
        }
    }

    /** Deep copy via an SFML round-trip so the clone shares no state. */
    private BProgram.Trigger copyTrigger(BProgram.Trigger t) {
        BProgram tmp = new BProgram();
        tmp.triggers.add(t);
        SfmlToBlocks.Result r = SfmlToBlocks.parse(BlocksToSfml.toSfml(tmp));
        if (r.ok() && r.program() != null && !r.program().triggers.isEmpty()) {
            return r.program().triggers.get(0);
        }
        return t;
    }

    private void pasteClipboard() {
        if (clipboardSfml == null || clipboardSfml.isBlank()) {
            showStatus("还没有复制任何积木", 0xFFB45309);
            return;
        }
        if (clipboardTriggers) {
            SfmlToBlocks.Result r = SfmlToBlocks.parse(clipboardSfml);
            if (r.ok() && r.program() != null && !r.program().triggers.isEmpty()) {
                pushUndo();
                program.triggers.addAll(r.program().triggers);
                showStatus("已粘贴 " + r.program().triggers.size() + " 个触发器", C_SELECT);
            } else {
                showStatus("粘贴失败：复制的触发器已经无法解析", 0xFFD13438);
            }
            return;
        }
        SfmlToBlocks.Result r = SfmlToBlocks.parse("every 20 ticks do\n" + clipboardSfml + "\nend");
        if (r.ok() && r.program() != null && !r.program().triggers.isEmpty()) {
            pushUndo();
            targetBody().addAll(r.program().triggers.get(0).body);
            showStatus("已粘贴 " + r.program().triggers.get(0).body.size() + " 个积木", C_SELECT);
        } else {
            showStatus("粘贴失败：复制的积木已经无法解析", 0xFFD13438);
        }
    }

    /**
     * Ctrl+D 快速复制：整卡选中时每张卡后面紧跟一份副本（副本错开 24px，
     * 若仍重叠由避让逻辑放到原卡正下方）；积木选中时副本插到原积木后面。
     * 复制后选中自动切到副本 —— 直接就能拖走。
     */
    private void duplicateSelection() {
        if (selectedTriggers.isEmpty() && selection.isEmpty()) return;
        pushUndo();
        if (!selectedTriggers.isEmpty()) {
            List<BProgram.Trigger> out = new ArrayList<>();
            List<BProgram.Trigger> copies = new ArrayList<>();
            for (BProgram.Trigger t : program.triggers) {
                out.add(t);
                if (selectedTriggers.contains(t)) {
                    BProgram.Trigger c = t.copy();
                    copies.add(c);
                    int[] p = layout.cardPosOf(t.id);
                    if (p != null) layout.setCardPos(c.id, p[0] + 24, p[1] + 24);
                    out.add(c);
                }
            }
            program.triggers.clear();
            program.triggers.addAll(out);
            selectedTriggers.clear();
            selectedTriggers.addAll(copies);
            selection.clear();
            layoutDirty = true;
            showStatus("已复制 " + copies.size() + " 张卡", C_SELECT);
            return;
        }
        LinkedHashSet<BProgram.Statement> newSel = new LinkedHashSet<>();
        for (BProgram.Statement s : orderedSelection()) {
            List<BProgram.Statement> list = containingListOf(s);
            if (list == null) continue;
            BProgram.Statement c = s.copy();
            list.add(list.indexOf(s) + 1, c);
            newSel.add(c);
        }
        selection.clear();
        selection.addAll(newSel);
        layoutDirty = true;
        showStatus("已复制 " + newSel.size() + " 个积木", C_SELECT);
    }

    /** 找到直接包含该积木的列表（可能位于任意 If 分支 / else 体）。 */
    private @Nullable List<BProgram.Statement> containingListOf(BProgram.Statement s) {
        for (BProgram.Trigger t : program.triggers) {
            List<BProgram.Statement> r = containingListIn(t.body, s);
            if (r != null) return r;
        }
        return null;
    }

    private @Nullable List<BProgram.Statement> containingListIn(List<BProgram.Statement> list, BProgram.Statement s) {
        if (list.contains(s)) return list;
        for (BProgram.Statement x : list) {
            if (x instanceof BProgram.Statement.If iff) {
                for (BProgram.Branch b : iff.branches) {
                    List<BProgram.Statement> r = containingListIn(b.body, s);
                    if (r != null) return r;
                }
                List<BProgram.Statement> r = containingListIn(iff.elseBody, s);
                if (r != null) return r;
            }
        }
        return null;
    }

    private void deleteSelection() {
        if (selection.isEmpty() && selectedTriggers.isEmpty()) {
            showStatus("没有选中可删除的积木", 0xFFB45309);
            return;
        }
        int statementCount = selection.size();
        int triggerCount = selectedTriggers.size();
        pushUndo();
        for (BProgram.Statement s : new ArrayList<>(selection)) {
            removeFromAll(s);
        }
        program.triggers.removeAll(selectedTriggers);
        selection.clear();
        selectedTriggers.clear();
        actionBarVisible = false;
        String deleted = triggerCount > 0
                ? triggerCount + " 个触发器"
                : statementCount + " 个积木";
        showStatus("已删除 " + deleted + "（Ctrl+Z 可撤销）", 0xFFDC2626);
    }

    private void saveSelectionAsTemplate() {
        String sfml = selectedTriggers.isEmpty() ? serializeSelection() : serializeTriggersAsBody();
        if (sfml == null || sfml.isBlank()) {
            showStatus("先框选或单击选中要保存的积木", 0xFFB45309);
            return;
        }
        popup = Popup.TextPopup.confirmed(this,
                panelX + panelW / 2 - 110, panelY + panelH / 2 - 22, 220,
                "我的模板", T_TPL_NAME.getString(), name -> {
            if (name.isBlank()) {
                showStatus("模板名称不能为空", 0xFFB45309);
            } else if (saveTemplateFile(name, sfml)) {
                showStatus("已保存模板「" + name + "」", 0xFF0C8F58);
            } else {
                showStatus("模板保存失败，请检查配置目录是否可写", 0xFFD13438);
            }
        }, null, "保存模板");
    }

    private record TplEntry(String name, String sfml) {
    }

    private static final Gson GSON = new Gson();
    private @Nullable List<TplEntry> templateCache;

    private List<TplEntry> loadTemplates() {
        if (templateCache != null) return templateCache;
        try {
            Path file = FMLPaths.CONFIGDIR.get().resolve("sfmfactorystudio").resolve("templates.json");
            if (!Files.exists(file)) return templateCache = List.of();
            var raw = GSON.fromJson(Files.readString(file), new TypeToken<List<TplEntry>>() {
            }.getType());
            List<TplEntry> out = new ArrayList<>();
            if (raw instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof TplEntry e) out.add(e);
                    else if (o instanceof Map<?, ?> m) {
                        out.add(new TplEntry(String.valueOf(m.get("name")), String.valueOf(m.get("sfml"))));
                    }
                }
            }
            return templateCache = List.copyOf(out);
        } catch (Exception e) {
            return templateCache = List.of();
        }
    }

    // ---- local recovery draft (drafts.json) -----------------------------------

    /**
     * Drafts deliberately live outside SFML and are never sent to the manager.
     * The base text lets us warn when the manager was changed after the draft
     * was made instead of silently overwriting newer saved work.
     */
    private record DraftEntry(String base, String sfml, long savedAt) {
    }

    private String draftKey() {
        var mc = Minecraft.getInstance();
        String world = "unknown";
        try {
            if (mc.hasSingleplayerServer() && mc.getSingleplayerServer() != null) {
                world = "singleplayer:" + mc.getSingleplayerServer().getWorldData().getLevelName();
            } else if (mc.getCurrentServer() != null) {
                world = "server:" + mc.getCurrentServer().ip;
            }
        } catch (Exception ignored) {
        }
        String dimension = "unknown";
        try {
            if (mc.level != null) dimension = mc.level.dimension().location().toString();
        } catch (Exception ignored) {
        }
        return world + "|" + dimension + "|" + layoutKey();
    }

    private @Nullable Path draftFile() {
        try {
            Path dir = FMLPaths.CONFIGDIR.get().resolve("sfmfactorystudio");
            Files.createDirectories(dir);
            return dir.resolve("drafts.json");
        } catch (IOException e) {
            return null;
        }
    }

    private Map<String, DraftEntry> readDraftFile() {
        Path file = draftFile();
        if (file == null || !Files.exists(file)) return new LinkedHashMap<>();
        try {
            Map<String, DraftEntry> raw = GSON.fromJson(Files.readString(file),
                    new TypeToken<Map<String, DraftEntry>>() {
                    }.getType());
            if (raw == null) return new LinkedHashMap<>();
            Map<String, DraftEntry> clean = new LinkedHashMap<>();
            for (Map.Entry<String, DraftEntry> e : raw.entrySet()) {
                DraftEntry value = e.getValue();
                if (e.getKey() != null && value != null && value.sfml() != null) {
                    clean.put(e.getKey(), value);
                }
            }
            return clean;
        } catch (Exception ignored) {
            return new LinkedHashMap<>();
        }
    }

    /** Write via a sibling temporary file so a crash cannot leave half-written JSON. */
    private boolean writeDraftFile(Map<String, DraftEntry> all) {
        Path file = draftFile();
        if (file == null) return false;
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.writeString(temp, GSON.toJson(all));
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException noAtomicMove) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void saveDraft() {
        if (!dirty) return;
        // Invalid half-typed code is exactly what crash recovery must preserve;
        // never replace it with the last valid block-generated source.
        String sfml = currentSource();
        if (sfml.equals(lastDraftText)) return;
        Map<String, DraftEntry> all = readDraftFile();
        all.put(draftKey(), new DraftEntry(savedProgramText, sfml, System.currentTimeMillis()));
        if (writeDraftFile(all)) {
            lastDraftText = sfml;
            saveLayouts();
        }
    }

    private void clearDraft() {
        Map<String, DraftEntry> all = readDraftFile();
        if (all.remove(draftKey()) != null) writeDraftFile(all);
        lastDraftText = "";
    }

    private void offerDraftRestore() {
        if (draftPromptChecked) return;
        draftPromptChecked = true;
        DraftEntry draft = readDraftFile().get(draftKey());
        if (draft == null || draft.sfml().isBlank()) return;
        if (draft.sfml().equals(currentSource())) {
            clearDraft();
            return;
        }
        boolean savedContentChanged = !Objects.equals(draft.base(), savedProgramText);
        String message = savedContentChanged
                ? "已保存内容后来有变化，请确认是否恢复旧草稿。"
                : "这是上次退出前自动保留、尚未保存的内容。";
        showStatus("发现一份未保存的本地草稿", 0xFFB45309);
        setPopup(new Popup.DecisionPopup(
                panelX + panelW / 2 - 160,
                panelY + panelH / 2 - 34,
                320,
                "发现未保存草稿",
                message,
                List.of("restore_draft", "delete_draft", "keep_draft"),
                List.of("恢复草稿", "删除草稿", "以后再说"),
                action -> {
                    switch (action) {
                        case "restore_draft" -> restoreDraft(draft);
                        case "delete_draft" -> {
                            clearDraft();
                            showStatus("已删除本地草稿，使用管理器中已保存的内容", C_TEXT_SUB);
                        }
                        default -> showStatus("草稿仍保留在本机，下次打开还可以恢复", C_SELECT);
                    }
                }));
    }

    private void restoreDraft(DraftEntry draft) {
        SfmlToBlocks.Result result = SfmlToBlocks.parse(draft.sfml());
        if (!result.ok() || result.program() == null) {
            previewMode = true;
            importFailed = program.triggers.isEmpty();
            codeTextEdited = true;
            codeAwaitingValidation = true;
            codeValidateDelay = -1;
            if (codeEditor != null) {
                settingCodeFromModel = true;
                codeEditor.setValueFromModel(draft.sfml());
                codeEditor.visible = true;
                settingCodeFromModel = false;
            }
            dirty = true;
            lastDraftText = draft.sfml();
            codeStatusText = result.errors().isEmpty() ? "草稿代码尚未完整" : "未同步：" + result.errors().get(0);
            codeStatusColor = C_ERR;
            showStatus("⚠ 已恢复未完成的代码草稿；修正后会自动生成积木", 0xFFB45309);
            return;
        }
        program = result.program();
        importFailed = false;
        layout.setProgram(program);
        layout.clearCardPos();
        loadLayouts();
        resourceEntryCache.clear();
        selection.clear();
        selectedTriggers.clear();
        expandedIds.clear();
        layout.setExpandedIds(expandedIds);
        selectedBody = null;
        modelVersion++;
        undoStack.clear();
        redoStack.clear();
        actionBarVisible = false;
        generatedCache = "";
        layoutDirty = true;
        dirty = true;
        draftSaveDelay = -1;
        lastDraftText = draft.sfml();
        if (nameBox != null) nameBox.setValue(program.name);
        if (codeEditor != null) {
            settingCodeFromModel = true;
            codeEditor.setValueFromModel(draft.sfml());
            settingCodeFromModel = false;
        }
        codeTextEdited = !draft.sfml().equals(generated());
        codeAwaitingValidation = false;
        lastModelSfml = generated();
        codeStatusText = "草稿已同步为积木";
        codeStatusColor = 0xFF18794E;
        showStatus("已恢复本地草稿；确认无误后请保存到管理器", 0xFF0C8F58);
    }

    private boolean saveTemplateFile(String name, String sfml) {
        try {
            Path dir = FMLPaths.CONFIGDIR.get().resolve("sfmfactorystudio");
            Files.createDirectories(dir);
            Path file = dir.resolve("templates.json");
            Path temp = dir.resolve("templates.json.tmp");
            List<TplEntry> list = new ArrayList<>(loadTemplates());
            list.removeIf(e -> e.name().equals(name));
            list.add(new TplEntry(name, sfml));
            Files.writeString(temp, GSON.toJson(list));
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException noAtomicMove) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            templateCache = List.copyOf(list);
            return true;
        } catch (IOException ignored) {
            return false;
        }
    }

    private void insertTemplate(String name) {
        for (TplEntry e : loadTemplates()) {
            if (e.name().equals(name)) {
                SfmlToBlocks.Result r = SfmlToBlocks.parse("every 20 ticks do\n" + e.sfml() + "\nend");
                if (r.ok() && r.program() != null && !r.program().triggers.isEmpty()) {
                    pushUndo();
                    targetBody().addAll(r.program().triggers.get(0).body);
                    layoutDirty = true;
                }
                return;
            }
        }
    }

    // ==================================================================== input 2

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
        if (popup != null && popup.isOver(mx, my)) {
            if (popup.mouseScrolled(mx, my, scrollY)) return true;
        }
        // 左侧积木栏：悬停时滚轮滚动（内容超高才有滚动量）
        if (mx >= panelX + 6 && mx < panelX + 6 + PALETTE_W
                && my >= panelY + TOOLBAR_H + 6 && my < panelY + panelH - 6 && paletteContentH > 0) {
            int trackTop = panelY + TOOLBAR_H + 8;
            int trackH = (panelY + panelH - 6) - trackTop - 4;
            int maxScroll = Math.max(0, paletteContentH - trackH);
            if (maxScroll > 0) {
                paletteScroll = Math.max(0, Math.min(maxScroll,
                        paletteScroll - (int) Math.signum(scrollY) * 18));
                return true;
            }
        }
        if (previewMode && codeEditor != null && my > previewTop()) {
            return codeEditor.mouseScrolled(mx, my, scrollX, scrollY);
        }
        if (issuesOpen && issuesPanelVisible && mx >= canvasX + canvasW + 10 && mx < canvasX + canvasW + 10 + ISSUES_W
                && my >= canvasY && my < canvasY + canvasH) {
            issuesScroll = Math.max(0, issuesScroll - (int) scrollY * 14);
            return true;
        }
        if (mx >= canvasX && mx < canvasX + canvasW && my >= canvasY && my < canvasY + canvasH) {
            double cx = ctX(mx), cy = ctY(my);
            float factor = scrollY > 0 ? 1.15f : 1 / 1.15f;
            zoom = Math.max(0.1f, Math.min(2.5f, zoom * factor));
            viewX = (int) Math.round(cx - (mx - canvasX - CANVAS_PAD) / zoom);
            viewY = (int) Math.round(cy - (my - canvasY - CANVAS_PAD) / zoom);
            return true;
        }
        return super.mouseScrolled(mx, my, scrollX, scrollY);
    }

    private int previewTop() {
        return canvasY + canvasH + 4;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // "/" 搜索：任何文本框未聚焦时触发（必须放在 popup/nameBox 之前，否则被吃掉）
        boolean inTextField = (nameBox != null && nameBox.isFocused())
                || (codeEditor != null && codeEditor.isFocused());
        if (keyCode == 53 && modifiers == 0 && !inTextField && popup == null) {
            openCardSearch();
            return true;
        }
        if (popup != null) {
            if (popup.keyPressed(keyCode, scanCode, modifiers)) return true;
            if (keyCode == 256) {
                popup = null;
                return true;
            }
        }
        if (nameBox != null && nameBox.isFocused()) {
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        boolean ctrl = (modifiers & 2) != 0;
        if (previewMode && codeEditor != null && codeEditor.isFocused()) {
            if (ctrl && keyCode == 83) {
                save();
                return true;
            }
            if (codeEditor.keyPressed(keyCode, scanCode, modifiers)) {
                codeSuggestDelay = 3;
                return true;
            }
            if (keyCode == 256) {
                codeEditor.setFocused(false);
                return true;
            }
        }
        if (ctrl && keyCode == 83) { // ctrl+s
            save();
            return true;
        }
        if (ctrl && keyCode == 90 && (modifiers & 1) == 0) { // ctrl+z
            undo();
            return true;
        }
        if (ctrl && (keyCode == 89 || (keyCode == 90 && (modifiers & 1) != 0))) { // ctrl+y / ctrl+shift+z
            redo();
            return true;
        }
        if (keyCode == 344) { // F8 — 定位下一个问题
            stepIssue(1);
            return true;
        }
        if (ctrl && keyCode == 67) { // ctrl+c
            copySelection();
            return true;
        }
        if (ctrl && keyCode == 86) { // ctrl+v
            pasteClipboard();
            return true;
        }
        if (ctrl && keyCode == 65) { // ctrl+a — select every trigger card whole
            selection.clear();
            selectedTriggers.clear();
            for (BProgram.Trigger t : program.triggers) {
                selectedTriggers.add(t);
            }
            if (!selectedTriggers.isEmpty()) {
                showActionBar(panelX + panelW / 2 - 120, panelY + panelH / 2);
            }
            return true;
        }
        if (ctrl && (modifiers & 1) == 0 && keyCode == 68) { // ctrl+d — 快速复制所选
            duplicateSelection();
            return true;
        }
        if (ctrl && (modifiers & 1) != 0 && keyCode == 68) { // ctrl+shift+D — 命中框可视化
            debugHits = !debugHits;
            return true;
        }
        if (ctrl && (modifiers & 1) != 0 && keyCode == 80) { // ctrl+shift+P — 性能浮层
            debugPerf = !debugPerf;
            return true;
        }
        if (keyCode == 261) { // delete
            deleteSelection();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char ch, int modifiers) {
        // '/' 字符触发搜索（比 keyCode 53 更可靠——中文输入法/不同键盘布局都能触发）
        if (ch == '/' && popup == null
                && (nameBox == null || !nameBox.isFocused())
                && (codeEditor == null || !codeEditor.isFocused())) {
            openCardSearch();
            return true;
        }
        if (popup != null && popup.charTyped(ch, modifiers)) return true;
        return super.charTyped(ch, modifiers);
    }

    @Override
    public void onClose() {
        if (!closingWithoutPrompt) requestClose();
    }

    @Override
    public void removed() {
        // A forced screen replacement, disconnect or game shutdown cannot show
        // a confirmation dialog. Persist the recoverable local copy instead.
        if (dirty) saveDraft();
        saveLayouts();
        super.removed();
    }

    // ================================================================== palette

    private void paletteItem(GuiGraphics g, int px, int py, int pw, String kind, String label,
                             int accent, double mx, double my) {
        boolean hover = mx >= px && mx < px + pw && my >= py && my < py + 17;
        rounded(g, px, py, pw, 17, 4, hover ? mix(G_CARD, accent, 22) : G_CARD_TRANS);
        g.fill(px, py + 3, px + 3, py + 14, accent);
        text(g, label, px + 9, py + 5, C_TEXT);
        uiHits.add(hit(px, py, pw, 17, K_PALETTE, kind, null));
    }

    private void renderPalette(GuiGraphics g, int mx, int my) {
        int px = panelX + 6, py = panelY + TOOLBAR_H + 6;
        int pw = PALETTE_W;
        int bottom = panelY + panelH - 6;
        rounded(g, px, py, pw, bottom - py, 8, G_CARD_TRANS);
        border(g, px, py, pw, bottom - py, G_BORDER_SOFT);
        g.enableScissor(px, py, px + pw, bottom);
        try {
        py += 8;
        int contentTop = py;
        py -= paletteScroll;

        py = section(g, px, py, T_CAT_TRIGGER.getString(), A_TIMER);
        paletteItem(g, px + 4, py, pw - 8, "timer", T_TIMER.getString(), A_TIMER, mx, my);
        py += 21;
        paletteItem(g, px + 4, py, pw - 8, "pulse", T_PULSE.getString(), A_PULSE, mx, my);
        py += 26;

        py = section(g, px, py, T_CAT_MOVE.getString(), A_INPUT);
        paletteItem(g, px + 4, py, pw - 8, "input", T_INPUT.getString(), A_INPUT, mx, my);
        py += 21;
        paletteItem(g, px + 4, py, pw - 8, "output", T_OUTPUT.getString(), A_OUTPUT, mx, my);
        py += 21;
        paletteItem(g, px + 4, py, pw - 8, "energy", T_ENERGY_TRANSFER.getString(), A_ENERGY, mx, my);
        py += 21;
        paletteItem(g, px + 4, py, pw - 8, "forget", T_FORGET.getString(), A_FORGET, mx, my);
        py += 26;

        py = section(g, px, py, T_CAT_LOGIC.getString(), A_IF);
        paletteItem(g, px + 4, py, pw - 8, "if", T_IF.getString() + "…", A_IF, mx, my);
        py += 26;

        py = section(g, px, py, T_CAT_RAW.getString(), A_RAW);
        paletteItem(g, px + 4, py, pw - 8, "comment", T_COMMENT.getString(), A_COMMENT, mx, my);
        py += 26;

        py = section(g, px, py, T_CAT_TPL.getString(), C_SELECT);
        paletteItem(g, px + 4, py, pw - 8, "tpl_smelt", T_TPL_SMELT.getString(), C_SELECT, mx, my);
        py += 21;
        paletteItem(g, px + 4, py, pw - 8, "tpl_sort", T_TPL_SORT.getString(), C_SELECT, mx, my);
        py += 21;
        paletteItem(g, px + 4, py, pw - 8, "tpl_even", T_TPL_EVEN.getString(), C_SELECT, mx, my);
        py += 21;
        paletteItem(g, px + 4, py, pw - 8, "tpl_fast", T_TPL_FAST.getString(), C_SELECT, mx, my);
        py += 26;

        List<TplEntry> mine = loadTemplates();
        if (!mine.isEmpty()) {
            py = section(g, px, py, T_CAT_MY.getString(), 0xFF0FA968);
            for (TplEntry e : mine) {
                paletteItem(g, px + 4, py, pw - 8, "mytpl:" + e.name(), e.name(), 0xFF0FA968, mx, my);
                py += 21;
            }
            py += 4;
        }
        int visibleH = bottom - contentTop;
        paletteContentH = (py + paletteScroll) - contentTop + 8;
        paletteScroll = Math.max(0, Math.min(paletteScroll, Math.max(0, paletteContentH - visibleH)));
        } finally {
            g.disableScissor();
        }
        // 滚动条指示（内容超高才显示）
        int trackTop = panelY + TOOLBAR_H + 8;
        int trackH = bottom - trackTop - 4;
        if (paletteContentH > trackH && paletteContentH > 0) {
            int thumbH = Math.max(18, trackH * trackH / paletteContentH);
            int thumbY = trackTop + (trackH - thumbH) * paletteScroll / Math.max(1, paletteContentH - trackH);
            g.fill(px + pw - 3, trackTop, px + pw - 1, trackTop + trackH, 0x28AEBDCD);
            g.fill(px + pw - 3, thumbY, px + pw - 1, thumbY + thumbH, 0x80AEBDCD);
        }
    }

    private int section(GuiGraphics g, int px, int py, String title, int accent) {
        text(g, title, px + 6, py + 3, C_TEXT_SUB);
        return py + 16;
    }

    // =================================================================== render

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float partialTick) {
        // Vanilla Screen.render() auto-calls this (1.20.2+) and its default runs the
        // menu-blur post effect over everything already drawn — incl. our panel and
        // text, because super.render() is called late in render(). No-op it.
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partialTick) {
        // Never trigger the vanilla menu blur — see renderBackground override above.
        // Dim only the strips outside the window; the panel itself is near-opaque.
        computePanel();
        int d = 0x50101828; // outside-dim
        g.fill(0, 0, width, panelY, d);
        g.fill(0, panelY + panelH, width, height, d);
        g.fill(0, panelY, panelX, panelY + panelH, d);
        g.fill(panelX + panelW, panelY, width, panelY + panelH, d);

        if (layoutDirty) {
            relayout();
        }

        hits.clear();
        uiHits.clear();
        hitPoolCursor = 0; // 命中框对象池：本帧从头复用
        JeiGhostDrops.beginFrame();

        rounded(g, panelX + 3, panelY + 4, panelW, panelH, 10, G_SHADOW);
        rounded(g, panelX, panelY, panelW, panelH, 10, G_PANEL);
        border(g, panelX, panelY, panelW, panelH, G_BORDER_SOFT);

        canvasX = panelX + PALETTE_W + 16;
        canvasY = panelY + TOOLBAR_H + 6;
        int baseCanvasW = panelX + panelW - 8 - canvasX;
        issuesPanelVisible = issuesOpen && baseCanvasW - (ISSUES_W + 10) >= 200;
        canvasW = baseCanvasW - (issuesPanelVisible ? ISSUES_W + 10 : 0);
        canvasH = panelH - TOOLBAR_H - 12 - (previewMode ? Math.max(112, panelH * 42 / 100) : 0);
        if (!fitted) {
            fitContent();
            fitted = true;
        }

        renderPalette(g, mx, my);

        // canvas glass + dot grid
        rounded(g, canvasX, canvasY, canvasW, canvasH, 8, G_CANVAS);
        border(g, canvasX, canvasY, canvasW, canvasH, G_BORDER_SOFT);
        g.enableScissor(canvasX + 1, canvasY + 1, canvasX + canvasW - 1, canvasY + canvasH - 1);
        try {
        // At far zoom-out, keep the grid readable without issuing thousands of
        // one-pixel draw calls every frame.
        int step = Math.max(18, Math.round(24 * zoom));
        // 极端缩小限制点阵总量（约 1600 点封顶），避免超宽画布单帧数千次单像素填充
        while ((canvasW / step + 1) * (canvasH / step + 1) > 1600) step *= 2;
        int startX = canvasX + 1 + ((sX(0) - canvasX - 1) % step + step) % step;
        int startY = canvasY + 1 + ((sY(0) - canvasY - 1) % step + step) % step;
        for (int gx = startX; gx < canvasX + canvasW - 1; gx += step) {
            for (int gy = startY; gy < canvasY + canvasH - 1; gy += step) {
                g.fill(gx, gy, gx + 1, gy + 1, G_DOT);
            }
        }

        if (program.triggers.isEmpty()) {
            renderGuideCards(g, mx, my);
        }

        // ---- content (zoom + pan transform) ----
        g.pose().pushPose();
        g.pose().translate(canvasX + CANVAS_PAD, canvasY + CANVAS_PAD, 0);
        g.pose().scale(zoom, zoom, 1);
        g.pose().translate(-viewX, -viewY, 0);

        renderZones(g, mx, my);
        for (CardL c : layout.cards()) {
            if (contentVisible(c.x(), c.y(), c.w(), c.h())) {
                renderCard(g, c, mx, my);
                // 聚焦只做视觉退后：区外卡片照常渲染、照常可交互（命中框完整），
                // 仅蒙一层画布色纱让它们后退，绝不隐藏（用户拍板 2026-09-02）。
                if (focusedZone != null && !zoneContainsCard(focusedZone, c)) {
                    g.fill(c.x(), c.y(), c.x() + c.w(), c.y() + c.h(), 0x55E3EAF3);
                }
            }
        }

        // insertion indicator
        Gap target = dragGroup != null || dragPaletteKind != null ? dropGap : null;
        if (target != null) {
            g.fill(target.x(), target.y() - 1, target.x() + target.w(), target.y() + 2, C_SELECT);
            g.fill(target.x(), target.y() - 4, target.x() + 4, target.y() + 5, C_SELECT);
        }
        // 卡片拖动落位提示：虚线框 + 轻微提亮，卡片本身已跟着光标实时移动；
        // 紧贴对齐生效时在贴合边画指示线，松手即"贴平"。
        if (dragTrigger != null && dragMoved) {
            for (CardL c : layout.cards()) {
                if (dragCards.contains(c.trigger())) {
                    g.fill(c.x(), c.y(), c.x() + c.w(), c.y() + c.h() - 3, 0x18000000);
                    dashedBorder(g, c.x() - 3, c.y() - 3, c.w() + 6, c.h(), C_SELECT);
                }
            }
            if (snapGuideX >= 0) {
                g.fill(snapGuideX, snapGuideY1, snapGuideX + 2, snapGuideY2, C_SELECT);
            }
            if (snapGuideY >= 0) {
                g.fill(snapGuideXL, snapGuideY, snapGuideXR, snapGuideY + 2, C_SELECT);
            }
        }

        // selection: bold tint + double border so it reads at any zoom
        for (BProgram.Statement s : selection) {
            int[] r = layout.rowRectOf(s.id);
            if (r != null && contentVisible(r[0], r[1], r[2], r[3])) {
                g.fill(r[0], r[1], r[0] + r[2], r[1] + r[3], 0x262F6FED);
                border(g, r[0] - 2, r[1] - 2, r[2] + 4, r[3] + 4, C_SELECT);
                border(g, r[0] - 1, r[1] - 1, r[2] + 2, r[3] + 2, 0xFF9CC7FF);
            }
        }
        // 诊断角标：有问题的积木/触发器常驻红（错误）或黄（提醒）左边条 + "!" 圆标。
        // 画在卡片之后、选区之后，保证任何积木上的问题一眼可见。
        for (var entry : blockSeverity.entrySet()) {
            int[] r = contentRectOfId(entry.getKey());
            if (r == null || !contentVisible(r[0], r[1], r[2], r[3])) continue;
            int color = entry.getValue() == ProgramDiagnostics.Severity.ERROR ? C_ERR : C_WARN;
            g.fill(r[0] - 3, r[1] + 1, r[0], r[1] + r[3] - 1, color);
            int chip = 11;
            int cx = r[0] + r[2] - chip + 2;
            int cy = r[1] - chip / 2 + 2;
            rounded(g, cx, cy, chip, chip, 3, 0xF7263138);
            String mark = "!";
            g.drawString(this.font, mark, cx + (chip - this.font.width(mark)) / 2, cy + 2, color, false);
        }

        // 定位闪烁：点击「定位」后相机已居中，这里画 2 秒呼吸边框把目标圈出来
        if (locateTicks > 0 && locateTarget != null) {
            int[] r = contentRectOf(locateTarget);
            if (r != null) {
                float t = (System.currentTimeMillis() % 600) / 600f;
                int alpha = 0x50 + (int) (0x50 * Math.abs(Math.sin(t * Math.PI)));
                int fill = (alpha << 24) | (C_ERR & 0xFFFFFF);
                int line = (0xFF << 24) | (C_ERR & 0xFFFFFF);
                g.fill(r[0] - 2, r[1] - 2, r[0] + r[2] + 2, r[1] + r[3] + 2, fill);
                border(g, r[0] - 2, r[1] - 2, r[2] + 4, r[3] + 4, line);
                border(g, r[0] - 1, r[1] - 1, r[2] + 2, r[3] + 2, line);
            }
        }

        renderLinks(g);

        if (bandSelecting && (!selection.isEmpty() || !selectedTriggers.isEmpty())) {
            String cnt = "已选 " + (selection.size() + selectedTriggers.size()) + " 个积木";
            int tw = this.font.width(cnt) + 14;
            int tx = Math.min(bandX1, bandX2);
            int ty = Math.min(bandY1, bandY2) - 18;
            rounded(g, tx, ty, tw, 14, 4, 0xF02F6FED);
            text(g, cnt, tx + 7, ty + 3, 0xFFFFFFFF);
        }

        // rubber band — sci-fi marquee: light cyan fill + glowing corner brackets
        if (bandSelecting) {
            int x1 = Math.min(bandX1, bandX2), x2 = Math.max(bandX1, bandX2);
            int y1 = Math.min(bandY1, bandY2), y2 = Math.max(bandY1, bandY2);
            g.fill(x1, y1, x2, y2, C_BAND);
            int pulse = 0xFF9FD0FF + (int) ((Math.sin(System.currentTimeMillis() / 180.0) + 1) * 0x08);
            border(g, x1, y1, x2 - x1, y2 - y1, 0xFF5AB0FF);
            int L = 7; // corner bracket arm length
            // top-left / top-right / bottom-left / bottom-right brackets
            g.fill(x1 - 1, y1 - 1, x1 - 1 + L, y1 + 1, pulse);
            g.fill(x1 - 1, y1 - 1, x1 + 1, y1 - 1 + L, pulse);
            g.fill(x2 - L + 1, y1 - 1, x2 + 1, y1 + 1, pulse);
            g.fill(x2, y1 - 1, x2 + 1, y1 - 1 + L, pulse);
            g.fill(x1 - 1, y2, x1 - 1 + L, y2 + 1, pulse);
            g.fill(x1 - 1, y2 - L + 1, x1 + 1, y2 + 1, pulse);
            g.fill(x2 - L + 1, y2, x2 + 1, y2 + 1, pulse);
            g.fill(x2, y2 - L + 1, x2 + 1, y2 + 1, pulse);
        }

        if (debugHits) {
            for (Hit h : hits) {
                border(g, h.x, h.y, h.w, h.h, DBG[Math.max(0, Math.min(h.kind, DBG.length - 1))]);
            }
        }

        g.pose().popPose();
        // ---- end content transform ----
        } finally {
            g.disableScissor(); // never leak the clip: it would cut the whole game to this rect
        }

        if (debugHits) {
            // UI 区命中框本来就是屏幕坐标，直接在屏幕空间画
            for (Hit h : uiHits) {
                border(g, h.x, h.y, h.w, h.h, DBG[Math.max(0, Math.min(h.kind, DBG.length - 1))]);
            }
        }

        // 性能浮层（Ctrl+Shift+P）：布局耗时 / 卡数 / 命中框 / 诊断次数
        if (debugPerf) {
            String perf = String.format(java.util.Locale.ROOT,
                    "布局 %.2f ms · 卡 %d · 内容命中 %d · UI 命中 %d · 诊断 %d 次 · %s",
                    perfLayoutNanos / 1_000_000.0, layout.cards().size(), hits.size(), uiHits.size(),
                    issuesRefreshCount, dirty ? "未保存" : "已保存");
            int pw = this.font.width(perf) + 12;
            rounded(g, canvasX + 6, canvasY + 6, pw, 16, 4, 0xC01B2432);
            text(g, perf, canvasX + 12, canvasY + 10, 0xFF9FE8C9);
        }

        // drag ghost (screen space, unscaled)
        if (dragGroup != null) {
            ghost(g, dragGroup.label() + (dragGroup.all().size() > 1 ? " ×" + dragGroup.all().size() : ""), dragGroup.accent(), mx, my);
        } else if (dragPaletteKind != null) {
            ghost(g, paletteLabel(dragPaletteKind), paletteAccent(dragPaletteKind), mx, my);
        }

        if (previewMode) {
            renderPreview(g);
            layoutCodeEditor();
        } else if (codeEditor != null) {
            codeEditor.visible = false;
        }

        if (issuesOpen && issuesPanelVisible) {
            renderIssues(g, mx, my);
        }

        renderToolbar(g, mx, my);
        renderActionBar(g, mx, my);
        super.render(g, mx, my, partialTick); // program name + live SFML editor

        if (popup != null) {
            popup.render(g, this.font, mx, my);
        }
    }

    private void renderZones(GuiGraphics g, int mx, int my) {
        for (Zone z : zones) {
            if (!contentVisible(z.x(), z.y(), z.w(), z.h())) continue;
            int fill = ZONE_FILLS[z.color()];
            int line = ZONE_BORDERS[z.color()];
            rounded(g, z.x(), z.y(), z.w(), z.h(), 10, fill);
            border(g, z.x(), z.y(), z.w(), z.h(), z == focusedZone ? C_SELECT : line);
            // 名称 1.5× 大字（LOD 缩小时仍然可读——它就是结构层）
            g.pose().pushPose();
            g.pose().translate(z.x() + 8, z.y() + 5, 0);
            g.pose().scale(1.5f, 1.5f, 1);
            g.drawString(this.font, z.name() + (z == focusedZone ? " ●" : ""), 0, 0, line, false);
            g.pose().popPose();
            if (zoom >= LOD_ZOOM) {
                // 标题：点击改名（弹窗出现在标题正下方，不飞到屏幕中央）
                int titleW = Math.max(40, this.font.width(z.name()) * 3 / 2 + 12);
                hits.add(hit(z.x() + 2, z.y(), titleW, 18, K_CLICK, null, () -> renameZone(z)));
                // ◎ 聚焦按钮（色块左边）：顶栏不再整宽吞点击，空白处照常起框选/拖动
                int focusX = z.x() + z.w() - 18 - 3 * 13 - 4 - 18;
                boolean focusHover = mx >= focusX && mx < focusX + 16 && my >= z.y() + 2 && my < z.y() + 16;
                g.drawString(this.font, "◎", focusX + 4, z.y() + 4,
                        z == focusedZone ? C_SELECT : focusHover ? C_TEXT : line, false);
                hits.add(hit(focusX, z.y() + 2, 16, 14, K_CLICK, z, () -> {
                    focusedZone = focusedZone == z ? null : z;
                    if (focusedZone != null) showStatus("已聚焦「" + z.name() + "」（区外卡片变淡但仍可操作），点 ◎ 恢复", C_SELECT);
                }));
                // 三个色块（✕ 左边）：点击换色，当前色带描边
                for (int ci = 0; ci < 3; ci++) {
                    int swx = z.x() + z.w() - 18 - (3 - ci) * 13 - 4;
                    g.fill(swx, z.y() + 4, swx + 10, z.y() + 14, ZONE_BORDERS[ci]);
                    if (ci == z.color()) border(g, swx - 1, z.y() + 3, 12, 12, C_TEXT);
                    final int picked = ci;
                    hits.add(hit(swx - 1, z.y() + 2, 13, 13, K_CLICK, null, () -> recolorZone(z, picked)));
                }
                hits.add(hit(z.x() + z.w() - 18, z.y() + 2, 14, 14, K_CLICK, null, () -> {
                    zones.remove(z);
                    if (focusedZone == z) focusedZone = null;
                    saveLayouts();
                }));
                g.drawString(this.font, "✕", z.x() + z.w() - 15, z.y() + 4, line, false);
            }
        }
        if (zoneDrag) {
            int x1 = Math.min(zoneX1, zoneX2), x2 = Math.max(zoneX1, zoneX2);
            int y1 = Math.min(zoneY1, zoneY2), y2 = Math.max(zoneY1, zoneY2);
            g.fill(x1, y1, x2, y2, 0x182F6FED);
            dashedBorder(g, x1, y1, x2 - x1, y2 - y1, C_SELECT);
        }
    }

    private static boolean zoneContainsCard(Zone z, CardL c) {
        int cx = c.x() + c.w() / 2, cy = c.y() + c.h() / 2;
        return cx >= z.x() && cx < z.x() + z.w() && cy >= z.y() && cy < z.y() + z.h();
    }

    private void renameZone(Zone z) {
        setPopup(new Popup.TextPopup(this, sX(z.x() + 4), sY(z.y()) + 20, 170,
                z.name(), "分区名称…", v -> {
                    String n = v.trim();
                    if (n.isEmpty() || n.equals(z.name())) return;
                    int idx = zones.indexOf(z);
                    if (idx >= 0) {
                        zones.set(idx, new Zone(n, z.color(), z.x(), z.y(), z.w(), z.h()));
                        saveLayouts();
                        if (focusedZone == z) focusedZone = zones.get(idx);
                    }
                }, null));
    }

    private void recolorZone(Zone z, int color) {
        if (color == z.color()) return;
        int idx = zones.indexOf(z);
        if (idx < 0) return;
        zones.set(idx, new Zone(z.name(), color, z.x(), z.y(), z.w(), z.h()));
        saveLayouts();
        if (focusedZone == z) focusedZone = zones.get(idx);
    }

    /**
     * 连线渲染：从源卡右上角 ◆ 到目标卡最近一面的中点，点状贝塞尔 + 箭头 +
     * 中点 ✕ 删除手柄；拖拽中的临时线跟光标。端点卡被删的连线自动清理。
     */
    private void renderLinks(GuiGraphics g) {
        links.removeIf(l -> layout.cardRectOf(l.a()) == null || layout.cardRectOf(l.b()) == null);
        for (BlockLink l : links) {
            int[] ra = layout.cardRectOf(l.a());
            int[] rb = layout.cardRectOf(l.b());
            if (ra == null || rb == null) continue;
            if (!contentVisible(ra[0], ra[1], ra[2], ra[3]) && !contentVisible(rb[0], rb[1], rb[2], rb[3])) continue;
            int ax = ra[0] + ra[2] - 6, ay = ra[1] + 8;   // 源卡 ◆ 手柄处
            int[] end = nearestFacePoint(rb, ax, ay);      // 目标卡最近一面中点
            int[] mid = drawDottedCurve(g, ax, ay, end[0], end[1]);
            if (zoom >= LOD_ZOOM) {
                g.drawString(this.font, "✕", mid[0] - 3, mid[1] - 4, 0xFF9AA3B2, false);
                final BlockLink fl = l;
                hits.add(hit(mid[0] - 7, mid[1] - 8, 14, 16, K_CLICK, null, () -> {
                    links.remove(fl);
                    saveLayouts();
                    showStatus("已删除连线", C_TEXT_SUB);
                }));
            }
        }
        if (linkDragFrom >= 0) {
            int[] ra = layout.cardRectOf(linkDragFrom);
            if (ra != null) {
                drawDottedCurve(g, ra[0] + ra[2] - 6, ra[1] + 8, (int) linkDragX, (int) linkDragY);
            }
        }
    }

    /** 距 (fromX, fromY) 最近的那个面的中点：左/右/上/下。 */
    private static int[] nearestFacePoint(int[] r, int fromX, int fromY) {
        int[][] faces = {
                {r[0], r[1] + r[3] / 2}, {r[0] + r[2], r[1] + r[3] / 2},
                {r[0] + r[2] / 2, r[1]}, {r[0] + r[2] / 2, r[1] + r[3]}};
        int[] best = faces[0];
        long bestDist = Long.MAX_VALUE;
        for (int[] f : faces) {
            long d = (long) (f[0] - fromX) * (f[0] - fromX) + (long) (f[1] - fromY) * (f[1] - fromY);
            if (d < bestDist) {
                bestDist = d;
                best = f;
            }
        }
        return best;
    }

    /** 点状三次贝塞尔（水平进出），返回中点坐标供删除手柄使用。 */
    private static int[] drawDottedCurve(GuiGraphics g, int x0, int y0, int x1, int y1) {
        int lift = Math.max(30, Math.abs(x1 - x0) / 2);
        int c1x = x0 + lift, c2x = x1 - lift;
        int midX = 0, midY = 0;
        for (int i = 0; i <= 14; i++) {
            float t = i / 14f, mt = 1 - t;
            double x = mt * mt * mt * x0 + 3 * mt * mt * t * c1x + 3 * mt * t * t * c2x + t * t * t * x1;
            double y = mt * mt * mt * y0 + 3 * mt * mt * t * y0 + 3 * mt * t * t * y1 + t * t * t * y1;
            if (i == 7) {
                midX = (int) x;
                midY = (int) y;
            }
            g.fill((int) x - 1, (int) y - 1, (int) x + 1, (int) y + 1, 0xB02F6FED);
        }
        g.fill(x1 - 1, y1 - 4, x1 + 2, y1 + 4, 0xB02F6FED); // 终点箭头
        return new int[]{midX, midY};
    }

    private void ghost(GuiGraphics g, String label, int accent, int mx, int my) {
        int w = this.font.width(label) + 18;
        rounded(g, mx - w / 2, my - 9, w, 18, 5, 0xF7FFFFFF);
        border(g, mx - w / 2, my - 9, w, 18, accent);
        g.fill(mx - w / 2 + 2, my - 6, mx - w / 2 + 5, my + 6, accent);
        g.drawString(this.font, label, mx - w / 2 + 10, my - 4, C_TEXT, false);
    }

    private String paletteLabel(String kind) {
        return switch (kind) {
            case "timer" -> T_TIMER.getString();
            case "pulse" -> T_PULSE.getString();
            case "input" -> T_INPUT.getString();
            case "output" -> T_OUTPUT.getString();
            case "energy" -> T_ENERGY_TRANSFER.getString();
            case "forget" -> T_FORGET.getString();
            case "if" -> T_IF.getString();
            case "comment" -> T_COMMENT.getString();
            case "raw" -> T_RAW.getString();
            default -> kind;
        };
    }

    private int paletteAccent(String kind) {
        return switch (kind) {
            case "timer" -> A_TIMER;
            case "pulse" -> A_PULSE;
            case "input" -> A_INPUT;
            case "output" -> A_OUTPUT;
            case "energy" -> A_ENERGY;
            case "forget" -> A_FORGET;
            case "if" -> A_IF;
            case "comment" -> A_COMMENT;
            default -> A_RAW;
        };
    }

    private void showActionBar(int screenX, int screenY) {
        abX = Math.max(panelX + 8, Math.min(screenX - 40, panelX + panelW - 260));
        abY = Math.min(screenY + 10, panelY + panelH - 34);
        actionBarVisible = true;
    }

    /** Small glass toolbar that pops up where the band selection finished. */
    private void renderActionBar(GuiGraphics g, int mx, int my) {
        boolean hasSel = !selection.isEmpty() || !selectedTriggers.isEmpty();
        if (!actionBarVisible || !hasSel) {
            actionBarVisible = hasSel && actionBarVisible;
            if (!actionBarVisible) return;
        }
        int w = 4 * 62 + 10;
        int h = 24;
        rounded(g, abX + 2, abY + 3, w, h, 8, G_SHADOW);
        rounded(g, abX, abY, w, h, 8, 0xF4FFFFFF);
        border(g, abX, abY, w, h, 0x802F6FED);
        String[] labels = {T_AB_COPY.getString(), T_AB_TPL.getString(), T_AB_DEL.getString(), T_AB_CANCEL.getString()};
        int[] colors = {0xFF2F6FED, 0xFF7C3AED, 0xFFDC2626, 0xFF5B6472};
        int bx = abX + 5;
        for (int i = 0; i < labels.length; i++) {
            int bw = 58;
            boolean hover = mx >= bx && mx < bx + bw && my >= abY + 4 && my < abY + h - 4;
            rounded(g, bx, abY + 4, bw, h - 8, 4, hover ? mix(0xFFFFFFFF, colors[i], 36) : 0xFFF1F4F9);
            g.drawString(this.font, labels[i], bx + (bw - this.font.width(labels[i])) / 2, abY + 8, colors[i], false);
            final int idx = i;
            uiHits.add(hit(bx, abY + 4, bw, h - 8, K_AB, null, () -> {
                switch (idx) {
                    case 0 -> copySelection();
                    case 1 -> saveSelectionAsTemplate();
                    case 2 -> deleteSelection();
                    default -> {
                        selection.clear();
                        selectedTriggers.clear();
                        showStatus("已取消选择", C_TEXT_SUB);
                    }
                }
                actionBarVisible = false; // 点击后立即消失，状态栏负责反馈
            }));
            bx += bw + 2;
        }
    }

    /** 程序名输入框底板左缘：面板左侧 + 「程序名」标签宽度 + 间距。 */
    private int namePillX() {
        return panelX + 10 + this.font.width(T_NAME.getString()) + 8;
    }

    private void renderToolbar(GuiGraphics g, int mx, int my) {
        rounded(g, panelX, panelY, panelW, TOOLBAR_H, 10, 0xFAFFFFFF);
        g.fill(panelX, panelY + TOOLBAR_H - 1, panelX + panelW, panelY + TOOLBAR_H, G_BORDER_SOFT);
        text(g, T_NAME.getString(), panelX + 10, panelY + 10, C_TEXT_SUB);
        // 程序名整体贴面板左侧（用户拍板 2026-09-02）：右侧留给按钮组，永不重叠。
        // light pill behind the borderless name box
        rounded(g, namePillX(), panelY + 5, 120, 19, 5, 0xF2FFFFFF);
        border(g, namePillX(), panelY + 5, 120, 19, nameBox.isFocused() ? C_SELECT : G_BORDER);

        // buttons right-to-left: 保存 | 代码 | 撤销 | 适配 | 问题 | 关闭 | [存为模板]
        int bh = 20;
        int bx = panelX + panelW - 8;
        bx -= 86;
        button(g, bx, panelY + 4, 86, bh, "⬤ " + T_SAVE.getString(), C_SAVE, C_SAVE_H, this::save, mx, my);
        bx -= 4 + 52;
        button(g, bx, panelY + 4, 52, bh, T_PREVIEW.getString(),
                previewMode ? 0xCC2F6FED : 0xCC5B6472,
                previewMode ? 0xCC2459C4 : 0xCC49525E, this::toggleCodeEditor, mx, my);
        bx -= 4 + 48;
        button(g, bx, panelY + 4, 48, bh, T_UNDO.getString(), 0xCC5B6472, 0xCC49525E, this::undo, mx, my);
        bx -= 4 + 46;
        button(g, bx, panelY + 4, 44, bh, "重做", 0xCC5B6472, 0xCC49525E, this::redo, mx, my);
        bx -= 4 + 42;
        button(g, bx, panelY + 4, 42, bh, T_FIT.getString(), 0xCC5B6472, 0xCC49525E, () -> {
            fitted = false;
            showStatus("已将全部积木适配到画布", C_SELECT);
        }, mx, my);
        bx -= 4 + 40;
        button(g, bx, panelY + 4, 40, bh, "分区", zoneDrawing ? 0xCC2F6FED : 0xCC5B6472,
                zoneDrawing ? 0xCC2459C4 : 0xCC49525E, () -> {
                    zoneDrawing = !zoneDrawing;
                    if (zoneDrawing) showStatus("在画布空白处拖出一个矩形即可创建分区（再点「分区」取消）", C_SELECT);
                }, mx, my);
        // 问题按钮：文案固定两字（错误/提醒）或四字（问题检查），宽度按文字
        // 实际宽度 + 余量计算，任何缩放下都不会超出按钮；数量在面板里看。
        long errCount = issueErrCount;
        long warnCount = issueWarnCount;
        String issueLabel = errCount > 0
                ? T_ISSUES_ERR.getString()
                : warnCount > 0 ? T_ISSUES_WARN.getString()
                : T_ISSUES_TITLE.getString();
        int issueColor = errCount > 0 ? 0xCCD13438 : warnCount > 0 ? 0xCCB45309 : 0xCC5B6472;
        int issueHover = errCount > 0 ? 0xCCB02A30 : warnCount > 0 ? 0xCC9C4708 : 0xCC49525E;
        int issueW = Math.max(40, this.font.width(issueLabel) + 14);
        bx -= 4 + issueW;
        button(g, bx, panelY + 4, issueW, bh, issueLabel, issueColor, issueHover, () -> {
            issuesOpen = !issuesOpen;
            issuesScroll = 0;
            refreshIssues();
        }, mx, my);
        bx -= 4 + 46;
        button(g, bx, panelY + 4, 46, bh, T_CLOSE.getString(), 0xCC5B6472, 0xCC49525E, this::closeEditor, mx, my);
        if (!selection.isEmpty() || !selectedTriggers.isEmpty()) {
            bx -= 4 + 68;
            button(g, bx, panelY + 4, 68, bh, T_TPL_SAVE.getString(), 0xCC7C3AED, 0xCC6D2FD9, this::saveSelectionAsTemplate, mx, my);
        }

        // title + status sit between the name box and the button group. The
        // status is right-aligned against the group's left edge so the extra
        // 存为模板 button (visible while blocks are selected) can never cover
        // it; the decorative title yields first when space runs out.
        int titleX = namePillX() + 120 + 12;
        int groupLeft = bx;
        String status = null;
        int col = C_TEXT_SUB;
        if (statusTicks > 0 && !statusText.isEmpty()) {
            status = statusText;
            col = statusColor;
        } else if (dirty) {
            status = T_DIRTY.getString();
            col = C_DIRTY;
        }
        int statusX = groupLeft - 12 - (status != null ? this.font.width(status) : 0);
        if (status != null && statusX >= titleX) {
            text(g, status, statusX, panelY + 10, col);
        }
        if (titleX + this.font.width(T_TITLE.getString()) + 10 < (status != null ? statusX : groupLeft)) {
            text(g, T_TITLE.getString(), titleX, panelY + 10, C_TEXT);
        }
        if (!program.triggers.isEmpty()) {
            String hint = "  / 搜索卡片";
            int hintX = groupLeft - 8 - this.font.width(hint);
            if (hintX > titleX) {
                text(g, hint, hintX, panelY + 10, 0xFF5C6779);
            }
        }
    }

    private void button(GuiGraphics g, int x, int y, int w, int h, String label, int color, int hoverColor,
                        Runnable action, double mx, double my) {
        boolean hover = mx >= x && mx < x + w && my >= y && my < y + h;
        rounded(g, x, y, w, h, 5, hover ? hoverColor : color);
        String t = label;
        g.drawString(this.font, t, x + (w - this.font.width(t)) / 2, y + (h - 8) / 2, 0xFFFFFFFF, false);
        uiHits.add(hit(x, y, w, h, K_CLICK, null, action));
    }

    private void renderPreview(GuiGraphics g) {
        int py = previewTop();
        int ph = panelY + panelH - 6 - py;
        rounded(g, canvasX, py, canvasW, ph, 8, G_CARD_TRANS);
        border(g, canvasX, py, canvasW, ph, G_BORDER_SOFT);
        text(g, "SFML 代码编辑", canvasX + 9, py + 7, C_TEXT);
        String state = this.font.plainSubstrByWidth(codeStatusText, Math.max(40, canvasW - 265));
        text(g, state, canvasX + 92, py + 7, codeStatusColor);
        String help = "Tab缩进 · Ctrl+/注释 · Ctrl+空格检查 · \\ 接受建议";
        int helpW = this.font.width(help);
        if (canvasW > helpW + 285) text(g, help, canvasX + canvasW - helpW - 9, py + 7, C_TEXT_SUB);
    }

    private void layoutCodeEditor() {
        if (codeEditor == null || !previewMode) return;
        int py = previewTop();
        int ph = panelY + panelH - 6 - py;
        codeEditor.setX(canvasX + 4);
        codeEditor.setY(py + 20);
        codeEditor.setWidth(Math.max(80, canvasW - 8));
        codeEditor.setHeight(Math.max(40, ph - 24));
        codeEditor.visible = true;
    }

    private void toggleCodeEditor() {
        previewMode = !previewMode;
        if (codeEditor != null) {
            codeEditor.visible = previewMode;
            if (previewMode) {
                layoutCodeEditor();
                if (!codeTextEdited) {
                    settingCodeFromModel = true;
                    codeEditor.setValueFromModel(generated());
                    settingCodeFromModel = false;
                    lastModelSfml = generated();
                }
                setInitialFocus(codeEditor);
                codeSuggestDelay = 2;
            } else {
                codeEditor.setFocused(false);
                setInitialFocus(nameBox);
            }
        }
    }

    // ============================================================== card render

    /** 卡片快速定位：搜索卡摘要/标签/名称，点选相机居中。 */
    private void openCardSearch() {
        List<String> values = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (BProgram.Trigger t : program.triggers) {
            String summary = cardSummary(t);
            Set<String> cardLabels = new LinkedHashSet<>();
            collectBodyLabels(t.body, cardLabels);
            values.add("t:" + t.id);
            labels.add((t instanceof BProgram.TimerTrigger ? "⟳" : "⚡") + " "
                    + summary + (cardLabels.isEmpty() ? "" : " [" + String.join(",", cardLabels) + "]"));
        }
        if (values.isEmpty()) return;
        setPopup(new Popup.ChoicePopup(panelX + PALETTE_W + 30, panelY + TOOLBAR_H + 12, 300,
                values, labels, "", picked -> {
            if (!picked.startsWith("t:")) return;
            long id = Long.parseLong(picked.substring(2));
            int[] r = layout.cardRectOf(id);
            if (r != null) {
                zoom = Math.max(0.6f, Math.min(1.25f, zoom));
                viewX = Math.round(r[0] + r[2] / 2f - canvasW / (2f * zoom));
                viewY = Math.round(r[1] + r[3] / 2f - canvasH / (2f * zoom));
            }
        }));
        showStatus("输入关键词搜索卡片（标签名/摘要），点选定位", C_TEXT_SUB);
    }

    private void collectBodyLabels(List<BProgram.Statement> body, Set<String> out) {
        for (BProgram.Statement s : body) {
            if (s instanceof BProgram.Statement.Input in) out.addAll(in.access.labels);
            else if (s instanceof BProgram.Statement.Output o) out.addAll(o.access.labels);
            else if (s instanceof BProgram.Statement.Forget f) out.addAll(f.labels);
            else if (s instanceof BProgram.Statement.If iff) {
                for (BProgram.Branch b : iff.branches) {
                    if (b.cond instanceof BProgram.Bool.Has h) out.addAll(h.access.labels);
                    collectBodyLabels(b.body, out);
                }
                collectBodyLabels(iff.elseBody, out);
            }
        }
    }

    // ---- 预览匹配：标签/NBT 条件 → 匹配物品列表 ----

    static boolean matchesFilter(BProgram.WithExpr expr, net.minecraft.world.item.ItemStack stack) {
        if (expr instanceof BProgram.WithExpr.Tag tag) {
            if (tag.matcher.startsWith("nbt:")) {
                return io.github.xianynomial.sfmfactorystudio.net.NbtMatcherHook.matchesComponent(tag.matcher, stack);
            }
            return itemMatchesTag(stack, tag.matcher);
        }
        if (expr instanceof BProgram.WithExpr.Not not) return !matchesFilter(not.inner, stack);
        if (expr instanceof BProgram.WithExpr.And and) {
            for (var p : and.parts) if (!matchesFilter(p, stack)) return false;
            return true;
        }
        if (expr instanceof BProgram.WithExpr.Or or) {
            for (var p : or.parts) if (matchesFilter(p, stack)) return true;
            return false;
        }
        return false;
    }

    static boolean itemMatchesTag(net.minecraft.world.item.ItemStack stack, String matcher) {
        var holder = net.minecraft.core.registries.BuiltInRegistries.ITEM.wrapAsHolder(stack.getItem());
        for (var tagKey : holder.tags().toList()) {
            if (tagMatcherMatches(matcher, tagKey.location())) return true;
        }
        return false;
    }

    static boolean tagMatcherMatches(String matcher, net.minecraft.resources.ResourceLocation tag) {
        String ns, path;
        int colon = matcher.indexOf(':');
        if (colon > 0) {
            ns = matcher.substring(0, colon);
            path = matcher.substring(colon + 1).replace('/', '.');
        } else {
            ns = ".*";
            path = matcher.replace('/', '.');
        }
        return io.github.xianynomial.sfmfactorystudio.net.NbtMatcherHook.wildcardMatches(ns, tag.getNamespace())
                && io.github.xianynomial.sfmfactorystudio.net.NbtMatcherHook.wildcardMatches(path, tag.getPath());
    }

    private void openFilterPreview(BProgram.WithFilter filter) {
        var mc = Minecraft.getInstance();
        List<net.minecraft.world.item.ItemStack> matched = new ArrayList<>();
        if (mc.player != null) {
            var inv = mc.player.getInventory();
            for (int i = 0; i < inv.getContainerSize(); i++) {
                var st = inv.getItem(i);
                if (!st.isEmpty() && matchesFilter(filter.expr, st)
                        && !matched.stream().anyMatch(m -> m.getItem() == st.getItem())) {
                    matched.add(st.copy());
                }
            }
        }
        for (var item : net.minecraft.core.registries.BuiltInRegistries.ITEM) {
            if (matched.size() >= PREVIEW_LIMIT) break;
            var st = new net.minecraft.world.item.ItemStack(item);
            if (matchesFilter(filter.expr, st)
                    && !matched.stream().anyMatch(m -> m.getItem() == item)) {
                matched.add(st);
            }
        }
        if (matched.isEmpty()) {
            showStatus("没有找到匹配的物品——检查条件是否太严", 0xFFB45309);
            return;
        }
        List<String> values = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < Math.min(PREVIEW_LIMIT, matched.size()); i++) {
            values.add("m:" + i);
            labels.add(matched.get(i).getHoverName().getString());
        }
        setPopup(new Popup.ChoicePopup(panelX + panelW / 2 - 130, panelY + panelH / 2 - 60,
                260, values, labels, "", picked -> {
                    showStatus("匹配预览共 " + matched.size() + " 件物品", C_TEXT_SUB);
                }));
    }

    /** 在指定内容坐标处新建一张触发器卡（右键菜单/引导卡用）。 */
    private void createCardAt(String kind, double cx, double cy) {
        pushUndo();
        BProgram.Trigger t = newTriggerCard(kind);
        program.triggers.add(t);
        layout.setCardPos(t.id,
                CardLayouts.snap((int) cx - CARD_W / 2),
                CardLayouts.snap((int) cy - HEAD_H / 2));
        layoutDirty = true;
        showStatus("已在此处新建触发器，从左侧拖入积木", C_SELECT);
    }

    /** 空画布引导卡：三张可点击的"第一步"选项，简单直接不出错。 */
    private void renderGuideCards(GuiGraphics g, int mx, int my) {
        String[][] guides = {
                {"⟳", "创建定时触发器", "timer"},
                {"⚡", "创建脉冲触发器", "pulse"},
                {"▶", "使用熔炉模板", "tpl_smelt"}};
        int gw = 160, gh = 44, gap = 12;
        int total = guides.length * gw + (guides.length - 1) * gap;
        int startX = canvasX + (canvasW - total) / 2;
        int startY = canvasY + Math.max(40, canvasH / 4);
        for (int i = 0; i < guides.length; i++) {
            int gx = startX + i * (gw + gap);
            boolean hover = mx >= gx && mx < gx + gw && my >= startY && my < startY + gh;
            rounded(g, gx + 2, startY + 3, gw, gh, 8, 0x30203A5A);
            rounded(g, gx, startY, gw, gh, 8, 0xFFFFFFFF);
            border(g, gx, startY, gw, gh, hover ? C_SELECT : G_BORDER);
            g.fill(gx, startY + 4, gx + 4, startY + gh - 4, hover ? C_SELECT : 0xFFBCC9D8);
            g.drawString(this.font, guides[i][0], gx + 12, startY + 14, 0xFF1B2432, false);
            g.drawString(this.font, guides[i][1], gx + 26, startY + 16,
                    hover ? C_SELECT : C_TEXT_SUB, false);
            final String kind = guides[i][2];
            final double ccx = ctX(gx + gw / 2.0), ccy = ctY(startY + gh / 2.0);
            uiHits.add(hit(gx, startY, gw, gh, K_CLICK, null, () -> {
                if (kind.equals("tpl_smelt")) {
                    clickAdd("tpl_smelt");
                    showStatus("已插入熔炉模板，连接方块标签即可使用", C_SELECT);
                } else {
                    createCardAt(kind, ccx, ccy);
                }
            }));
        }
        String hint = "从左侧拖入积木 · 滚轮缩放 · 拖动平移 · 右键菜单";
        text(g, hint, canvasX + canvasW / 2 - this.font.width(hint) / 2,
                startY + gh + 16, C_TEXT_SUB);
    }

    private void renderCard(GuiGraphics g, CardL c, int mx, int my) {
        BProgram.Trigger t = c.trigger();
        boolean timer = t instanceof BProgram.TimerTrigger;
        int accent = timer ? A_TIMER : A_PULSE;
        int x = c.x(), y = c.y(), w = c.w(), h = c.h();

        rounded(g, x + 2, y + 3, w, h - 3, 8, G_SHADOW);
        rounded(g, x, y, w, h - 3, 8, G_CARD);
        int headBg = mix(G_CARD, accent, 26);
        rounded(g, x, y, w, HEAD_H, 8, headBg);
        g.fill(x, y + HEAD_H / 2, x + w, y + HEAD_H, headBg);
        rounded(g, x, y, 6, HEAD_H, 3, accent);
        border(g, x, y, w, h - 3, G_BORDER);

        // 头部抓手：3×2 点阵，提示"这里可以拖"。画在 accent 条右侧，不占额外宽度。
        int gripC = mix(accent, 0xFFFFFFFF, 120);
        for (int r = 0; r < 3; r++) {
            for (int c2 = 0; c2 < 2; c2++) {
                g.fill(x + 8 + c2 * 3, y + 9 + r * 4, x + 10 + c2 * 3, y + 11 + r * 4, gripC);
            }
        }

        // trigger-level selection / drag feedback: bold blue frame
        if (selectedTriggers.contains(t) || t == dragTrigger) {
            border(g, x - 2, y - 2, w + 4, h + 1, C_SELECT);
            border(g, x - 1, y - 1, w + 2, h - 1, 0xFF9CC7FF);
        }

        // header grab zone — registered BEFORE the header's own fields so the
        // number/unit/icon hits (added later) keep priority over the drag zone
        // 整卡占位命中（最先注册=优先级最低）：卡的每一个像素都归卡所有，
        // 后续注册的标题/积木行/页脚按钮全部排在它前面照常优先。
        // 有了它，框选永远不可能从卡内任何空白（页脚/折叠/LOD/行间隙）起手。
        hits.add(hit(x, y, w, h, K_BODY_SEL, t.body, null));
        hits.add(hit(x, y, w, HEAD_H, K_HEAD, t, null));

        // 头部左上角 ✕：删除这一张卡（永远删“自己”，与底部 − 的“删副本”分工）
        drawIcon(g, x + 1, y + 4, "✕", () -> deleteTrigger(t), mx, my, 0xFFC22B21);

        int fx = x + 20;
        text(g, timer ? "⟳" : "⚡", fx, y + 10, accent);
        fx += 14;
        if (timer) {
            var tt = (BProgram.TimerTrigger) t;
            fx = drawText(g, fx, y + 4, T_EVERY.getString());
            int numberX = fx;
            String countText = Long.toString(tt.count);
            int numberW = Math.max(26, this.font.width(countText) + 10);
            boolean invalidInterval = tt.count < TimerRules.minimumCount(tt);
            boolean numberHover = overField(mx, my, numberX, y + 7, numberW, BAR_H - 6);
            pill(g, numberX, y + 7, numberW, BAR_H - 6, numberHover);
            g.drawString(this.font, countText,
                    numberX + (numberW - this.font.width(countText)) / 2, y + 10,
                    invalidInterval ? 0xFFC22B21 : 0xFF0B5B39, false);
            hits.add(hit(numberX, y + 7, numberW, BAR_H - 6, K_CLICK, null,
                    () -> openTimerNumber(numberX, y + 7, numberW, tt)));
            fx += numberW + 4;
            fx = drawChoice(g, fx, y + 4, tt.unit == BProgram.TimerTrigger.Unit.TICKS ? T_TICKS.getString() : T_SECONDS.getString(),
                    List.of("ticks", "seconds"),
                    List.of(T_TICKS.getString(), T_SECONDS.getString()),
                    v -> {
                        pushUndo();
                        BProgram.TimerTrigger.Unit next = v.equals("seconds")
                                ? BProgram.TimerTrigger.Unit.SECONDS : BProgram.TimerTrigger.Unit.TICKS;
                        if (tt.unit != next) {
                            if (next == BProgram.TimerTrigger.Unit.SECONDS) {
                                tt.count = Math.max(1, tt.count > Long.MAX_VALUE - 19
                                        ? Long.MAX_VALUE / 20 + 1 : (tt.count + 19) / 20);
                            } else {
                                tt.count = tt.count > Long.MAX_VALUE / 20 ? Long.MAX_VALUE : tt.count * 20;
                            }
                            tt.unit = next;
                        }
                        long minimum = TimerRules.minimumCount(tt);
                        if (tt.count < minimum) {
                            showStatus("当前内容最少需要 " + minimum
                                    + (tt.unit == BProgram.TimerTrigger.Unit.TICKS ? " 刻" : " 秒")
                                    + "；不会偷偷修改你的输入", 0xFFB45309);
                        }
                    }, mx, my);
            fx = drawText(g, fx, y + 4, T_DO.getString());
            // 全局/偏移已从 UI 移除（进阶语法，模型与序列化保留以无损往返旧程序）
        } else {
            fx = drawText(g, fx, y + 4, T_PULSE.getString());
        }

        // 头部右上：◆ 连线手柄（整枚图标 ~22×24 任意位置可点）+ ▼ 折叠 + ◀ ▶ 排序
        int ix = x + w - 8;
        int linkX = ix - 22;
        boolean linkHot = linkDragFrom == t.id
                || (linkDragFrom < 0 && mx >= linkX && mx < linkX + 22 && my >= y + 2 && my < y + 26);
        g.drawString(this.font, "◆", linkX + 3, y + 10, linkHot ? C_SELECT : C_TEXT, false);
        hits.add(hit(linkX, y + 2, 22, 24, K_CLICK, t, () -> {
            if (linkDragFrom < 0) linkDragFrom = t.id;
        }));
        ix = linkX;
        ix = drawIcon(g, ix - 22, y + 4, collapsedCards.contains(t.id) ? "▶" : "▼",
                () -> toggleCardCollapse(t), mx, my, C_TEXT_SUB);
        // 定时/红石切换：保留全部正文，只换触发方式
        drawField(g, ix - 38, y + 4,
                t instanceof BProgram.TimerTrigger ? "定时" : "红石",
                30, () -> convertTrigger(t), mx, my, false);
        ix -= 40;
        ix = drawIcon(g, ix - 22, y + 4, "▶", () -> {
            pushUndo();
            moveTrigger(t, 1);
        }, mx, my, 0xFF5B6472);
        drawIcon(g, ix - 22, y + 4, "◀", () -> {
            pushUndo();
            moveTrigger(t, -1);
        }, mx, my, 0xFF5B6472);

        // 正文：折叠 = 一行摘要；LOD（缩太小）= 摘要 + 提示；否则正常渲染。
        // 两种省略形态都要整卡占位命中：否则卡内空白会穿透成"空白画布"起框选。
        if (collapsedCards.contains(t.id)) {
            String cs = "▶ " + cardSummary(t);
            float csScale = 2.0f;
            int csW = (int)(this.font.width(cs) * csScale);
            int csH = (int)(this.font.lineHeight * csScale);
            int csX = x + (w - csW) / 2;
            if (csX < x + 4) csX = x + 4;
            int bodyTop = y + HEAD_H, bodyBot = y + h - FOOT_H;
            int csY = bodyTop + Math.max(2, (bodyBot - bodyTop - csH) / 2);
            g.pose().pushPose();
            g.pose().translate(csX, csY, 0);
            g.pose().scale(csScale, csScale, 1);
            g.drawString(this.font, cs, 0, 0, C_TEXT, false);
            g.pose().popPose();
        } else if (zoom < LOD_ZOOM) {
            // LOD 极端缩小：点击卡片=缩放到该卡可编辑大小
            hits.add(hit(x, y, w, h, K_CLICK, t, () -> {
                zoom = Math.max(0.6f, zoom);
                viewX = Math.round(x + w / 2f - canvasW / (2f * zoom));
                viewY = Math.round(y + h / 2f - canvasH / (2f * zoom));
                showStatus("已缩放到可编辑大小", C_SELECT);
            }));
            String cs = cardSummary(t) + "  点击放大";
            float csScale = 2.0f;
            int csW = (int)(this.font.width(cs) * csScale);
            int csH = (int)(this.font.lineHeight * csScale);
            int csX = x + (w - csW) / 2;
            if (csX < x + 4) csX = x + 4;
            int bodyTop = y + HEAD_H, bodyBot = y + h - FOOT_H;
            int csY = bodyTop + Math.max(2, (bodyBot - bodyTop - csH) / 2);
            g.pose().pushPose();
            g.pose().translate(csX, csY, 0);
            g.pose().scale(csScale, csScale, 1);
            g.drawString(this.font, cs, 0, 0, C_TEXT, false);
            g.pose().popPose();
        } else {
            int by = y + HEAD_H + 6;
            renderBody(g, t.body, x + CARD_INNER, by, mx, my);
        }

        // footer: [−] removes the complete run card; [＋] makes a deep copy of it.
        if (!collapsedCards.contains(t.id) && zoom >= LOD_ZOOM) {
            int duplicateX = x + w - 28;
            int removeX = duplicateX - 24;
            int actionY = y + h - FOOT_H;
            g.fill(x + 6, y + h - 10, removeX - 5, y + h - 4, mix(G_CARD, accent, 22));
            drawTriggerFooterButton(g, removeX, actionY, "−", 0xFFC22B21,
                    () -> deleteTriggerSmart(t), mx, my);
            drawTriggerFooterButton(g, duplicateX, actionY, "＋", accent,
                    () -> duplicateTriggerBelow(t, h), mx, my);
        }
    }

    private void toggleCardCollapse(BProgram.Trigger t) {
        if (!collapsedCards.remove(t.id)) collapsedCards.add(t.id);
        layout.setCollapsedCards(collapsedCards);
        layout.markBodyDirty(t.body);
        layoutDirty = true;
    }

    private void toggleIfCollapse(BProgram.Statement.If iff) {
        if (!collapsedIfs.remove(iff.id)) collapsedIfs.add(iff.id);
        layout.setCollapsedIfs(collapsedIfs);
        layout.markAllDirty();
        layoutDirty = true;
    }

    /** 卡片摘要（折叠/LOD 时显示）：顶层积木计数。 */
    /** 卡片摘要：触发器类型 + 使用的标签 + 取放计数——一眼认出这张卡是干嘛的。 */
    private String cardSummary(BProgram.Trigger t) {
        String head;
        if (t instanceof BProgram.TimerTrigger tt) {
            head = "⟳" + tt.count + (tt.unit == BProgram.TimerTrigger.Unit.TICKS ? "刻" : "秒");
        } else {
            head = "⚡脉冲";
        }
        Set<String> labels = new LinkedHashSet<>();
        collectBodyLabels(t.body, labels);
        String labelStr = labels.isEmpty() ? "" : " " + String.join("→", labels);
        int in = 0, out = 0;
        for (BProgram.Statement s : t.body) {
            if (s instanceof BProgram.Statement.Input) in++;
            else if (s instanceof BProgram.Statement.Output) out++;
        }
        String counts = "";
        if (in > 0) counts += " " + in + "取出";
        if (out > 0) counts += " " + out + "放入";
        return head + labelStr + counts;
    }

    private void drawTriggerFooterButton(GuiGraphics g, int x, int y, String icon, int accent,
                                         Runnable onClick, int mx, int my) {
        int w = 20, h = 18;
        boolean hover = overField(mx, my, x, y, w, h);
        rounded(g, x + 1, y + 2, w, h, 6, G_SHADOW);
        rounded(g, x, y, w, h, 6, hover ? C_SELECT : mix(G_CARD, accent, 72));
        border(g, x, y, w, h, hover ? 0xFF1F5FD0 : mix(G_BORDER, accent, 110));
        g.drawString(this.font, icon, x + (w - this.font.width(icon)) / 2, y + 5,
                hover ? 0xFFFFFFFF : C_TEXT, false);
        hits.add(hit(x, y, w, h, K_CLICK, null, onClick));
    }

    private void deleteTrigger(BProgram.Trigger trigger) {
        if (!program.triggers.contains(trigger)) return;
        pushUndo();
        program.triggers.remove(trigger);
        layout.removeCardPos(trigger.id);
        selectedTriggers.remove(trigger);
        if (keepPosTrigger == trigger) keepPosTrigger = null;
        showStatus("已删除完整运行积木", 0xFFC22B21);
    }

    /**
     * 底部 − 的语义（用户拍板）：同指纹的“副本”堆在正下方时，删掉最近复制
     * 的那个副本（后进先出，像计数器减一）；独立卡没有副本可删，才删自己。
     * 要删“这一张”卡永远用头部左上角 ✕。
     */
    private void deleteTriggerSmart(BProgram.Trigger t) {
        BProgram.Trigger copy = copyBelowOf(t);
        deleteTrigger(copy != null ? copy : t);
    }

    /** 同列、贴在本卡下方、同触发头指纹的最近一张卡（＋复制出来的副本）。 */
    private @Nullable BProgram.Trigger copyBelowOf(BProgram.Trigger t) {
        int[] r = layout.cardRectOf(t.id);
        if (r == null) return null;
        String key = CardLayouts.triggerKey(t);
        BProgram.Trigger best = null;
        int bestY = Integer.MAX_VALUE;
        for (BProgram.Trigger other : program.triggers) {
            if (other == t || !CardLayouts.triggerKey(other).equals(key)) continue;
            int[] or = layout.cardRectOf(other.id);
            if (or == null || Math.abs(or[0] - r[0]) > 8) continue;       // 同列
            if (or[1] < r[1] + r[3] - 8) continue;                        // 在本卡下方（紧贴也算）
            if (or[1] < bestY) {
                bestY = or[1];
                best = other;
            }
        }
        return best;
    }

    private void duplicateTriggerBelow(BProgram.Trigger source, int sourceHeight) {
        int sourceIndex = program.triggers.indexOf(source);
        if (sourceIndex < 0) return;
        pushUndo();
        BProgram.Trigger copy = source.copy();
        program.triggers.add(sourceIndex + 1, copy);
        int[] sourcePos = layout.cardPosOf(source.id);
        int x = sourcePos == null ? 0 : sourcePos[0];
        int y = sourcePos == null ? 0 : sourcePos[1];
        // 紧贴对齐：副本直接贴在原卡正下方（零间距，用户拍板 2026-09-02）
        layout.setCardPos(copy.id, x, CardLayouts.snap(y + sourceHeight));
        keepPosTrigger = copy;
        selection.clear();
        selectedTriggers.clear();
        selectedTriggers.add(copy);
        showStatus("已复制完整运行积木，紧贴排列在正下方", C_SELECT);
    }

    /**
     * 定时 ↔ 红石脉冲 互转：正文/注释原样迁移，卡片位置/折叠/展开/选中/连线
     * 全部跟着换到新触发器上；定时参数（周期/全局/偏移）在转红石时丢弃，
     * 转回定时恢复默认 20 刻。
     */
    private void convertTrigger(BProgram.Trigger old) {
        int idx = program.triggers.indexOf(old);
        if (idx < 0) return;
        pushUndo();
        BProgram.Trigger next;
        if (old instanceof BProgram.TimerTrigger) {
            next = new BProgram.PulseTrigger();
        } else {
            BProgram.TimerTrigger timer = new BProgram.TimerTrigger();
            timer.count = Math.max(TimerRules.minimumCount(timer), timer.count);
            next = timer;
        }
        next.body.addAll(old.body);
        next.leadingComments.addAll(old.leadingComments);
        program.triggers.set(idx, next);

        // 位置/折叠/展开/选中/连线随 id 迁移
        int[] pos = layout.cardPosOf(old.id);
        if (pos != null) layout.setCardPos(next.id, pos[0], pos[1]);
        if (expandedIds.remove(old.id)) expandedIds.add(next.id);
        if (collapsedCards.remove(old.id)) collapsedCards.add(next.id);
        if (selectedTriggers.remove(old)) selectedTriggers.add(next);
        for (int i = 0; i < links.size(); i++) {
            BlockLink l = links.get(i);
            if (l.a() == old.id) links.set(i, new BlockLink(next.id, l.b()));
            else if (l.b() == old.id) links.set(i, new BlockLink(l.a(), next.id));
        }
        layoutDirty = true;
        showStatus(next instanceof BProgram.TimerTrigger ? "已切换为定时触发" : "已切换为红石脉冲触发", C_SELECT);
    }

    private void moveTrigger(BProgram.Trigger t, int dir) {
        int i = program.triggers.indexOf(t);
        int j = i + dir;
        if (j < 0 || j >= program.triggers.size()) return;
        program.triggers.set(i, program.triggers.get(j));
        program.triggers.set(j, t);
    }

    /** Renders a body using layout positions; returns end y. */
    private int renderBody(GuiGraphics g, List<BProgram.Statement> list, int x, int y, int mx, int my) {
        // 卡片可能因备选资源被加宽：用布局的真实宽度，保证命中框与可见性裁剪正确
        int w = layout.bodyWidthOf(list);
        // body background click target (lowest priority: registered first)
        int[] pos = layout.addRowPosOf(list);
        if (pos != null) {
            int bodyHitHeight = Math.max(ADD_H, pos[1] + ADD_H - y + 8);
            if (contentVisible(x, y - 4, w, bodyHitHeight)) {
                hits.add(hit(x, y - 4, w, bodyHitHeight, K_BODY_SEL, list, null));
            }
        }
        for (int i = 0; i < list.size(); i++) {
            BProgram.Statement s = list.get(i);
            int height = layout.heightOf(s);
            if (contentVisible(x, y, w, height)) {
                renderStatement(g, list, i, s, x, y, mx, my, w);
            }
            y += height + ROW_GAP;
        }
        int[] ap = layout.addRowPosOf(list);
        if (ap != null) {
            if (contentVisible(x, ap[1], w, ADD_H)) {
                renderAddRow(g, list, x, ap[1], mx, my);
            }
            return ap[1] + ADD_H;
        }
        return y;
    }

    private int renderStatement(GuiGraphics g, List<BProgram.Statement> list, int index,
                                BProgram.Statement s, int x, int y, int mx, int my, int w) {
        int[] r = layout.rowRectOf(s.id);
        if (r == null) return y;
        int ry = r[1], rw = r[2];
        if (s instanceof BProgram.Statement.Input in) {
            renderIO(g, list, index, s, in, x, ry, rw, mx, my, A_INPUT, T_INPUT.getString());
        } else if (s instanceof BProgram.Statement.Output out) {
            renderIO(g, list, index, s, out, x, ry, rw, mx, my, A_OUTPUT, T_OUTPUT.getString());
        } else if (s instanceof BProgram.Statement.Forget f) {
            renderForget(g, list, index, f, x, ry, rw, mx, my);
        } else if (s instanceof BProgram.Statement.If iff) {
            renderIf(g, list, index, iff, x, ry, rw, mx, my);
        } else if (s instanceof BProgram.Statement.Comment c) {
            renderComment(g, list, index, c, x, ry, rw, mx, my);
        } else if (s instanceof BProgram.Statement.Raw raw) {
            renderRaw(g, list, index, raw, x, ry, rw, mx, my);
        }
        return y;
    }

    private void barBase(GuiGraphics g, int x, int y, int w, int h, int accent, boolean selected) {
        rounded(g, x + 1, y + 2, w, h, 6, G_SHADOW);
        rounded(g, x, y, w, h, 6, selected ? mix(G_CARD, C_SELECT, 30) : G_CARD);
        border(g, x, y, w, h, selected ? C_SELECT : G_BORDER);
        g.fill(x, y + 2, x + 3, y + h - 2, accent);
    }

    private void registerBarGrip(List<BProgram.Statement> list, int index, BProgram.Statement s, int x, int y, int w, String label, int accent) {
        hits.add(hit(x, y, w, BAR_H, K_GRIP, new DragRef(list, s, index, label, accent), null));
        // 右键任意积木行：复制这一条（SFML 往返深拷贝，Ctrl+V 或右键空白处粘贴）
        hits.add(hit(x, y, w, BAR_H, K_RCLICK, null, () -> copySingleStatement(s)));
    }

    /** 右键复制的单积木深拷贝：先拷贝模型，再用 SFML 往返验证可转换。 */
    private void copySingleStatement(BProgram.Statement s) {
        BProgram tmp = new BProgram();
        tmp.triggers.add(new BProgram.TimerTrigger());
        tmp.triggers.get(0).body.add(s.copy());
        String sfml = BlocksToSfml.toSfml(tmp);
        SfmlToBlocks.Result check = SfmlToBlocks.parse("every 20 ticks do\n" + sfml + "\nend");
        if (!check.ok()) {
            showStatus("复制失败：这条积木无法转换为代码", 0xFFD13438);
            return;
        }
        clipboardSfml = sfml;
        clipboardTriggers = false;
        showStatus("已复制 1 个积木（Ctrl+V 粘贴）", C_SELECT);
    }

    private void renderIO(GuiGraphics g, List<BProgram.Statement> list, int index,
                          BProgram.Statement stmt, BProgram.Statement.Input in,
                          int x, int y, int w, int mx, int my, int accent, String verb) {
        boolean selected = selection.contains(stmt);
        barBase(g, x, y, w, BAR_H, accent, selected);
        registerBarGrip(list, index, stmt, x, y, w, verb, accent);
        final int sxx = x, syy = y;
        // 中文语序：从 [标签] 方块取出 [数量] [资源]
        int fx = x + 12;
        fx = drawText(g, fx, y, T_IO_FROM.getString());
        String labelDisp = in.access.labels.isEmpty() ? T_LABEL.getString() : String.join("+", in.access.labels);
        hits.add(hit(fx, y, 32, BAR_H, K_RCLICK, null, () -> openLabelContext(sxx, syy, in.access.labels)));
        fx = drawField(g, fx, y, labelDisp, 32,
                () -> openLabelEditor(sxx, syy, in.access.labels), mx, my, false);
        fx = drawText(g, fx, y, T_IO_TAKE.getString());
        BProgram.ResourceLimit rl = primaryLimit(in.limits);
        fx = drawInlineQuantity(g, fx, y, rl, mx, my);
        fx = drawResourceField(g, fx, y, rl, 42, mx, my);
        if (rl == null || rl.resources.size() <= 1) {
            fx = drawAndAddSlot(g, fx, y, rl, mx, my);
        }
        fx = drawField(g, fx, y, expandedIds.contains(stmt.id) ? "收起" : "展开", 34,
                () -> expandOrMenu(stmt, in, x, y, list, index), mx, my, true);
        drawDelete(g, x + w - 16, y + 3, () -> {
            pushUndo();
            list.remove(index);
        }, mx, my);

        drawAltResourceRows(g, x, y + BAR_H, rl, mx, my);

        if (expandedIds.contains(stmt.id)) {
            renderIOOptions(g, x + INDENT, y + BAR_H + EditorLayout.altResourceRows(in.limits) * BAR_H, mx, my, in, list, index);
        }
    }

    private void renderIO(GuiGraphics g, List<BProgram.Statement> list, int index,
                          BProgram.Statement stmt, BProgram.Statement.Output out,
                          int x, int y, int w, int mx, int my, int accent, String verb) {
        boolean selected = selection.contains(stmt);
        barBase(g, x, y, w, BAR_H, accent, selected);
        registerBarGrip(list, index, stmt, x, y, w, verb, accent);
        final int sxx = x, syy = y;
        // 中文语序：放入 [标签] 方块 [数量] [资源]
        int fx = x + 12;
        fx = drawText(g, fx, y, T_IO_PUT.getString());
        String labelDisp = out.access.labels.isEmpty() ? T_LABEL.getString() : String.join("+", out.access.labels);
        hits.add(hit(fx, y, 32, BAR_H, K_RCLICK, null, () -> openLabelContext(sxx, syy, out.access.labels)));
        fx = drawField(g, fx, y, labelDisp, 32,
                () -> openLabelEditor(sxx, syy, out.access.labels), mx, my, false);
        fx = drawText(g, fx, y, T_IO_BLOCK.getString());
        BProgram.ResourceLimit rl = primaryLimit(out.limits);
        fx = drawInlineQuantity(g, fx, y, rl, mx, my);
        fx = drawResourceField(g, fx, y, rl, 42, mx, my);
        if (rl == null || rl.resources.size() <= 1) {
            fx = drawAndAddSlot(g, fx, y, rl, mx, my);
        }
        fx = drawField(g, fx, y, expandedIds.contains(stmt.id) ? "收起" : "展开", 34,
                () -> expandOrMenu(stmt, out, x, y, list, index), mx, my, true);
        drawDelete(g, x + w - 16, y + 3, () -> {
            pushUndo();
            list.remove(index);
        }, mx, my);

        drawAltResourceRows(g, x, y + BAR_H, rl, mx, my);

        if (expandedIds.contains(stmt.id)) {
            renderOutputOptions(g, x + INDENT, y + BAR_H + EditorLayout.altResourceRows(out.limits) * BAR_H, mx, my, out, list, index);
        }
    }

    /** 数量点选：常用值一键即选，免弹键盘；手动输入作为第二入口。 */
    private void openQtyQuickPick(int x, int y, BProgram.ResourceLimit rl) {
        List<String> values = List.of("1", "16", "32", "64", "all", "manual");
        List<String> labels = new ArrayList<>(List.of("1", "16", "32", "64", "全部", "手动输入…"));
        setPopup(new Popup.ChoicePopup(sX(x), sY(y) + BAR_H, 150, values, labels, "", picked -> {
            if (picked.equals("manual")) {
                openNumber(x, y, 40, rl.quantity == null ? 0 : rl.quantity, v -> {
                    rl.quantity = v <= 0 ? null : v;   // openNumber 已推撤销；0/清空 = 全部
                    if (rl.quantity == null) rl.quantityEach = false;
                    layoutDirty = true;
                });
                return;
            }
            pushUndo();
            rl.quantity = picked.equals("all") ? null : Long.parseLong(picked);
            if (rl.quantity == null) rl.quantityEach = false;
            layoutDirty = true;
        }));
    }

    /**
     * 「和」空位：默认常驻一个空槽，点击选资源或从 JEI 拖入即新增一个备选；
     * 不填则完全不参与程序。与主槽同样的视觉（灰底空槽+淡＋号）。
     */
    private int drawAndAddSlot(GuiGraphics g, int x, int y, BProgram.ResourceLimit rl, int mx, int my) {
        if (rl == null) return x;
        int fx = drawText(g, x, y, "和");
        final int px = fx, py = y;
        int size = BAR_H;
        boolean hover = overField(mx, my, fx, y, size, size);
        g.fill(fx + 1, y + 1, fx + size - 1, y + size - 1, hover ? 0x997080A0 : 0x90606B7E);
        g.fill(fx + 1, y + 1, fx + size - 1, y + 2, 0x8C2A2A2A);
        g.fill(fx + 1, y + 1, fx + 2, y + size - 1, 0x8C2A2A2A);
        g.fill(fx + 1, y + size - 2, fx + size - 1, y + size - 1, 0x8CFFFFFF);
        g.fill(fx + size - 2, y + 1, fx + size - 1, y + size - 1, 0x8CFFFFFF);
        g.drawString(this.font, "＋", fx + 6, y + 6, 0xFF8A94A6, false);
        hits.add(hit(fx, y, size, size, K_RCLICK, null, () -> openResourceSlotContext(px, py,
                BProgram.ResourceRef.forKind(BProgram.ResourceKind.ITEM), picked -> {
                    pushUndo();
                    rl.resources.add(picked);
                    layoutDirty = true;
                })));
        hits.add(hit(fx, y, size, size, K_CLICK, null, () -> openNewResourceKindMenu(px, py, res -> {
            pushUndo();
            rl.resources.add(res);
            layoutDirty = true;
        })));
        addGhostZone(fx, y, size, size, "*", dropped -> {
            try {
                BProgram.ResourceRef incoming = BProgram.ResourceRef.parse(dropped);
                pushUndo();
                rl.resources.add(incoming);
                layoutDirty = true;
            } catch (IllegalArgumentException ex) {
                showStatus("✖ 无法识别这个资源", 0xFFD13438);
            }
        });
        return fx + size + 4;
    }

    /**
     * 主组「或者也搬运」备选资源：**单行横向铺开**，不再按 4 个/行换行。
     *
     * 放不下时由 {@link EditorLayout#cardWidth} 把整张卡片横向加长（布局阶段已按
     * 同样的估算预留宽度），所以这里只管一路往右排——不会溢出卡片，也就不会和
     * 下面的积木、页脚按钮重叠。
     */
    /**
     * 主组备选资源续行：紧凑芯片（物品槽+✕，固定 30px 步进），排完后「和」
     * 空位收尾。不换行——卡片宽度由 EditorLayout 按内容计算（不重叠）。
     */
    private void drawAltResourceRows(GuiGraphics g, int x, int y, BProgram.ResourceLimit rl, int mx, int my) {
        if (rl == null || rl.resources.size() <= 1) return;
        int fx = x + 12;
        for (int idx = 1; idx < rl.resources.size(); idx++) {
            final int i2 = idx;
            fx = drawResourceValueSlot(g, fx, y, rl.resources.get(idx), picked -> {
                pushUndo();
                rl.resources.set(i2, picked);
            }, mx, my);
            fx = drawIcon(g, fx - 2, y, "✕", () -> {
                pushUndo();
                rl.resources.remove(i2);
                layoutDirty = true;
            }, mx, my, 0xFFC22B21);
        }
        drawAndAddSlot(g, fx, y, rl, mx, my);
    }

    /**
     * 展开按钮：已展开→收起；已有扩展内容→展开；还什么都没有→展开并直接弹
     * 添加菜单（省掉旧流程里「点扩展积木→再点添加扩展积木」的多一次点击）。
     */
    private void expandOrMenu(BProgram.Statement stmt, Object io, int x, int y,
                              List<BProgram.Statement> list, int index) {
        if (expandedIds.contains(stmt.id)) {
            toggleOptions(stmt);
            return;
        }
        boolean any = hasExtensionConfig(io);
        toggleOptions(stmt); // 展开区先立起来，加完内容立刻可见
        if (!any) openIOExtensionMenu(x, y, io, list, index);
    }

    /** 扩展区是否已有任何已配置内容（备选资源不算——它们常驻在语句主体上）。 */
    private boolean hasExtensionConfig(Object io) {
        if (io instanceof BProgram.Statement.Input in) {
            for (BProgram.ResourceLimit rl : in.limits) {
                if (rl != null && (rl.quantity != null || rl.retain != null || rl.with != null)) return true;
            }
            return !in.except.isEmpty() || in.access.eachSide || !in.access.sides.isEmpty()
                    || !in.access.slots.isEmpty()
                    || in.access.roundRobin != BProgram.RoundRobinMode.NONE || in.each;
        }
        BProgram.Statement.Output out = (BProgram.Statement.Output) io;
        for (BProgram.ResourceLimit rl : out.limits) {
            if (rl != null && (rl.quantity != null || rl.retain != null || rl.with != null)) return true;
        }
        return !out.except.isEmpty() || out.access.eachSide || !out.access.sides.isEmpty()
                || !out.access.slots.isEmpty()
                || out.access.roundRobin != BProgram.RoundRobinMode.NONE
                || out.each || out.emptySlots;
    }

    /**
     * 数量内联字段：默认「全部」（=不限制），点开输入数字；输 0 或清空恢复全部。
     * 设置数量后旁边出现「每种/合计」切换（quantityEach）。
     */
    private int drawInlineQuantity(GuiGraphics g, int x, int y, BProgram.ResourceLimit rl, int mx, int my) {
        if (rl == null) return x;
        String qtyDisp = rl.quantity == null ? T_QTY_ALL.getString() : String.valueOf(rl.quantity);
        final int px = x, py = y;
        int fx = drawField(g, x, y, qtyDisp, rl.quantity == null ? 30 : Math.max(26, font.width(qtyDisp) + 12),
                () -> openQtyQuickPick(px, py, rl), mx, my, false);
        if (rl.quantity != null) {
            fx = drawField(g, fx, y, rl.quantityEach ? T_QTY_EACH_KIND.getString() : T_QTY_TOTAL.getString(),
                    34, () -> {
                        pushUndo();
                        rl.quantityEach = !rl.quantityEach;
                    }, mx, my, false);
        }
        return fx;
    }

    private void renderIOOptions(GuiGraphics g, int x, int y, int mx, int my,
                                 BProgram.Statement.Input in, List<BProgram.Statement> list, int index) {
        renderIOExtensions(g, x, y, mx, my, in, list, index);
    }

    private void renderOutputOptions(GuiGraphics g, int x, int y, int mx, int my,
                                     BProgram.Statement.Output out, List<BProgram.Statement> list, int index) {
        renderIOExtensions(g, x, y, mx, my, out, list, index);
    }

    private void renderIOExtensions(GuiGraphics g, int x, int y, int mx, int my, Object io,
                                    List<BProgram.Statement> list, int index) {
        List<BProgram.ResourceLimit> limits;
        List<BProgram.ResourceRef> except;
        BProgram.LabelAccess access;
        int accent;
        if (io instanceof BProgram.Statement.Input input) {
            limits = input.limits;
            except = input.except;
            access = input.access;
            accent = A_INPUT;
        } else {
            BProgram.Statement.Output output = (BProgram.Statement.Output) io;
            limits = output.limits;
            except = output.except;
            access = output.access;
            accent = A_OUTPUT;
        }
        int w = layout.bodyWidthOf(list) - INDENT - 8;
        rounded(g, x - 4, y, w + 8, EditorLayout.ioOptionsHeight(io), 5, 0x44F0F4FA);
        if (limits.isEmpty()) limits.add(new BProgram.ResourceLimit());

        for (int i = 0; i < limits.size(); i++) {
            BProgram.ResourceLimit limit = limits.get(i);
            String groupPrefix = i == 0 ? "" : "第 " + (i + 1) + " 组：";
            if (i > 0) {
                int fx = extensionRow(g, x, y, w, accent, groupPrefix + "另外搬运");
                BProgram.ResourceRef current = firstResource(limit);
                final int limitIndex = i;
                fx = drawResourceSelector(g, fx, y, current, value -> {
                    pushUndo();
                    setFirstResource(limit, value);
                }, mx, my);
                drawIcon(g, x + w - 18, y, "✕", () -> {
                    pushUndo();
                    limits.remove(limitIndex);
                    layoutDirty = true;
                }, mx, my, 0xFFC22B21);
                y += OPT_H;
            }
            if (i > 0) for (int resourceIndex = 1; resourceIndex < limit.resources.size(); resourceIndex++) {
                BProgram.ResourceRef current = limit.resources.get(resourceIndex);
                int fx = extensionRow(g, x, y, w, accent, groupPrefix + "或者也搬运");
                final int ri = resourceIndex;
                fx = drawResourceSelector(g, fx, y, current, value -> {
                    pushUndo();
                    limit.resources.set(ri, value);
                }, mx, my);
                drawIcon(g, x + w - 18, y, "✕", () -> {
                    pushUndo();
                    limit.resources.remove(ri);
                    layoutDirty = true;
                }, mx, my, 0xFFC22B21);
                y += OPT_H;
            }
            if (limit.quantity != null && i > 0) {
                // 主组的数量已内联到语句行（默认「全部」）；这里只服务"另外搬运"的多余组
                int fx = extensionRow(g, x, y, w, accent, groupPrefix + "最多搬运");
                fx = drawNum(g, fx, y, limit.quantity, 28, value -> {
                    pushUndo();
                    limit.quantity = value;
                }, mx, my);
                fx = drawField(g, fx, y, limit.quantityEach ? "每种分别计算" : "全部合计", 56, () -> {
                    pushUndo();
                    limit.quantityEach = !limit.quantityEach;
                }, mx, my, false);
                drawIcon(g, x + w - 18, y, "✕", () -> {
                    pushUndo();
                    limit.quantity = null;
                    limit.quantityEach = false;
                    layoutDirty = true;
                }, mx, my, 0xFFC22B21);
                y += OPT_H;
            }
            if (limit.retain != null) {
                int fx = extensionRow(g, x, y, w, accent, groupPrefix + "至少留下");
                fx = drawNum(g, fx, y, limit.retain, 28, value -> {
                    pushUndo();
                    limit.retain = value;
                }, mx, my);
                fx = drawField(g, fx, y, limit.retainEach ? "每种分别保留" : "全部合计保留", 64, () -> {
                    pushUndo();
                    limit.retainEach = !limit.retainEach;
                }, mx, my, false);
                drawIcon(g, x + w - 18, y, "✕", () -> {
                    pushUndo();
                    limit.retain = null;
                    limit.retainEach = false;
                    layoutDirty = true;
                }, mx, my, 0xFFC22B21);
                y += OPT_H;
            }
            if (limit.with != null) {
                y = renderWithRow(g, x, y, w, accent, groupPrefix, limit, mx, my);
            }
        }

        for (int i = 0; i < except.size(); i++) {
            BProgram.ResourceRef current = except.get(i);
            int fx = extensionRow(g, x, y, w, accent, "排除");
            final int exceptIndex = i;
            fx = drawResourceSelector(g, fx, y, current, value -> {
                pushUndo();
                except.set(exceptIndex, value);
            }, mx, my);
            drawIcon(g, x + w - 18, y, "✕", () -> {
                pushUndo();
                except.remove(exceptIndex);
                layoutDirty = true;
            }, mx, my, 0xFFC22B21);
            y += OPT_H;
        }

        if (access.eachSide || !access.sides.isEmpty()) {
            int fx = extensionRow(g, x, y, w, accent, "指定侧面");
            final int fieldX = fx, rowY = y;
            fx = drawField(g, fx, y, sidesDisp(access), 80, () -> openSideEditor(fieldX, rowY, access), mx, my, false);
            drawIcon(g, x + w - 18, y, "✕", () -> {
                pushUndo();
                access.eachSide = false;
                access.sides.clear();
                layoutDirty = true;
            }, mx, my, 0xFFC22B21);
            y += OPT_H;
        }
        if (!access.slots.isEmpty()) {
            int fx = extensionRow(g, x, y, w, accent, "指定槽位");
            final int fieldX = fx, rowY = y;
            fx = drawField(g, fx, y, slotText(access.slots), 70,
                    () -> openTextEditor(fieldX, rowY, slotText(access.slots),
                            value -> setSlotsFromText(access.slots, value), null, 150), mx, my, false);
            drawIcon(g, x + w - 18, y, "✕", () -> {
                pushUndo();
                access.slots.clear();
                layoutDirty = true;
            }, mx, my, 0xFFC22B21);
            y += OPT_H;
        }
        if (access.roundRobin != BProgram.RoundRobinMode.NONE) {
            int fx = extensionRow(g, x, y, w, accent, "轮流选择");
            final int fieldX = fx, rowY = y;
            fx = drawField(g, fx, y, rrDisp(access.roundRobin), 80, () -> openChoice(fieldX, rowY,
                    access.roundRobin.name().toLowerCase(java.util.Locale.ROOT),
                    List.of("label", "block"), List.of("按标签轮流", "按方块轮流"), value -> {
                        pushUndo();
                        access.roundRobin = BProgram.RoundRobinMode.fromSfml(value);
                    }), mx, my, false);
            drawIcon(g, x + w - 18, y, "✕", () -> {
                pushUndo();
                access.roundRobin = BProgram.RoundRobinMode.NONE;
                layoutDirty = true;
            }, mx, my, 0xFFC22B21);
            y += OPT_H;
        }

        if (io instanceof BProgram.Statement.Input input && input.each) {
            extensionToggleRow(g, x, y, w, accent, "每个方块分别取出", () -> input.each = false, mx, my);
            y += OPT_H;
        } else if (io instanceof BProgram.Statement.Output output && output.each) {
            extensionToggleRow(g, x, y, w, accent, "每个方块分别存入", () -> output.each = false, mx, my);
            y += OPT_H;
        }
        if (io instanceof BProgram.Statement.Output output && output.emptySlots) {
            extensionToggleRow(g, x, y, w, accent, "只放入完全空白的槽位", () -> output.emptySlots = false, mx, my);
            y += OPT_H;
        }

        extensionRow(g, x, y, w, C_SELECT, "＋ 添加");
        final int addY = y;
        hits.add(hit(x, y, w, OPT_H - 2, K_CLICK, null,
                () -> openIOExtensionMenu(x, addY, io, list, index)));
    }

    // ---- 资源标签：条件药丸链 + 「＋ 且…」「＋ 或…」小积木 --------------------

    private static final int WITH_PILL_MAX = 76;   // 单颗条件药丸的宽度上限
    private static final int WITH_BTN_W = 44;      // 小积木按钮宽度

    /**
     * 资源标签不再挤成一行摘要：每个条件是一颗可点的小积木，链尾永远跟着
     * 「＋ 且…」「＋ 或…」，加完一颗就往后长一颗。行数由
     * {@link EditorLayout#withRows} 用同一套规则算，渲染和布局不会打架。
     */
    private int renderWithRow(GuiGraphics g, int x, int y, int w, int accent,
                              String groupPrefix, BProgram.ResourceLimit limit, int mx, int my) {
        BProgram.WithFilter with = limit.with;
        boolean negated = with.expr instanceof BProgram.WithExpr.Not;
        String label = shortUi(with.mode == BProgram.WithFilter.Mode.WITHOUT
                ? (negated ? "排除特征·反" : "排除特征")
                : (negated ? "资源标签·反" : "资源标签"), 6);
        final int labelY = y;   // 标签只在第一行，下面的循环会推进 y
        int startX = extensionRow(g, x, y, w, accent, groupPrefix + label);
        // 药丸链 + 两个小积木 + 行尾 ✕ 必须整行放得下：标签再长也只能挤到这里
        int reserved = WITH_PILL_MAX * EditorLayout.WITH_TAGS_PER_ROW + 4 + WITH_BTN_W * 2 + 4 + 24;
        startX = Math.min(startX, x + w - reserved);

        List<BProgram.WithExpr.Tag> tags = new ArrayList<>();
        List<Boolean> tagIsOr = new ArrayList<>();
        collectTagsWithConn(with.expr, tags, tagIsOr, false);
        int fx = startX;
        int placed = 0;
        for (int i = 0; i < tags.size(); i++) {
            if (placed == EditorLayout.WITH_TAGS_PER_ROW) {
                y += OPT_H;
                extensionRow(g, x, y, w, accent, "");
                fx = startX;
                placed = 0;
            }
            // 第一个标签不带前缀，后续标签带「且」或「或」前缀
            String conn = (i == 0) ? "" : (tagIsOr.get(i) ? "或 " : "且 ");
            fx = drawWithTagPill(g, fx, y, limit, tags.get(i), conn, mx, my);
            placed++;
        }
        final int addX = fx, addY = y;
        drawWithAddBlock(g, fx, y, "＋ 且…", false,
                () -> openWithAddMenu(addX, addY, limit, false), mx, my);
        drawWithAddBlock(g, fx + WITH_BTN_W + 4, y, "＋ 或…", true,
                () -> openWithAddMenu(addX + WITH_BTN_W + 4, addY, limit, true), mx, my);
        // 标签本身是"整体设置"的入口：with/without/取反/预览都还在里面
        hits.add(hit(x, labelY, Math.max(24, startX - x - 4), OPT_H - 2, K_CLICK, null,
                () -> openWithEditor(x, labelY, limit)));
        drawIcon(g, x + w - 18, y, "✕", () -> {
            pushUndo();
            limit.with = null;
            layoutDirty = true;
        }, mx, my, 0xFFC22B21);
        return y + OPT_H;
    }

    /** 一颗条件药丸：点开重选 / 手动编辑 / 删除，右侧自带 ✕。 */

    /** 收集标签时同时记录连接类型：isOr[i]=true 表示第 i 个标签与前面是"或"关系。 */
    private void collectTagsWithConn(BProgram.WithExpr expr, List<BProgram.WithExpr.Tag> tags,
                                     List<Boolean> isOrList, boolean parentIsOr) {
        if (expr instanceof BProgram.WithExpr.Tag tag) {
            tags.add(tag);
            isOrList.add(parentIsOr);
        } else if (expr instanceof BProgram.WithExpr.And and) {
            for (BProgram.WithExpr p : and.parts) collectTagsWithConn(p, tags, isOrList, false);
        } else if (expr instanceof BProgram.WithExpr.Or or) {
            for (BProgram.WithExpr p : or.parts) collectTagsWithConn(p, tags, isOrList, true);
        } else if (expr instanceof BProgram.WithExpr.Not not) {
            collectTagsWithConn(not.inner, tags, isOrList, parentIsOr);
        }
    }
    private int drawWithTagPill(GuiGraphics g, int x, int y, BProgram.ResourceLimit limit,
                                 BProgram.WithExpr.Tag tag, String connector, int mx, int my) {
        String name = ResourceTagIndex.displayName(nbtComponentDisplay(tag.matcher));
        String shown = shortUi(name, 16);
        int connW = connector.isEmpty() ? 0 : this.font.width(connector) + 2;
        int pw = Math.max(48, connW + this.font.width(shown) + 30);
        boolean hover = overField(mx, my, x, y + 2, pw - 18, OPT_H - 6);
        pill(g, x, y + 2, pw - 18, OPT_H - 6, hover);
        if (connW > 0) {
            g.drawString(this.font, connector, x + 4, y + 8,
                    connector.startsWith("或") ? 0xFFD79A2B : 0xFF1B4FA0, false);
        }
        g.drawString(this.font, shown, x + 4 + connW, y + 8, 0xFF1B4FA0, false);
        final int px2 = x, py2 = y;
        hits.add(hit(x, y + 2, pw - 18, OPT_H - 6, K_CLICK, null,
                () -> openWithTagMenu(px2, py2 + OPT_H, limit, tag)));
        drawIcon(g, x + pw - 17, y - 1, "✕", () -> {
            pushUndo();
            BProgram.WithExpr remaining = removeWithTag(limit.with.expr, tag);
            if (remaining == null) limit.with = null;
            else limit.with.expr = remaining;
            layoutDirty = true;
        }, mx, my, 0xFFC22B21);
        return x + pw + 4;
    }

    /** 链尾的小积木：且用蓝、或用橙，一眼分清两种组合方式。 */
    private void drawWithAddBlock(GuiGraphics g, int x, int y, String label, boolean or,
                                  Runnable onClick, int mx, int my) {
        boolean hover = overField(mx, my, x, y + 2, WITH_BTN_W, OPT_H - 6);
        rounded(g, x, y + 2, WITH_BTN_W, OPT_H - 6, 4,
                hover ? (or ? 0xFFFFE3C2 : 0xFFCFE4FA) : (or ? 0xFFFFF4E6 : 0xFFF1F5FB));
        border(g, x, y + 2, WITH_BTN_W, OPT_H - 6, or ? 0xFFD79A2B : 0xFF7FA8DD);
        g.drawString(this.font, label, x + (WITH_BTN_W - this.font.width(label)) / 2, y + 6,
                or ? 0xFF8A5A00 : 0xFF1B4FA0, false);
        hits.add(hit(x, y + 2, WITH_BTN_W, OPT_H - 6, K_CLICK, null, onClick));
    }

    /** 「＋ 且…」「＋ 或…」点开后的三条路径，和原先的入口完全一致。 */
    private void openWithAddMenu(int x, int y, BProgram.ResourceLimit limit, boolean useOr) {
        String prefix = useOr ? "或：" : "且：";
        List<String> values = new ArrayList<>(List.of("item", "all", "manual"));
        List<String> labels = new ArrayList<>(List.of(
                prefix + "从物品选择资源标签",
                prefix + "搜索全部资源标签",
                prefix + "手动输入原标签（高级）"));
        if (SfmCaps.withComponent()) {
            values.add("nbt");
            labels.add(prefix + "按物品组件(NBT)筛选…");
        }
        setPopup(new Popup.ChoicePopup(sX(x), sY(y) + OPT_H, 210, values, labels, "", picked -> {
            if (limit.with == null) return;
            Consumer<String> add = matcher -> {
                pushUndo();
                limit.with.expr = appendWithTag(limit.with.expr, matcher, useOr);
                layoutDirty = true;
            };
            switch (picked) {
                case "item" -> openResourceTagPicker(false, add);
                case "all" -> openResourceTagPicker(true, add);
                case "nbt" -> Minecraft.getInstance().setScreen(
                        new io.github.xianynomial.sfmfactorystudio.client.NbtItemPickerScreen(this, id ->
                                add.accept(nbtMatcher(id))));
                default -> openManualNewTag(sX(x), sY(y) + OPT_H, add);
            }
        }));
    }

    /** 点条件药丸本身：重选 / 手改 / 删除这一项。 */
    private void openWithTagMenu(int x, int y, BProgram.ResourceLimit limit,
                                 BProgram.WithExpr.Tag tag) {
        String display = ResourceTagIndex.displayName(nbtComponentDisplay(tag.matcher));
        setPopup(new Popup.ChoicePopup(sX(x), sY(y), 210,
                List.of("pick", "manual", "remove"),
                List.of("重新选择：" + display, "手动编辑原标签", "删除这一项"), "", picked -> {
                    switch (picked) {
                        case "pick" -> openResourceTagPicker(false, matcher -> {
                            pushUndo();
                            tag.matcher = matcher;
                            layoutDirty = true;
                        });
                        case "manual" -> setPopup(new Popup.TextPopup(this, sX(x), sY(y), 190,
                                tag.matcher, "原标签，例如 c:ingots/iron",
                                value -> setWithTag(tag, value), null));
                        default -> {
                            pushUndo();
                            BProgram.WithExpr remaining = removeWithTag(limit.with.expr, tag);
                            if (remaining == null) limit.with = null;
                            else limit.with.expr = remaining;
                            layoutDirty = true;
                        }
                    }
                }));
    }

    /** 输入⇄输出互转：原位翻转，保留限制组/排除/侧面/槽位/轮流全部配置。 */
    private void convertIO(Object io, List<BProgram.Statement> list, int index) {
        if (index < 0 || index >= list.size() || list.get(index) != io) return;
        pushUndo();
        if (io instanceof BProgram.Statement.Input in) {
            BProgram.Statement.Output out = new BProgram.Statement.Output();
            out.limits.addAll(in.limits);
            out.except.addAll(in.except);
            out.access.copyFrom(in.access);
            out.each = in.each;
            list.set(index, out);
            showStatus("已转为「放入方块」，全部配置保留", C_SELECT);
        } else if (io instanceof BProgram.Statement.Output out) {
            BProgram.Statement.Input in = new BProgram.Statement.Input();
            in.limits.addAll(out.limits);
            in.except.addAll(out.except);
            in.access.copyFrom(out.access);
            in.each = out.each;
            list.set(index, in);
            showStatus("已转为「从方块取出」，全部配置保留", C_SELECT);
        }
        layoutDirty = true;
    }

    private int extensionRow(GuiGraphics g, int x, int y, int w, int accent, String label) {
        rounded(g, x, y, w, OPT_H - 2, 5, mix(G_CARD, accent, 18));
        border(g, x, y, w, OPT_H - 2, mix(G_BORDER, accent, 45));
        g.fill(x, y + 2, x + 3, y + OPT_H - 4, accent);
        return drawText(g, x + 9, y, label);
    }

    private void extensionToggleRow(GuiGraphics g, int x, int y, int w, int accent,
                                    String label, Runnable clear, int mx, int my) {
        extensionRow(g, x, y, w, accent, label);
        drawIcon(g, x + w - 18, y, "✕", () -> {
            pushUndo();
            clear.run();
            layoutDirty = true;
        }, mx, my, 0xFFC22B21);
    }

    private BProgram.ResourceRef firstResource(BProgram.ResourceLimit limit) {
        return limit.resources.isEmpty()
                ? BProgram.ResourceRef.forKind(BProgram.ResourceKind.ITEM)
                : limit.resources.get(0);
    }

    private void setFirstResource(BProgram.ResourceLimit limit, BProgram.ResourceRef resource) {
        if (limit.resources.isEmpty()) limit.resources.add(resource);
        else limit.resources.set(0, resource);
    }

    private String shortUi(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }

    private void openIOExtensionMenu(int x, int y, Object io, List<BProgram.Statement> list, int index) {
        List<BProgram.ResourceLimit> limits;
        List<BProgram.ResourceRef> except;
        BProgram.LabelAccess access;
        boolean each;
        boolean emptySlots = false;
        if (io instanceof BProgram.Statement.Input input) {
            limits = input.limits;
            except = input.except;
            access = input.access;
            each = input.each;
        } else {
            BProgram.Statement.Output output = (BProgram.Statement.Output) io;
            limits = output.limits;
            except = output.except;
            access = output.access;
            each = output.each;
            emptySlots = output.emptySlots;
        }
        primaryLimit(limits);
        List<String> values = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        // 菜单按使用频率排序：常用筛选/保留在前，多组与类型转换垫底；
        // 只有第 2 组起才带"第 N 组"前缀——单组是绝大多数场景。
        BProgram.ResourceLimit primary = limits.isEmpty() ? null : limits.get(0);
        if (primary != null && primary.with == null && supportsResourceFeatures(primary)) {
            addChoice(values, labels, "with:0", "按资源标签筛选");
        }
        if (primary != null && primary.retain == null) addChoice(values, labels, "retain:0", "至少留下指定数量");
        addChoice(values, labels, "except", "排除一种资源");
        if (!access.eachSide && access.sides.isEmpty()) addChoice(values, labels, "sides", "指定方块侧面");
        if (access.slots.isEmpty()) addChoice(values, labels, "slots", "指定槽位");
        if (access.roundRobin == BProgram.RoundRobinMode.NONE) addChoice(values, labels, "round_robin", "轮流选择目标");
        if (!each) addChoice(values, labels, "each", "每个方块分别处理");
        if (io instanceof BProgram.Statement.Output && !emptySlots) {
            addChoice(values, labels, "empty_slots", "只放入完全空白的槽位");
        }
        if (SfmCaps.withComponent()) {
            addChoice(values, labels, "nbt", "NBT 组件筛选…");
        }
        for (int i = 1; i < limits.size(); i++) {
            BProgram.ResourceLimit limit = limits.get(i);
            String group = "第 " + (i + 1) + " 组：";
            if (limit.quantity == null) addChoice(values, labels, "quantity:" + i, group + "限制搬运数量");
            if (limit.retain == null) addChoice(values, labels, "retain:" + i, group + "至少留下指定数量");
            addChoice(values, labels, "add_or:" + i, group + "或者再选一种资源");
            if (limit.with == null && supportsResourceFeatures(limit)) {
                addChoice(values, labels, "with:" + i, group + "按资源标签筛选");
            }
        }
        addChoice(values, labels, "add_group", "另外搬运一组资源");
        addChoice(values, labels, "convert", io instanceof BProgram.Statement.Input ? "⇄ 转为「放入方块」" : "⇄ 转为「从方块取出」");
        setPopup(new Popup.ChoicePopup(sX(x), sY(y) + OPT_H, 190, values, labels, "", action -> {
            int separator = action.indexOf(':');
            if (separator > 0) {
                int limitIndex;
                try {
                    limitIndex = Integer.parseInt(action.substring(separator + 1));
                } catch (NumberFormatException ignored) {
                    return;
                }
                if (limitIndex < 0 || limitIndex >= limits.size()) return;
                BProgram.ResourceLimit selectedLimit = limits.get(limitIndex);
                String command = action.substring(0, separator);
                switch (command) {
                    case "quantity" -> {
                        pushUndo();
                        selectedLimit.quantity = 1L;
                        layoutDirty = true;
                    }
                    case "retain" -> {
                        pushUndo();
                        selectedLimit.retain = 1L;
                        layoutDirty = true;
                    }
                    case "with" -> {
                        // Open the three safe entry paths (pick by item, search
                        // all, or advanced raw id) without creating a wildcard.
                        showWithEditor(sX(x), sY(y) + OPT_H,
                                () -> selectedLimit.with, value -> selectedLimit.with = value);
                    }
                    case "add_or" -> openNewResourceKindMenu(x, y, resource -> {
                        pushUndo();
                        if (selectedLimit.resources.isEmpty()) {
                            selectedLimit.resources.add(BProgram.ResourceRef.forKind(BProgram.ResourceKind.ITEM));
                        }
                        selectedLimit.resources.add(resource);
                        layoutDirty = true;
                    });
                }
                return;
            }
            switch (action) {
                case "convert" -> convertIO(io, list, index);
                case "nbt" -> Minecraft.getInstance().setScreen(
                        new io.github.xianynomial.sfmfactorystudio.client.NbtItemPickerScreen(this, id -> {
                            pushUndo();
                            BProgram.ResourceLimit rl = primaryLimit(limits);
                            BProgram.WithExpr tag = new BProgram.WithExpr.Tag(nbtMatcher(id));
                            if (rl.with == null) {
                                BProgram.WithFilter created = new BProgram.WithFilter();
                                created.expr = tag;
                                rl.with = created;
                            } else {
                                BProgram.WithExpr.And and = new BProgram.WithExpr.And();
                                and.parts.add(rl.with.expr);
                                and.parts.add(tag);
                                rl.with.expr = and;
                            }
                            layoutDirty = true;
                        }));

                case "add_group" -> openNewResourceKindMenu(x, y, resource -> {
                    pushUndo();
                    BProgram.ResourceLimit added = new BProgram.ResourceLimit();
                    added.resources.add(resource);
                    limits.add(added);
                    layoutDirty = true;
                });
                case "except" -> openNewResourceKindMenu(x, y, resource -> {
                    pushUndo();
                    except.add(resource);
                    layoutDirty = true;
                });
                case "sides" -> openSideEditor(x, y, access);
                case "slots" -> openTextEditor(x, y, "", value -> setSlotsFromText(access.slots, value), null, 150);
                case "round_robin" -> openChoice(x, y, "none",
                        List.of("label", "block"), List.of("按标签轮流", "按方块轮流"), value -> {
                            pushUndo();
                            access.roundRobin = BProgram.RoundRobinMode.fromSfml(value);
                            layoutDirty = true;
                        });
                case "each" -> {
                    pushUndo();
                    if (io instanceof BProgram.Statement.Input input) input.each = true;
                    else ((BProgram.Statement.Output) io).each = true;
                    layoutDirty = true;
                }
                case "empty_slots" -> {
                    pushUndo();
                    ((BProgram.Statement.Output) io).emptySlots = true;
                    layoutDirty = true;
                }
            }
        }));
    }

    private boolean supportsResourceFeatures(BProgram.ResourceLimit limit) {
        if (limit.resources.isEmpty()) return true; // default item resource
        return limit.resources.stream().filter(java.util.Objects::nonNull)
                .anyMatch(resource -> resource.kind() != BProgram.ResourceKind.FORGE_ENERGY
                        && resource.kind() != BProgram.ResourceKind.REDSTONE);
    }

    private void addChoice(List<String> values, List<String> labels, String value, String label) {
        values.add(value);
        labels.add("＋ " + label);
    }

    private void openNewResourceKindMenu(int x, int y, Consumer<BProgram.ResourceRef> onPick) {
        showNewResourceKindMenu(sX(x), sY(y) + OPT_H, onPick);
    }

    private void showNewResourceKindMenu(int screenX, int screenY, Consumer<BProgram.ResourceRef> onPick) {
        List<BProgram.ResourceKind> kinds = List.of(
                BProgram.ResourceKind.ITEM, BProgram.ResourceKind.FLUID,
                BProgram.ResourceKind.CHEMICAL, BProgram.ResourceKind.GAS,
                BProgram.ResourceKind.SLURRY, BProgram.ResourceKind.PIGMENT,
                BProgram.ResourceKind.REDSTONE, BProgram.ResourceKind.INFUSION,
                BProgram.ResourceKind.FORGE_ENERGY);
        setPopup(new Popup.ChoicePopup(screenX, screenY, 150,
                kinds.stream().map(kind -> kind.name().toLowerCase(java.util.Locale.ROOT)).toList(),
                kinds.stream().map(kind -> "添加" + kind.chineseName).toList(), "", value ->
                onPick.accept(BProgram.ResourceRef.forKind(
                        BProgram.ResourceKind.valueOf(value.toUpperCase(java.util.Locale.ROOT))))));
    }

    private String sidesDisp(BProgram.LabelAccess access) {
        if (access.eachSide) return "每个侧面";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < access.sides.size(); i++) {
            sb.append(i == 0 ? "" : ",").append(sideZh(access.sides.get(i)));
        }
        return sb.toString();
    }

    private String rrDisp(BProgram.RoundRobinMode rr) {
        return switch (rr) {
            case LABEL -> "按标签轮流";
            case BLOCK -> "按方块轮流";
            case NONE -> "不轮流";
        };
    }

    private String slotText(List<BProgram.SlotRange> slots) {
        return slots.stream().map(BProgram.SlotRange::sfml).collect(java.util.stream.Collectors.joining(","));
    }

    private void setSlotsFromText(List<BProgram.SlotRange> target, String text) {
        List<BProgram.SlotRange> parsed = new ArrayList<>();
        try {
            if (!text.isBlank()) {
                for (String part : text.split("[,，]")) {
                    BProgram.SlotRange range = BProgram.SlotRange.parse(part);
                    if (!parsed.contains(range)) parsed.add(range);
                }
            }
        } catch (IllegalArgumentException ex) {
            showStatus("✖ " + ex.getMessage(), 0xFFD13438);
            return;
        }
        pushUndo();
        target.clear();
        target.addAll(parsed);
    }

    private String shortWith(BProgram.WithFilter filter) {
        return (filter.mode == BProgram.WithFilter.Mode.WITHOUT ? "排除特征：" : "只要特征：")
                + shortWithExpr(filter.expr);
    }

    private String shortWithExpr(BProgram.WithExpr expr) {
        if (expr instanceof BProgram.WithExpr.Tag tag) {
            return ResourceTagIndex.displayName(nbtComponentDisplay(tag.matcher));
        }
        if (expr instanceof BProgram.WithExpr.Not not) return "不是 " + shortWithExpr(not.inner);
        if (expr instanceof BProgram.WithExpr.And and) {
            return and.parts.stream().map(this::shortWithExpr).collect(java.util.stream.Collectors.joining(" 且 "));
        }
        if (expr instanceof BProgram.WithExpr.Or or) {
            return or.parts.stream().map(this::shortWithExpr).collect(java.util.stream.Collectors.joining(" 或 "));
        }
        return "任意";
    }

    private void setWithFromText(BProgram.ResourceLimit limit, String text) {
        if (text.isBlank()) {
            pushUndo();
            limit.with = null;
            return;
        }
        SfmlToBlocks.ResultWithFilter parsed = SfmlToBlocks.parseWithFilter(text);
        if (!parsed.ok()) {
            showStatus("✖ 资源标签条件不正确：" + String.join("；", parsed.errors()), 0xFFD13438);
            return;
        }
        pushUndo();
        limit.with = parsed.filter();
    }

    private void renderForget(GuiGraphics g, List<BProgram.Statement> list, int index,
                              BProgram.Statement.Forget f, int x, int y, int w, int mx, int my) {
        boolean selected = selection.contains(f);
        barBase(g, x, y, w, BAR_H, A_FORGET, selected);
        registerBarGrip(list, index, f, x, y, w, T_FORGET.getString(), A_FORGET);
        final int sxx = x, syy = y;
        int fx = x + 12;
        fx = drawText(g, fx, y, T_FORGET.getString());
        String labelDisp = f.labels.isEmpty() ? T_ALL.getString() : String.join("+", f.labels);
        hits.add(hit(fx, y, 32, BAR_H, K_RCLICK, null, () -> openLabelContext(sxx, syy, f.labels)));
        fx = drawField(g, fx, y, labelDisp, 32, () -> openLabelEditor(sxx, syy, f.labels, true), mx, my, false);
        drawDelete(g, x + w - 16, y + 3, () -> {
            pushUndo();
            list.remove(index);
        }, mx, my);
    }

    private void renderComment(GuiGraphics g, List<BProgram.Statement> list, int index,
                               BProgram.Statement.Comment c, int x, int y, int w, int mx, int my) {
        boolean selected = selection.contains(c);
        barBase(g, x, y, w, BAR_H, A_COMMENT, selected);
        g.fill(x + 1, y + 2, x + w - 1, y + BAR_H - 2, 0x30EAB308);
        registerBarGrip(list, index, c, x, y, w, T_COMMENT.getString(), A_COMMENT);
        final int sxx = x, syy = y;
        int fx = x + 12;
        String text = c.text.isBlank() ? "输入备注…" : c.text;
        if (text.length() > 30) text = text.substring(0, 30) + "…";
        fx = drawField(g, fx, y, text, 90,
                () -> openTextEditor(sxx, syy, c.text, v -> {
                    pushUndo();
                    c.text = v;
                }, null, 200), mx, my, false);
        drawDelete(g, x + w - 16, y + 3, () -> {
            pushUndo();
            list.remove(index);
        }, mx, my);
    }

    private void renderRaw(GuiGraphics g, List<BProgram.Statement> list, int index,
                           BProgram.Statement.Raw raw, int x, int y, int w, int mx, int my) {
        boolean selected = selection.contains(raw);
        barBase(g, x, y, w, BAR_H, A_RAW, selected);
        registerBarGrip(list, index, raw, x, y, w, T_RAW.getString(), A_RAW);
        final int sxx = x, syy = y;
        int fx = x + 12;
        String summary = raw.text.replace("\n", " ");
        if (summary.length() > 26) summary = summary.substring(0, 26) + "…";
        fx = drawField(g, fx, y, summary, 70,
                () -> {
                    if (!previewMode) toggleCodeEditor();
                    showStatus("兼容内容请直接在下方代码编辑区修改", 0xFFB45309);
                }, mx, my, false);
        drawDelete(g, x + w - 16, y + 3, () -> {
            pushUndo();
            list.remove(index);
        }, mx, my);
    }

    private void renderIf(GuiGraphics g, List<BProgram.Statement> list, int index,
                          BProgram.Statement.If iff, int x, int y, int w, int mx, int my) {
        boolean selected = selection.contains(iff);
        if (collapsedIfs.contains(iff.id)) {
            // 折叠 If：一行条件 + 展开角标，嵌套体不渲染
            if (contentVisible(x, y, w, BAR_H)) {
                barBase(g, x, y, w, BAR_H, A_IF, selected);
                registerBarGrip(list, index, iff, x, y, w, T_IF.getString(), A_IF);
                drawDelete(g, x + 2, y + 3, () -> {
                    pushUndo();
                    list.remove(index);
                }, mx, my);
                int fx = x + 18;
                fx = drawText(g, fx, y, T_IF.getString());
                if (!iff.branches.isEmpty()) {
                    fx = renderCond(g, iff.branches.get(0).cond, fx, y, mx, my);
                }
                drawIcon(g, fx, y, "▶ 展开", () -> toggleIfCollapse(iff), mx, my, C_TEXT_SUB);
            }
            return;
        }
        for (int bi = 0; bi < iff.branches.size(); bi++) {
            BProgram.Branch b = iff.branches.get(bi);
            boolean first = bi == 0;
            if (contentVisible(x, y, w, BAR_H)) {
                barBase(g, x, y, w, BAR_H, A_IF, selected && first);
                if (first) registerBarGrip(list, index, iff, x, y, w, T_IF.getString(), A_IF);
                int fx;
                if (first) {
                    // 行首左上角 ✕：删除整个判断积木（含全部分支与否则体）
                    drawDelete(g, x + 2, y + 3, () -> {
                        pushUndo();
                        list.remove(index);
                    }, mx, my);
                    fx = x + 18;
                } else {
                    fx = x + 12;
                }
                fx = drawText(g, fx, y, first ? T_IF.getString() : T_ELSE.getString());
                fx = renderCond(g, b.cond, fx, y, mx, my);
                if (first) {
                    fx = drawIcon(g, fx, y, "▼", () -> toggleIfCollapse(iff), mx, my, C_TEXT_SUB);
                }
                final int fbi = bi;
                final int addConditionX = fx;
                final int addConditionY = y;
                fx = drawField(g, fx, y, T_ADDCOND.getString(), 28,
                        () -> openAddConditionMenu(addConditionX, addConditionY, b), mx, my, false);
                fx = drawField(g, fx, y, T_ADDELSEIF.getString(), 44, () -> {
                    pushUndo();
                    BProgram.Branch added = new BProgram.Branch();
                    added.cond = newConditionHas();
                    iff.branches.add(fbi + 1, added);
                    layoutDirty = true;
                }, mx, my, false);
                drawDelete(g, x + w - 16, y + 3, () -> {
                    pushUndo();
                    if (iff.branches.size() > 1) iff.branches.remove(fbi);
                    else list.remove(index);
                }, mx, my);
            }
            y += BAR_H + ROW_GAP;
            y = renderBody(g, b.body, x + INDENT, y, mx, my) + ROW_GAP;
        }
        if (iff.hasElse || !iff.elseBody.isEmpty()) {
            if (contentVisible(x, y, w, BAR_H)) {
                barBase(g, x, y, w, BAR_H, mix(G_CARD, A_IF, 110), false);
                drawText(g, x + 12, y, T_ELSE.getString());
                drawDelete(g, x + w - 16, y + 3, () -> {
                    pushUndo();
                    iff.elseBody.clear();
                    iff.hasElse = false;
                }, mx, my);
            }
            y += BAR_H + ROW_GAP;
            y = renderBody(g, iff.elseBody, x + INDENT, y, mx, my) + ROW_GAP;
        } else {
            if (contentVisible(x, y, Math.min(w, 130), BAR_H)) {
                rounded(g, x, y, Math.min(w, 130), BAR_H, 6, mix(G_CARD, A_IF, 110));
                border(g, x, y, Math.min(w, 130), BAR_H, G_BORDER);
                drawField(g, x + 12, y, T_ADDELSE.getString(), 44,
                        () -> {
                            pushUndo();
                            iff.hasElse = true;
                        }, mx, my, false);
            }
            y += BAR_H + ROW_GAP;
        }
        if (contentVisible(x, y + 1, Math.min(w, 90), 5)) {
            g.fill(x, y + 1, x + Math.min(w, 90), y + 6, mix(G_CARD, A_IF, 110));
        }
    }

    private int renderCond(GuiGraphics g, BProgram.Bool cond, int x, int y, int mx, int my) {
        if (cond instanceof BProgram.Bool.Has h) {
            return drawField(g, x, y, condSummary(h), 56, () -> openConditionEditor(x, y, h), mx, my, true);
        } else if (cond instanceof BProgram.Bool.Redstone r) {
            return drawField(g, x, y, "红石" + (r.comparison == null ? "" : " " + r.comparison.symbol() + " " + r.number), 40,
                    () -> openConditionEditor(x, y, r), mx, my, true);
        } else if (cond instanceof BProgram.Bool.Not n) {
            return drawField(g, x, y, "¬" + T_COND.getString(), 30, () -> openConditionEditor(x, y, n), mx, my, true);
        } else if (cond instanceof BProgram.Bool.And a) {
            int fx = x;
            for (int i = 0; i < a.parts.size(); i++) {
                fx = renderCond(g, a.parts.get(i), fx, y, mx, my);
                if (i < a.parts.size() - 1) {
                    fx = drawField(g, fx, y, T_AND.getString(), 16, () -> openConditionEditor(x, y, a), mx, my, false);
                }
            }
            return fx;
        } else if (cond instanceof BProgram.Bool.Or o) {
            int fx = x;
            for (int i = 0; i < o.parts.size(); i++) {
                fx = renderCond(g, o.parts.get(i), fx, y, mx, my);
                if (i < o.parts.size() - 1) {
                    fx = drawField(g, fx, y, T_OR.getString(), 16, () -> openConditionEditor(x, y, o), mx, my, false);
                }
            }
            return fx;
        } else if (cond instanceof BProgram.Bool.RawBool r) {
            return drawField(g, x, y, "⌨ " + (r.text.length() > 18 ? r.text.substring(0, 18) + "…" : r.text),
                    50, () -> {
                        if (!previewMode) toggleCodeEditor();
                        showStatus("兼容条件请直接在下方代码编辑区修改", 0xFFB45309);
                    }, mx, my, true);
        } else if (cond instanceof BProgram.Bool.Const c) {
            return drawField(g, x, y, c.value ? "总是成立" : "永不成立", 44,
                    () -> openConditionEditor(x, y, c), mx, my, true);
        }
        return x;
    }

    private String condSummary(BProgram.Bool.Has h) {
        StringBuilder sb = new StringBuilder();
        if (h.setMode != BProgram.Bool.SetMode.DEFAULT) sb.append(setOpZh(h.setMode)).append(' ');
        sb.append(h.access.labels.isEmpty() ? "?" : String.join("+", h.access.labels));
        sb.append(' ').append(T_HAS.getString()).append(' ').append(h.comparison.symbol()).append(' ').append(h.number);
        if (!h.resources.isEmpty()) sb.append(' ').append(shortResource(h.resources.get(0)));
        return sb.toString();
    }

    private String setOpZh(BProgram.Bool.SetMode op) {
        return switch (op) {
            case DEFAULT -> "默认合计";
            case OVERALL -> "所有方块合计";
            case SOME -> "至少一个方块";
            case EVERY -> "每个方块";
            case ONE -> "恰好一个方块";
            case LONE -> "不超过一个方块";
        };
    }

    private String sideZh(BProgram.Side side) {
        return switch (side) {
            case TOP -> "顶面";
            case BOTTOM -> "底面";
            case NORTH -> "北面";
            case SOUTH -> "南面";
            case EAST -> "东面";
            case WEST -> "西面";
            case LEFT -> "左面";
            case RIGHT -> "右面";
            case FRONT -> "前面";
            case BACK -> "后面";
            case NULL -> "无方向面";
        };
    }

    private String shortResource(BProgram.ResourceRef resource) {
        if (resource == null || resource.isWildcard()) return "全部" + (resource == null ? "物品" : resource.kind().chineseName);
        String part = resource.resourcePart();
        return part.substring(Math.max(0, part.lastIndexOf(':') + 1));
    }

    private int renderAddRow(GuiGraphics g, List<BProgram.Statement> list, int x, int y, int mx, int my) {
        boolean selected = list == selectedBody;
        String text = T_ADDSTMT.getString();
        int w = this.font.width(text) + 16;
        rounded(g, x, y, w, ADD_H - 2, 4, selected ? mix(G_CARD, C_SELECT, 45) : 0x88FFFFFF);
        border(g, x, y, w, ADD_H - 2, selected ? C_SELECT : G_BORDER_SOFT);
        text(g, text, x + 8, y + 4, selected ? C_SELECT : C_TEXT_SUB);
        hits.add(hit(x, y, w, ADD_H - 2, K_CLICK, null, () -> openBlockMenu(x, y, list)));
        return y + ADD_H;
    }

    /** Clicking a body's 放入积木 pill opens the block picker for that body. */
    private void openBlockMenu(int x, int y, List<BProgram.Statement> body) {
        List<String> values = List.of("input", "output", "forget", "if", "comment");
        List<String> labels = List.of(T_INPUT.getString(), T_OUTPUT.getString(), T_FORGET.getString(),
                T_IF.getString() + "…", T_COMMENT.getString());
        setPopup(new Popup.ChoicePopup(sX(x), sY(y) + ADD_H + 2, 110, values, labels, null, kind -> {
            selectedBody = body;
            pushUndo();
            body.add(buildBlock(kind));
            layoutDirty = true;
        }));
    }

    // ------------------------------------------------------------- draw helpers

    /** Category and concrete resource are deliberately rendered as two controls. */
    private int drawResourceField(GuiGraphics g, int x, int y, @Nullable BProgram.ResourceLimit rl,
                                  int minW, int mx, int my) {
        BProgram.ResourceRef current = rl != null && !rl.resources.isEmpty()
                ? rl.resources.get(0)
                : BProgram.ResourceRef.forKind(BProgram.ResourceKind.ITEM);
        Consumer<BProgram.ResourceRef> setter = picked -> {
            pushUndo();
            if (rl != null) {
                if (rl.resources.isEmpty()) rl.resources.add(picked);
                else rl.resources.set(0, picked);
            }
        };
        return drawResourceSelector(g, x, y, current, setter, mx, my);
    }

    private int drawResourceSelector(GuiGraphics g, int x, int y, BProgram.ResourceRef current,
                                     Consumer<BProgram.ResourceRef> setter, int mx, int my) {
        String category = current.kind() == BProgram.ResourceKind.CUSTOM
                ? current.typeNamespace + ":" + current.typeName
                : current.kind().chineseName;
        int fx = drawField(g, x, y, category, 32,
                () -> openResourceKindMenu(x, y, current, setter), mx, my, false);
        return drawResourceValueSlot(g, fx, y, current, setter, mx, my);
    }

    /** Empty means all resources of the already-selected category. */
    private int drawResourceValueSlot(GuiGraphics g, int x, int y, BProgram.ResourceRef current,
                                      Consumer<BProgram.ResourceRef> setter, int mx, int my) {
        int size = BAR_H; // 20px, like an 18px vanilla slot + breathing room
        boolean hover = overField(mx, my, x, y, size, size);
        g.fill(x + 1, y + 1, x + size - 1, y + size - 1, hover ? 0x997080A0 : 0x90606B7E);
        g.fill(x + 1, y + 1, x + size - 1, y + 2, 0x8C2A2A2A);
        g.fill(x + 1, y + 1, x + 2, y + size - 1, 0x8C2A2A2A);
        g.fill(x + 1, y + size - 2, x + size - 1, y + size - 1, 0x8CFFFFFF);
        g.fill(x + size - 2, y + 1, x + size - 1, y + size - 1, 0x8CFFFFFF);
        if (!current.isWildcard()) {
            ResourceIndex.Entry entry = lookupResourceEntry(current);
            if (entry != null) {
                ResourceIndex.renderIcon(g, this.font, entry, x + 2, y + 2);
            } else {
                String name = current.resourcePart();
                String abbr = name.isBlank() ? "?" : name.substring(Math.max(0, name.lastIndexOf(':') + 1));
                text(g, abbr.substring(0, Math.min(2, abbr.length())), x + 4, y + size / 2 - 4, C_TEXT);
            }
        }
        if (hover) border(g, x, y, size, size, C_SELECT);
        hits.add(hit(x, y, size, size, K_RCLICK, null,
                () -> openResourceSlotContext(x, y, current, setter)));
        hits.add(hit(x, y, size, size, K_CLICK, null,
                () -> openResourceValueMenu(x, y, current, setter)));
        addGhostZone(x, y, size, size, current.toString(), dropped -> {
            try {
                BProgram.ResourceRef incoming = BProgram.ResourceRef.parse(dropped);
                if (!sameResourceCategory(current, incoming)) {
                    showStatus("✖ 请先把资源类别改成“" + incoming.kind().chineseName + "”再放入", 0xFFD13438);
                    return;
                }
                setter.accept(incoming);
            } catch (IllegalArgumentException ex) {
                showStatus("✖ 无法识别这个资源", 0xFFD13438);
            }
        });
        return x + size + 4;
    }

    private @Nullable ResourceIndex.Entry lookupResourceEntry(BProgram.ResourceRef resource) {
        if (resourceEntryCache.containsKey(resource)) return resourceEntryCache.get(resource);
        ResourceIndex.Entry entry = ResourceIndex.lookup(resource.sfml().replace("\"", ""));
        resourceEntryCache.put(resource, entry); // IdentityHashMap also caches misses (null).
        return entry;
    }

    private int drawText(GuiGraphics g, int x, int y, String t) {
        text(g, t, x, y + 6, C_TEXT);
        return x + this.font.width(t) + 5;
    }

    /** Flat text without shadow — kills the "ghosting" on light backgrounds. */
    private void text(GuiGraphics g, String t, int x, int y, int color) {
        g.drawString(this.font, t, x, y, color, false);
    }

    private int drawNum(GuiGraphics g, int x, int y, long value, int minW, Consumer<Long> set, int mx, int my) {
        String t = String.valueOf(value);
        int tw = this.font.width(t); // 只测一次：此前每帧每数字测两次
        int w = Math.max(minW, tw + 10);
        pill(g, x, y + 3, w, BAR_H - 6, overField(mx, my, x, y + 3, w, BAR_H - 6));
        g.drawString(this.font, t, x + (w - tw) / 2, y + 6, 0xFF0B5B39, false);
        hits.add(hit(x, y + 3, w, BAR_H - 6, K_CLICK, null, () -> openNumber(x, y + 3, w, value, set)));
        return x + w + 4;
    }

    private int drawField(GuiGraphics g, int x, int y, String t, int minW,
                          Runnable onClick, int mx, int my, boolean accent) {
        int tw = this.font.width(t); // 只测一次：此前每帧每字段测两次
        int w = Math.max(minW, tw + 10);
        boolean hover = overField(mx, my, x, y + 3, w, BAR_H - 6);
        pill(g, x, y + 3, w, BAR_H - 6, hover);
        g.drawString(this.font, t, x + (w - tw) / 2, y + 6, accent ? 0xFF4C3A9F : C_TEXT, false);
        hits.add(hit(x, y + 3, w, BAR_H - 6, K_CLICK, null, onClick));
        return x + w + 4;
    }

    private int drawChoice(GuiGraphics g, int x, int y, String display, List<String> values,
                           List<String> labels, Consumer<String> onSelect, int mx, int my) {
        return drawField(g, x, y, display, 24,
                () -> openChoice(x, y + BAR_H + 2, display, values, labels, onSelect), mx, my, false);
    }

    private int drawIcon(GuiGraphics g, int x, int y, String icon, Runnable onClick, int mx, int my, int color) {
        boolean hover = overField(mx, my, x, y + 3, 16, BAR_H - 6);
        if (hover) {
            rounded(g, x, y + 3, 16, BAR_H - 6, 4, C_PILL_HOVER);
        }
        g.drawString(this.font, icon, x + 5, y + 6, color, false);
        hits.add(hit(x, y + 3, 16, BAR_H - 6, K_CLICK, null, onClick));
        return x + 20;
    }

    private void drawDelete(GuiGraphics g, int x, int y, Runnable onClick, int mx, int my) {
        boolean hover = overField(mx, my, x, y, 14, 14);
        if (hover) {
            rounded(g, x, y, 14, 14, 4, 0xFFFDE3E1);
        }
        g.drawString(this.font, "✕", x + 4, y + 3, hover ? 0xFFC22B21 : 0xFF9AA3B2, false);
        hits.add(hit(x, y, 14, 14, K_CLICK, null, onClick));
    }

    private void pill(GuiGraphics g, int x, int y, int w, int h, boolean hover) {
        int bg = hover ? C_PILL_HOVER : C_PILL;
        rounded(g, x, y, w, h, h / 2, bg);
        border(g, x, y, w, h, hover ? 0xFF8FB4E8 : C_PILL_BORDER);
    }

    /** Rounded rect with radius 3 (two overlapping fills + corner trim). */
    private static void rounded(GuiGraphics g, int x, int y, int w, int h, int r, int color) {
        if (r <= 0 || w < r * 2 || h < r * 2) {
            g.fill(x, y, x + w, y + h, color);
            return;
        }
        g.fill(x + 1, y, x + w - 1, y + h, color);
        g.fill(x, y + 1, x + w, y + h - 1, color);
    }

    private static void border(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(x + 1, y, x + w - 1, y + 1, color);
        g.fill(x + 1, y + h - 1, x + w - 1, y + h, color);
        g.fill(x, y + 1, x + 1, y + h - 1, color);
        g.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
    }

    /** 虚线描边：拖动卡片时标出落位。 */
    private static void dashedBorder(GuiGraphics g, int x, int y, int w, int h, int color) {
        int x2 = x + w, y2 = y + h;
        int dash = 5;
        for (int i = x; i < x2; i += dash * 2) {
            int e = Math.min(i + dash, x2);
            g.fill(i, y, e, y + 1, color);
            g.fill(i, y2 - 1, e, y2, color);
        }
        for (int i = y; i < y2; i += dash * 2) {
            int e = Math.min(i + dash, y2);
            g.fill(x, i, x + 1, e, color);
            g.fill(x2 - 1, i, x2, e, color);
        }
    }

    private static int mix(int a, int b, int ratio) {
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        int r = (ar * (255 - ratio) + br * ratio) / 255;
        int gg = (ag * (255 - ratio) + bg * ratio) / 255;
        int bl = (ab * (255 - ratio) + bb * ratio) / 255;
        return 0xFF000000 | (r << 16) | (gg << 8) | bl;
    }

    private boolean overField(double mx, double my, int contentX, int contentY, int w, int h) {
        double cmx = ctX(mx), cmy = ctY(my);
        return cmx >= contentX && cmx < contentX + w && cmy >= contentY && cmy < contentY + h;
    }

    // ------------------------------------------------------- popup open helpers
    //
    // 坐标约定（务必遵守）：所有 openXxx 一律收【内容坐标】，函数内部用
    // sX()/sY() 换算且只换算一次，最后统一走 setPopup() 落位。
    // 修复前 renderIOOptions/renderOutputOptions 里 8 处调用先 sxOf()/syOf()
    // 转成屏幕坐标，openXxx 内部又转一次 —— 画布原点被算了两遍，弹层整体
    // 右下偏移约 (canvasX+12)·(1+zoom)，1080p 下约 160×50 px。

    /** 弹层落位的唯一出口：换算后按面板边界收敛，绝不让弹层飞出编辑窗口。 */
    private void setPopup(Popup p) {
        int minX = panelX + 4;
        int minY = panelY + 4;
        int rightEdge = panelX + panelW - 4;
        int bottomEdge = panelY + panelH - 4;
        // 1) 先夹住左上角：列表类弹层的可用高度取决于最终 y
        p.x = Math.max(minX, Math.min(p.x, Math.max(minX, rightEdge - p.w)));
        p.y = Math.max(minY, Math.min(p.y, Math.max(minY, bottomEdge - p.h)));
        // 2) 按剩余空间展开（高度/宽度可能被改写）
        p.applyBounds(minX, rightEdge, minY, bottomEdge);
        // 3) 尺寸变了要再夹一次，宽度变大时尤其必要
        p.x = Math.max(minX, Math.min(p.x, Math.max(minX, rightEdge - p.w)));
        p.y = Math.max(minY, Math.min(p.y, Math.max(minY, bottomEdge - p.h)));
        popup = p;
    }

    private void openChoice(int x, int y, String current, List<String> values, List<String> labels,
                            Consumer<String> onSelect) {
        setPopup(new Popup.ChoicePopup(sX(x), sY(y) + BAR_H, 120, values, labels, current, onSelect));
    }

    private void openNumber(int x, int y, int w, long value, Consumer<Long> set) {
        setPopup(new Popup.TextPopup(this, sX(x), sY(y) + BAR_H - 3, Math.max(80, w + 30), String.valueOf(value), "0..999999",
                s -> {
                    try {
                        long v = Long.parseLong(s);
                        pushUndo();
                        set.accept(Math.max(0, v));
                    } catch (NumberFormatException ignored) {
                    }
                }, null));
    }

    private void openTimerNumber(int x, int y, int w, BProgram.TimerTrigger trigger) {
        long minimum = TimerRules.minimumCount(trigger);
        String unit = trigger.unit == BProgram.TimerTrigger.Unit.TICKS ? "刻" : "秒";
        String reason = TimerRules.usesOnlyEnergyIO(trigger.body)
                ? "纯能量传输，本服限制（" + unit + "）"
                : "普通传输，本服限制（" + unit + "）";
        setPopup(new Popup.NumberPopup(sX(x), sY(y) + BAR_H, Math.max(200, w + 150),
                trigger.count, minimum, reason, value -> {
            pushUndo();
            trigger.count = value;
            if (value < minimum) {
                showStatus("✖ 本服务器要求至少 " + minimum + unit
                        + "；数值已保留，请修改后再保存", 0xFFD13438);
            } else {
                String duration = trigger.unit == BProgram.TimerTrigger.Unit.TICKS
                        ? value + " 刻 = " + formatSeconds(value / 20.0)
                        : value + " 秒 = " + (value * 20) + " 刻";
                showStatus("执行间隔已设为 " + duration, C_SELECT);
            }
        }));
    }

    private static String formatSeconds(double seconds) {
        if (seconds == Math.rint(seconds)) return (long) seconds + " 秒";
        return String.format(java.util.Locale.ROOT, "%.2f 秒", seconds);
    }

    private void openTextEditor(int x, int y, String initial, Consumer<String> onDone,
                                Runnable unused, int width) {
        setPopup(new Popup.TextPopup(this, sX(x), sY(y) + BAR_H - 3, width, initial, "", onDone, null));
    }

    /** Fully structured Chinese editor for `with/without` tag expressions. */
    private void openWithEditor(int x, int y, BProgram.ResourceLimit limit) {
        showWithEditor(sX(x), sY(y) + BAR_H + 2, () -> limit.with, value -> limit.with = value);
    }

    private void showWithEditor(int screenX, int screenY,
                                Supplier<BProgram.WithFilter> getter,
                                Consumer<BProgram.WithFilter> setter) {
        BProgram.WithFilter current = getter.get();
        List<String> values = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        if (current == null) {
            values.addAll(List.of("first_item", "first_all", "first_manual"));
            labels.addAll(List.of("从物品选择资源标签", "搜索全部资源标签", "手动输入原标签（高级）"));
            if (SfmCaps.withComponent()) {
                values.add("first_nbt");
                labels.add("按物品组件(NBT)筛选…");
            }
        } else {
            // 「且/或」已经搬到行内的小积木上，这里只留整体设置，菜单才不会长得要滚
            values.addAll(List.of("clear", "with", "without", "not", "preview_match"));
            labels.addAll(List.of("不限制资源标签", "只处理符合条件的资源", "排除符合条件的资源",
                    "把整个条件取反", "预览匹配物品…"));
        }
        setPopup(new Popup.ChoicePopup(screenX, screenY, 230, values, labels, "", picked -> {
            if (picked.equals("preview_match")) {
                BProgram.WithFilter f = getter.get();
                if (f != null) openFilterPreview(f);
                return;
            }
            if (picked.equals("first_nbt")) {
                Minecraft.getInstance().setScreen(
                        new io.github.xianynomial.sfmfactorystudio.client.NbtItemPickerScreen(this, id -> {
                            pushUndo();
                            BProgram.WithFilter created = new BProgram.WithFilter();
                            created.expr = new BProgram.WithExpr.Tag(nbtMatcher(id));
                            setter.accept(created);
                            layoutDirty = true;
                        }));
                return;
            }
            if (picked.equals("first_item") || picked.equals("first_all")) {
                openResourceTagPicker(picked.equals("first_all"), matcher -> {
                    pushUndo();
                    BProgram.WithFilter created = new BProgram.WithFilter();
                    created.expr = new BProgram.WithExpr.Tag(matcher);
                    setter.accept(created);
                    layoutDirty = true;
                });
                return;
            }
            if (picked.equals("first_manual")) {
                openManualNewTag(screenX, screenY, matcher -> {
                    pushUndo();
                    BProgram.WithFilter created = new BProgram.WithFilter();
                    created.expr = new BProgram.WithExpr.Tag(matcher);
                    setter.accept(created);
                    layoutDirty = true;
                });
                return;
            }
            if (picked.equals("clear")) {
                pushUndo();
                setter.accept(null);
                return;
            }
            BProgram.WithFilter filter = getter.get();
            if (filter == null) return;

            switch (picked) {
                case "with" -> {
                    pushUndo();
                    filter.mode = BProgram.WithFilter.Mode.WITH;
                }
                case "without" -> {
                    pushUndo();
                    filter.mode = BProgram.WithFilter.Mode.WITHOUT;
                }
                case "not" -> {
                    pushUndo();
                    if (filter.expr instanceof BProgram.WithExpr.Not not) {
                        filter.expr = not.inner;
                    } else {
                        BProgram.WithExpr.Not not = new BProgram.WithExpr.Not();
                        not.inner = filter.expr;
                        filter.expr = not;
                    }
                }
                default -> {
                }
            }
        }));
    }

    // ---- NBT 组件条件（伪标签 nbt:<组件id>，服务端 fork 支持时可用）-----------

    

    /**
     * 组件 id → 伪标签匹配串：SFML 的 tagMatcher 语法是 ns:elem/elem（路径内不允许
     * 冒号），所以组件 id 的冒号写成斜杠：minecraft:enchantments → nbt:minecraft/enchantments。
     */
    private static String nbtMatcher(String componentIdOrDeep) {
        // 深路径形如 minecraft:enchantments/minecraft.sharpness——组件 id 的冒号
        // 写斜杠，选择器原样保留
        int slash = componentIdOrDeep.indexOf('/');
        if (slash < 0) return "nbt:" + componentIdOrDeep.replace(':', '/');
        String comp = componentIdOrDeep.substring(0, slash).replace(':', '/');
        return "nbt:" + comp + componentIdOrDeep.substring(slash);
    }

    /** 伪标签匹配串 → 组件 id（显示用）。非 nbt: 前缀原样返回。 */
    private static String nbtComponentDisplay(String matcher) {
        if (!matcher.startsWith("nbt:")) return matcher;
        var parsed = io.github.xianynomial.sfmfactorystudio.net.NbtMatcherHook.parse(matcher);
        if (parsed == null) return matcher;
        String zh = ComponentNames.nameOf(parsed.componentId());
        StringBuilder sb = new StringBuilder("NBT·").append(zh != null ? zh : parsed.componentId());
        for (String sel : parsed.selector()) {
            if (sel.startsWith("gt")) sb.append("·>").append(sel.substring(2));
            else if (sel.startsWith("lt")) sb.append("·<").append(sel.substring(2));
            else if (sel.startsWith("eq")) sb.append("·=").append(sel.substring(2));
            else sb.append("·").append(sel);
        }
        return sb.toString();
    }

    /** NBT 组件条件选择：物品选择器（推荐）/ 常用中文列表 / 手动输入。 */
    

    /**
     * 背包物品的 NBT 采集已升级为独立选择器 NbtItemPickerScreen
     * （图标网格 + 拼音搜索 + 中文组件列表），此处只保留手动输入校验。
     */
    

    /** 程序里是否用了 nbt: 伪标签（无服务器支持时保存前拦截，不发请求）。 */
    private boolean programHasNbtMatcher() {
        for (BProgram.Trigger t : program.triggers) {
            if (bodyHasNbtMatcher(t.body)) return true;
        }
        return false;
    }

    private static boolean bodyHasNbtMatcher(List<BProgram.Statement> list) {
        for (BProgram.Statement s : list) {
            if (s instanceof BProgram.Statement.Input in) {
                for (BProgram.ResourceLimit rl : in.limits) if (exprHasNbt(rl.with)) return true;
            } else if (s instanceof BProgram.Statement.Output o) {
                for (BProgram.ResourceLimit rl : o.limits) if (exprHasNbt(rl.with)) return true;
            } else if (s instanceof BProgram.Statement.If iff) {
                for (BProgram.Branch b : iff.branches) {
                    if (b.cond instanceof BProgram.Bool.Has h && exprHasNbt(h.with)) return true;
                    if (bodyHasNbtMatcher(b.body)) return true;
                }
                if (bodyHasNbtMatcher(iff.elseBody)) return true;
            }
        }
        return false;
    }

    private static boolean exprHasNbt(BProgram.WithFilter filter) {
        return filter != null && exprHasNbtNode(filter.expr);
    }

    private static boolean exprHasNbtNode(BProgram.WithExpr e) {
        if (e instanceof BProgram.WithExpr.Tag t) return t.matcher.startsWith("nbt:");
        if (e instanceof BProgram.WithExpr.Not n) return exprHasNbtNode(n.inner);
        if (e instanceof BProgram.WithExpr.And a) {
            for (BProgram.WithExpr p : a.parts) if (exprHasNbtNode(p)) return true;
        }
        if (e instanceof BProgram.WithExpr.Or o) {
            for (BProgram.WithExpr p : o.parts) if (exprHasNbtNode(p)) return true;
        }
        return false;
    }

    private BProgram.WithExpr appendWithTag(BProgram.WithExpr current, String matcher, boolean useOr) {
        BProgram.WithExpr.Tag added = new BProgram.WithExpr.Tag(matcher);
        if (useOr && current instanceof BProgram.WithExpr.Or or) {
            or.parts.add(added);
            return or;
        }
        if (!useOr && current instanceof BProgram.WithExpr.And and) {
            and.parts.add(added);
            return and;
        }
        if (useOr) {
            BProgram.WithExpr.Or or = new BProgram.WithExpr.Or();
            or.parts.add(current);
            or.parts.add(added);
            return or;
        }
        BProgram.WithExpr.And and = new BProgram.WithExpr.And();
        and.parts.add(current);
        and.parts.add(added);
        return and;
    }

    private void openManualNewTag(int screenX, int screenY, Consumer<String> onValid) {
        setPopup(new Popup.TextPopup(this, screenX, screenY, 210, "",
                "原标签，例如 c:ingots/iron", value -> {
            String matcher = validTagMatcher(value);
            if (matcher != null) onValid.accept(matcher);
        }, null));
    }

    private @Nullable String validTagMatcher(String value) {
        String matcher = value == null ? "" : value.trim().replaceFirst("^#+", "");
        SfmlToBlocks.ResultWithFilter parsed = SfmlToBlocks.parseWithFilter("with #" + matcher);
        if (!parsed.ok()) {
            showStatus("✖ 资源标签原标签格式不正确", 0xFFD13438);
            return null;
        }
        return matcher;
    }

    private void openResourceTagPicker(boolean allTags, Consumer<String> onPick) {
        Minecraft.getInstance().setScreen(new io.github.xianynomial.sfmfactorystudio.client.ResourceTagPickerScreen(
                this, allTags, onPick));
    }

    private @Nullable BProgram.WithExpr removeWithTag(BProgram.WithExpr expr, BProgram.WithExpr.Tag target) {
        if (expr == target) return null;
        if (expr instanceof BProgram.WithExpr.Not not) {
            BProgram.WithExpr inner = removeWithTag(not.inner, target);
            if (inner == null) return null;
            not.inner = inner;
            return not;
        }
        if (expr instanceof BProgram.WithExpr.And and) {
            removeWithTagFromGroup(and.parts, target);
            if (and.parts.isEmpty()) return null;
            return and.parts.size() == 1 ? and.parts.get(0) : and;
        }
        if (expr instanceof BProgram.WithExpr.Or or) {
            removeWithTagFromGroup(or.parts, target);
            if (or.parts.isEmpty()) return null;
            return or.parts.size() == 1 ? or.parts.get(0) : or;
        }
        return expr;
    }

    private void removeWithTagFromGroup(List<BProgram.WithExpr> parts, BProgram.WithExpr.Tag target) {
        for (int i = 0; i < parts.size(); i++) {
            BProgram.WithExpr before = parts.get(i);
            BProgram.WithExpr after = removeWithTag(before, target);
            if (after == before) continue;
            if (after == null) parts.remove(i);
            else parts.set(i, after);
            return;
        }
    }

    private void collectWithTags(BProgram.WithExpr expr, List<BProgram.WithExpr.Tag> out) {
        if (expr instanceof BProgram.WithExpr.Tag tag) out.add(tag);
        else if (expr instanceof BProgram.WithExpr.Not not) collectWithTags(not.inner, out);
        else if (expr instanceof BProgram.WithExpr.And and) and.parts.forEach(part -> collectWithTags(part, out));
        else if (expr instanceof BProgram.WithExpr.Or or) or.parts.forEach(part -> collectWithTags(part, out));
    }

    private void setWithTag(BProgram.WithExpr.Tag tag, String value) {
        String matcher = validTagMatcher(value);
        if (matcher == null) return;
        pushUndo();
        tag.matcher = matcher;
    }

    /**
     * 标签药丸右键菜单：复制标签组 / 粘贴标签组（覆盖）/ 编辑 / 清空。
     * 空药丸 + 剪贴板有标签时直接粘贴，免菜单。
     */
    private void openLabelContext(int x, int y, List<String> target) {
        if (copiedLabels != null && target.isEmpty()) {
            pushUndo();
            target.addAll(copiedLabels);
            layoutDirty = true;
            showStatus("已粘贴标签 " + String.join("+", copiedLabels), C_SELECT);
            return;
        }
        List<String> values = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        if (!target.isEmpty()) {
            values.add("copy");
            labels.add("复制标签组");
        }
        if (copiedLabels != null) {
            values.add("paste");
            labels.add("粘贴：" + String.join("+", copiedLabels));
        }
        values.add("edit");
        labels.add("编辑标签…");
        if (!target.isEmpty()) {
            values.add("clear");
            labels.add("清空标签");
        }
        setPopup(new Popup.ChoicePopup(sX(x), sY(y) + BAR_H, 170, values, labels, "", action -> {
            switch (action) {
                case "copy" -> {
                    copiedLabels = new ArrayList<>(target);
                    showStatus("已复制标签 " + String.join("+", target), C_SELECT);
                }
                case "paste" -> {
                    pushUndo();
                    target.clear();
                    target.addAll(copiedLabels);
                    layoutDirty = true;
                }
                case "edit" -> openLabelEditor(x, y, target);
                case "clear" -> {
                    pushUndo();
                    target.clear();
                    layoutDirty = true;
                }
            }
        }));
    }

    private void openLabelEditor(int x, int y, List<String> target) {
        openLabelEditor(x, y, target, false);
    }

    private void openLabelEditor(int x, int y, List<String> target, boolean allowEmpty) {
        showLabelEditor(sX(x), sY(y) + BAR_H + 2, target, allowEmpty);
    }

    private void showLabelEditor(int screenX, int screenY, List<String> target, boolean allowEmpty) {
        List<String> known = new ArrayList<>(knownLabels);
        for (String l : program.collectLabels()) {
            if (!known.contains(l)) known.add(l);
        }
        Map<String, Integer> counts = new LinkedHashMap<>(knownLabelCounts);
        for (String label : known) counts.putIfAbsent(label, 0);
        setPopup(new Popup.LabelPopup(screenX, screenY, 220,
                known, counts, new ArrayList<>(target),
                result -> {
                    if (!allowEmpty && result.stream().noneMatch(label -> label != null && !label.isBlank())) {
                        showStatus("✖ 这里必须保留至少一个方块标签", 0xFFD13438);
                        return;
                    }
                    pushUndo();
                    target.clear();
                    target.addAll(result);
                    for (String l : result) {
                        if (!knownLabels.contains(l)) knownLabels.add(l);
                        knownLabelCounts.putIfAbsent(l, 0);
                    }
                }, SFMGuiNetwork.labelsSupported()));
    }

    private void openResourceKindMenu(int contentX, int contentY, BProgram.ResourceRef current,
                                      Consumer<BProgram.ResourceRef> setter) {
        showResourceKindMenu(sX(contentX), sY(contentY) + BAR_H + 2, current, setter);
    }

    private void showResourceKindMenu(int screenX, int screenY, BProgram.ResourceRef current,
                                      Consumer<BProgram.ResourceRef> setter) {
        List<BProgram.ResourceKind> kinds = List.of(
                BProgram.ResourceKind.ITEM,
                BProgram.ResourceKind.FLUID,
                BProgram.ResourceKind.CHEMICAL,
                BProgram.ResourceKind.GAS,
                BProgram.ResourceKind.SLURRY,
                BProgram.ResourceKind.PIGMENT,
                BProgram.ResourceKind.REDSTONE,
                BProgram.ResourceKind.INFUSION,
                BProgram.ResourceKind.FORGE_ENERGY);
        List<String> values = kinds.stream().map(kind -> kind.name().toLowerCase(java.util.Locale.ROOT)).toList();
        List<String> labels = kinds.stream().map(kind -> kind.chineseName).toList();
        String selected = current.kind() == BProgram.ResourceKind.CUSTOM
                ? ""
                : current.kind().name().toLowerCase(java.util.Locale.ROOT);
        setPopup(new Popup.ChoicePopup(screenX, screenY, 120,
                values, labels, selected, value -> {
            BProgram.ResourceKind kind = BProgram.ResourceKind.valueOf(value.toUpperCase(java.util.Locale.ROOT));
            if (kind != current.kind()) setter.accept(BProgram.ResourceRef.forKind(kind));
        }));
    }

    private void openResourceValueMenu(int contentX, int contentY, BProgram.ResourceRef current,
                                       Consumer<BProgram.ResourceRef> setter) {
        showResourceValueMenu(sX(contentX), sY(contentY) + BAR_H + 2, current, setter);
    }

    /**
     * 资源槽右键：复制/粘贴/清空。复制把资源存进会话剪贴板；粘贴把剪贴板
     * 资源填进本槽（跨卡片可用）。有剪贴板时空槽右键直接粘贴，免菜单。
     */
    private void openResourceSlotContext(int contentX, int contentY,
                                         BProgram.ResourceRef current, Consumer<BProgram.ResourceRef> setter) {
        if (copiedResource != null && current.isWildcard()) {
            // 空槽 + 剪贴板有货：直接粘贴（最常见意图，免一次菜单）
            pushUndo();
            setter.accept(copiedResource);
            showStatus("已粘贴资源 " + copiedResource.resourcePart(), C_SELECT);
            return;
        }
        List<String> values = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        if (!current.isWildcard()) {
            values.add("copy");
            labels.add("复制资源");
        }
        if (copiedResource != null) {
            values.add("paste");
            labels.add("粘贴：" + copiedResource.resourcePart());
            values.add("clear");
            labels.add("清空此槽");
        }
        values.add("browse");
        labels.add("浏览选择…");
        setPopup(new Popup.ChoicePopup(sX(contentX), sY(contentY) + BAR_H, 170, values, labels, "", action -> {
            switch (action) {
                case "copy" -> {
                    copiedResource = current;
                    showStatus("已复制资源 " + current.resourcePart() + "，右键任意空槽粘贴", C_SELECT);
                }
                case "paste" -> {
                    pushUndo();
                    setter.accept(copiedResource);
                }
                case "clear" -> {
                    pushUndo();
                    setter.accept(BProgram.ResourceRef.forKind(current.kind()));
                }
                case "browse" -> openResourceValueMenu(contentX, contentY, current, setter);
            }
        }));
    }

    private void showResourceValueMenu(int screenX, int screenY, BProgram.ResourceRef current,
                                       Consumer<BProgram.ResourceRef> setter) {
        setPopup(new Popup.ChoicePopup(screenX, screenY, 150,
                List.of("browse", "id", "all"),
                List.of("浏览" + current.kind().chineseName, "输入资源名称", "该类别的全部资源"),
                "", action -> {
            switch (action) {
                case "browse" -> openBuiltInCatalog(current.kind(), setter);
                case "id" -> setPopup(new Popup.TextPopup(this, screenX, screenY, 220,
                        current.resourcePart(), "例如 water 或 minecraft:water", value -> {
                    try {
                        setter.accept(current.withResourcePart(value));
                    } catch (IllegalArgumentException ex) {
                        showStatus("✖ " + ex.getMessage(), 0xFFD13438);
                    }
                }, null));
                case "all" -> setter.accept(BProgram.ResourceRef.forKind(current.kind()));
            }
        }));
    }

    private boolean sameResourceCategory(BProgram.ResourceRef expected, BProgram.ResourceRef incoming) {
        return expected.typeNamespace.equalsIgnoreCase(incoming.typeNamespace)
                && expected.typeName.equalsIgnoreCase(incoming.typeName);
    }

    private void openBuiltInCatalog(BProgram.ResourceKind kind, Consumer<BProgram.ResourceRef> setter) {
        if (kind == BProgram.ResourceKind.CUSTOM) {
            showStatus("这个自定义类别请使用“输入资源名称”", 0xFFB45309);
            return;
        }
        Minecraft.getInstance().setScreen(new io.github.xianynomial.sfmfactorystudio.client.ResourcePickerScreen(this, kind, picked -> {
            try {
                BProgram.ResourceRef incoming = BProgram.ResourceRef.parse(picked);
                if (incoming.kind() == kind) setter.accept(incoming);
            } catch (IllegalArgumentException ex) {
                showStatus("✖ 无法识别这个资源", 0xFFD13438);
            }
        }));
    }

    private BProgram.ResourceRef firstResource(List<BProgram.ResourceRef> resources) {
        return resources.isEmpty()
                ? BProgram.ResourceRef.forKind(BProgram.ResourceKind.ITEM)
                : resources.get(0);
    }

    private void setFirstResource(List<BProgram.ResourceRef> resources, BProgram.ResourceRef resource) {
        if (resources.isEmpty()) resources.add(resource);
        else resources.set(0, resource);
    }

    private void showResourceListMenu(int screenX, int screenY, List<BProgram.ResourceRef> resources,
                                      String addLabel) {
        List<String> values = new ArrayList<>(List.of("add"));
        List<String> labels = new ArrayList<>(List.of("＋ " + addLabel));
        for (int i = 0; i < resources.size(); i++) {
            BProgram.ResourceRef resource = resources.get(i);
            String name = resource.isWildcard() ? "全部" : resource.resourcePart();
            values.add("kind:" + i);
            labels.add("第 " + (i + 1) + " 项类别：" + resource.kind().chineseName);
            values.add("value:" + i);
            labels.add("第 " + (i + 1) + " 项资源：" + name);
            values.add("delete:" + i);
            labels.add("删除第 " + (i + 1) + " 项");
        }
        setPopup(new Popup.ChoicePopup(screenX, screenY, 190, values, labels, "", action -> {
            if (action.equals("add")) {
                showNewResourceKindMenu(screenX, screenY, resource -> {
                    pushUndo();
                    resources.add(resource);
                });
                return;
            }
            int colon = action.indexOf(':');
            if (colon < 0) return;
            int index = Integer.parseInt(action.substring(colon + 1));
            if (index < 0 || index >= resources.size()) return;
            BProgram.ResourceRef current = resources.get(index);
            if (action.startsWith("kind:")) {
                showResourceKindMenu(screenX, screenY, current, replacement -> {
                    pushUndo();
                    resources.set(index, replacement);
                });
            } else if (action.startsWith("value:")) {
                showResourceValueMenu(screenX, screenY, current, replacement -> {
                    pushUndo();
                    resources.set(index, replacement);
                });
            } else if (action.startsWith("delete:")) {
                pushUndo();
                resources.remove(index);
            }
        }));
    }

    private void openSideEditor(int x, int y, BProgram.LabelAccess access) {
        showSideEditor(sX(x), sY(y) + BAR_H + 2, access);
    }

    private void showSideEditor(int screenX, int screenY, BProgram.LabelAccess access) {
        List<String> values = new ArrayList<>(List.of("each", "top", "bottom", "north", "south", "east", "west", "front", "back", "left", "right", "null"));
        List<String> labels = new ArrayList<>(List.of("每个侧面", "顶面", "底面", "北面", "南面", "东面", "西面", "前面", "后面", "左面", "右面", "无方向面"));
        setPopup(new Popup.ChoicePopup(screenX, screenY, 90, values, labels,
                access.eachSide ? "each" : access.sides.isEmpty() ? "" : access.sides.get(0).sfml(),
                picked -> {
                    pushUndo();
                    if (picked.equals("each")) {
                        access.eachSide = !access.eachSide;
                        if (access.eachSide) access.sides.clear();
                        return;
                    }
                    access.eachSide = false;
                    BProgram.Side side = BProgram.Side.fromSfml(picked);
                    if (access.sides.contains(side)) access.sides.remove(side);
                    else access.sides.add(side);
                }));
    }

    private void openConditionEditor(int x, int y, BProgram.Bool cond) {
        if (cond instanceof BProgram.Bool.Not not
                && (not.inner instanceof BProgram.Bool.And || not.inner instanceof BProgram.Bool.Or)) {
            BProgram.Bool group = not.inner;
            List<String> values = List.of("remove_not", "and", "or", "add_has", "add_redstone", "remove");
            List<String> labels = List.of("取消整组取反", "全部条件都满足", "任意一个条件满足",
                    "＋ 再加普通条件", "＋ 再加红石条件", "移除最后一项");
            setPopup(new Popup.ChoicePopup(sX(x), sY(y) + BAR_H + 2, 160, values, labels,
                    "", action -> {
                if (action.equals("remove_not")) {
                    pushUndo();
                    replaceIn(program, cond, group);
                } else {
                    editLogicalCondition(group, action);
                }
            }));
            return;
        }
        if (cond instanceof BProgram.Bool.And || cond instanceof BProgram.Bool.Or) {
            List<String> values = List.of("and", "or", "add_has", "add_redstone", "remove", "not");
            List<String> labels = List.of("全部条件都满足", "任意一个条件满足", "再加普通条件", "再加红石条件", "移除最后一项", "把整组条件取反");
            setPopup(new Popup.ChoicePopup(sX(x), sY(y) + BAR_H + 2, 150, values, labels,
                    cond instanceof BProgram.Bool.And ? "and" : "or", action -> editLogicalCondition(cond, action)));
            return;
        }
        setPopup(new ConditionPopup(sX(x), sY(y) + BAR_H + 2, cond));
    }

    private void editLogicalCondition(BProgram.Bool condition, String action) {
        List<BProgram.Bool> parts = condition instanceof BProgram.Bool.And and ? and.parts : ((BProgram.Bool.Or) condition).parts;
        switch (action) {
            case "and", "or" -> {
                boolean alreadyRequested = action.equals("and")
                        ? condition instanceof BProgram.Bool.And
                        : condition instanceof BProgram.Bool.Or;
                if (alreadyRequested) return;
                pushUndo();
                BProgram.Bool replacement;
                if (action.equals("and")) {
                    BProgram.Bool.And and = new BProgram.Bool.And();
                    and.parts.addAll(parts);
                    replacement = and;
                } else {
                    BProgram.Bool.Or or = new BProgram.Bool.Or();
                    or.parts.addAll(parts);
                    replacement = or;
                }
                replaceIn(program, condition, replacement);
            }
            case "add_has" -> {
                pushUndo();
                parts.add(newConditionHas());
            }
            case "add_redstone" -> {
                pushUndo();
                parts.add(new BProgram.Bool.Redstone());
            }
            case "remove" -> {
                if (parts.isEmpty()) return;
                pushUndo();
                parts.remove(parts.size() - 1);
                if (parts.size() == 1) replaceIn(program, condition, parts.get(0));
                else if (parts.isEmpty()) replaceIn(program, condition, new BProgram.Bool.Const(true));
            }
            case "not" -> {
                pushUndo();
                BProgram.Bool.Not not = new BProgram.Bool.Not();
                not.inner = condition;
                replaceIn(program, condition, not);
            }
        }
    }

    private void openAddConditionMenu(int x, int y, BProgram.Branch branch) {
        List<String> values = List.of(
                "and_has", "and_redstone",
                "or_has", "or_redstone", "not");
        List<String> labels = List.of(
                "＋ 同时满足：方块里有资源", "＋ 同时满足：红石信号",
                "＋ 任一满足：方块里有资源", "＋ 任一满足：红石信号",
                branch.cond instanceof BProgram.Bool.Not ? "取消现有判断的整体取反" : "把现有判断整体取反");
        setPopup(new Popup.ChoicePopup(sX(x), sY(y) + BAR_H + 2, 190,
                values, labels, "", action -> {
            pushUndo();
            if (action.equals("not")) {
                if (branch.cond instanceof BProgram.Bool.Not not) {
                    branch.cond = not.inner;
                } else {
                    BProgram.Bool.Not not = new BProgram.Bool.Not();
                    not.inner = branch.cond;
                    branch.cond = not;
                }
                return;
            }
            BProgram.Bool added = switch (action) {
                case "and_redstone", "or_redstone" -> new BProgram.Bool.Redstone();
                default -> newConditionHas();
            };
            boolean useOr = action.startsWith("or_");
            if (useOr && branch.cond instanceof BProgram.Bool.Or or) {
                or.parts.add(added);
            } else if (!useOr && branch.cond instanceof BProgram.Bool.And and) {
                and.parts.add(added);
            } else if (useOr) {
                BProgram.Bool.Or or = new BProgram.Bool.Or();
                or.parts.add(branch.cond);
                or.parts.add(added);
                branch.cond = or;
            } else {
                BProgram.Bool.And and = new BProgram.Bool.And();
                and.parts.add(branch.cond);
                and.parts.add(added);
                branch.cond = and;
            }
        }));
    }

    private @Nullable BProgram.ResourceLimit primaryLimit(List<BProgram.ResourceLimit> limits) {
        if (limits.isEmpty()) {
            var rl = new BProgram.ResourceLimit();
            limits.add(rl);
            return rl;
        }
        return limits.get(0);
    }

    private void toggleOptions(BProgram.Statement stmt) {
        if (!expandedIds.remove(stmt.id)) expandedIds.add(stmt.id);
        layout.markAllDirty(); // 展开改变卡高；点按不频繁，直接全部重排
        layoutDirty = true;
    }

    // =========================================================== condition popup

    /** Structured editor for a single condition.
     *
     *  Layout: pill 链沿水平方向累加，到达右边界自动折到下一行
     *  （flow layout）。applyBounds 在落位后按最长行宽度收紧 w、按
     *  实际行数算出 h，避免原来写死 240×144 时"≥ 物品 全部 更多资源"
     *  这一行被截到面板外面、把画布上的"否则如"压住的错乱。 */
    private class ConditionPopup extends Popup {
        private final BProgram.Bool cond;
        private final int rowH = 22;
        /** 上下左右的内边距：左右 6 / 上下 6，跟原 render() 的 rx/ry 起点对齐。 */
        private static final int PAD = 12;
        /** 面板最小宽度，避免出现窄到看不清的窄条。 */
        private static final int MIN_W = 220;
        private final List<Hit> localHits = new ArrayList<>();
        /** drawP 的当前行 y；render() 开头重置。存为字段是为了让换行后的
         *  ry 能跨 drawP 调用持续推进（参数 py 只能改当前调用）。 */
        private int ry;
        /** 最近一个 drawP 画下的 pill 左上角与宽度——给 JeiGhostDrops 注册
         *  资源按钮的 ghost drop 矩形用。 */
        private int lastPillX, lastPillY, lastPillW;

        ConditionPopup(int sx, int sy, BProgram.Bool cond) {
            this.x = sx;
            this.y = sy;
            this.w = 240;
            this.cond = cond;
            // h 是初值；setPopup -> applyBounds 会按内容重写。
            this.h = rowH * 6 + PAD;
        }

        private BProgram.Bool inner() {
            return cond instanceof BProgram.Bool.Not n ? n.inner : cond;
        }

        private String kind() {
            BProgram.Bool c = inner();
            if (c instanceof BProgram.Bool.Redstone) return "redstone";
            if (c instanceof BProgram.Bool.RawBool) return "raw";
            if (c instanceof BProgram.Bool.Const constant) return constant.value ? "true" : "false";
            return "has";
        }

        private @Nullable BProgram.Bool.Has asHas() {
            if (inner() instanceof BProgram.Bool.Has h) return h;
            return null;
        }

        @Override
        public void render(GuiGraphics g, Font fnt, int mx, int my) {
            localHits.clear();
            rounded(g, x + 2, y + 3, w, h, 8, G_SHADOW);
            rounded(g, x, y, w, h, 8, G_CARD);
            border(g, x, y, w, h, G_BORDER);
            int rx = x + 6;
            ry = y + 5;
            // 弹出子菜单都从 ConditionPopup 底部弹出，避免换行后位置错位；
            // 用 this.h 而不是局部 ph——onClick 在玩家点击时才执行，要拿当下面板高度。
            int popY = y + h - 22;
            rx = drawP(g, fnt, rx, switch (kind()) {
                case "has" -> T_COND.getString();
                case "redstone" -> "红石";
                case "true" -> "总是成立";
                case "false" -> "永不成立";
                default -> "原样";
            }, 40, () -> setPopup(new Popup.ChoicePopup(x + 6, popY, 120,
                    List.of("has", "redstone"),
                    List.of(T_COND.getString(), "红石信号"), kind(),
                    this::switchKind)), mx, my);
            rx = drawP(g, fnt, rx, cond instanceof BProgram.Bool.Not ? "◉ 已取反" : "○ 取反", 56,
                    () -> {
                        pushUndo();
                        toggleNot();
                    }, mx, my);
            BProgram.Bool.Has has = asHas();
            if (has != null) {
                // (硬换行 ry += rowH 全部去掉，由 drawP 自动 flow；详见 applyBounds 注释)
                rx = drawP(g, fnt, rx, setOpZh(has.setMode), 70,
                        () -> setPopup(new Popup.ChoicePopup(x + 6, popY, 150,
                                List.of("default", "overall", "some", "every", "one", "lone"),
                                List.of("默认按合计", "所有方块合计", "至少一个方块", "每个方块", "恰好一个方块", "不超过一个方块"),
                                has.setMode.name().toLowerCase(java.util.Locale.ROOT), v -> {
                            pushUndo();
                            has.setMode = BProgram.Bool.SetMode.valueOf(v.toUpperCase(java.util.Locale.ROOT));
                        })), mx, my);
                rx = drawP(g, fnt, rx,
                        has.access.labels.isEmpty() ? T_LABEL.getString() : String.join("+", has.access.labels), 50,
                        () -> showLabelEditor(x + 6, popY, has.access.labels, false), mx, my);
                // (硬换行 ry += rowH 全部去掉，由 drawP 自动 flow；详见 applyBounds 注释)
                rx = drawP(g, fnt, rx, has.comparison.symbol(), 24, () -> setPopup(new Popup.ChoicePopup(
                        x + 6, popY, 90, List.of(">", ">=", "=", "<=", "<"),
                        List.of(">", "≥", "=", "≤", "<"), has.comparison.symbol(), v -> {
                            pushUndo();
                            has.comparison = BProgram.Bool.Comparison.fromSfml(v);
                        })), mx, my);
                long num = has.number;
                // 数字 pill 的二级弹窗原本"贴在该 pill 下方"，但当 pill 因换行
                // 落到新行时，事先 capture 的 frx/fry 会指向旧位置——改成统一
                // 从面板底部弹出（跟其他 pill 的二级弹窗一致）。
                rx = drawP(g, fnt, rx, String.valueOf(num), 34, () ->
                        popup = new Popup.TextPopup(BlockEditorScreen.this, x + 6, popY, 90,
                                String.valueOf(num), "0..999999",
                                s -> {
                                    try {
                                        pushUndo();
                                        has.number = Math.max(0, Long.parseLong(s.trim()));
                                    } catch (NumberFormatException ignored) {
                                    }
                                }, null), mx, my);
                BProgram.ResourceRef resource = firstResource(has.resources);
                rx = drawP(g, fnt, rx, resource.kind().chineseName, 38,
                        () -> showResourceKindMenu(x + 6, popY, resource, replacement -> {
                            pushUndo();
                            setFirstResource(has.resources, replacement);
                        }), mx, my);
                String resourceText = resource.isWildcard() ? "□ 全部" : shortResource(resource);
                rx = drawP(g, fnt, rx, resourceText, 44,
                        () -> showResourceValueMenu(x + 6, popY, resource, replacement -> {
                            pushUndo();
                            setFirstResource(has.resources, replacement);
                        }), mx, my);
                // 资源按钮 ghost drop：注册矩形改用 lastPill*——资源按钮 pill 自身
                // 的真实矩形（覆盖换行后落到新行的情况，不再跨越两行）。
                // 走 addGhostZoneScreen：弹窗是屏幕坐标系，但要裁进画布，折叠/收起
                // 后不留落点。
                addGhostZoneScreen(lastPillX, lastPillY, lastPillW, 16, resource.toString(), dropped -> {
                    try {
                        BProgram.ResourceRef incoming = BProgram.ResourceRef.parse(dropped);
                        if (!sameResourceCategory(resource, incoming)) {
                            showStatus("✖ 请先选择“" + incoming.kind().chineseName + "”类别", 0xFFD13438);
                            return;
                        }
                        pushUndo();
                        setFirstResource(has.resources, incoming);
                    } catch (IllegalArgumentException ex) {
                        showStatus("✖ 无法识别这个资源", 0xFFD13438);
                    }
                });
                rx = drawP(g, fnt, rx, "＋更多资源", 54,
                        () -> showResourceListMenu(x + 6, popY, has.resources, "再加一种判断资源"), mx, my);
                // (硬换行 ry += rowH 全部去掉，由 drawP 自动 flow；详见 applyBounds 注释)
                String withText = has.with == null ? "＋资源标签" : shortUi(shortWith(has.with), 15);
                rx = drawP(g, fnt, rx, withText, 80,
                        () -> showWithEditor(x + 6, popY, () -> has.with, value -> has.with = value), mx, my);
                String exceptText = has.except.isEmpty() ? "＋排除资源" : "排除 " + has.except.size() + " 种资源";
                rx = drawP(g, fnt, rx, exceptText, 70,
                        () -> showResourceListMenu(x + 6, popY, has.except, "添加要排除的资源"), mx, my);
                // (硬换行 ry += rowH 全部去掉，由 drawP 自动 flow；详见 applyBounds 注释)
                String sideText = has.access.eachSide || !has.access.sides.isEmpty()
                        ? sidesDisp(has.access) : "不限侧面";
                rx = drawP(g, fnt, rx, sideText, 58,
                        () -> showSideEditor(x + 6, popY, has.access), mx, my);
                String slotsText = has.access.slots.isEmpty() ? "不限槽位" : slotText(has.access.slots);
                rx = drawP(g, fnt, rx, slotsText, 58, () -> setPopup(new Popup.TextPopup(
                        BlockEditorScreen.this, x + 6, popY, 150, slotText(has.access.slots),
                        "槽位，例如 0,2-5", value -> setSlotsFromText(has.access.slots, value), null)), mx, my);
                rx = drawP(g, fnt, rx, rrDisp(has.access.roundRobin), 64,
                        () -> setPopup(new Popup.ChoicePopup(x + 6, popY, 120,
                                List.of("none", "label", "block"),
                                List.of("不轮流", "按标签轮流", "按方块轮流"),
                                has.access.roundRobin.name().toLowerCase(java.util.Locale.ROOT), value -> {
                            pushUndo();
                            has.access.roundRobin = BProgram.RoundRobinMode.fromSfml(value);
                        })), mx, my);
                // (硬换行 ry += rowH 全部去掉，由 drawP 自动 flow；详见 applyBounds 注释)
                rx = drawP(g, fnt, rx, "✕ 删除条件", 60, () -> {
                    pushUndo();
                    replaceSelf(new BProgram.Bool.Const(true));
                    popup = null;
                }, mx, my);
            } else if (inner() instanceof BProgram.Bool.Redstone r) {
                // (硬换行 ry += rowH 全部去掉，由 drawP 自动 flow；详见 applyBounds 注释)
                String redstoneComparison = r.comparison == null ? "有信号" : r.comparison.symbol();
                rx = drawP(g, fnt, rx, r.comparison == null ? "有信号" : r.comparison.symbol(), 30,
                        () -> setPopup(new Popup.ChoicePopup(x + 6, popY, 100,
                                List.of("any", ">", ">=", "=", "<=", "<"),
                                List.of("有信号", ">", "≥", "=", "≤", "<"), redstoneComparison, v -> {
                            pushUndo();
                            r.comparison = v.equals("any") ? null : BProgram.Bool.Comparison.fromSfml(v);
                        })), mx, my);
                long num = r.number;
                rx = drawP(g, fnt, rx, String.valueOf(num), 34, () ->
                        popup = new Popup.TextPopup(BlockEditorScreen.this, x + 6, popY, 90,
                                String.valueOf(num), "0..999999",
                                s -> {
                                    try {
                                        pushUndo();
                                        r.number = Math.max(0, Long.parseLong(s.trim()));
                                    } catch (NumberFormatException ignored) {
                                    }
                                }, null), mx, my);
                // (硬换行 ry += rowH 全部去掉，由 drawP 自动 flow；详见 applyBounds 注释)
                rx = drawP(g, fnt, rx, "✕ 删除条件", 60, () -> {
                    pushUndo();
                    replaceSelf(new BProgram.Bool.Const(true));
                    popup = null;
                }, mx, my);
            } else if (inner() instanceof BProgram.Bool.RawBool r) {
                // (硬换行 ry += rowH 全部去掉，由 drawP 自动 flow；详见 applyBounds 注释)
                rx = drawP(g, fnt, rx, "兼容条件（只读）", 100,
                        () -> {
                            if (!previewMode) toggleCodeEditor();
                            showStatus("请在下方代码编辑区修改兼容条件", 0xFFB45309);
                        }, mx, my);
                // (硬换行 ry += rowH 全部去掉，由 drawP 自动 flow；详见 applyBounds 注释)
                rx = drawP(g, fnt, rx, "✕ 删除条件", 60, () -> {
                    pushUndo();
                    replaceSelf(new BProgram.Bool.Const(true));
                    popup = null;
                }, mx, my);
            } else if (inner() instanceof BProgram.Bool.Const constant) {
                // (硬换行 ry += rowH 全部去掉，由 drawP 自动 flow；详见 applyBounds 注释)
                rx = drawP(g, fnt, rx,
                        constant.value ? "这个条件始终成立" : "这个条件始终不成立", 110,
                        () -> {
                            pushUndo();
                            constant.value = !constant.value;
                        }, mx, my);
                // (硬换行 ry += rowH 全部去掉，由 drawP 自动 flow；详见 applyBounds 注释)
                rx = drawP(g, fnt, rx, "✕ 删除条件", 60, () -> {
                    pushUndo();
                    replaceSelf(newConditionHas());
                    popup = null;
                }, mx, my);
            }
        }

        /** Pill 绘制助手：放得下就紧跟上一个 pill；放不下自动折到下一行。
         *  返回新 rx 给下一个 pill；ry 用字段推进（跨 drawP 调用持续）。 */
        private int drawP(GuiGraphics g, Font fnt, int px,
                          String t, int minW, Runnable onClick, int mx, int my) {
            int pw = Math.max(minW, fnt.width(t) + 10);
            // 放不下就换行；用面板内边距右边界（x + w - 6）做裁剪。
            // 允许换行的前提是面板还能再装一行（ry + rowH 仍在 y + h 范围内），
            // 避免在已经被裁短的小面板里无限追加行。
            if (px + pw > x + w - 6 && ry < y + h - rowH) {
                px = x + 6;
                ry += rowH;
            }
            boolean hover = mx >= px && mx < px + pw && my >= ry && my < ry + 16;
            pill(g, px, ry, pw, 16, hover);
            g.drawString(fnt, t, px + (pw - fnt.width(t)) / 2, ry + 4, C_TEXT, false);
            localHits.add(Hit.of(px, ry, pw, 16, K_CLICK, null, onClick));
            lastPillX = px;
            lastPillY = ry;
            lastPillW = pw;
            return px + pw + 6;
        }

        /** 落位时按面板剩余空间算 w/h：宽度按最长行收紧，高度按行数展开。
         *  setPopup 顺序保证本方法在 render() 前被调用，渲染时 w/h 已是最终值。 */
        @Override
        public void applyBounds(int minX, int maxX, int minY, int maxY) {
            Font fnt = Minecraft.getInstance().font;
            int[] widths = computePillWidths(fnt);
            if (widths.length == 0) {
                this.h = rowH + PAD;
                return;
            }
            int panelMaxW = Math.max(MIN_W, maxX - minX);
            int panelMaxH = Math.max(rowH + PAD, maxY - minY);

            // 第一遍：在最大可用宽度下贪心分组——找出"最长那一行"的宽度。
            // 第二遍：用这个宽度重算行数（收窄到该宽度后行数可能变多）。
            // 跑两遍是因为"最宽行宽度"和"行数"互相耦合。
            int firstWidest = flowLayout(widths, panelMaxW - PAD)[0];
            int wantW = Math.max(MIN_W, Math.min(firstWidest + PAD, panelMaxW));
            int[] again = flowLayout(widths, wantW - PAD);
            int rows = again[1];
            int widest = again[0];

            // 行数变化后再校准一次宽度（极端短文本可能让 wantW 比真实最宽还小）。
            this.w = Math.max(MIN_W, Math.min(widest + PAD, panelMaxW));
            int[] finalLayout = flowLayout(widths, this.w - PAD);
            this.h = Math.min(panelMaxH, finalLayout[1] * rowH + PAD);
        }

        /** 收集 render() 里每个 pill 的最终宽度，与 render() 的 pill 顺序一致——
         *  唯一区别是这里不渲染、不记录 hit，只算 pw。 */
        private int[] computePillWidths(Font fnt) {
            List<Integer> out = new ArrayList<>();
            out.add(pillW(fnt, switch (kind()) {
                case "has" -> T_COND.getString();
                case "redstone" -> "红石";
                case "true" -> "总是成立";
                case "false" -> "永不成立";
                default -> "原样";
            }, 40));
            out.add(pillW(fnt, cond instanceof BProgram.Bool.Not ? "◉ 已取反" : "○ 取反", 56));

            BProgram.Bool.Has has = asHas();
            if (has != null) {
                out.add(pillW(fnt, setOpZh(has.setMode), 70));
                out.add(pillW(fnt, has.access.labels.isEmpty() ? T_LABEL.getString() : String.join("+", has.access.labels), 50));
                out.add(pillW(fnt, has.comparison.symbol(), 24));
                out.add(pillW(fnt, String.valueOf(has.number), 34));
                BProgram.ResourceRef resource = firstResource(has.resources);
                out.add(pillW(fnt, resource.kind().chineseName, 38));
                out.add(pillW(fnt, resource.isWildcard() ? "□ 全部" : shortResource(resource), 44));
                out.add(pillW(fnt, "＋更多资源", 54));
                String withText = has.with == null ? "＋资源标签" : shortUi(shortWith(has.with), 15);
                out.add(pillW(fnt, withText, 80));
                String exceptText = has.except.isEmpty() ? "＋排除资源" : "排除 " + has.except.size() + " 种资源";
                out.add(pillW(fnt, exceptText, 70));
                String sideText = has.access.eachSide || !has.access.sides.isEmpty() ? sidesDisp(has.access) : "不限侧面";
                out.add(pillW(fnt, sideText, 58));
                String slotsText = has.access.slots.isEmpty() ? "不限槽位" : slotText(has.access.slots);
                out.add(pillW(fnt, slotsText, 58));
                out.add(pillW(fnt, rrDisp(has.access.roundRobin), 64));
                out.add(pillW(fnt, "✕ 删除条件", 60));
            } else if (inner() instanceof BProgram.Bool.Redstone r) {
                out.add(pillW(fnt, r.comparison == null ? "有信号" : r.comparison.symbol(), 30));
                out.add(pillW(fnt, String.valueOf(r.number), 34));
                out.add(pillW(fnt, "✕ 删除条件", 60));
            } else if (inner() instanceof BProgram.Bool.RawBool) {
                out.add(pillW(fnt, "兼容条件（只读）", 100));
                out.add(pillW(fnt, "✕ 删除条件", 60));
            } else if (inner() instanceof BProgram.Bool.Const constant) {
                out.add(pillW(fnt, constant.value ? "这个条件始终成立" : "这个条件始终不成立", 110));
                out.add(pillW(fnt, "✕ 删除条件", 60));
            }
            return out.stream().mapToInt(Integer::intValue).toArray();
        }

        private static int pillW(Font fnt, String t, int minW) {
            return Math.max(minW, fnt.width(t) + 10);
        }

        /** 贪心 flow layout：固定宽度 contentW，把宽度数组按"放得下就加，
         *  放不下换行"分组，返回 [最宽行累加宽度, 行数]。 */
        private static int[] flowLayout(int[] widths, int contentW) {
            int widest = 0;
            int line = 0;
            int rows = 1;
            for (int pw : widths) {
                if (line == 0) {
                    line = pw;
                    widest = Math.max(widest, line);
                } else if (line + 6 + pw <= contentW) {
                    line += 6 + pw;
                    widest = Math.max(widest, line);
                } else {
                    rows++;
                    line = pw;
                    widest = Math.max(widest, line);
                }
            }
            return new int[]{widest, rows};
        }

        private void switchKind(String k) {
            pushUndo();
            BProgram.Bool replacement = switch (k) {
                case "redstone" -> new BProgram.Bool.Redstone();
                default -> newConditionHas();
            };
            replaceSelf(replacement);
            popup = new ConditionPopup(x, y, replacement);
        }

        private void toggleNot() {
            if (cond instanceof BProgram.Bool.Not n) {
                replaceSelf(n.inner);
            } else {
                BProgram.Bool.Not n = new BProgram.Bool.Not();
                n.inner = cond;
                replaceSelf(n);
            }
            popup = null;
        }

        private void replaceSelf(BProgram.Bool with) {
            replaceIn(program, cond, with);
        }

        @Override
        public boolean mouseClicked(double mx, double my, int button) {
            if (!isOver(mx, my)) {
                keepOpen = false;
                return true;
            }
            for (Hit h : localHits) {
                if (in(h, mx, my) && h.onClick != null) {
                    h.onClick.run();
                    return true;
                }
            }
            return true;
        }
    }

    private boolean replaceIn(BProgram p, BProgram.Bool target, BProgram.Bool with) {
        for (BProgram.Trigger t : p.triggers) {
            if (replaceInList(t.body, target, with)) return true;
        }
        return false;
    }

    private boolean replaceInList(List<BProgram.Statement> statements, BProgram.Bool target, BProgram.Bool with) {
        for (BProgram.Statement s : statements) {
            if (s instanceof BProgram.Statement.If iff) {
                for (BProgram.Branch b : iff.branches) {
                    if (b.cond == target) {
                        b.cond = with;
                        return true;
                    }
                    if (replaceInBool(b.cond, target, with)) return true;
                    if (replaceInList(b.body, target, with)) return true;
                }
                if (replaceInList(iff.elseBody, target, with)) return true;
            }
        }
        return false;
    }

    private boolean replaceInBool(BProgram.Bool node, BProgram.Bool target, BProgram.Bool with) {
        if (node instanceof BProgram.Bool.And a) {
            for (int i = 0; i < a.parts.size(); i++) {
                if (a.parts.get(i) == target) {
                    a.parts.set(i, with);
                    return true;
                }
                if (replaceInBool(a.parts.get(i), target, with)) return true;
            }
        } else if (node instanceof BProgram.Bool.Or o) {
            for (int i = 0; i < o.parts.size(); i++) {
                if (o.parts.get(i) == target) {
                    o.parts.set(i, with);
                    return true;
                }
                if (replaceInBool(o.parts.get(i), target, with)) return true;
            }
        } else if (node instanceof BProgram.Bool.Not n) {
            if (n.inner == target) {
                n.inner = with;
                return true;
            }
            return replaceInBool(n.inner, target, with);
        }
        return false;
    }
}
