# 1.x 文档归档

> 归档时间：2026-11-23（2.0.0-beta1 规划启动时）
> 对应代码：**1.6.0**（tag `v1.6`）及更早，Minecraft 1.20.1 / Forge 47.x

## 这里是什么

| 文件 | 1.x 时的路径 | 内容 | 现在还有效吗 |
|---|---|---|---|
| [`original-design.md`](original-design.md) | `doc/original-design.md` | 上游 **HugMe**（NeoForge 1.21 / v1.3）的完整设计与实现规格：C1–C17 验收、K1–K20 缺陷、动作数据逐通道表、常量表 | **行为语义仍然有效**；平台细节是 1.21 NeoForge 的，不是 1.20.1 |
| [`port-plan.md`](port-plan.md) | `doc/port-plan.md` | 1.20.1 Forge 的迁移方案与决策记录：PORT-FIX / KEEP / DEFER、平台差异总表、排错手册 | **方案已过时**（v1.4–1.6 已经取代文中很多描述），只作决策考古 |
| [`development.md`](development.md) | `doc/development.md` | 1.6 维护者说明：环境、类表、两个构建坑、不许动的常量 | 对 1.x 代码有效 |

## 为什么归档

2.0 把仓库改成多平台布局（`forge-1.20.1/` + `fabric-1.21.11/` + `common/`），文档也要跟着换结构。
1.x 的这三份文档是**历史资料**，不再随代码更新；2.0 的工作以 [`../../2.0.0-beta1-plan.md`](../../2.0.0-beta1-plan.md) 为准。

## 怎么用

- 想理解「贴贴」的**行为语义、动作数据、历史缺陷**：读 `original-design.md`。
  注意它描述的是 **1.21 NeoForge 原版 HugMe**；1.20.1 移植版的实际行为以 1.x 源码和 `CHANGELOG.md` 为准。
- 想改 **1.20.1 的旧代码**（1.x 维护分支）：读 `development.md` + 实际源码。
  **不要照抄 `port-plan.md` 的代码片段**——它在 v1.4 之后已经过时（例如文中还没有第三人称镜头、蹲下取消、协议 v3）。
- 想找 **1.x 各版本改了什么**：看仓库根目录 [`CHANGELOG.md`](../../../CHANGELOG.md)。

## 1.x 代码基线

- tag：`v1.6`（`gradle.properties` 里是 `1.6.0`；历史 tag 还有 `v1.5`、`v1.5.1`）
- 远程：<https://github.com/skyofsimba/LetsCuddle>
- 2.0 开工时会从该基线拉 `release/1.x` 维护分支；1.x 只修 bug，不再加功能。

## 链接修正

`development.md` 里指向仓库根 `CHANGELOG.md` 的相对路径，已从 `../CHANGELOG.md` 改为 `../../../CHANGELOG.md`；
`original-design.md` / `port-plan.md` 之间的相对引用没有变化（仍在同一目录）。
