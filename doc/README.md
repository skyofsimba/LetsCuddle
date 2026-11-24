# LetsCuddle 文档索引

## 当前工作（2.0）

| 文档 | 内容 | 谁该读 |
|---|---|---|
| [架构说明](architecture.md) | 模块与构建、运行时状态机、协议、渲染/相机、线程模型、常量表、扩展点与已知限制 | **改代码/做兼容前先读** |
| [IDEA 配置](ide-setup.md) | 一个窗口里同时导入两个 Gradle 工程（各自 Gradle/JDK），构建与 runClient | 在本仓库写代码的人 |
| [2.0.0-beta1 实施计划](2.0.0-beta1-plan.md) | 现状、目标、决策、架构、P0–P9 阶段、验收、Git/发布规范、变更记录 | **新接手的人/AI 先读这份** |
| [动作包格式 v1](animation-pack-format.md) | manifest 字段、目录、合并/覆盖、热重载、校验、安全边界（冻结） | 实现者、包审核者 |
| [动作包制作指南](animation-pack-authoring.md) | 从零做一套动作：工具链、步骤、对齐、调试、发布 | 动作包作者 |

## 历史（1.x）

| 文档 | 内容 |
|---|---|
| [1.x 文档归档](archive/1.x/README.md) | 原设计规格、1.20.1 迁移方案、1.6 开发说明 |

## 仓库根目录

- [README](../README.md) — 玩家向说明、版本矩阵、安装、构建
- [CHANGELOG](../CHANGELOG.md) — 版本变更
- [LICENSE](../LICENSE) — MIT
- [`common/README.md`](../common/README.md) — 共享源码的边界规则
- [`test-packs/`](../test-packs/README.md) — 动作包示例与验收矩阵

## 构建与测试

```bash
cd forge-1.20.1  && ./gradlew build   # JDK 17
cd fabric-1.21.11 && ./gradlew build  # JDK 21
cd forge-1.20.1  && ./gradlew test    # common/ 的单元测试（需要联网拉 JUnit 5）
```

两个工程互相独立（各自的 wrapper、Gradle、JDK、映射），只共享 `common/`。

## 第一次接触本项目的 AI：读什么

1. [2.0.0-beta1 计划](2.0.0-beta1-plan.md) 的 §0–§4（执行协议、现状、目标、决策、架构），再看 §14 的实现状态。
2. 要动动作包：读 [格式](animation-pack-format.md)；要给作者写文档：读 [制作指南](animation-pack-authoring.md)。
3. `doc/archive/1.x/` 只用于查行为语义，**不要当现状**。
