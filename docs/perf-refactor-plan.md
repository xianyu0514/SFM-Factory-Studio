# BlockEditorScreen 性能与可维护性重构计划

> 2026-09-02 制定。目标：**最大性能收益，且不改变任何现有功能与交互**。
> 主文件现状：`client/blocks/BlockEditorScreen.java` 共 5152 行，集状态、持久化、布局、
> 渲染、输入、弹窗、诊断调度于一身。

---

## 一、现状诊断（按每帧 / 每次交互的实际成本排序）

### A. 热路径性能问题

| # | 问题 | 位置 | 成本 |
|---|------|------|------|
| A1 | **拖拽帧全量重排**：拖动卡片/积木时每帧 `layoutDirty=true` → `relayout()` 清空并重建全部 `rowRect/measuredHeights/gaps/bodies/addRowPos`（每积木 ~4 次 map 操作 + record 分配） | `mouseDragged` 1681 → `layoutPass` 1208-1246 | O(全部积木)/帧，大程序拖拽掉帧主因 |
| A2 | **问题面板每帧折行**：`wrapText` 对每条 issue 每帧执行，内部 `font.width(current+c)` 逐字符建串，单条 O(L²)；issue 不变也重算 | `renderIssues` 1094-1098, `wrapText` 1022-1035 | 面板打开时持续 GC 压力 |
| A3 | **诊断每 5 tick 无条件重算**：程序和标签都没变也跑 `ProgramDiagnostics.check`（全程序遍历）+ 排序 + blockSeverity 重建 | `tick` 498-501 → `refreshIssues` 912 | 4 次/秒全遍历，空闲时纯浪费 |
| A4 | **每帧 Hit/Lambda 分配**：`hits.clear()` 后每个可见字段/图标/胶囊 new 一个 `Hit` record + 捕获变量的 lambda（500 积木 × ~5 字段 × 60fps ≈ 15 万对象/秒） | `render` 2621 起全部 drawField/drawIcon/drawNum | 持续 minor GC |
| A5 | **线性空间查询**：`nearestGap` 每拖拽帧遍历全部缝隙；`liveBandSelect` 每框选帧遍历全部行 + 全部卡；`contentRectOf(Trigger)` 线性扫 `cardLayouts` | 1849-1860, 1804-1822, 932-942 | O(n)/帧，n 大时拖拽/框选卡顿 |
| A6 | **诊断角标无视口判断**：`blockSeverity` 循环画角标前没有 `contentVisible` 检查，屏外积木也发 draw call（被 scissor 裁掉但已提交） | 2706-2717 | 小，白费 draw call |
| A7 | **工具栏每帧 stream 计数**：错误/提醒数每帧 filter+count 两次 | `renderToolbar` 2914-2915, `renderIssues` 1052 | 小，可随 issuesCache 缓存 |
| A8 | **首次打开卡顿**：oracle 两张大 Map 在第一次诊断时同步构建（ATM10 数万条目） | `buildKindOracle` 997-1019 | 一次性 10-50ms 单帧卡顿 |

### B. 已有的正确优化（保持，不要退化）

- 卡片/行两级视口裁剪 `contentVisible`（`render` 2673-2677、`renderBody` 3186-3203）——命中框只随可见元素注册，语义正确；
- 拖动中跳过 O(n²) 避让（`layoutPass` 1245、`relayout` 1201）；
- 点阵网格随 zoom 调步长（2649-2656）；`generated()`/模板/资源条目均有缓存；
- oracle 静态会话级缓存、`resourceEntryCache` 缓存 miss。

### C. 可维护性问题

1. **对象身份耦合**：`rowRect/measuredHeights/expanded/cardPos/blockSeverity/bodyIndices/addRowPos` 全是
   `IdentityHashMap`。任何模型重建（撤销、代码同步、草稿恢复）都换整套对象 → 撤销要靠"指纹快照搬坐标"补救（`undo` 551-568），
   `expanded` 在代码同步时被整体清空（`applyCodeText` 727），`blockSeverity` 在两次刷新间指向死对象。
2. **失效逻辑散落 40+ 处**：`pushUndo(); generatedCache=""; previewCache=null; layoutDirty=true;` 的组合以不同子集复制粘贴在全文件，漏写一项就是缓存不同步 bug。
3. **死代码**：`previewCache`（只写 null 从不读）、`ifHeight`（3245）、`registerBarGripAt`（4043）。
4. **单文件 5152 行**：布局纯逻辑（本可脱离 MC 单测）和渲染/输入耦合；`ConditionPopup` 内部类 260 行。

---

## 二、目标架构

包 `io.github.xianynomial.sfmfactorystudio.client.blocks`（布局与空间索引放 `model` 子包，保持 MC-free 可单测）：

```
BlockEditorScreen          薄壳：Screen 生命周期、init/tick/render 委派、super.render 调控件
├─ BlockTexts              ~80 个 Loc 常量
├─ BlockTheme              颜色 + 尺寸常量（含画布/卡片度量）
├─ EditorState             程序 + 版本号 + 撤销栈 + 脏标记 + 草稿 + 标签缓存 + 诊断缓存
│    └─ mutate(受影响触发器, Runnable)   唯一编辑入口：pushUndo + 失效 + 版本号自增
├─ EditorLayout   (model)  两阶段布局；全部矩形/缝隙/高度，按稳定 id 键控；每卡缓存增量重排
├─ SpatialIndex   (model)  均匀网格（cell≈256 内容像素）：行/卡/缝隙矩形，点查/矩形查/最近缝隙
├─ IssuesService           版本门控的诊断调度 + 折行缓存 + 严重度映射（按 id）
├─ EditorRenderer          画布/卡片/积木/调色板/工具栏/问题面板/浮层渲染 + draw 辅助
├─ EditorInput             mouseClicked/Dragged/Released/Scrolled/keyPressed 状态机
├─ HitRegistry             无 lambda 的命中框（kind + id + 回调枚举），复用槽位数组
├─ PopupHost               popup 字段 + 落位收敛；ConditionPopup 迁入 BlockFieldPopups
├─ CardPositionStore       layouts.json 读写 + 指纹匹配（格式不变）
└─ DraftStore              drafts.json 读写
```

**稳定编号（id）设计**：`Trigger`/`Statement` 增加 `public final long id`，构造时取自
`AtomicLong`；`copy()` 分配新 id；**不写入 SFML、不写入任何磁盘文件**（磁盘仍用现有
`triggerKey` 指纹，跨会话稳定）。id 的作用：
- 内存中所有 UI 映射从对象身份改为 `HashMap<Long,·>`，模型重建后旧 id 自然失效（不会指向错对象）；
- 模块间只传 long，不传对象图 → 布局/渲染/输入解耦的前提；
- 每卡布局缓存、空间索引、未来差异历史（路线图第 6 项）都以 id 为键。

---

## 三、分阶段实施（每阶段独立编译 + 78 项测试全绿 + 交付 jar）

### P0 护栏与速赢（约半天）
1. 删除死代码：`previewCache`、`ifHeight`、`registerBar gripAt` 及其全部赋值点。
2. **折行缓存**：`wrapText` 结果按（issuesCache 引用, innerW）缓存，issues 或宽度变化才重算 → 直接消灭 A2。
3. 工具栏/面板的错误·提醒计数随 issuesCache 一起缓存 → 消灭 A7。
4. 角标循环加 `contentVisible` → 消灭 A6。
5. 新增 headless 基准测试：合成 500 积木程序跑 `relayout`，记录毫秒数（后续各阶段的回归护栏）。
- **风险**：极低（纯局部改动）。**收益**：面板打开时每帧成本归零。

### P1 稳定 id + EditorState（约 1 天）
1. `Trigger`/`Statement` 加 `id`；`SfmlToBlocks`/`copy()` 自然获得新 id。
2. 建 `EditorState`：集中 program、undoStack、dirty、generatedCache、issuesCache、版本号
   `programVersion`/`labelsVersion`；提供 `mutate(...)` 漏斗统一"pushUndo + 清缓存 + 置 layoutDirty + 版本自增"。
   全文件 40+ 处散落失效序列逐步收拢进来。
3. `rowRect/measuredHeights/expanded/cardPos/blockSeverity` 改 `HashMap<Long,·>`；
   `bodyIndices/addRowPos` 改为（所属语句 id + 分支序号）复合键。
4. 撤销/代码同步的**指纹迁移逻辑原样保留**（`undo` 551-568、`applyCodeText` 705-721 不动语义），
   额外用语句结构指纹迁移 `expanded`（可选增强：代码同步不再全部收起）。
- **风险**：中（触及所有映射使用点）。**验证**：现有 19 个测试文件全绿 + 手测撤销/代码同步/拖拽。
- **收益**：维护性为主（失效收拢、身份解耦），并为 P2/P3 铺路。

### P2 EditorLayout 拆分 + 每卡增量重排（约 1-2 天）
1. `relayout/layoutPass/layoutCard/layoutBody/layoutStatement/separateOverlaps/ensureCardPositions`
   及坐标变换 `sX/sY/ctX/ctY/contentVisible/fitContent` 整体迁入 `model/EditorLayout`（无 GuiGraphics/Font 依赖——现布局只用固定度量，天然纯函数）。
2. **每卡布局缓存**：按触发器 id 缓存该卡的行矩形/缝隙/高度列表；`mutate` 标记受影响的触发器
   （编辑漏斗知道改了哪个 body；拿不到就保守全标），重排时只重算脏卡 + 复跑避让（避让本就只看卡级矩形，卡数少）。
3. 拖动帧路径：只有被拖卡坐标变化 → 单卡矩形更新 + 空间索引局部更新，**不再触碰其他卡的行**。
- **收益**：A1 消灭。拖拽帧成本从 O(全部积木) 降到 O(被拖卡积木 + 卡数)。
- **验证**：新增"全量重排 vs 增量重排输出一致"等价性测试（随机程序，参考 BlockInvariantTests 的 1000 随机做法）。

### P3 SpatialIndex（约 1 天）
1. 均匀网格（cell 256 内容像素）：布局完成后把行 id、卡 id、Gap 引用插入；增量重排时只更新动过的条目。
2. 替换调用点：
   - `nearestGap`（拖拽帧，A5）→ 网格 x 桶 + y 最近查询；
   - `liveBandSelect`（框选帧）→ `queryRect` 一次取候选，再做精确矩形判定（**帽子语义不变**：碰到卡的任意部分即整卡入选）；
   - `contentRectOf(Trigger)`、角标可见性 → O(1)/O(候选) 查询。
3. **点击命中仍走 hits 列表**：命中优先级（"后注册者胜"、K_HEAD 先于字段、字段先于行抓手）是交互契约，
   空间索引只服务拖拽/框选等高频帧查询，不动点击分发语义。
- **验证**：`SpatialIndexTest`——与暴力遍历结果等价（插入/查询/移动/删除）。

### P4 诊断调度（约半天）
1. `refreshIssues` 加版本门控：`programVersion` 与 `labelsVersion` 都没变 → 直接返回（保留 5 tick 节流作为延迟上限）。消灭 A3。
   **规则语义零改动**——只改"何时算"，不改"算什么"（诊断哲学 2026-09-02 拍板项全部保持）。
2. （可选）oracle 预热：打开管理器 GUI 时异步 `buildKindOracle`，消除编辑器首次诊断的单帧卡顿（A8）。
   读的全是不可变注册表数据，客户端线程首次使用前 join 即可。
- **说明**：搜索/标签计算方面——资源/标签选择器在独立 Screen 里、按键入事件过滤，`collectLabels`
  只在标签同步/编辑器打开时跑，均为事件驱动，本就没有每帧成本；真正每 tick 烧 CPU 的只有诊断，故 P4 聚焦于此。

### P5 渲染 / 输入 / 弹窗拆分（约 2-3 天）
1. `BlockTexts`/`BlockTheme` 抽取（机械移动）。
2. `EditorRenderer`：render/renderPalette/renderToolbar/renderIssues/renderActionBar/renderCard/
   renderBody/renderStatement/renderIO*/renderIf/renderCond + 全部 draw 辅助。
3. `HitRegistry`：`Hit` 从 (rect, kind, data, Runnable) 改为 (rect, kind, long id, int aux)，
   点击时分发到 switch——**消灭每帧 lambda/record 分配**（A4）。复用对象池：每帧覆写槽位而非新建。
   JEI ghost drop 每帧注册保留（JEI 需要当前值，量小）。
4. `EditorInput`：mouse/key 状态机迁移；`PopupHost` + `ConditionPopup` 移入 `BlockFieldPopups`。
5. `BlockEditorScreen` 变薄壳。`CardPositionStore`/`DraftStore` 顺手成类。
- **风险**：最大的一步，纯搬家不改逻辑；分小提交（先 Theme/Texts → Renderer → Input → Popup），每步编译+测试+手测。
- **收益**：A4 消灭；文件从 5152 行降到 ~600 行壳 + 8 个职责单一的模块；后续加功能（标签管理中心、流程图）不再互相踩。

### P6（收尾）性能计数器
`Ctrl+Shift+P` 调试浮层：上一帧布局耗时、可见积木数、hits 数、issues 重算次数。用于在游戏内确认各阶段收益（路线图第 7 项的"性能计数器"顺带完成）。

---

## 四、明确不做的事（防功能回归清单）

- **不改**拖拽/框选交互规则（2026-08-31 拍板：点击积木=拖动、K_BODY_SEL 起框、K_HEAD 整卡拖、帽子吞选、Ctrl+A 全选整卡、松手回原位、严禁 `targetBody().addAll` 兜底）。
- **不改**诊断哲学（2026-09-02 拍板：新积木不报错、TAKE_ALL 不提醒、同标签进出不提醒、未绑定标签每标签一条、ERROR 只留给结构问题）。
- **不改** layouts.json / templates.json / drafts.json 磁盘格式（指纹继续做跨会话匹配；id 仅内存）。
- **不改** SFML 生成/解析一字一句（78 项测试里的往返无损是红线）。
- `enableScissor` try/finally 配对、`renderBackground` 空实现、JEI 早查询非零默认尺寸、按钮组右对齐让位——全部原样保留。
- 不引入多线程模型编辑（诊断只做"何时算"的门控；唯一可选异步是只读注册表预热）。

## 五、验证与交付流程（每阶段）

1. `JAVA_TOOL_OPTIONS="-Djava.net.preferIPv6Addresses=true" ./gradlew build`（编译即打包）；
2. `./gradlew test`——现有 78 项 + 本计划新增（布局等价、空间索引、折行缓存、基准护栏）全绿；
3. 产物拷到 `D:\youxi\chaojigui\sfmfactorystudio-1.21.1-0.1.0-all.jar`，旧包备份 `*.before-<阶段>.bak`；
4. 游戏内手测清单：拖卡/拖积木/框选/组拖/撤销/代码双向同步/问题面板定位与修复/JEI 拖放/存模板。

## 六、预期收益汇总

| 场景 | 现状 | 之后 |
|------|------|------|
| 拖动卡片（500 积木） | 每帧全量重排 + 全缝隙扫描 | 单卡重排 + 网格最近缝隙，其他卡零触碰 |
| 问题面板打开空闲 | 每帧 O(L²) 折行 + 每帧 stream 计数 | 零（缓存命中） |
| 空闲诊断 | 4 次/秒全程序遍历 | 仅程序/标签变化后 5 tick 内一次 |
| 每帧分配 | ~数千 Hit/lambda | 池化覆写，接近零 |
| 首次打开 | oracle 同步构建单帧卡顿 | （P4 可选项）后台预热 |
| 代码维护 | 单文件 5152 行、40+ 处失效散写 | 8 个单一职责模块 + 一个 mutate 漏斗 |
