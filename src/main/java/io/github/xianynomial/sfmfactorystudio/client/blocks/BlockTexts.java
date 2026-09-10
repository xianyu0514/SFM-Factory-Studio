package io.github.xianynomial.sfmfactorystudio.client.blocks;

import io.github.xianynomial.sfmfactorystudio.client.Loc;

/**
 * All UI copy of the block editor in one place. New keys must still be
 * registered in {@code assets/sfmfactorystudio/lang/zh_cn.json} and {@code en_us.json}
 * (Loc falls back to the Chinese default when a key is missing).
 */
public final class BlockTexts {
    public static final Loc T_TITLE = new Loc("gui.sfmfactorystudio.blocks.title", "SFM 智造工坊 · 可视化编程");
    public static final Loc T_SAVE = new Loc("gui.sfmfactorystudio.blocks.save", "保存");
    public static final Loc T_PREVIEW = new Loc("gui.sfmfactorystudio.blocks.preview", "代码编辑");
    public static final Loc T_CLOSE = new Loc("gui.sfmfactorystudio.blocks.close", "关闭");
    public static final Loc T_UNDO = new Loc("gui.sfmfactorystudio.blocks.undo", "撤销");
    public static final Loc T_FIT = new Loc("gui.sfmfactorystudio.blocks.fit", "适配");
    public static final Loc T_NAME = new Loc("gui.sfmfactorystudio.blocks.name", "程序名");
    public static final Loc T_CAT_TRIGGER = new Loc("gui.sfmfactorystudio.blocks.cat_trigger", "新建任务");
    public static final Loc T_CAT_MOVE = new Loc("gui.sfmfactorystudio.blocks.cat_move", "插入积木");
    public static final Loc T_CAT_LOGIC = new Loc("gui.sfmfactorystudio.blocks.cat_logic", "判断");
    public static final Loc T_CAT_RAW = new Loc("gui.sfmfactorystudio.blocks.cat_raw", "说明");
    public static final Loc T_CAT_TPL = new Loc("gui.sfmfactorystudio.blocks.cat_tpl", "一键模板");
    public static final Loc T_CAT_MY = new Loc("gui.sfmfactorystudio.blocks.cat_my", "我的模板");
    // 「我的模板」分组头右侧的保存入口 + 空态提示（存为模板从框选动作条迁来，动作条减负）
    public static final Loc T_MY_SAVE = new Loc("gui.sfmfactorystudio.blocks.mytpl_save", "★ 保存");
    public static final Loc T_MY_HINT = new Loc("gui.sfmfactorystudio.blocks.mytpl_hint", "框选积木后点「★ 保存」");
    public static final Loc T_TIMER = new Loc("gui.sfmfactorystudio.blocks.timer", "定时重复执行");
    public static final Loc T_PULSE = new Loc("gui.sfmfactorystudio.blocks.pulse", "收到红石脉冲时");
    public static final Loc T_INPUT = new Loc("gui.sfmfactorystudio.blocks.input", "从方块取出");
    public static final Loc T_OUTPUT = new Loc("gui.sfmfactorystudio.blocks.output", "放入方块");
    // 中文语序拆分：从 [标签] 方块取出 [数量] [资源] / 放入 [标签] 方块 [数量] [资源]
    public static final Loc T_IO_FROM = new Loc("gui.sfmfactorystudio.blocks.io_from", "从");
    public static final Loc T_IO_TAKE = new Loc("gui.sfmfactorystudio.blocks.io_take", "取出");
    public static final Loc T_IO_PUT = new Loc("gui.sfmfactorystudio.blocks.io_put", "放入");

    public static final Loc T_QTY_ALL = new Loc("gui.sfmfactorystudio.blocks.qty_all", "全部");
    public static final Loc T_QTY_EACH_KIND = new Loc("gui.sfmfactorystudio.blocks.qty_each_kind", "每种");
    public static final Loc T_QTY_TOTAL = new Loc("gui.sfmfactorystudio.blocks.qty_total", "合计");
    public static final Loc T_ENERGY_TRANSFER = new Loc("gui.sfmfactorystudio.blocks.energy_transfer", "高频传输能量");
    public static final Loc T_FORGET = new Loc("gui.sfmfactorystudio.blocks.forget", "清空取出记录");
    public static final Loc T_IF = new Loc("gui.sfmfactorystudio.blocks.if", "当");
    public static final Loc T_RAW = new Loc("gui.sfmfactorystudio.blocks.raw", "旧版内容（只读）");
    public static final Loc T_COMMENT = new Loc("gui.sfmfactorystudio.blocks.comment", "注释");
    public static final Loc T_EVERY = new Loc("gui.sfmfactorystudio.blocks.every", "每");
    public static final Loc T_TICKS = new Loc("gui.sfmfactorystudio.blocks.ticks", "刻");
    public static final Loc T_SECONDS = new Loc("gui.sfmfactorystudio.blocks.seconds", "秒");
    public static final Loc T_DO = new Loc("gui.sfmfactorystudio.blocks.do", "执行：");
    public static final Loc T_ALL = new Loc("gui.sfmfactorystudio.blocks.all", "全部");








    public static final Loc T_ELSE = new Loc("gui.sfmfactorystudio.blocks.else", "以上都不满足时：");
    // 否则如果行的行前缀（与「+ 否则如果」按钮叫法一致）
    public static final Loc T_ELSEIF = new Loc("gui.sfmfactorystudio.blocks.elseif", "否则如果");
    public static final Loc T_ADDELSE = new Loc("gui.sfmfactorystudio.blocks.addelse", "+ 否则");
    public static final Loc T_ADDCOND = new Loc("gui.sfmfactorystudio.blocks.addcond", "＋ 添加判断");
    public static final Loc T_ADDELSEIF = new Loc("gui.sfmfactorystudio.blocks.addelseif", "+ 否则如果");

    public static final Loc T_AND = new Loc("gui.sfmfactorystudio.blocks.and", "且");
    public static final Loc T_OR = new Loc("gui.sfmfactorystudio.blocks.or", "或");
    public static final Loc T_COND = new Loc("gui.sfmfactorystudio.blocks.cond", "条件");
    // has 条件的存在动词：与代码层 `if a has …` 一词对应（勿用「取出」这类动作词）
    public static final Loc T_COND_HAS = new Loc("gui.sfmfactorystudio.blocks.cond_has", "中有");
    public static final Loc T_LABEL = new Loc("gui.sfmfactorystudio.blocks.label", "标签");
    public static final Loc T_ADDSTMT = new Loc("gui.sfmfactorystudio.blocks.add_stmt", "+ 放入积木");




    public static final Loc T_SAVED_OK = new Loc("gui.sfmfactorystudio.blocks.saved", "✔ 已保存到磁盘");

    public static final Loc T_TPL_SMELT = new Loc("gui.sfmfactorystudio.blocks.tpl_smelt", "熔炉自动线");
    public static final Loc T_TPL_SORT = new Loc("gui.sfmfactorystudio.blocks.tpl_sort", "满仓分类");
    public static final Loc T_TPL_EVEN = new Loc("gui.sfmfactorystudio.blocks.tpl_even", "均衡分配");
    public static final Loc T_TPL_FAST = new Loc("gui.sfmfactorystudio.blocks.tpl_fast", "高频并行");

    public static final Loc T_TPL_NAME = new Loc("gui.sfmfactorystudio.blocks.tpl_name", "输入模板名称");
    public static final Loc T_DIRTY = new Loc("gui.sfmfactorystudio.blocks.dirty", "● 未保存 — Ctrl+S 保存");

    public static final Loc T_AB_COPY = new Loc("gui.sfmfactorystudio.blocks.ab_copy", "⧉ 复制");
    public static final Loc T_AB_TPL = new Loc("gui.sfmfactorystudio.blocks.ab_tpl", "★ 存为模板");
    public static final Loc T_AB_DEL = new Loc("gui.sfmfactorystudio.blocks.ab_del", "✕ 删除");
    public static final Loc T_AB_CANCEL = new Loc("gui.sfmfactorystudio.blocks.ab_cancel", "○ 取消");
    public static final Loc T_ISSUES_TITLE = new Loc("gui.sfmfactorystudio.blocks.issues_title", "问题检查");
    public static final Loc T_ISSUES_ERR = new Loc("gui.sfmfactorystudio.blocks.issues_err", "错误");
    public static final Loc T_ISSUES_WARN = new Loc("gui.sfmfactorystudio.blocks.issues_warn", "提醒");
    public static final Loc T_ISSUES_NONE = new Loc("gui.sfmfactorystudio.blocks.issues_none", "没有发现问题 ✔");
    public static final Loc T_ISSUES_LOCATE = new Loc("gui.sfmfactorystudio.blocks.issues_locate", "定位");
    public static final Loc T_ISSUES_FIX = new Loc("gui.sfmfactorystudio.blocks.issues_fix", "修复");
    public static final Loc T_ISSUES_PUSH_LABELS = new Loc("gui.sfmfactorystudio.blocks.issues_push_labels", "推送缺失标签到标签枪");
    public static final Loc T_ISSUES_MORE = new Loc("gui.sfmfactorystudio.blocks.issues_more", "还有 {n} 条，见「问题」面板");






    private BlockTexts() {
    }
}
