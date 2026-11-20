# LetsCuddle（开发说明）

面向开发者/维护者的工程说明。玩法、安装、致谢等面向用户的文档在仓库根目录的 [`../README.md`](../README.md)，
更新日志见 [`../CHANGELOG.md`](../CHANGELOG.md)。

## 版本矩阵

| 项 | 值 |
|---|---|
| Minecraft | 1.20.1 |
| Forge | 47.4.29 |
| ForgeGradle | 6.0.x（`[6.0.16,6.2)`） |
| Gradle | 8.8（wrapper 已随仓库提供，Windows 用 `gradlew.bat`） |
| JDK | 17（toolchain） |
| 映射 | official / 1.20.1 |
| 依赖 | `dev.kosmx.player-anim:player-animation-lib-forge:1.0.2-rc1+1.20`（modid `playeranimator`，仅客户端） |
| 协议版本 | `3`（`HugNetwork#PROTOCOL`，改动 payload 必须 +1） |

## 目录

```
src/main/java/xyz/skyofsimba/
├── Letscuddle.java                 入口：注册物品、创造栏、命令注册、首登发券
├── animation/AnimationManager      动作层 letscuddle:animations（优先级 42、SpeedModifier 0.5）
├── animation/HugAnimationEnum      动作定义（稳定字符串 id + 显示名）
├── client/HugCameraHandler         第三人称切换（含尊重玩家 F5）
├── command/HugCommandHandler       服务端状态机：请求/接受/拒绝/锁定/过期/蹲下取消
├── events/RenderPlayerEventHandler 渲染接管、阴影处理、头部可见性
├── item/HugTicketItem              抱抱券：发起请求、Shift 切换动作
└── network/                        SimpleChannel 通道 + S2C 状态包
```

## 构建与运行

```bat
gradlew.bat build        :: 产物 build/libs/letscuddle-<版本>.jar（reobf 后，可发布）
gradlew.bat runClient    :: 开发客户端（run/）
gradlew.bat runClient2   :: 第二个开发客户端（run2/，用户名 Dev2），双人测试用
gradlew.bat runServer    :: 开发服务端（run/，需先把 run/eula.txt 改成 eula=true）
```

双人测试两条路：`runServer` + 两个客户端连 `localhost`；或 `runClient` + `runClient2` 其中一方「对局域网开放」。
建议第三个观察者（或第三人称）确认 64 格广播与贴合效果。

## 开发环境注意

**Access Transformer 用 SRG 名**：`src/main/resources/META-INF/accesstransformer.cfg` 里只有一行，
把 `Camera#setPosition(Vec3)` 打开成 public（第三人称镜头偏移用）。ForgeGradle 6 的 AT 阶段跑在
**SRG 命名**的注入 jar 上，所以这一行必须写 SRG 名 `m_90581_`，写官方名 `setPosition` 会静默不生效；
打出来的 jar 里也带这份 AT，生产环境（同样是 SRG 命名）能正常应用。改完 AT 后首次构建会重新生成
AT 过的 Forge 工件（约 1 分钟）；若报「Could not delete output file ... binpatched.jar」，删掉
`build/fg_cache` 再构建即可。

**MixinGradle 必须保留**（`buildscript` + `apply plugin: 'org.spongepowered.mixin'`）。
本模组自身没有 mixin，但这个插件会给 run 任务注入
`-Dmixin.env.remapRefMap=true` 与 `-Dmixin.env.refMapRemappingFile=build/createSrgToMcp/output.srg`；
少了它们，开发环境里 **playerAnimator 自带的 mixin** 会因为 refmap 仍是 SRG 名（`m_8119_`）而注入失败，
客户端直接崩在 `PlayerEntityMixin`。因此本工程**不声明** `mixin {}` 配置块、也不打包 `*.mixins.json`
（打包产物里没有 `MixinConfigs` 清单项）。

## 不要动的数据/常量

- `assets/letscuddle/player_animation/*.json` 四份动作数据**字节级**不要改：`name` 字段是动作 id、
  `stopTick=61` 与 120 tick 锁定对齐、缺省 `version` 让 `torso` 被当成整模根节点 `body`（贴合靠它）；
- `SpeedModifier(0.5F)` 与层优先级 `42`；
- `RenderPlayerEventHandler` 里 PoseStack 的调用顺序，以及 body 根节点那 5 行变换；
- 落点公式（发起者另一侧 1.3 格）、120 tick、60 秒、64 格、5 格等常量。
