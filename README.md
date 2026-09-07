# SFM Factory Studio — 1.20.1 (Forge) 移植版

本分支是 [SFM Factory Studio](https://github.com/xianyu0514/SFM-Factory-Studio)（默认 `main` 分支，1.21.1 NeoForge）的 **Minecraft 1.20.1 Forge 移植**，功能与 main 分支保持同步：积木编辑器、同屏 SFML 双向编辑、中文诊断、资源标签、NBT 组件筛选、JEI 联动、双语界面（跟随 Minecraft 语言设置）。

**完整文档（中英双语）见 [main 分支 README](https://github.com/xianyu0514/SFM-Factory-Studio#readme)**：
[English](https://github.com/xianyu0514/SFM-Factory-Studio/blob/main/README.md) | [简体中文](https://github.com/xianyu0514/SFM-Factory-Studio/blob/main/README_zh.md)

## 安装（1.20.1 Forge）

1. 安装 [Super Factory Manager 4.34.0](https://www.curseforge.com/minecraft/mc-mods/super-factory-manager)（必须前置）
2. 把 `SFM-Factory-Studio-1.20.1-0.8.jar` 放进 `mods` 文件夹

| 安装方式 | 编辑器 | NBT 标签筛选 | TPS 工具 |
|---|---|---|---|
| 只装客户端 | ✅ | 入口自动隐藏 | ❌ |
| 客户端 + 服务端都装 | ✅ | ✅ 解锁 | ✅ 可选开启 |

## 与 1.21.1 版的已知差异

- 输入框无文字阴影（1.20.1 原版 `EditBox` 不支持，属视觉差异）
- NBT 筛选基于 1.20.1 原生 NBT 树语义（1.21.1 为数据组件），两侧能力等价

## 构建

需要 JDK 21：`./gradlew build`（产物在 `build/libs/`）。
构建前把 SFM 4.34.0 的 jar 放到 `libs/`（见 `gradle.properties`，不入库）。

## 许可

[MPL-2.0](LICENSE) · 基于 [SFM-GUI](https://github.com/MimosaLW/SuperFactoryManager-GUI) 与 [Super Factory Manager](https://github.com/TeamDman/SuperFactoryManager) 改造
