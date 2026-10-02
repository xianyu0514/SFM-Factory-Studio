# 更新日志 / Changelog

## 0.9.1 · 2026-10-02

## 中文

- **打开容器卡顿根治**：槽位布局缓存的读取、合并与写盘全部移出游戏线程——首次读取后台加载、相同布局不再重复保存、写盘自动合并并在失败后重试；编辑器布局与草稿同样改为后台合并写盘，拖拽结束等操作不再伴随同步磁盘读写（关闭编辑器时同步收尾，退出不丢数据）。
- **校准更快也更省**：槽位配对状态机重写，已排除的组合永久退出计算；学习稳定后服务端采样自动降速（每秒 4 次降至最低 0.5 次），界面一有变化立即恢复全速——学习语义与速度不变，机器静止时服务端几乎零开销。
- **资源目录预热**：物品/流体/化学品目录改为进世界后后台分帧构建（每帧 ≤2ms），首次打开编辑器或资源选择器不再一次性枚举全部注册表物品造成可感知停顿（大型整合包收益最明显）。
- **默认代码视图**：首次打开默认「积木 60% / 代码 40%」分屏，窗口空间不足时优先保留积木；**F6** 或工具栏按钮随时展开/收起代码，不丢失未保存内容；比例、专注、换行与展开状态按管理器记忆，显示代码不再自动抢输入焦点。
- **代码悬停文档**：鼠标停在 `input`、`retain`、`round robin`、`>=` 等写法上，浮出说明与可运行示例，随游戏语言切换中英文——48 个关键词覆盖控制流、集合语义、比较符、方向、时间单位与红石（数据源自官方 VSCode 扩展并翻译）。
- **编辑器操作增强**：代码区新增右键菜单（全选/复制/剪切/粘贴）；三击选中整行；光标所在词的全部出现位置淡蓝高亮，读程序找引用一目了然。
- **日志更干净、排障更容易**：开关容器界面的校准簿记日志默认静默，只在学到槽位编号时记录一条结果；新增「校准详细日志」配置（默认关闭，排查编号问题时打开）；异常日志补全堆栈；启动行显示模组版本。
- **兼容性**：槽位布局、代码视图偏好、草稿与模板全部兼容旧档，替换 JAR 即可继续使用，无需迁移。

## English

- **Chest-opening stutter fixed:** slot-layout cache reads, merging, and disk writes all moved off the game thread — first load happens in the background, identical captures no longer re-save, and writes are coalesced with automatic retry. Editor layouts and drafts save in the background too; dragging and resizing no longer touch the disk (closing flushes synchronously so nothing is lost).
- **Smarter calibration:** the slot pairing state machine was rewritten so eliminated combinations permanently leave the computation. After learning settles, server sampling automatically slows from 4/s to 0.5/s and instantly returns to full speed on any change — same learning semantics and speed, near-zero server cost while machines sit idle.
- **Resource catalog prewarm:** the item/fluid/chemical catalog now builds in the background after joining a world (≤2 ms per frame), so first opening the editor or a picker no longer enumerates every registered item at once — the biggest win in large modpacks.
- **Default code view:** first use opens a 60% blocks / 40% code split; small windows keep the canvas. **F6** or the toolbar button expands/collapses code without losing unsaved edits. Ratio, focus, wrapping, and visibility are remembered per manager, and showing code no longer steals keyboard focus.
- **Hover documentation:** rest the mouse on `input`, `retain`, `round robin`, `>=` and friends for inline descriptions with runnable examples, localized to your game language — 48 keywords covering control flow, set semantics, comparisons, directions, time units, and redstone (ported and translated from the official VSCode extension).
- **Editor quality of life:** right-click menu in the code pane (select all / copy / cut / paste); triple-click selects a whole line; every occurrence of the word under the caret is softly highlighted.
- **Quieter logs, easier debugging:** per-container calibration bookkeeping is silent by default — one line is logged only when slot numbering is actually learned. A new "verbose calibration logs" config (off by default) restores full tracing; exception logs now include stack traces; the startup line shows the mod version.
- **Compatibility:** slot layouts, code-view preferences, drafts, and templates are fully compatible with previous saves — replace the JAR and keep playing, no migration needed.

## Downloads / 下载

- `SFM-Factory-Studio-1.20.1-0.9.1.jar` — Minecraft 1.20.1 / Forge 47.x
- `SFM-Factory-Studio-1.21.1-0.9.1.jar` — Minecraft 1.21.1 / NeoForge 21.1.x

需要安装 Super Factory Manager；本次基于 4.34.0 验证。升级时替换旧版附属 JAR。

Requires Super Factory Manager; validated against 4.34.0. Replace the previous addon JAR when upgrading.

---


## 0.9.0 · 2026-09-17

## 中文

- **代码区自适应**：支持拖动调整上下分屏、代码专注视图（F7）和显示换行（Alt+Z）；小窗口自动切换代码视图，长行与末行均可滚动访问，换行不改动源码。
- **滚动与布局修复**：横纵滚动条常驻可见，支持滑块拖动、轨道翻页和 Shift+滚轮横向滚动；修复滚轮失效、分隔条跳动及拖出边界后无法正常释放，缩放时保留阅读位置。
- **编辑更顺手**：新增查找（Ctrl+F）、前后匹配（F3 / Shift+F3）、跳行（Ctrl+G）和替换（Ctrl+H）；支持替换下一处、全部替换及一步撤销，双击选词、连续输入合并撤销。
- **状态更清楚**：压缩代码区顶部留白，底栏集中显示保存/同步状态、行列及总行数；完整提示悬停可见，带行列信息的编译错误可点击定位，补全列表适配代码区空间。
- **编辑状态保留**：调整窗口或切换视图时保留光标、选区和撤销历史（源码未被替换时）；按管理器记忆分屏比例、换行和专注偏好。
- **英文界面补全**：修复比较菜单、成本提示等中文残留；示例标题、说明、注释和标签随游戏语言切换，改进帮助页长标题与资源选择器标题，补充翻译完整性检查。
- **模板与管理器修复**：“我的模板”支持删除前确认；修复返回管理器后按钮消失，以及窗口缩放后按钮显示与点击位置不一致。
- **发布构建加固**：将 1.20.1 拼音库隔离纳入构建流程，延续 0.8.9 与 Just Enough Characters 的共存修复；增加编辑器布局、拖动、替换和错误定位回归测试。

## English

- **Adaptive code workspace:** draggable split view, focus mode (F7), and display-only line wrapping (Alt+Z). Small windows switch to code view; long lines and the final line remain reachable by scrolling.
- **Scrolling and layout fixes:** always-visible scrollbars, draggable thumbs, page-by-page track clicks, and Shift+wheel horizontal scrolling. Fixed swallowed wheel input, divider jumps, and pointer release outside the pane; resizing preserves the reading position.
- **Faster editing:** find (Ctrl+F), next/previous match (F3 / Shift+F3), go to line (Ctrl+G), and replace (Ctrl+H). Replace next or all with one-step undo; double-click to select words and undo continuous typing as a group.
- **Clearer feedback:** a compact header and footer show save/sync status, cursor position, and line count. Hover for full messages; locate compiler errors that include source positions. Completion menus fit the available space.
- **Persistent editing state:** resizing and switching views preserve the cursor, selection, and undo history when source text is unchanged. Split ratio, wrapping, and focus preferences are remembered per manager.
- **More complete English UI:** removed Chinese leftovers in comparison menus and cost hints. Example titles, descriptions, comments, and labels follow the game language; improved help-title truncation and resource-picker titles, with translation checks added.
- **Template and manager fixes:** delete custom templates with confirmation. Fixed missing manager buttons after returning from the editor and misaligned buttons after resizing.
- **Safer release builds:** 1.20.1 builds now automatically isolate the bundled pinyin library, preserving 0.8.9 compatibility with Just Enough Characters. Added regression coverage for layout, dragging, replacement, and error navigation.

## Downloads / 下载

- `SFM-Factory-Studio-1.20.1-0.9.0.jar` — Minecraft 1.20.1 / Forge 47.x
- `SFM-Factory-Studio-1.21.1-0.9.0.jar` — Minecraft 1.21.1 / NeoForge 21.1.x

需要安装 Super Factory Manager；本次基于 4.34.0 验证。升级时替换旧版附属 JAR。

Requires Super Factory Manager; validated against 4.34.0. Replace the previous addon JAR when upgrading.

---

## 0.7（首个开源版本）

面向 Minecraft 1.21.1 / NeoForge 21.1 / Super Factory Manager 4.34.0 的中文可视化编程附属。

### 积木编辑器
- 触发器卡片无限画布布局，位置自动记忆；框选、批量复制、右键菜单、撤销/重做
- 完整 SFML 语法覆盖：触发器、取出/存入/遗忘、扩展积木（数量/保留/资源特征/排除/侧面/槽位/轮流/分别处理/仅空槽）、If/Else If/Else、注释
- 一键模板：熔炉自动线、满仓分类、均衡分配、高频并行
- 同屏 SFML 源码双向同步（语法着色、智能建议、草稿自动恢复）
- 无损往返：注释与格式保留；官方示例 + 1000 组随机程序全部通过 SFM 本体编译器

### 画布组织
- 蓝/绿/黄三分区工作区（命名、聚焦、持久化）
- 卡片折叠 / If 折叠 / LOD 缩略视图
- 积木连线（纯视觉备注）

### 诊断与校验
- 保存前 SFM 编译器校验 + 中文本地体检（缺标签、无效槽位、恒假条件等）
- 问题面板：定位跳转 + 一键修复；未绑定标签一键推送到标签枪

### 资源特征
- 可视化物品标签选择器（中文名、覆盖范围提示、成员预览、拼音搜索）
- 且/或/非条件药丸链，每条件可删除、整链预览匹配物品
- NBT 组件筛选（双端安装解锁）：附魔（含等级）、药水、自定义名称、custom_data（子键/多级路径/数值比较）等；服务端 Mixin 实现，SFM 更新失效时自动退回原版行为

### 服务器选项（默认全关，与原版行为完全一致）
- 空转退避 / 每刻全局预算：模组列表「配置」按钮打开全中文设置界面，即时生效
- 纯客户端安装亦可完整使用编辑器（NBT 入口自动隐藏）

### 性能
- 纯客户端零每刻开销；增量布局（编辑只重排内容变化的卡）、命中对象池、视野裁剪
- 117 项单元测试全绿
