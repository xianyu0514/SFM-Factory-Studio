# NBT 组件可视化中文选择器 计划

> 2026-09-03 制定。目标：NBT 筛选的获取与展示全面中文化、可视化，
> 交互对标现有的标签选择器 / 物品选择器。

## 一、组件中文名 + 值预览（基础层，纯逻辑可单测）
- `ComponentNames`：原版全部数据组件的中文映射表
  （minecraft:enchantments→附魔、stored_enchantments→附魔书附魔、
  custom_data→自定义数据、potion_contents→药水内容、damage→损伤值、
  custom_name→自定义名称、unbreakable→不可破坏、trim→盔甲纹饰、
  attribute_modifiers→属性修饰符、repair_cost→修复费用…约 30 条，
  未收录的显示"组件(原始id)"兜底）。
- `componentPreview(type, stack)`：值预览——附魔→"锋利 III、耐久 I"
  （走 Component.translatable）；自定义名称→实际文本；药水→药水注册名；
  damage/repair_cost→数字；其余→值 toString 截断。全部客户端可算。
- 显示格式统一：**「附魔 · 锋利 III」**（副行灰色小字 minecraft:enchantments，
  保留原始 id 供高级用户核对）。

## 二、NbtItemPickerScreen（新全屏选择器，对标标签/物品选择器）
两级页面，复用现有选择器的成熟骨架（setScreen + 回调返回编辑器）：
1. **选物品页**：读客户端背包（背包/快捷栏/盔甲/副手，带完整组件同步），
   物品图标网格 + 名称 + 数量，拼音搜索框（复用 PinyinSearch）。
2. **选组件页**：所选物品的全部非默认组件，每行=彩色圆点 + 中文名 +
   值预览 + 灰色原始 id；点击即生成 `nbt:` 条件返回编辑器。
   物品无组件时页面直接提示"这件物品没有 NBT 数据"并退回选物品页。

## 三、接线与既有显示升级
- openNbtPicker 菜单改造：「从物品选择 NBT（推荐）」（新选择器）、
  常用组件直接列出中文名（附魔 / 药水内容 / 自定义数据 / 损伤值…）、
  「手动输入组件 id（高级）」保留。
- 积木上已选条件的摘要：`nbt:minecraft/enchantments` → 显示「NBT·附魔」；
  特征编辑菜单里对已有 nbt 条目的"重新选择/编辑/删除"文案同步中文名。

## 四、验收
- 从背包选附魔书 → 第二页看到「附魔书附魔 · 经修补 I」这类中文行，点选建条件；
- 积木摘要显示「NBT·附魔」；生成的 SFML 仍为 #nbt:minecraft/stored_enchantments
  （语法层零改动，往返无损红线不动）；
- 原版 SFM 服上入口照旧隐藏；全部测试保持绿。

工作量约一天：ComponentNames+预览（0.5 天内，含单测）、选择器屏（0.5 天）、接线（少量）。
