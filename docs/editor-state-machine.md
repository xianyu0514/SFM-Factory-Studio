# 编辑器状态机说明

> 本文档是 `BlockEditorScreen` 双向同步机制的**权威说明**。改任何带
> `codeTextEdited` / `codeAwaitingValidation` / `blocksNewerThanCode` /
> `lastModelSfml` / `fitted` / `settingCodeFromModel` 的代码前必读。
> 2026-09-04 因两起生产 bug（标签丢失、视角被抢）而补写。

## 一、谁拥有权威（最重要的一件事）

编辑器里同时存在两份"程序"：

| 副本 | 存放处 | 什么时候是最新事实 |
| --- | --- | --- |
| **模型** | `program`（积木树） | 积木操作、选择器回调、模板插入之后 |
| **代码** | `codeEditor.value()`（源码窗文本） | 玩家在代码窗打字之后（`codeTextEdited == true` 期间） |

**权威切换规则（唯一规则）：**

- `codeTextEdited == false` → **模型权威**。代码窗内容 = `generated()`，
  任何模型变化都要推到代码窗。
- `codeTextEdited == true` → **代码权威**。玩家正在打字，模型侧不得覆盖
  代码窗；代码窗在 `codeValidateDelay`（8 刻防抖）后经 `applyCodeText`
  回写模型——成功则代码继续权威（`codeTextEdited` 清零），失败则保留
  双方原样。

`applyCodeText` 成功 = 代码交回权威；`pushUndo` 系操作 = 模型夺回权威。

## 二、标志语义与翻转点

| 标志 | 含义 | 谁置位 | 谁清零 |
| --- | --- | --- | --- |
| `codeTextEdited` | 代码窗有未提交的手输内容（代码权威） | `onCodeTextChanged`（玩家打字）、init 回显且窗文本≠generated、restoreDraft | `applyCodeText` 成功、tick 模型→代码同步、`setValueFromModel` 路径 |
| `codeAwaitingValidation` | 有代码等待防抖校验；为 true 时 tick 模型→代码同步被暂停 | `onCodeTextChanged`、`importFailed`、restoreDraft（解析失败） | `applyCodeText`（成功或失败都清） |
| `blocksNewerThanCode` | 模型被积木侧改过、代码窗还没跟上。**不依赖 lastModelSfml 比较的显式信号** | `pushUndo`、`replaceProgramPreservingLayout`（undo/redo） | tick 模型→代码同步执行时、`applyCodeText` 成功 |
| `lastModelSfml` | 上次同步到代码窗的模型序列化文本（变化检测基线） | 每次模型→代码同步后、init、save 成功 | ——（只赋值不清零） |
| `settingCodeFromModel` | 正在由模型写入代码窗（抑制 `onCodeTextChanged` 误触发） | 同步写入前 true，写完立刻 false | ——（同步块内自洽） |
| `fitted` | 画布需要自动适配（**仅两种合法来源：屏幕首次打开、玩家点「适配」按钮**） | 声明处 false、适配按钮 | render 里 `fitContent()` 后 true |

## 三、已知的危险路径（历史事故地图）

### 1. `init()` 会因 setScreen 往返而重跑

Minecraft 每次把屏幕设为当前屏都调 `init()`。标签选择器 / NBT 选择器 /
JEI 全屏都是**独立 Screen**，选完返回 = 编辑器重新 init。

- **事故 1（已修）**：init 回显曾无条件取 `codeEditor.value()` 并在文本
  ≠ generated 时置 `codeTextEdited = true`——选择器刚写进模型的条件被
  过期旧文本"抢走权威"，保存时整体丢失。**修法**：init 回显分支改为
  `codeTextEdited == false ? generated() : codeEditor.value()`。
- **事故 2（已修）**：init 曾置 `fitted = false`——从选择器返回视角被
  强制拉回适配。**修法**：init 不再碰 fitted。fitContent 的合法触发只剩
  首次打开和「适配」按钮。

**规则：任何"去别的 Screen 再回来"的新功能，返回路径不得重置视角/权威
状态。**

### 2. `lastModelSfml` 会被 init 刷新

init 末尾 `lastModelSfml = generated()`。如果只靠"模型变了"（比较基线）
检测积木侧编辑，init 重跑会把基线洗掉导致漏检——这就是
`blocksNewerThanCode` 存在的原因。**新的积木侧编辑入口必须调用
`pushUndo()`（它置此标志），不要自己直接改模型。**

### 3. tick 同步的三个前置条件

`tick()` 里的模型→代码同步要求：`codeValidateDelay <= 0`（没有打字防抖
在途）且 `!codeAwaitingValidation`（没有待校验代码）。在这些条件下同步
被跳过是**故意的**（不能覆盖玩家正在输入的内容）；跳过期间积木侧编辑由
`blocksNewerThanCode` 兜底，等条件恢复后下一 tick 补同步。

### 4. 卡片宽度可变：渲染必须问布局

卡片宽度随备选资源数量增长（`EditorLayout.cardWidth`）。渲染器**禁止**
用常量 `CARD_W` 推导语句区宽度/命中框/可见性——必须用
`layout.bodyWidthOf(body)`（2026-09-04 修复的"放积木延迟出现"即此）。
新增任何渲染代码同样禁止写死宽度。

## 四、调试断言日志

`BlockEditorScreen.DEBUG_STATE`（常量，默认 false；排查问题时改 true
并重编译）。开启后每次 `tick()` 与 `init()` 若检测到标志组合异常会往
日志打一行 `[sfmjimu-state]`：

- `codeAuthoritative but blocksNewer` —— 代码权威期间积木侧又编辑了
  （合法但值得留意是否漏同步）
- `awaitingValidation > 40 ticks` —— 防抖悬停过久（校验链可能卡死）
- `stale codeText but model unchanged` —— 代码权威但模型与代码序列化
  相等（权威切换可能丢失）

遇到"积木/代码不同步""保存后内容回退"一类问题：先开
`DEBUG_STATE` 复现，把日志行贴出来对照第二节即可定位是哪个标志没翻对。

## 五、修改清单（checklist）

新增功能动到以下任何一点时，逐条自检：

1. 改模型 → 走 `pushUndo()`（自动置 `blocksNewerThanCode`、失效缓存）。
2. 选择器/子屏幕返回 → init 不得重置 `fitted`，回显必须尊重
   `codeTextEdited`。
3. 从代码侧回写模型 → 只能通过 `applyCodeText`，成功后清
   `codeTextEdited`/`codeAwaitingValidation`/`blocksNewerThanCode`。
4. 渲染宽度/命中框 → `layout.bodyWidthOf` / `c.w()`，禁止 `CARD_W` 字面量。
5. 接受外部数据（选择器/手输/JEI 拖入）→ 先过 `SfmlSyntax` 白名单
   （见 docs 同目录说明与该类 javadoc）。
