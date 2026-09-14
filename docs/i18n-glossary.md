# i18n 术语表 / Glossary

界面与文档统一用词。新增文案先查此表；发现不一致请改代码而不是另造词。
UI and docs must use these terms consistently. Check here before writing new copy.

| 中文 | English | 说明 / Notes |
|---|---|---|
| 标签 | label | 标签枪绑定的名称，参与程序匹配。**用户自命名内容，永不翻译** |
| 资源标签 | resource tag | 物品/流体的分类标签（`#minecraft:ingot`） |
| 积木 | block | 编辑器里的一条语句 |
| 任务 | task | 一张触发器卡（用户视角的"任务"） |
| 触发器 | trigger | 程序执行入口（定时 / 红石脉冲） |
| 取出 | take (input) | 从标签方块取出进暂存 |
| 放入 | put (output) | 从暂存放入标签方块 |
| 清空取出记录 | forget | SFML `forget`：清掉本轮取出记录 |
| 条件 | condition | `if` 判断 |
| 满足 […] 时 | when […] matches | 主判断行句式 |
| 或满足 […] 时 | or when […] matches | else-if 行句式 |
| 都不满足时 | when none match | else 行句式 |
| 中有 | has / contains | has 条件动词（勿用动作词「取出」） |
| 至少/多于/正好/最多/不足 | at least / more than / exactly / at most / fewer than | 比较符自然词 |
| 侧面 | side | 方块朝向面（每一面 / 的默认面） |
| 槽位 | slot | 容器槽位编号 |
| 一键模板 | Quick Templates | 内置整卡模板 |
| 我的模板 | My Templates | 用户保存的模板 |
| 智造编辑 | Visual Edit | 管理器界面的可视化编辑按钮 |
| 拉取标签 | Pull Labels | 从管理器拉取标签到标签枪 |

## 键命名约定 / Key conventions

- `blocks.cmp_*`：比较符**句子用词**（多于 / at least，无符号）。
- `ed.cmp_*`：比较符**菜单标签**（多于（>） / more than (>)，带符号）——两套并存是刻意的，格式不同勿合并。
- `%s` 占位符数量中英必须一致（LangCoverageTest 强制）。

## 检查工具 / Guardrails

- `HardcodedChineseTest`：源码硬编码中文扫描（Loc 默认参数/配置注释/`// i18n-ok` 豁免）。
- `LangCoverageTest`：键双向覆盖 + 中英键集相等 + `%s` 占位符一致 + en 无 CJK。
- `ExampleProgramsTest.bothLanguageVariantsCompile`：示例中英两版都过真编译器。
