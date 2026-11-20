# Let's Cuddle!（LetsCuddle）

HugMe（NeoForge × 1.21 × v1.3）的 **Minecraft 1.20.1 / Forge** 移植版。

用「抱抱券」右键另一名玩家 → 对方收到带 `[同意] / [拒绝]` 按钮的请求 → 同意后两人被摆成面对面、原地钉住 6 秒，
并用 [playerAnimator](https://modrinth.com/mod/playeranimator) 播放一对互补的拥抱动作（周围 64 格内的玩家都能看到）。

- 原始设计规格：`../doc/original-design.md`
- 迁移方案与缺陷裁决：`../doc/port-plan.md`

## 版本矩阵

| 项 | 值 |
|---|---|
| Minecraft | 1.20.1 |
| Forge | 47.4.29 |
| ForgeGradle | 6.0.x（`[6.0.16,6.2)`） |
| Gradle | 8.8（wrapper 已随仓库提供） |
| JDK | 17（toolchain，foojay 可自动装） |
| 映射 | official / 1.20.1 |
| 依赖 | `dev.kosmx.player-anim:player-animation-lib-forge:1.0.2-rc1+1.20`（modid `playeranimator`，**仅客户端需要**） |
| modid / 包名 | `letscuddle` / `xyz.skyofsimba`（资源命名空间同步为 `assets/letscuddle`、`data/letscuddle`） |
| 许可证 | MIT（原模组 © 2024 TunYuntuwuQWQ，见 `LICENSE`） |

## 构建

```bat
gradlew.bat build
```

产物：`build/libs/letscuddle-1.3.jar`（已 reobf，可直接拖进 `mods/`；`runClient` 用的 dev jar 不能发布）。
首次构建要下载 Forge userdev 并反编译 MC，**10–30 分钟**属正常。

## 开发运行

| 任务 | 用途 |
|---|---|
| `gradlew.bat runClient` | 开发客户端（工作目录 `run/`） |
| `gradlew.bat runClient2` | 第二个开发客户端（`run2/`，用户名 `Dev2`），用于双人验证 |
| `gradlew.bat runServer` | 开发服务器（`run/`）；首次需把 `run/eula.txt` 改成 `eula=true` |
| `gradlew.bat runData` | 数据生成（本模组没有 datagen provider，一般不需要） |

双人验证两条路（都需要至少一个旁观者视角才能确认 64 格广播与第三人称动作）：

1. **服务端 + 两个客户端**：先 `runServer`（`run/server.properties` 里 `online-mode=false`），两个客户端都连 `localhost`；
2. **两个开发客户端**：一个 `runClient`，一个 `runClient2`，其中一个开「对局域网开放」。

## 游戏内

- 物品 `letscuddle:hug_ticket`「抱抱券」：创造模式「工具与实用物品」栏，或按「纸 / 淡蓝染料 / 粉色染料」十字配方合成 4 张；
- 该存档**首次登录**自动发 16 张（判定 `world/playerdata/<uuid>.dat` 是否存在，与原版一致）；
- 命令：`/letscuddle acceptRequest <玩家名>`、`/letscuddle rejectRequest <玩家名>`（聊天栏按钮会自动带上）。

## 与原版的差异（有意为之，详见 `port-plan.md` 第 8 章）

**移植修复**：请求过期从线程池改为 tick 计时；「已接受」消息移到站位校验之后；落点计算除零保护；掉线立刻解锁
并广播结束包（客户端另加 200 tick 兜底）；服务端不再引用客户端类（`DistExecutor`）；动作缺失时只警告不 NPE。

**明确保留**：落点方向与 1.3 格间距、120 tick 锁定、60 秒过期、64 格广播、动作 50% 降速、动作 JSON 字节不变。

**本次身份调整**（相对 `port-plan.md`）：modid/包名/命名空间改为 `letscuddle`、`xyz.skyofsimba`，
命令改为 `/letscuddle`，资源目录相应从 `assets/hugme` 迁到 `assets/letscuddle`（动作 JSON、贴图字节未变）。

## 开发环境注意

本模组自身**不含 mixin**，但 `build.gradle` 仍保留了 MixinGradle 插件（`apply plugin: 'org.spongepowered.mixin'`）：
它的作用不是给我们生成 mixin，而是给 `runClient` / `runServer` 注入
`-Dmixin.env.remapRefMap=true` 与 `-Dmixin.env.refMapRemappingFile=build/createSrgToMcp/output.srg`。
少了这两项，开发环境里 **playerAnimator 自带的 mixin** 会因为 refmap 还是 SRG 名（`m_8119_`）而注入失败，
客户端启动直接崩在 `PlayerEntityMixin`。生产环境（正式 jar）不需要这两个属性。

> 因为模组没有自己的 mixin，`mixin {}` 配置块与 `letscuddle.mixins.json` 都已删除 —— 打包产物里没有
> `MixinConfigs` 清单项，也不会再出现「refmap 读不到」的开发期警告。
