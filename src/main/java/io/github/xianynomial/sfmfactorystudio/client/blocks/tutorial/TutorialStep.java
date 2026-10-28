package io.github.xianynomial.sfmfactorystudio.client.blocks.tutorial;

import io.github.xianynomial.sfmfactorystudio.client.Loc;

/**
 * 新手引导的步骤序列。
 *
 * <p>顺序按 SFM 的真实心智模型设计：<b>建卡 → 指定取货点 → 指定送货点 → 放开侧面 →
 * 换资源类型 → 选具体资源 → 存盘</b>。每一屏只讲一件事，做完立刻能看到画面变化。
 *
 * <p>关于完成条件：除首尾两屏外，全部由 {@code TutorialState#satisfiedBy} 从
 * {@code BProgram} 状态推导，不监听"用户点了哪个按钮"——事件会漏（撤销回退）、
 * 会重复计数，而且三个版本的输入事件签名并不相同。
 *
 * <p>文案一律写成 {@code new Loc("完整键", "中文默认")} 的字面量形式：
 * 项目的 {@code HardcodedChineseTest} 认得这种构造（中文是运行时按语言解析的默认值），
 * {@code LangCoverageTest} 也要求每个键都能被静态扫到——拼出来的键会让它判定成孤儿键。
 */
public enum TutorialStep {

    /** 欢迎屏：手动推进。 */
    WELCOME(new Loc("gui.sfmfactorystudio.ed.tut_welcome_title", "两分钟上手"),
            new Loc("gui.sfmfactorystudio.ed.tut_welcome_body",
                    "这是积木编辑器。跟着点几下，就能写出第一个能跑的程序。"),
            null, true),

    /** 拖一张「定时重复执行」卡进来；它会自带取出 + 放入两行。 */
    ADD_CARD(new Loc("gui.sfmfactorystudio.ed.tut_add_card_title", "从一张卡开始"),
            new Loc("gui.sfmfactorystudio.ed.tut_add_card_body",
                    "把左边「定时重复执行」拖到画布上。这一张卡就是一个小任务。"),
            "pal.timer", false),

    /** 指定从哪个标签取出。 */
    PICK_IN_LABEL(new Loc("gui.sfmfactorystudio.ed.tut_pick_in_label_title", "告诉它从哪拿"),
            new Loc("gui.sfmfactorystudio.ed.tut_pick_in_label_body",
                    "点第一行的标签，选一个容器。手上拿【标签枪】右键点箱子就能给它贴标签；附近一个容器都没有就先按 ESC 去贴一个。"),
            "access.in.label", false),

    /** 指定放到哪个标签。 */
    PICK_OUT_LABEL(new Loc("gui.sfmfactorystudio.ed.tut_pick_out_label_title", "再告诉它放哪"),
            new Loc("gui.sfmfactorystudio.ed.tut_pick_out_label_body",
                    "点第二行的标签，选一个不同的容器作为输出目标（同样用标签枪贴好）。"),
            "access.out.label", false),

    /** 棱面：默认面 → 每一面。 */
    EACH_SIDE(new Loc("gui.sfmfactorystudio.ed.tut_each_side_title", "六个面都算上"),
            new Loc("gui.sfmfactorystudio.ed.tut_each_side_body",
                    "现在它只看一个面。点这里改成「每一面」——容器的每个接口都能用。"),
            "access.side", false),

    /** 资源类型：物品 → 流体。 */
    TO_FLUID(new Loc("gui.sfmfactorystudio.ed.tut_to_fluid_title", "换一种东西搬"),
            new Loc("gui.sfmfactorystudio.ed.tut_to_fluid_body",
                    "默认搬物品。点这里改成「流体」——水、岩浆、原油都能搬。"),
            "access.category", false),

    /** 资源选择器里挑一个具体资源。 */
    PICK_RESOURCE(new Loc("gui.sfmfactorystudio.ed.tut_pick_resource_title", "具体搬什么"),
            new Loc("gui.sfmfactorystudio.ed.tut_pick_resource_body",
                    "点流体旁边的「＋」，进资源选择器，挑一个具体的水或岩浆。"),
            "access.kind", false),

    /** 收尾屏：手动推进。 */
    DONE(new Loc("gui.sfmfactorystudio.ed.tut_done_title", "现在轮到你了"),
            new Loc("gui.sfmfactorystudio.ed.tut_done_body",
                    "一张卡只搬一种东西。真正有用的是几张卡配合——定时把矿石送进熔炉、把产物收回箱子、把多余的水抽走。"),
            null, true);

    public final Loc title;
    public final Loc body;
    /** 高亮锚点 id；{@code null} 表示居中卡片、不指向任何控件。 */
    public final String anchor;
    /** true = 必须玩家点按钮才前进（首尾两屏）；false = 状态满足即自动前进。 */
    public final boolean manual;

    TutorialStep(Loc title, Loc body, String anchor, boolean manual) {
        this.title = title;
        this.body = body;
        this.anchor = anchor;
        this.manual = manual;
    }

    /** 下一个步骤；已是最后一步则返回自身。 */
    public TutorialStep next() {
        TutorialStep[] all = values();
        return this.ordinal() + 1 < all.length ? all[this.ordinal() + 1] : this;
    }

    public boolean isLast() {
        return this == DONE;
    }
}
