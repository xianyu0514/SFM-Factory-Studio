# 更新日志 / Changelog

## 1.0 · 2026-10-28 · 首个正式版

## 中文

- **修复（性能，最严重）**：块编辑器的渲染循环每帧都会写一条 `[jei-probe]` INFO 日志。60 FPS 下约 60 行/秒，渲染线程被同步磁盘 IO 拖慢，`latest.log` 迅速刷爆。现已移除；布局诊断改由 `[jei-layout]` 承担（只在 JEI 状态变化或首次打开时打一行）。
- **修复（多人服务器安全）**：标签请求与"拉取标签"两个网络处理现在校验玩家与目标管理器的距离（64 格）。此前客户端可指定任意坐标读取远处管理器磁盘上的标签，`拉取标签` 还会把结果写进请求者自己的标签枪，绕过原版 4~6 格交互距离。同时给拉取加了每玩家 250ms 重入限流——该操作会在服务端主线程编译程序，原先可被循环发包放大成 CPU 负担。
- **修复（功能静默失效）**：槽位校准的自适应采样间隔在"界面静止约 1 分钟"后会因整数溢出变成负数，导致采样实际停止且无任何日志。现在改用 long 移位并钳制到上限。
- **修复（内存与存档串扰）**：校准会话表是静态的，服务器停止时未清理，且每个会话多持有一个从未使用的服务端引用。单机反复进出世界会让整棵服务端对象无法回收，旧会话的采样基线也可能残留到下一次进入。现在新增会话表清理并在服务器停止时释放。
- **修复（健壮性）**：标签回包的解码增加条目数上限（不得超过剩余可读字节数）。此前畸形包可让客户端分配超大数组而 OOM。
- **修复（交互）**：代码编辑区的右键此前无反应——判断用的是中键（`button == 2`），而注释一直写的是右键。现在右键与中键都能打开剪贴板菜单。
- **改进（性能）**：槽位能力查询不再对同一批朝向重复做两次能力发现（每次都要解析线缆网络），单槽内容的读取也从两遍合并为一遍；该请求的日志降为 debug。
- **改进**：资源显示名缓存加上限（4096），长期游玩不再无界增长。
- **修复（三版功能对齐）**：26.1.2 线的流体条目此前降级为纯文字——因为 26.1 移除了 `IClientFluidTypeExtensions` 的 `getStillTexture()` / `getTintColor()`。现在改用 26.1 的模型驱动接口（`ModelManager → FluidStateModelSet → FluidModel.stillMaterial()`，与 JEI 29.47 同一路径）取静置纹理与染色，显示与 1.21.1 / 1.20.1 一致；未注册模型的模组流体回退为桶图标，无桶才退文字。顺带修正另两版回退分支丢失本地化名（显示成 registry id）的问题。
- **内部**：`sfm` 依赖下限由 `[4.30,)` 收紧到 `[4.34,)`，与 SFM 本体当前版本严格对齐。4.30~4.33 从未针对本附属测试过，放宽只会让用户装上后在运行期撞 `NoSuchMethodError`。
- **测试**：三版打包全部通过（302 / 288 / 289 用例，0 失败），三版一致性、语言 key、资源完整性检查同步通过。

## English

- **Fixed (performance, most severe):** the block editor's render loop wrote an INFO line (`[jei-probe]`) on every frame — roughly 60 lines/second, dragging the render thread into synchronous disk IO and flooding `latest.log`. Removed; layout diagnostics are now covered by `[jei-layout]` (one line, only when the JEI state changes or on first open).
- **Fixed (multiplayer safety):** the label-request and label-pull handlers now validate the distance between the player and the target manager (64 blocks). Previously a client could name any coordinate to read label data from a distant manager's disk, and "pull labels" would write the result into the requester's own label gun, bypassing the vanilla 4–6 block interaction range. Pulling is also rate-limited to one call per 250 ms per player: it compiles the program on the server thread, so looping packets could amplify it into CPU load.
- **Fixed (silent feature failure):** the slot-calibration adaptive sampler's interval integer-overflowed into a negative value after roughly one minute of an idle screen, which silently stopped sampling with no log line. Now computed with a long shift and clamped to the cap.
- **Fixed (memory and save cross-talk):** the calibration session table is static and was never cleared when the server stopped, and every session also held an unused server reference. Repeatedly leaving and entering a single-player world kept the whole server object alive, and a stale session's sampling baseline could leak into the next world. The table is now cleared on server stop.
- **Fixed (robustness):** label reply decoding now caps the entry count (it may not exceed the remaining readable bytes). A malformed packet could previously make the client allocate an oversized array and OOM.
- **Fixed (interaction):** right-clicking inside the code editor did nothing — the check used the middle button (`button == 2`) while the comment said "right click". Both buttons now open the clipboard menu.
- **Improved (performance):** the slot-capability query no longer performs capability discovery twice over the same faces (each pass resolves cable networks), and per-slot contents are read in one pass instead of two; that request's log dropped to debug.
- **Improved:** the resource display-name cache is capped (4096), so it no longer grows without bound over long play sessions.
- **Fixed (feature parity across lines):** fluid entries on the 26.1.2 line were text-only, because 26.1 removed `getStillTexture()` / `getTintColor()` from `IClientFluidTypeExtensions`. They now take the still texture and tint from 26.1's model-driven API (`ModelManager → FluidStateModelSet → FluidModel.stillMaterial()`, the same path JEI 29.47 uses), matching 1.21.1 / 1.20.1. Fluids without a registered model fall back to their bucket icon, and only then to text. Also fixed the other two lines losing the localised name (showing the raw registry id) in the fallback branch.
- **Internal:** the `sfm` dependency floor is tightened from `[4.30,)` to `[4.34,)` to match the current SFM release. 4.30–4.33 were never tested with this addon; allowing them only defers the failure to a `NoSuchMethodError` at runtime.
- **Tests:** all three builds pass (302 / 288 / 289 cases, 0 failures), together with the three-way parity, language-key and resource-integrity checks.

## 0.10.0 · 2026-10-23

## 中文

- **修复（稳定性）**：代码编辑器的行号栏与正文两处裁剪区改为 `try/finally` 释放。此前若绘制中途抛异常，裁剪矩形会泄漏到整个屏幕——玩家看到游戏画面被裁成编辑器的一小块，只能重启客户端。TPS 设置界面的说明文字区域存在同样问题，一并修复。
- **修复（多人服务器安全）**：槽位校准会话现在校验发起者与目标方块的距离（64 格，与槽位能力查询一致）。此前客户端可指定任意坐标，让服务端持续扫描远处容器的内容变化。
- **修复（性能）**：空程序的源码生成结果不再每刻重算（原先用空字符串当缓存哨兵，空程序每次调用都会重新序列化）。
- **改进**：TPS 设置界面的说明文字改为自动换行，窄窗口不再横向溢出。
- **改进**：`minecraft` 依赖下限收紧为 26.1.2，与 `neoforge` 依赖范围语义对齐，避免 MC 26.1 玩家看到指向错误方向的报错。
- **内部**：删除 4 个零引用的私有方法；停用联动功能中每帧空转的视图动画调用；联动诊断日志降为 debug。
- **测试**：新增裁剪区释放护栏与服务端入参校验护栏；三版一致性、语言 key、资源完整性检查脚本随仓库提供。

## English

- **Fixed (stability):** both scissor regions in the code editor (gutter and body) now release in `try/finally`. Previously an exception mid-draw left the clip rect applied to the whole screen — the game appeared cropped to a corner of the editor and required a client restart. The TPS settings screen had the same flaw and is fixed too.
- **Fixed (multiplayer safety):** slot-calibration sessions now validate the distance between the player and the target block (64 blocks, matching the slot-capability query). Previously a client could name any coordinate and make the server keep scanning a distant container for content changes.
- **Fixed (performance):** the generated source for an empty program is no longer recomputed every tick (an empty string served as the cache sentinel, so empty programs re-serialised on every call).
- **Improved:** the TPS settings description text now wraps, so it no longer overflows horizontally in narrow windows.
- **Improved:** the `minecraft` dependency lower bound is tightened to 26.1.2 to match the `neoforge` range, so MC 26.1 players no longer receive a misleading error.
- **Internal:** removed four unreferenced private methods; disabled the per-frame no-op view-animation call left behind by the paused link feature; link diagnostics downgraded to debug.
- **Tests:** added guards for scissor release and server payload validation; parity, language-key and resource checks ship with the repo.

## 0.9.1 · 2026-10-19 · Minecraft 26.1.2 / NeoForge 26.1.2.72（新增支持线）

本仓库是 26.1.2 线。1.20.1（Forge）与 1.21.1（NeoForge）两线在各自仓库继续维护，功能与本线保持一致。

## 中文

- **新增支持**：Minecraft 26.1.2 / NeoForge 26.1.2.72，与 Super Factory Manager 4.34.0（26.1.2 构建）配套。
- **渲染**：迁移到 26.1 的两阶段 GUI 绘制模型（`GuiGraphics` → `GuiGraphicsExtractor`），6 个界面与 2 个控件的绘制入口、文字/物品/贴图绘制全部对齐新 API，布局、配色、业务规则均不变。
- **输入**：鼠标/键盘/字符事件改为 26.1 的事件对象（`MouseButtonEvent` / `KeyEvent` / `CharacterEvent`），快捷键、拖拽、滚轮、双击行为不变。
- **能力查询**：迁移到 26.1 的 `ResourceHandler` 能力系统，槽位枚举与内容读取逻辑不变。
- **移出**：Mekanism 化学图标分支（26.1.2 无 Mekanism 发布）。SFM 的 `chemical`/`gas` 等资源类型仍可正常枚举与生成。
- **已知变化**：流体条目不再显示贴图（26.1.2 移除了 `getStillTexture`/`getTintColor`/`Fluid.getFluidType`，MC 与 NeoForge 两侧均无替代），仍可搜索、选取并生成正确的 SFML。

## English

- **New support line:** Minecraft 26.1.2 / NeoForge 26.1.2.72, paired with Super Factory Manager 4.34.0 (26.1.2 build).
- **Rendering:** moved to the 26.1 two-stage GUI model (`GuiGraphics` → `GuiGraphicsExtractor`); all draw entry points and text/item/sprite drawing align with the new API. Layout, colours and behaviour are unchanged.
- **Input:** mouse/keyboard/character events now use 26.1 event records (`MouseButtonEvent` / `KeyEvent` / `CharacterEvent`); shortcuts, dragging, scrolling and double-click behave the same.
- **Capabilities:** moved to the 26.1 `ResourceHandler` capability system; slot enumeration and content reads are unchanged.
- **Removed:** the Mekanism chemical-icon branch (no Mekanism release exists for 26.1.2). SFM's `chemical`/`gas` resource types still enumerate and generate normally.
- **Known change:** fluid entries no longer show a texture (26.1.2 removed `getStillTexture`/`getTintColor`/`Fluid.getFluidType` with no replacement on either side). They are still searchable, selectable, and generate correct SFML.

## 0.9.1 · 2026-10-02

## 中文

- **性能**：开箱卡顿根治——布局缓存与编辑器存档全部后台读写，资源目录进世界后台预热，首次打开编辑器不再停顿。
- **代码编辑器**：新增关键词悬停文档（中英说明+示例）、右键菜单（全选/复制/剪切/粘贴）、三击选行、光标词高亮。
- **默认代码视图**：首次打开为「积木 60% / 代码 40%」分屏，F6 展开/收起不丢代码，偏好按管理器记忆。
- **日志**：开箱簿记默认静默，新增「校准详细日志」排障开关，异常带堆栈，启动行显示版本号。

## English

- **Performance:** chest-opening stutter fixed — layout cache and editor saves run fully in the background, and the resource catalog prebuilds after joining a world, so the editor opens instantly.
- **Code editor:** hover docs for SFML keywords with bilingual examples, right-click menu (select all / copy / cut / paste), triple-click line select, and word-occurrence highlight.
- **Default code view:** first use opens a 60% blocks / 40% code split; F6 toggles code without losing edits; preferences remembered per manager.
- **Logs:** calibration bookkeeping is silent by default, a new verbose toggle helps debugging, errors now log stack traces, and the startup line shows the version.

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
