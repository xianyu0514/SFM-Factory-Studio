package io.github.xianynomial.sfmfactorystudio.client;

import io.github.xianynomial.sfmfactorystudio.client.blocks.model.BProgram;
import io.github.xianynomial.sfmfactorystudio.client.blocks.model.SlotLayoutData;
import io.github.xianynomial.sfmfactorystudio.client.blocks.model.SlotNumbering;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 槽位可视化选择器：按容器真实布局渲染格子，点选/刷选后回写槽位文本。
 *
 * <p>编号语义（本类的核心承诺）：
 * <ul>
 * <li>校准成功 —— 每格显示的编号就是 SFM 实际寻址的能力槽索引，所见即所得；
 * 不可寻址的格子（升级卡等）置灰不可选，绿字横幅明示。</li>
 * <li>校准中 —— 格子不显示编号（避免先显示空间序号再跳变）；「确认」暂不可用。</li>
 * <li>校准失败（服务端未装/超时/无能力面）—— 退回屏幕顺序编号，琥珀色横幅
 * 明示偏差风险，绝不静默装作没问题。</li>
 * </ul>
 *
 * <p>显示保证：格子永不互相重叠（缩放有下限）、坐标归一化（负数/偏移原点的
 * 模组 GUI 不会画出背景框）、内容超出视口时滚轮/拖动滚动且按钮永远钉在屏内、
 * 全部文字超宽自动缩小适配（4K + 高 GUI 缩放也不出屏）。
 *
 * <p>交互：单击选中/取消；按住拖动刷选；Shift+点击范围选中；滚轮滚动、
 * Shift+滚轮横向滚动；「确认」把选中槽号压缩成 1,3-9 形式回写
 * （空选择 = 不限制 = 全部槽位）。
 */
public final class SlotPickerScreen extends Screen {
    private static final int CELL = 20;
    private static final int HIDDEN_COLS = 9;                // 合成区每行格数
    private static final int CALIBRATE_TIMEOUT_TICKS = 60;   // 3 秒无回包 = 未校准
    private static final int SCROLL_STEP = 32;

    /** 选槽结果：slotText = "1,3-9"（空 = 全部）；calibratedTotal = 校准出的能力槽总数（未校准 null）。 */
    public interface ResultCallback {
        void accept(String slotText, Integer calibratedTotal);
    }

    private final Screen parent;
    private final BlockPos requestedPos;   // 语句标签第一台机器（多机优选前）
    private final BlockPos containerPos;   // 实际显示布局的机器
    private final List<SlotNumbering.MenuSlot> capturedSlots;   // 捕获的界面格（不变）
    private List<SlotNumbering.MenuSlot> menuSlots;             // 显示列表 = 捕获格 + 隐藏槽合成格
    private int syntheticFrom = -1;        // 合成格起始 seq（-1 = 无）
    private int labelRawX, labelRawY;      // 合成区标签的内容坐标
    private final List<BProgram.SlotRange> initialRanges;
    private final ResultCallback onResult;

    private SlotNumbering.Result numbering;
    private SlotNumbering.ViewLayout view = new SlotNumbering.ViewLayout(1f, CELL * 9, CELL, 0, 0);
    private enum CapState { PENDING, READY, FAILED }
    private CapState capState = CapState.PENDING;
    private boolean capabilityMissing = false;   // 服务端确认所有朝向都没有物品能力面
    private boolean tooFar = false;              // 方块不存在或超出读取距离
    private String sideFallbackDir = null;       // 限定方向无槽位，实际按该朝向编号（null 面 = "null"）
    private final String sidesCode;              // 语句的侧面限定（请求校准用）
    private boolean initialApplied = false;      // 已写槽号区间是否已映射为选中
    private int ticksElapsed;
    private boolean selectionTouched = false;
    private int droppedFromCalibration = 0;

    private final TreeSet<Integer> selected = new TreeSet<>();   // 菜单格 seq
    private int brushFrom = -1;

    private int availW, availH;
    private int vpW, vpH;                    // 视口（背景框）尺寸
    private int gridX, gridY;                // 视口左上
    private int resultY, buttonY;            // 钉在屏内的结果行/按钮行
    private int scrollX, scrollY;

    /** 打开中的选择器（按容器坐标），接收服务端校准回包。 */
    private static final Map<BlockPos, SlotPickerScreen> WAITERS = new ConcurrentHashMap<>();

    public SlotPickerScreen(Screen parent, BlockPos requestedPos, BlockPos containerPos,
                            SlotLayoutData.Layout layout, String sidesCode,
                            List<BProgram.SlotRange> initialRanges, ResultCallback onResult) {
        super(Component.literal(L_TITLE.getString()));
        this.parent = parent;
        this.requestedPos = requestedPos;
        this.containerPos = containerPos;
        this.sidesCode = sidesCode == null || sidesCode.isBlank() ? "null" : sidesCode;
        this.initialRanges = initialRanges == null ? List.of() : List.copyOf(initialRanges);
        this.onResult = onResult;
        List<SlotNumbering.MenuSlot> slots = new ArrayList<>();
        if (layout != null) {
            int seq = 0;
            for (SlotLayoutData.SlotCapture c : layout.slots()) {
                slots.add(new SlotNumbering.MenuSlot(seq++, c.x(), c.y(), c.item(), c.count(), c.capIndex()));
            }
        }
        this.capturedSlots = slots;
        this.menuSlots = slots;
        // 先以兜底编号占位（校准期间不显示任何编号，避免数字跳变的观感）
        this.numbering = SlotNumbering.compute(menuSlots, null, null);
    }

    /**
     * 语句侧面限定 → 请求编码（SFML 侧面名，逗号分隔）。
     * each side = 全部 7 面（与 SFML 解析 SideQualifier.ALL 一致）；
     * 显式侧面 = 用户选的面；什么都没写 = "null"（SFM 默认 = 无侧面查询）。
     */
    public static String sidesCode(BProgram.LabelAccess access) {
        if (access == null) return "null";
        if (access.eachSide) return "top,bottom,north,south,east,west,null";
        if (!access.sides.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (BProgram.Side s : access.sides) {
                if (sb.length() > 0) sb.append(',');
                sb.append(s.sfml());
            }
            return sb.toString();
        }
        return "null";
    }

    /** 服务端校准数据入口（BlockEditorScreen.acceptSlotCapability 转发，主线程）。 */
    public static void onCapabilityData(BlockPos pos, int state, String refDir, int total,
                                        List<String> items, List<Integer> counts) {
        SlotPickerScreen picker = pos == null ? null : WAITERS.get(pos);
        if (picker != null) picker.applyCapability(state, refDir, total, items, counts);
    }

    private void applyCapability(int state, String refDir, int total, List<String> items, List<Integer> counts) {
        if (capState != CapState.PENDING) return;
        if (state < 0) {
            // 方块不存在或距离过远：无法校准（横幅单独提示）
            tooFar = true;
            capState = CapState.FAILED;
            applyInitialSelection();
            return;
        }
        capabilityMissing = state == 2;
        sideFallbackDir = state == 1 ? refDir : null;
        List<SlotNumbering.CapSlot> caps = new ArrayList<>();
        if (total > 0 && items != null) {
            for (int i = 0; i < total; i++) {
                String item = i < items.size() ? items.get(i) : "";
                int count = i < counts.size() && counts.get(i) != null ? counts.get(i) : 0;
                caps.add(new SlotNumbering.CapSlot(i, item, count));
            }
        }
        boolean hasCapability = total > 0;
        TreeSet<Integer> before = new TreeSet<>(selected);
        // 第一遍：捕获格与能力槽配对，找出"GUI 里没画出来的能力槽"
        SlotNumbering.Result firstPass = SlotNumbering.compute(capturedSlots,
                hasCapability ? caps : null, hasCapability ? total : null);
        rebuildDisplaySlots(firstPass, hasCapability ? caps : null, hasCapability ? total : 0);
        // 第二遍：含合成格的完整显示列表（合成格靠 capHint 精确认领真实序号）
        this.numbering = SlotNumbering.compute(menuSlots, hasCapability ? caps : null,
                hasCapability ? total : null);
        if (!selectionTouched) {
            // 玩家没动过：已写的槽号区间按（校准后的）真实编号重新解释
            applyInitialSelection();
        }
        selected.removeIf(seq -> !numbering.isAddressable(seq));
        droppedFromCalibration = countDropped(before);
        capState = hasCapability ? CapState.READY : CapState.FAILED;
    }

    /** 隐藏能力槽（SFM 可寻址但 GUI 没画）以合成格补显在布局下方，真实编号可选。 */
    private void rebuildDisplaySlots(SlotNumbering.Result firstPass, List<SlotNumbering.CapSlot> caps, int total) {
        if (caps == null || total <= 0) {
            syntheticFrom = -1;
            menuSlots = capturedSlots;
            return;
        }
        List<Integer> hidden = SlotNumbering.unclaimedCapIndexes(firstPass, total);
        if (hidden.isEmpty()) {
            syntheticFrom = -1;
            menuSlots = capturedSlots;
            return;
        }
        int minX = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        for (SlotNumbering.MenuSlot s : capturedSlots) {
            minX = Math.min(minX, s.x());
            maxY = Math.max(maxY, s.y() + CELL);
        }
        labelRawX = minX;
        labelRawY = maxY + CELL + 2;
        syntheticFrom = capturedSlots.size();
        List<SlotNumbering.MenuSlot> all = new ArrayList<>(capturedSlots);
        int synthY = maxY + CELL + 14;
        for (int k = 0; k < hidden.size(); k++) {
            int capIndex = hidden.get(k);
            SlotNumbering.CapSlot c = caps.get(capIndex);
            all.add(new SlotNumbering.MenuSlot(all.size(),
                    minX + (k % HIDDEN_COLS) * CELL, synthY + (k / HIDDEN_COLS) * CELL,
                    c.item(), c.count(), capIndex));
        }
        menuSlots = all;
    }

    private void applyInitialSelection() {
        if (initialApplied) return;
        initialApplied = true;
        selected.clear();
        selected.addAll(SlotNumbering.selectionForRanges(this.numbering, this.initialRanges));
    }

    private int countDropped(TreeSet<Integer> before) {
        int n = 0;
        for (int seq : before) if (!selected.contains(seq)) n++;
        return n;
    }

    private void requestCalibration() {
        if (containerPos == null || menuSlots.isEmpty()) {
            capState = CapState.FAILED;
            return;
        }
        WAITERS.put(containerPos, this);
        boolean sent = io.github.xianynomial.sfmfactorystudio.net.SFMGuiNetwork
                .sendToServerBestEffortChecked(
                        new io.github.xianynomial.sfmfactorystudio.net.SlotCapabilityRequestPayload(
                                containerPos, sidesCode));
        if (!sent) {
            capState = CapState.FAILED;   // 服务端未装附属：横幅明示未校准
            applyInitialSelection();
        }
    }

    @Override
    protected void init() {
        if (containerPos != null && capState == CapState.PENDING && ticksElapsed == 0
                && WAITERS.get(containerPos) != this) {
            requestCalibration();   // 只发一次；窗口 resize 重跑 init 不会重发
        }
        relayout();
    }

    @Override
    public void removed() {
        if (containerPos != null && WAITERS.get(containerPos) == this) WAITERS.remove(containerPos);
    }

    @Override
    public void tick() {
        if (capState == CapState.PENDING && ++ticksElapsed > CALIBRATE_TIMEOUT_TICKS) {
            capState = CapState.FAILED;   // 编号保持兜底模式，横幅明示未校准
            applyInitialSelection();
        }
    }

    // ---- 布局（渲染与点击共用同一份几何数据，永不漂移）----

    private void relayout() {
        availW = Math.max(60, width - 40);
        availH = Math.max(60, height - 170);
        view = SlotNumbering.computeView(CELL, menuSlots, availW, availH);
        vpW = Math.min(view.contentW(), availW);
        vpH = Math.min(view.contentH(), availH);
        gridX = (width - vpW) / 2;
        gridY = Math.max(66, height / 2 - vpH / 2);
        scrollX = clampScroll(scrollX, view.contentW(), vpW);
        scrollY = clampScroll(scrollY, view.contentH(), vpH);
        int bottom = gridY + vpH;
        resultY = Math.min(bottom + 12, height - 44);
        buttonY = Math.min(bottom + 26, height - 24);
        if (buttonY < resultY + 8) buttonY = resultY + 8;
    }

    private static int clampScroll(int value, int content, int viewport) {
        int max = Math.max(0, content - viewport);
        return Math.max(0, Math.min(value, max));
    }

    /** 格子在屏幕上的位置（含归一化、缩放、滚动）。渲染与命中共用。 */
    private int drawX(SlotNumbering.MenuSlot s) {
        return coordX(s.x());
    }

    private int drawY(SlotNumbering.MenuSlot s) {
        return coordY(s.y());
    }

    private int coordX(int rawX) {
        return gridX + Math.round((rawX + view.shiftX()) * view.scale()) - scrollX;
    }

    private int coordY(int rawY) {
        return gridY + Math.round((rawY + view.shiftY()) * view.scale()) - scrollY;
    }

    private int cellSize() {
        return Math.max(Math.round(SlotNumbering.MIN_CELL_PX), Math.round(CELL * view.scale()));
    }

    private int seqAt(double mx, double my) {
        int sz = cellSize();
        for (SlotNumbering.MenuSlot s : menuSlots) {
            int x = drawX(s);
            int y = drawY(s);
            if (mx >= x && mx < x + sz && my >= y && my < y + sz) return s.seq();
        }
        return -1;
    }

    // ---- 输入 ----

    private final List<int[]> buttonRects = new ArrayList<>();
    private final List<Runnable> buttonActions = new ArrayList<>();

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0) {
            for (int i = 0; i < buttonRects.size(); i++) {
                int[] b = buttonRects.get(i);
                if (mx >= b[0] && mx < b[0] + b[2] && my >= b[1] && my < b[1] + 18) {
                    buttonActions.get(i).run();
                    return true;
                }
            }
            int seq = seqAt(mx, my);
            if (seq >= 0) {
                if (capState == CapState.PENDING) return true;   // 校准中编号未定
                if (!numbering.isAddressable(seq)) return true;   // 置灰格不可选
                selectionTouched = true;
                droppedFromCalibration = 0;
                if (hasShiftDown() && !selected.isEmpty()) {
                    int from = selected.last();
                    for (int i = Math.min(from, seq); i <= Math.max(from, seq); i++) {
                        if (numbering.isAddressable(i)) selected.add(i);
                    }
                } else {
                    if (selected.contains(seq)) selected.remove(seq);
                    else selected.add(seq);
                    brushFrom = seq;
                }
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (button == 0 && brushFrom >= 0 && capState != CapState.PENDING) {
            int seq = seqAt(mx, my);
            if (seq >= 0 && numbering.isAddressable(seq)) {
                selectionTouched = true;
                for (int i = Math.min(brushFrom, seq); i <= Math.max(brushFrom, seq); i++) {
                    if (numbering.isAddressable(i)) selected.add(i);
                }
            }
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        brushFrom = -1;
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollXAxis, double scrollYAxis) {
        int dx = hasShiftDown() ? (int) Math.round(-scrollYAxis * SCROLL_STEP) : (int) Math.round(scrollXAxis * SCROLL_STEP);
        int dy = hasShiftDown() ? 0 : (int) Math.round(-scrollYAxis * SCROLL_STEP);
        if (dx != 0 || dy != 0) {
            scrollX = clampScroll(scrollX + dx, view.contentW(), vpW);
            scrollY = clampScroll(scrollY + dy, view.contentH(), vpH);
            return true;
        }
        return super.mouseScrolled(mx, my, scrollXAxis, scrollYAxis);
    }

    // ---- 渲染 ----

    @Override
    public void render(GuiGraphics g, int mx, int my, float partialTick) {
        this.renderBackground(g, mx, my, partialTick);

        if (menuSlots.isEmpty()) {
            renderGuidance(g);
            return;
        }

        drawFittedCentered(g, L_TITLE.getString(), 20, 0xFFFFFFFF);
        drawFittedCentered(g, L_HINT.getString(), 31, 0xFF8A93A5);
        drawFittedCentered(g, targetText(), 42, 0xFF8A93A5);
        drawFittedCentered(g, bannerText(), 53, bannerColor());

        relayout();
        g.fill(gridX - 4, gridY - 4, gridX + vpW + 4, gridY + vpH + 4, 0xFF2A2A2E);

        boolean calibrating = capState == CapState.PENDING;
        int sz = cellSize();
        int hoverSeq = seqAt(mx, my);
        g.enableScissor(gridX - 2, gridY - 2, gridX + vpW + 2, gridY + vpH + 2);
        try {
            if (syntheticFrom >= 0) {
                g.drawString(this.font, L_HIDDEN_SECTION.getString(),
                        coordX(labelRawX), coordY(labelRawY), 0xFF8A93A5, false);
            }
            for (SlotNumbering.MenuSlot s : menuSlots) {
                int x = drawX(s);
                int y = drawY(s);
                if (x + sz < gridX || x > gridX + vpW || y + sz < gridY || y > gridY + vpH) continue;   // 视口剔除
                boolean addressable = numbering.isAddressable(s.seq());
                boolean sel = selected.contains(s.seq());
                boolean hover = s.seq() == hoverSeq;
                // 校准中：所有格子一律普通外观（编号/置灰判定未定，不得提前示色）
                int body = calibrating ? 0xFF1B1B1E : !addressable ? 0xFF151517 : sel ? 0xFF3A6FD8 : 0xFF1B1B1E;
                int frame = calibrating ? 0xFF55555C : !addressable ? 0xFF3A3A40 : sel ? 0xFF7FA8FF : hover ? 0xFF9AA3B2 : 0xFF55555C;
                g.fill(x + 1, y + 1, x + sz - 1, y + sz - 1, body);
                border(g, x, y, sz, sz, frame);
                if (!calibrating && addressable) {
                    int num = numbering.number(s.seq());
                    if (num >= 0 && sz >= 14 && this.font.width(String.valueOf(num)) <= sz - 6) {
                        g.drawCenteredString(this.font, String.valueOf(num), x + sz / 2, y + sz / 2 - 4,
                                sel ? 0xFFEAF2FF : 0xFF8A8A92);
                    }
                } else if (!calibrating && !addressable && sz >= 14) {
                    g.drawCenteredString(this.font, "·", x + sz / 2, y + sz / 2 - 4, 0xFF6A6A72);
                }
            }
        } finally {
            g.disableScissor();
        }

        renderScrollbars(g);

        // 结果行 + 按钮（矩形由 render 与 mouseClicked 共用；永远钉在屏内）
        String result = calibrating ? "" : SlotNumbering.numbersOf(numbering, selected);
        if (!calibrating && droppedFromCalibration > 0) {
            result = result.isEmpty() ? "" : result + "  " + L_DROPPED.getString(droppedFromCalibration);
        }
        String resultText = result.isEmpty() ? L_NONE.getString() : "slots " + result;
        int resultColor = calibrating ? 0xFF9AA3B2 : selected.isEmpty() ? 0xFF909090 : 0xFF7FA8FF;
        drawFittedCentered(g, resultText, resultY, resultColor);

        buttonRects.clear();
        buttonActions.clear();
        boolean confirmReady = capState != CapState.PENDING;
        addButton(g, width / 2 - 110, buttonY, 70, L_OK.getString(),
                confirmReady ? 0xFF2FA84F : 0xFF3C5A46, confirmReady ? this::confirm : () -> {
                }, mx, my);
        addButton(g, width / 2 - 35, buttonY, 70, L_CLEAR.getString(), 0xFF5B6472, () -> {
            if (capState != CapState.PENDING) {
                selected.clear();
                selectionTouched = true;
                droppedFromCalibration = 0;
            }
        }, mx, my);
        addButton(g, width / 2 + 40, buttonY, 70, L_CLOSE.getString(), 0xFF5B6472, this::onClose, mx, my);

        renderTooltip(g, hoverSeq, mx, my);
    }

    /** 目标机器行：标题 + 坐标；多机优选换过机器时明示。 */
    private String targetText() {
        SlotLayoutData.Layout captured = null;
        String title = "";
        int x = 0, y = 0, z = 0;
        if (containerPos != null) {
            x = containerPos.getX();
            y = containerPos.getY();
            z = containerPos.getZ();
            captured = ClientGuiLayoutCache.get(containerPos);
            title = captured != null && captured.title() != null ? captured.title() : "";
        }
        String base = L_TARGET.getString() + "：" + (title.isEmpty() ? "—" : title)
                + " (" + x + ", " + y + ", " + z + ")";
        if (requestedPos != null && containerPos != null && !requestedPos.equals(containerPos)) {
            base += "  ·  " + L_MULTI.getString();
        }
        return base;
    }

    private String bannerText() {
        if (capState == CapState.FAILED && tooFar) {
            return L_TOO_FAR.getString();
        }
        if (capState == CapState.FAILED && capabilityMissing) {
            return L_NO_CAPABILITY.getString();
        }
        if (capState == CapState.READY && sideFallbackDir != null) {
            return L_SIDE_FALLBACK.getString(
                    sideFallbackDir.equals("null") ? L_DIR_NULL.getString() : sideFallbackDir);
        }
        return switch (capState) {
            case PENDING -> L_CALIBRATING.getString();
            case READY -> {
                String base = L_CALIBRATED.getString();
                boolean hasGrey = false;
                for (SlotNumbering.MenuSlot s : menuSlots) if (!numbering.isAddressable(s.seq())) hasGrey = true;
                if (numbering.hiddenCaps() > 0) {
                    base += "  " + L_HIDDEN_CAPS.getString(numbering.hiddenCaps());
                } else if (hasGrey) {
                    base += "  " + L_GREY_NOTE.getString();
                }
                yield base;
            }
            case FAILED -> L_UNCALIBRATED.getString();
        };
    }

    private int bannerColor() {
        if (capState == CapState.READY && sideFallbackDir != null) return 0xFFE8B339;
        return switch (capState) {
            case PENDING -> 0xFF9AA3B2;
            case READY -> 0xFF4CC38A;
            case FAILED -> 0xFFE8B339;
        };
    }

    /** 内容超出视口时画细滚动条（右缘=纵向，下缘=横向）。 */
    private void renderScrollbars(GuiGraphics g) {
        if (view.contentH() > vpH) {
            int trackX = gridX + vpW + 2;
            float ratio = (float) vpH / view.contentH();
            int thumbH = Math.max(12, Math.round(vpH * ratio));
            int thumbY = gridY + Math.round((vpH - thumbH) * (scrollY / (float) Math.max(1, view.contentH() - vpH)));
            g.fill(trackX, gridY, trackX + 2, gridY + vpH, 0xFF3A3A40);
            g.fill(trackX, thumbY, trackX + 2, thumbY + thumbH, 0xFF8A93A5);
        }
        if (view.contentW() > vpW) {
            int trackY = gridY + vpH + 2;
            float ratio = (float) vpW / view.contentW();
            int thumbW = Math.max(12, Math.round(vpW * ratio));
            int thumbX = gridX + Math.round((vpW - thumbW) * (scrollX / (float) Math.max(1, view.contentW() - vpW)));
            g.fill(gridX, trackY, gridX + vpW, trackY + 2, 0xFF3A3A40);
            g.fill(thumbX, trackY, thumbX + thumbW, trackY + 2, 0xFF8A93A5);
        }
    }

    private void renderGuidance(GuiGraphics g) {
        drawFittedCentered(g, L_TITLE.getString(), height / 2 - 44, 0xFFFFFFFF);
        drawFittedCentered(g, L_NO_RECORD.getString(), height / 2 - 20, 0xFFE0E0E0);
        drawFittedCentered(g, L_STEP1.getString(), height / 2 - 2, 0xFFE0E0E0);
        drawFittedCentered(g, L_STEP2.getString(), height / 2 + 14, 0xFFE0E0E0);
        drawFittedCentered(g, L_STEP3.getString(), height / 2 + 30, 0xFF909090);
        drawFittedCentered(g, L_STEP4.getString(), height / 2 + 48, 0xFF909090);
    }

    /** 悬停提示：灰格说明不可寻址；可选格显示编号与捕获时的内容。 */
    private void renderTooltip(GuiGraphics g, int seq, int mx, int my) {
        if (seq < 0 || capState == CapState.PENDING) return;
        String text;
        if (!numbering.isAddressable(seq)) {
            text = L_GREY_HOVER.getString();
        } else {
            SlotNumbering.MenuSlot s = menuSlots.get(seq);
            String name = s.hasSignature() ? displayNameOf(s.item()) : "";
            text = "#" + numbering.number(seq) + (name.isEmpty() ? "" : " · " + name + " ×" + s.count());
        }
        int w = this.font.width(text);
        int tx = Math.min(mx + 8, width - w - 8);
        int ty = Math.min(my + 10, height - 16);
        g.fill(tx - 3, ty - 2, tx + w + 3, ty + 10, 0xEE101014);
        g.drawString(this.font, text, tx, ty, 0xFFEAF2FF, false);
    }

    private static String displayNameOf(String itemId) {
        try {
            var held = BuiltInRegistries.ITEM.getOptional(ResourceLocation.parse(itemId));
            if (held.isPresent()) return held.get().getDescription().getString();
        } catch (Throwable ignored) {
            // 异常 id 直接显示原始串
        }
        return itemId;
    }

    private void confirm() {
        if (onResult != null) {
            String text = SlotNumbering.numbersOf(numbering, selected);
            onResult.accept(text, numbering.calibrated() ? numbering.capTotal() : null);
        }
        onClose();
    }

    @Override
    public void onClose() {
        if (containerPos != null && WAITERS.get(containerPos) == this) WAITERS.remove(containerPos);
        Minecraft mc = Minecraft.getInstance();
        if (parent != null) mc.setScreen(parent);
        else mc.setScreen(null);
    }

    // ---- 文本与绘制助手 ----

    /** 居中文字；超出屏宽时整体缩小到恰好放下（4K 高 GUI 缩放也不出屏）。 */
    private void drawFittedCentered(GuiGraphics g, String text, int y, int color) {
        int w = this.font.width(text);
        int maxW = width - 12;
        if (w <= maxW) {
            g.drawCenteredString(this.font, text, width / 2, y, color);
            return;
        }
        float scale = maxW / (float) w;
        var pose = g.pose();
        pose.pushPose();
        pose.translate(width / 2f, y + 4.5f, 0);
        pose.scale(scale, scale, 1);
        pose.translate(-width / 2f, -(y + 4.5f), 0);
        g.drawCenteredString(this.font, text, width / 2, y, color);
        pose.popPose();
    }

    private void addButton(GuiGraphics g, int x, int y, int w, String label, int color, Runnable onClick, double mx, double my) {
        boolean hover = mx >= x && mx < x + w && my >= y && my < y + 18;
        g.fill(x, y, x + w, y + 18, hover ? mix(color, 0xFFFFFFFF, 40) : color);
        g.drawCenteredString(this.font, label, x + w / 2, y + 5, 0xFFFFFFFF);
        buttonRects.add(new int[]{x, y, w});
        buttonActions.add(onClick);
    }

    private static int mix(int a, int b, int ratio) {
        int r = (a >> 16 & 255) * (255 - ratio) / 255 + (b >> 16 & 255) * ratio / 255;
        int gg = (a >> 8 & 255) * (255 - ratio) / 255 + (b >> 8 & 255) * ratio / 255;
        int bl = (a & 255) * (255 - ratio) / 255 + (b & 255) * ratio / 255;
        return 0xFF000000 | r << 16 | gg << 8 | bl;
    }

    private static void border(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + 1, color);
        g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y, x + 1, y + h, color);
        g.fill(x + w - 1, y, x + w, y + h, color);
    }

    // ---- 双语文案 ----
    private static final Loc L_TITLE = new Loc("gui.sfmfactorystudio.slot.slot_title", "选择槽位（beta）");
    private static final Loc L_HINT = new Loc("gui.sfmfactorystudio.slot.slot_hint", "单击选中 · 拖动刷选 · Shift+点击选范围");
    private static final Loc L_CALIBRATING = new Loc("gui.sfmfactorystudio.slot.slot_calibrating", "正在向服务端校准槽位序号…");
    private static final Loc L_CALIBRATED = new Loc("gui.sfmfactorystudio.slot.slot_calibrated", "✓ 已校准：编号 = 实际槽位序号，所见即所得");
    private static final Loc L_UNCALIBRATED = new Loc("gui.sfmfactorystudio.slot.slot_uncalibrated", "⚠ 未校准：编号为屏幕顺序，多容器机器可能与实际序号有偏差（建议先放 1 个物品试运行）");
    private static final Loc L_NO_CAPABILITY = new Loc("gui.sfmfactorystudio.slot.slot_no_capability", "⚠ 该方块没有可寻址的物品槽，指定的槽位不会生效");
    private static final Loc L_GREY_NOTE = new Loc("gui.sfmfactorystudio.slot.slot_grey_note", "灰格 = 不可寻址（如升级卡槽）");
    private static final Loc L_HIDDEN_CAPS = new Loc("gui.sfmfactorystudio.slot.slot_hidden_caps", "另有 %s 个实际槽位不在此界面显示");
    private static final Loc L_GREY_HOVER = new Loc("gui.sfmfactorystudio.slot.slot_grey_hover", "此格不对应实际可寻址槽位");
    private static final Loc L_NO_RECORD = new Loc("gui.sfmfactorystudio.slot.slot_no_record", "还没有这个容器的布局记录");
    private static final Loc L_STEP1 = new Loc("gui.sfmfactorystudio.slot.slot_step1", "① 返回游戏，右键打开一次该容器的界面");
    private static final Loc L_STEP2 = new Loc("gui.sfmfactorystudio.slot.slot_step2", "② 再回到这里，就会还原成和原版一样的布局");
    private static final Loc L_STEP3 = new Loc("gui.sfmfactorystudio.slot.slot_step3", "③ 也可以直接关闭后输入槽位数字");
    private static final Loc L_STEP4 = new Loc("gui.sfmfactorystudio.slot.slot_step4", "打开过容器界面后，编号会自动校准成实际槽位序号");
    private static final Loc L_NONE = new Loc("gui.sfmfactorystudio.slot.slot_none", "未选择（=全部槽位）");
    private static final Loc L_OK = new Loc("gui.sfmfactorystudio.slot.slot_ok", "确认");
    private static final Loc L_CLEAR = new Loc("gui.sfmfactorystudio.slot.slot_clear", "清空");
    private static final Loc L_CLOSE = new Loc("gui.sfmfactorystudio.slot.slot_close", "关闭");
    private static final Loc L_TARGET = new Loc("gui.sfmfactorystudio.slot.slot_target", "目标");
    private static final Loc L_MULTI = new Loc("gui.sfmfactorystudio.slot.slot_multi_hint", "该标签绑定多台机器，显示的是有布局记录的一台");
    private static final Loc L_DROPPED = new Loc("gui.sfmfactorystudio.slot.slot_dropped", "（已剔除 %s 个不可寻址格）");
    private static final Loc L_TOO_FAR = new Loc("gui.sfmfactorystudio.slot.slot_too_far", "⚠ 距离太远或方块不存在，无法读取槽位——靠近到 64 格内再打开");
    private static final Loc L_SIDE_FALLBACK = new Loc("gui.sfmfactorystudio.slot.slot_side_fallback", "⚠ 所选侧面没有槽位，编号按 %s 面显示——SFM 访问此机器需要写侧面限定（如 each side）");
    private static final Loc L_DIR_NULL = new Loc("gui.sfmfactorystudio.slot.slot_dir_null", "无侧面");
    private static final Loc L_HIDDEN_SECTION = new Loc("gui.sfmfactorystudio.slot.slot_hidden_section", "▼ 此界面未显示的槽位（编号即真实槽位序号）");
}
