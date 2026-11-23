# LetsCuddle 文档索引

## 当前工作（2.0）

| 文档 | 内容 | 谁该读 |
|---|---|---|
| [2.0.0-beta1 实施计划](2.0.0-beta1-plan.md) | 现状、目标、决策、架构、P0–P9 阶段、验收、Git/发布规范 | **新接手的人/AI 先读这份** |
| [动作包格式 v1](animation-pack-format.md) | manifest 字段、目录、合并/覆盖、热重载、校验、安全边界（冻结） | 实现者、包审核者 |
| [动作包制作指南](animation-pack-authoring.md) | 从零做一套动作：工具链、步骤、对齐、调试、发布 | 动作包作者 |

## 历史（1.x）

| 文档 | 内容 |
|---|---|
| [1.x 文档归档](archive/1.x/README.md) | 原设计规格、1.20.1 迁移方案、1.6 开发说明 |

## 仓库根目录

- [README](../README.md) — 玩家向说明、安装、构建
- [CHANGELOG](../CHANGELOG.md) — 版本变更
- [LICENSE](../LICENSE) — MIT

## 第一次接触本项目的 AI：读什么

1. [2.0.0-beta1 计划](2.0.0-beta1-plan.md) 的 §0–§4（执行协议、现状、目标、决策、架构）。
2. 找到你负责的阶段（§7），只读该阶段与它的验收（§8）。
3. 如果要动动作包：读 [格式](animation-pack-format.md)；如果要写作者文档：读 [制作指南](animation-pack-authoring.md)。
4. `doc/archive/1.x/` 只用于查行为语义，**不要当现状**。
