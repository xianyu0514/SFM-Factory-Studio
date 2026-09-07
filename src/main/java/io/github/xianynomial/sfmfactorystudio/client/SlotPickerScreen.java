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
 * <li>校准失败（服务端未装/超时/无能力面）—— 退回屏幕顺序编号，琥珀色横幅
 * 明示偏差风险，绝不静默装作没问题。</li>
 * </ul>
 *
 * <p>交互：单击选中/取消；按住拖动刷选；Shift+点击范围选中；「清空」；
 * 「确认」把选中槽号压缩成 1,3-9 形式回写（空选择 = 不限制 = 全部槽位）。
 * 悬停格子显示编号与捕获到的内容。
 */
public final class SlotPickerScreen extends Screen {
    private static final int CELL = 20;
    private static final int COLS = 9;                       // 仅缩放兜底用；真实布局来自捕获
    private static final int CALIBRATE_TIMEOUT_TICKS = 60;   // 3 秒无回包 = 未校准

    /** 选槽结果：slotText = "1,3-9"（空 = 全部）；calibratedTotal = 校准出的能力槽总数（未校准 null）。 */
    public interface ResultCallback {
        void accept(String slotText, Integer calibratedTotal);
    }

    private final Screen parent;
    private final BlockPos containerPos;
    private final List<SlotNumbering.MenuSlot> menuSlots;
    private final List<BProgram.SlotRange> initialRanges;
    private final ResultCallback onResult;

    private SlotNumbering.Result numbering;
    private enum CapState { PENDING, READY, FAILED }
    private CapState capState = CapState.PENDING;
    private boolean capabilityMissing = false;   // 服务端回报没有能力面（区别于超时/未装）
    private int ticksElapsed;
    private boolean selectionTouched = false;
    private int droppedFromCalibration = 0;

    private final TreeSet<Integer> selected = new TreeSet<>();   // 菜单格 seq
    private int brushFrom = -1;

    private float viewScale = 1.0f;
    private int gridW, gridH, gridX, gridY;

    /** 打开中的选择器（按容器坐标），接收服务端校准回包。 */
    private static final Map<BlockPos, SlotPickerScreen> WAITERS = new ConcurrentHashMap<>();

    public SlotPickerScreen(Screen parent, BlockPos containerPos, SlotLayoutData.Layout layout,
                            List<BProgram.SlotRange> initialRanges, ResultCallback onResult) {
        super(Component.literal(L_TITLE.getString()));
        this.parent = parent;
        this.containerPos = containerPos;
        this.initialRanges = initialRanges == null ? List.of() : List.copyOf(initialRanges);
        this.onResult = onResult;
        List<SlotNumbering.MenuSlot> slots = new ArrayList<>();
        if (layout != null) {
            int seq = 0;
            for (SlotLayoutData.SlotCapture c : layout.slots()) {
                slots.add(new SlotNumbering.MenuSlot(seq++, c.x(), c.y(), c.item(), c.count(), c.capIndex()));
            }
        }
        this.menuSlots = slots;
        // 先以兜底编号（屏幕顺序）显示，校准回包到达后原位换算成真实序号
        this.numbering = SlotNumbering.compute(menuSlots, null, null);
        this.selected.addAll(SlotNumbering.selectionForRanges(this.numbering, this.initialRanges));
    }

    /** 服务端校准数据入口（BlockEditorScreen.acceptSlotCapability 转发，主线程）。 */
    public static void onCapabilityData(BlockPos pos, int total, List<String> items, List<Integer> counts) {
        SlotPickerScreen picker = pos == null ? null : WAITERS.get(pos);
        if (picker != null) picker.applyCapability(total, items, counts);
    }

    private void applyCapability(int total, List<String> items, List<Integer> counts) {
        if (capState != CapState.PENDING) return;
        capabilityMissing = total <= 0;
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
        this.numbering = SlotNumbering.compute(menuSlots, hasCapability ? caps : null,
                hasCapability ? total : null);
        if (selectionTouched) {
            // 玩家已手动调整过：保留物理格子选择，只剔除新判定的不可寻址格
            selected.removeIf(seq -> !numbering.isAddressable(seq));
        } else {
            // 玩家没动过：已写的槽号区间按校准后的真实编号重新解释
            selected.clear();
            selected.addAll(SlotNumbering.selectionForRanges(this.numbering, this.initialRanges));
            selected.removeIf(seq -> !numbering.isAddressable(seq));
        }
        droppedFromCalibration = countDropped(before);
        capState = hasCapability ? CapState.READY : CapState.FAILED;
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
                        new io.github.xianynomial.sfmfactorystudio.net.SlotCapabilityRequestPayload(containerPos));
        if (!sent) capState = CapState.FAILED;   // 服务端未装附属：横幅明示未校准
    }

    @Override
    protected void init() {
        relayoutGrid();
        if (containerPos != null && capState == CapState.PENDING && ticksElapsed == 0
                && WAITERS.get(containerPos) != this) {
            requestCalibration();   // 只发一次；窗口 resize 重跑 init 不会重发
        }
    }

    @Override
    public void removed() {
        if (containerPos != null && WAITERS.get(containerPos) == this) WAITERS.remove(containerPos);
    }

    @Override
    public void tick() {
        if (capState == CapState.PENDING && ++ticksElapsed > CALIBRATE_TIMEOUT_TICKS) {
            capState = CapState.FAILED;   // 编号保持兜底模式，横幅明示未校准
        }
    }

    // ---- 布局与命中（渲染与点击共用同一份几何数据，永不漂移）----

    private void relayoutGrid() {
        int maxX = 0, maxY = 0;
        for (SlotNumbering.MenuSlot s : menuSlots) {
            maxX = Math.max(maxX, s.x() + CELL);
            maxY = Math.max(maxY, s.y() + CELL);
        }
        int rawW = Math.max(maxX, COLS * CELL);
        int rawH = Math.max(maxY, CELL);
        float availW = width - 40, availH = height - 170;
        viewScale = Math.min(1.0f, Math.min(availW / rawW, availH / rawH));
        gridW = Math.round(rawW * viewScale);
        gridH = Math.round(rawH * viewScale);
        gridX = (width - gridW) / 2;
        gridY = Math.max(64, height / 2 - gridH / 2);
    }

    private int seqAt(double mx, double my) {
        int sz = Math.max(8, Math.round(CELL * viewScale));
        for (SlotNumbering.MenuSlot s : menuSlots) {
            int x = gridX + Math.round(s.x() * viewScale);
            int y = gridY + Math.round(s.y() * viewScale);
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
        if (button == 0 && brushFrom >= 0) {
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

    // ---- 渲染 ----

    @Override
    public void render(GuiGraphics g, int mx, int my, float partialTick) {
        this.renderBackground(g);

        if (menuSlots.isEmpty()) {
            renderGuidance(g);
            return;
        }

        g.drawCenteredString(this.font, L_TITLE.getString(), width / 2, 22, 0xFFFFFFFF);
        g.drawCenteredString(this.font, L_HINT.getString(), width / 2, 36, 0xFF909090);
        g.drawCenteredString(this.font, bannerText(), width / 2, 50, bannerColor());

        relayoutGrid();
        g.fill(gridX - 4, gridY - 4, gridX + gridW + 4, gridY + gridH + 4, 0xFF2A2A2E);

        int cellSize = Math.max(8, Math.round(CELL * viewScale));
        int hoverSeq = seqAt(mx, my);
        for (SlotNumbering.MenuSlot s : menuSlots) {
            int x = gridX + Math.round(s.x() * viewScale);
            int y = gridY + Math.round(s.y() * viewScale);
            boolean addressable = numbering.isAddressable(s.seq());
            boolean sel = selected.contains(s.seq());
            boolean hover = s.seq() == hoverSeq;
            int body = !addressable ? 0xFF151517 : sel ? 0xFF3A6FD8 : 0xFF1B1B1E;
            int frame = !addressable ? 0xFF3A3A40 : sel ? 0xFF7FA8FF : hover ? 0xFF9AA3B2 : 0xFF55555C;
            g.fill(x + 1, y + 1, x + cellSize - 1, y + cellSize - 1, body);
            border(g, x, y, cellSize, cellSize, frame);
            int num = numbering.number(s.seq());
            if (addressable && num >= 0 && cellSize >= 14 && this.font.width(String.valueOf(num)) <= cellSize - 6) {
                g.drawCenteredString(this.font, String.valueOf(num), x + cellSize / 2, y + cellSize / 2 - 4,
                        sel ? 0xFFEAF2FF : 0xFF8A8A92);
            }
            if (!addressable && cellSize >= 14) {
                g.drawCenteredString(this.font, "·", x + cellSize / 2, y + cellSize / 2 - 4, 0xFF6A6A72);
            }
        }

        // 结果行 + 按钮（矩形由 render 与 mouseClicked 共用）
        String result = SlotNumbering.numbersOf(numbering, selected);
        g.drawCenteredString(this.font, result.isEmpty() ? L_NONE.getString() : "slots " + result,
                width / 2, gridY + gridH + 12, selected.isEmpty() ? 0xFF909090 : 0xFF7FA8FF);
        int by = gridY + gridH + 26;
        buttonRects.clear();
        buttonActions.clear();
        addButton(g, width / 2 - 110, by, 70, L_OK.getString(), 0xFF2FA84F, this::confirm, mx, my);
        addButton(g, width / 2 - 35, by, 70, L_CLEAR.getString(), 0xFF5B6472, () -> {
            selected.clear();
            selectionTouched = true;
            droppedFromCalibration = 0;
        }, mx, my);
        addButton(g, width / 2 + 40, by, 70, L_CLOSE.getString(), 0xFF5B6472, this::onClose, mx, my);

        renderTooltip(g, hoverSeq, mx, my);
    }

    private void renderGuidance(GuiGraphics g) {
        g.drawCenteredString(this.font, L_TITLE.getString(), width / 2, height / 2 - 44, 0xFFFFFFFF);
        g.drawCenteredString(this.font, L_NO_RECORD.getString(), width / 2, height / 2 - 20, 0xFFE0E0E0);
        g.drawCenteredString(this.font, L_STEP1.getString(), width / 2, height / 2 - 2, 0xFFE0E0E0);
        g.drawCenteredString(this.font, L_STEP2.getString(), width / 2, height / 2 + 14, 0xFFE0E0E0);
        g.drawCenteredString(this.font, L_STEP3.getString(), width / 2, height / 2 + 30, 0xFF909090);
        g.drawCenteredString(this.font, L_STEP4.getString(), width / 2, height / 2 + 48, 0xFF909090);
    }

    private String bannerText() {
        if (capState == CapState.FAILED && capabilityMissing) {
            return L_NO_CAPABILITY.getString();
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
        return switch (capState) {
            case PENDING -> 0xFF9AA3B2;
            case READY -> 0xFF4CC38A;
            case FAILED -> 0xFFE8B339;
        };
    }

    /** 悬停提示：灰格说明不可寻址；可选格显示编号与捕获时的内容。 */
    private void renderTooltip(GuiGraphics g, int seq, int mx, int my) {
        if (seq < 0) return;
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
            var held = BuiltInRegistries.ITEM.getOptional(new ResourceLocation(itemId));
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

    // ---- 绘制助手 ----

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
}
