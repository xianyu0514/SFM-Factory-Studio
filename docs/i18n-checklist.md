# i18n 验收清单 / i18n Acceptance Checklist

发版前逐项过。任何一项不过不发版。

## 自动化（每次构建强制）

- [ ] `LangCoverageTest`：代码键 ↔ 中英语言文件双向覆盖、中英键集相等
- [ ] `LangCoverageTest.placeholderCountsMatchBetweenZhAndEn`：`%s` 数量一致
- [ ] `LangCoverageTest.englishValuesContainNoChinese`：en_us 无 CJK 残留
- [ ] `HardcodedChineseTest`：源码无绕过翻译系统的硬编码中文
- [ ] `ExampleProgramsTest.bothLanguageVariantsCompile`：示例中英两版过真编译器

## 人工英文验收（切游戏语言到 English，逐屏过）

- [ ] 新建程序：引导卡、积木库全部分组与条目、新建任务/插入积木
- [ ] 判断编辑：主句、比较符菜单、侧面/槽位/资源选择器标题
- [ ] 示例：帮助页四按钮标题不截断错位、载入后注释/标签/程序名为英文、能编译保存
- [ ] 诊断与修复：问题面板消息、修复按钮、定位路径
- [ ] 卡片成本角标 tooltip（绑定约…句式已英文）
- [ ] 模板：保存/删除/空态提示
- [ ] 保存退出重进：草稿恢复提示
- [ ] 管理器界面：智造编辑 / 拉取标签
- [ ] 不同窗口大小 + GUI 缩放（英文更长，防溢出）

## 双版本同步（1.21.1 与 1.20.1 各查一遍）

- [ ] 共有功能的 lang 键两边一致（历史上成本提示只修了一个版本）
- [ ] README / README_en 的安装文件名与发布版本一致
- [ ] 最终 jar 内 `lang/en_us.json` 无 CJK（解包抽查）
