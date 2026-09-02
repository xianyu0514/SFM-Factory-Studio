package ca.teamdman.sfmjimu.client.blocks;

import ca.teamdman.sfmjimu.client.Loc;

/**
 * All UI copy of the block editor in one place. New keys must still be
 * registered in {@code assets/sfmjimu/lang/zh_cn.json} and {@code en_us.json}
 * (Loc falls back to the Chinese default when a key is missing).
 */
public final class BlockTexts {
    public static final Loc T_TITLE = new Loc("gui.sfmjimu.blocks.title", "SFM 智造工坊 · 可视化编程");
    public static final Loc T_SAVE = new Loc("gui.sfmjimu.blocks.save", "保存");
    public static final Loc T_PREVIEW = new Loc("gui.sfmjimu.blocks.preview", "代码编辑");
    public static final Loc T_CLOSE = new Loc("gui.sfmjimu.blocks.close", "关闭");
    public static final Loc T_UNDO = new Loc("gui.sfmjimu.blocks.undo", "撤销");
    public static final Loc T_FIT = new Loc("gui.sfmjimu.blocks.fit", "适配");
    public static final Loc T_NAME = new Loc("gui.sfmjimu.blocks.name", "程序名");
    public static final Loc T_CAT_TRIGGER = new Loc("gui.sfmjimu.blocks.cat_trigger", "什么时候运行");
    public static final Loc T_CAT_MOVE = new Loc("gui.sfmjimu.blocks.cat_move", "搬运资源");
    public static final Loc T_CAT_LOGIC = new Loc("gui.sfmjimu.blocks.cat_logic", "判断");
    public static final Loc T_CAT_RAW = new Loc("gui.sfmjimu.blocks.cat_raw", "说明");
    public static final Loc T_CAT_TPL = new Loc("gui.sfmjimu.blocks.cat_tpl", "一键模板");
    public static final Loc T_CAT_MY = new Loc("gui.sfmjimu.blocks.cat_my", "我的模板");
    public static final Loc T_TIMER = new Loc("gui.sfmjimu.blocks.timer", "定时重复执行");
    public static final Loc T_PULSE = new Loc("gui.sfmjimu.blocks.pulse", "收到红石脉冲时");
    public static final Loc T_INPUT = new Loc("gui.sfmjimu.blocks.input", "从方块取出");
    public static final Loc T_OUTPUT = new Loc("gui.sfmjimu.blocks.output", "放入方块");
    public static final Loc T_ENERGY_TRANSFER = new Loc("gui.sfmjimu.blocks.energy_transfer", "高频传输能量");
    public static final Loc T_FORGET = new Loc("gui.sfmjimu.blocks.forget", "清空本轮取出记录");
    public static final Loc T_IF = new Loc("gui.sfmjimu.blocks.if", "如果");
    public static final Loc T_RAW = new Loc("gui.sfmjimu.blocks.raw", "旧版内容（只读）");
    public static final Loc T_COMMENT = new Loc("gui.sfmjimu.blocks.comment", "注释");
    public static final Loc T_EVERY = new Loc("gui.sfmjimu.blocks.every", "每");
    public static final Loc T_TICKS = new Loc("gui.sfmjimu.blocks.ticks", "刻");
    public static final Loc T_SECONDS = new Loc("gui.sfmjimu.blocks.seconds", "秒");
    public static final Loc T_DO = new Loc("gui.sfmjimu.blocks.do", "执行");
    public static final Loc T_ALL = new Loc("gui.sfmjimu.blocks.all", "全部");
    public static final Loc T_RETAIN = new Loc("gui.sfmjimu.blocks.retain", "至少留下");
    public static final Loc T_EACH_LABEL = new Loc("gui.sfmjimu.blocks.each_label", "每个方块分别处理");
    public static final Loc T_SIDES = new Loc("gui.sfmjimu.blocks.sides", "侧面");
    public static final Loc T_SLOTS = new Loc("gui.sfmjimu.blocks.slots", "槽位");
    public static final Loc T_RR = new Loc("gui.sfmjimu.blocks.round_robin", "轮流选择");
    public static final Loc T_EXCEPT = new Loc("gui.sfmjimu.blocks.except", "排除");
    public static final Loc T_EMPTY = new Loc("gui.sfmjimu.blocks.empty_slots", "只放空槽");
    public static final Loc T_THEN = new Loc("gui.sfmjimu.blocks.then", "那么");
    public static final Loc T_ELSE = new Loc("gui.sfmjimu.blocks.else", "否则");
    public static final Loc T_ADDELSE = new Loc("gui.sfmjimu.blocks.addelse", "+ 否则");
    public static final Loc T_ADDCOND = new Loc("gui.sfmjimu.blocks.addcond", "＋ 添加判断");
    public static final Loc T_ADDELSEIF = new Loc("gui.sfmjimu.blocks.addelseif", "+ 否则如果");
    public static final Loc T_HAS = new Loc("gui.sfmjimu.blocks.has", "有");
    public static final Loc T_AND = new Loc("gui.sfmjimu.blocks.and", "且");
    public static final Loc T_OR = new Loc("gui.sfmjimu.blocks.or", "或");
    public static final Loc T_COND = new Loc("gui.sfmjimu.blocks.cond", "条件");
    public static final Loc T_LABEL = new Loc("gui.sfmjimu.blocks.label", "标签");
    public static final Loc T_ADDSTMT = new Loc("gui.sfmjimu.blocks.add_stmt", "+ 放入积木");
    public static final Loc T_RES = new Loc("gui.sfmjimu.blocks.resource", "资源");
    public static final Loc T_RES_ID = new Loc("gui.sfmjimu.blocks.res_id", "输入ID…");
    public static final Loc T_RES_CLEAR = new Loc("gui.sfmjimu.blocks.res_clear", "清空");
    public static final Loc T_RES_BROWSE = new Loc("gui.sfmjimu.blocks.res_browse", "浏览物品…");
    public static final Loc T_SAVED_OK = new Loc("gui.sfmjimu.blocks.saved", "✔ 已保存到磁盘");
    public static final Loc T_EMPTY_PROGRAM = new Loc("gui.sfmjimu.blocks.empty_program", "从左侧拖入或点击积木，开始编写程序\n拖动空白处框选积木 · 滚轮缩放 · 拖动平移");
    public static final Loc T_GLOBAL = new Loc("gui.sfmjimu.blocks.global", "全局");
    public static final Loc T_PLUS = new Loc("gui.sfmjimu.blocks.plus", "+偏移");
    public static final Loc T_TPL_SMELT = new Loc("gui.sfmjimu.blocks.tpl_smelt", "熔炉自动线");
    public static final Loc T_TPL_SORT = new Loc("gui.sfmjimu.blocks.tpl_sort", "满仓分类");
    public static final Loc T_TPL_EVEN = new Loc("gui.sfmjimu.blocks.tpl_even", "均衡分配");
    public static final Loc T_TPL_FAST = new Loc("gui.sfmjimu.blocks.tpl_fast", "高频并行");
    public static final Loc T_TPL_SAVE = new Loc("gui.sfmjimu.blocks.tpl_save", "存为模板");
    public static final Loc T_TPL_NAME = new Loc("gui.sfmjimu.blocks.tpl_name", "输入模板名称");
    public static final Loc T_DIRTY = new Loc("gui.sfmjimu.blocks.dirty", "● 未保存 — Ctrl+S 保存");
    public static final Loc T_WARN_MIN = new Loc("gui.sfmjimu.blocks.warn_min", "单触发器最快 20 刻（SFM 配置 timerTriggerMinimumIntervalInTicks 可调至 1）");
    public static final Loc T_AB_COPY = new Loc("gui.sfmjimu.blocks.ab_copy", "⧉ 复制");
    public static final Loc T_AB_TPL = new Loc("gui.sfmjimu.blocks.ab_tpl", "★ 存为模板");
    public static final Loc T_AB_DEL = new Loc("gui.sfmjimu.blocks.ab_del", "✕ 删除");
    public static final Loc T_AB_CANCEL = new Loc("gui.sfmjimu.blocks.ab_cancel", "○ 取消");
    public static final Loc T_ISSUES_TITLE = new Loc("gui.sfmjimu.blocks.issues_title", "问题检查");
    public static final Loc T_ISSUES_ERR = new Loc("gui.sfmjimu.blocks.issues_err", "错误");
    public static final Loc T_ISSUES_WARN = new Loc("gui.sfmjimu.blocks.issues_warn", "提醒");
    public static final Loc T_ISSUES_NONE = new Loc("gui.sfmjimu.blocks.issues_none", "没有发现问题 ✔");
    public static final Loc T_ISSUES_LOCATE = new Loc("gui.sfmjimu.blocks.issues_locate", "定位");
    public static final Loc T_ISSUES_FIX = new Loc("gui.sfmjimu.blocks.issues_fix", "修复");
    public static final Loc T_ISSUES_PUSH_LABELS = new Loc("gui.sfmjimu.blocks.issues_push_labels", "推送缺失标签到标签枪");
    public static final Loc T_ISSUES_MORE = new Loc("gui.sfmjimu.blocks.issues_more", "还有 {n} 条，见「问题」面板");
    public static final Loc R_ALL = new Loc("gui.sfmjimu.blocks.res_all", "全部资源");
    public static final Loc R_ITEM = new Loc("gui.sfmjimu.blocks.res_item", "全部物品");
    public static final Loc R_FLUID = new Loc("gui.sfmjimu.blocks.res_fluid", "全部流体");
    public static final Loc R_CHEM = new Loc("gui.sfmjimu.blocks.res_chem", "全部化学品");
    public static final Loc R_ENERGY = new Loc("gui.sfmjimu.blocks.res_energy", "全部能量");

    private BlockTexts() {
    }
}
