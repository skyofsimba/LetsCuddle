# 开发说明

面向接手维护的人。版本历史见 [`../CHANGELOG.md`](../CHANGELOG.md)。

## 环境

| 项 | 值 |
|---|---|
| Minecraft / Forge | 1.20.1 / 47.4.29 |
| JDK / Gradle | 17 / 8.8（用仓库自带的 wrapper） |
| 映射 | official 1.20.1 |
| 动画库 | playerAnimator `1.0.2-rc1+1.20`（modid `playeranimator`，**仅客户端**需要） |
| 协议版本 | `CuddleNetwork.PROTOCOL`，当前 `3`；改动 payload 必须 +1 |

## 代码结构

| 类                                                    | 职责                                          |
| ---------------------------------------------------- | ------------------------------------------- |
| `Letscuddle`                                         | 入口：注册物品、创造栏、命令注册、首登发券                       |
| `command/CuddleCommandHandler`                       | 服务端状态机：请求 / 接受 / 拒绝 / 每 tick 锁定 / 过期 / 蹲下取消 |
| `item/CuddleTicketItem`                              | 贴贴券：发起请求、Shift 切换动作、tooltip                 |
| `network/CuddleNetwork` + `CuddleRenderPayload`      | 通道与 S2C 状态包（字段顺序固定）                         |
| `network/CuddleRenderClientHandler`                  | 收包后加锁 / 解锁 / 播动作（仅客户端）                      |
| `animation/AnimationManager` + `CuddleAnimationEnum` | 动作层注册与播放（优先级 42、速度 ×0.5）                    |
| `events/RenderPlayerEventHandler`                    | 取消原版渲染、自绘模型、阴影与头部处理                         |
| `client/CuddleCameraHandler`                         | 第三人称切换、镜头偏移                                 |

## 构建与运行

```bat
gradlew.bat build       :: 产物 build/libs/letscuddle-<版本>.jar（reobf 后，可发布）
gradlew.bat runClient   :: 开发客户端（工作目录 run/）
gradlew.bat runServer   :: 开发服务端（首次启动前把 run/eula.txt 改成 eula=true）
```

## 两个必须知道的坑

1. **Access Transformer 必须写 SRG 名**：`src/main/resources/META-INF/accesstransformer.cfg` 只有一行，
   把 `Camera#setPosition(Vec3)` 开成 public（第三人称镜头偏移用）。ForgeGradle 6 的 AT 阶段跑在 SRG 命名的
   jar 上，所以只能写 `m_90581_`；写官方名 `setPosition` 会静默不生效。改完先 `clean` 再构建；
   若报 `Could not delete output file ... binpatched.jar`，删掉 `build/fg_cache` 重试。
2. **不要删 MixinGradle**：本模组自己没有 mixin，但 `buildscript` 里的 mixingradle 会给 run 任务注入
   `-Dmixin.env.remapRefMap` 等参数；删掉它，开发环境里 playerAnimator 自带的 mixin 会注入失败，
   客户端直接崩在 `PlayerEntityMixin`。

## 不要动的数据与常量

- `assets/letscuddle/player_animation/*.json` 四份动作数据：`name` 是动作 id、`stopTick=61` 与 120 tick 锁定对齐；
  **不要加 `version` 字段**（加了之后 `torso` 不再被当成整模根节点 `body`，贴合会消失）。
- 动作层 `letscuddle:animations` 的优先级 `42` 与 `SpeedModifier(0.5F)`。
- `RenderPlayerEventHandler` 里 PoseStack 的调用顺序，以及 body 根节点那 5 行变换。
- 落点公式（发起者另一侧 1.3 格）、120 tick、60 秒、64 格、5 格。
