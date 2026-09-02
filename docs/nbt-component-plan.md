# NBT/组件区分传输：SF M fork + 编辑器自动启停 实现计划

> 2026-09-02 制定。目标：`with component "minecraft:xxx"` 语法区分同物品不同组件；
> 服务端装了 fork 自动启用，没装自动隐藏。安全性兜底：SFM 保存本来就在服务端
> 走真实编译器校验——检测失误最坏是"保存被拒+报错"，不会写坏程序。

## 语法设计（v1 定稿）
- `with component "minecraft:enchantments"` —— **组件键存在性匹配**（该组件
  存在且非默认值即命中），覆盖绝大多数场景：附魔、自定义数据、药水内容、
  损伤、附魔书附魔等。
- 可与现有 `with #tag` 自由组合（且/或/非），复用 WithFilter 全套组合逻辑。
- v2（可选，后置）：值匹配 `with component "minecraft:custom_data" ~ "foo"`
  （子串），仅在 v1 落地后按需求加。

## 阶段 1：SFM fork（服务端+客户端同 jar，约 1-1.5 天）
1. **语法与解析**：SFML.g4 加 `withComponent` 叶子规则（ANTLR 重新生成），
   ASTVisitor 分支。
2. **AST**：新类 `WithComponent implements WithClause`；`matchesStack` 里按
   ResourceType 分派——物品走 `ItemStack.getComponents()`，流体走
   `FluidStack.getComponents()`（1.21 两者都有组件），键解析走
   `BuiltInRegistries.DATA_COMPONENT_TYPE`，未知键在编译期报错（不静默）。
3. **往返与补全**：toString 输出新语法（往返无损红线）；intellisense 后置。
4. **能力宣告**：新 optional payload `sfmjimu:sfm_capabilities`
   （codec=字符串列表，如 `["with_component"]`），玩家登录时服务端推送。
   约 20 行，注册为 optional 不影响未装编辑器的客户端。
5. **测试**：往返无损 + 真实传输测试（附魔书/药水分流各一）+ 未知组件键报错。
6. **发布**：fork 版本号 bump（如 4.34.0-nbt1），MPL-2.0 合规（源码公开）。
   保持 fork 改动最小化（一条语法规则+一个类+一个 payload），降低追上游成本。

## 阶段 2：编辑器（纯客户端，约 1 天）
1. **能力接收**：注册 `sfmjimu:sfm_capabilities` 的 playToClient 接收器
   （optional，沿用现有 optional 基建）；静态 `SfmCaps.withComponent` 标志，
   断线/换服时重置。
2. **模型与往返**：`BProgram.WithExpr` 新增 `Component(String componentId)`
   节点；BlocksToSfml 生成 `with component "..."`；SfmlToBlocks 解析 fork 的
   `WithComponent` AST 节点（客户端也装 fork，解析器天然认识新语法）。
3. **UI 按能力显隐**：资源特征编辑菜单新增「按物品组件(NBT)筛选」入口，
   **仅当 withComponent=true 时出现**；弹层=常用组件列表（附魔/自定义数据/
   药水内容/损伤…）+ 手动输入组件 id；选中后生成 Component 节点，摘要显示
   「组件:minecraft:enchantments」。
4. **保存前置校验**：withComponent=false 但程序含 Component 节点时，保存前
   直接拦截并提示「此服务器未安装 NBT 区分支持，请先移除组件条件」——比
   等服务端报错体验好；正常场景服务端校验仍是最终防线。
5. **边角**：能力 on 但客户端是原版 SFM（解析器不认识新语法）→ 导入时解析
   失败进代码模式，加提示「检测到组件条件语法，请更新客户端 SFM」。

## 阶段 3：验证矩阵（必须全过）
| 场景 | 预期 |
|------|------|
| 双端 fork | 菜单出现，语法可用，保存+真实分流成功 |
| 双端原版 SFM | 菜单隐藏，一切如常；手工写语法 → 本地拦截提示 |
| **客户端 fork/编辑器 + 服务端原版 SFM** | 正常连入正常游玩：NBT 入口隐藏，其余全功能；已含组件条件的旧程序可编辑但保存被本地拦截 |
| 服务端 fork + 客户端原版 SFM | 编辑器提示更新客户端 SFM（边角 5） |
| 单人（fork） | 全功能 |
| 服务器没装 SFM | 编辑器入口不可达，模组完全空闲，零影响 |
| fork 服上未用新语法的旧程序 | 行为与原版完全一致（纯增量） |
| 全部 91 项既有测试 | 绿（编辑器默认关闭新入口不影响现行为） |

## 交付物与顺序
1. SFM fork jar（用户装到客户端+服务端，替代原版 SFM）
2. 编辑器 jar（照常装客户端，未遇 fork 服时零变化）
3. 两份 jar 一起交付到工作区根目录（.bak 备份），fork 源码放独立目录。

## 风险与对策
- **追上游成本**：fork 改动刻意最小（一规则一类一包）；SFM 更新时 rebase 面
  积小。
- **payload 未达**（极端时序）：登录推送 + 编辑器打开时视为未知=关闭，
  宁可隐藏不可误开；保存校验兜底。
- **组件键拼错**：编译期报错进问题面板，不做静默忽略。
