# 架构说明

> 面向：要在这个项目上继续开发、做兼容或接 API 的人。
> 配套：[2.0 计划](2.0.0-beta1-plan.md) · [动作包格式](animation-pack-format.md) · [制作指南](animation-pack-authoring.md)
> 状态：2.0.0-beta2。

## 1. 一句话

玩家用「贴贴券」右键另一人 → 对方点同意 → 服务端把接受者摆到发起者另一侧 1.3 格、双方转身、
锁定 6 秒；客户端用动画库播两段互补动作（1.20.1 playerAnimator / 1.21.11 PAL），
附近 64 格的玩家同样能看到。

## 2. 构建矩阵

| 工程 | MC | 构建插件 | Gradle / JDK | 映射 | 元数据 |
|---|---|---|---|---|---|
| `forge-1.20.1` | 1.20.1 | ForgeGradle 6（FG6）`[6.0.16,6.2)` | 8.8 / 17（Gradle 自身可用 21） | `official` | `META-INF/mods.toml` + `pack.mcmeta`(15) |
| `fabric-1.21.11` | 1.21.11 | fabric-loom-remap 1.17.20 | 9.8 / 21（Gradle 自身必须 21） | Yarn `1.21.11+build.6` | `fabric.mod.json` |

- 两个平台是**两个独立 Gradle 工程**：FG6 与 Loom 的 Gradle/JDK 要求不兼容，合成一个多模块工程只会互相拖累。
- `common/` 没有 `build.gradle`，两边用 `srcSets.main.{java,resources}.srcDir` 引入同一份源码。
- `common/` 的约束：不得出现 `net.minecraft.*` / `net.minecraftforge.*` / `net.fabricmc.*`；必须能用 Java 17 编译。

## 3. 目录与分层

```
common/src/main/java/xyz/skyofsimba/core/   纯 Java：常量、数学、数据模型、解析/采样
common/src/main/resources/                  共享资源：lang / model / 贴图 / manifest / anim/raw
forge-1.20.1/src/main/java/xyz/skyofsimba/  平台胶水（服务端 + 少量共享入口）
forge-1.20.1/src/main/java/.../client|events|animation/  1.20.1 客户端专属
fabric-1.21.11/src/main/java/xyz/skyofsimba/            平台胶水（服务端 + 共享）
fabric-1.21.11/src/client/java/xyz/skyofsimba/          1.21.11 客户端专属（split source set）
```

两端的**同名类保持同构**（`CuddleCommandHandler`、`CuddleTicketItem`、`CuddleAnimRegistry`、`CuddleCapsStore`、
`CuddleNetwork`、5 个 payload），方便并排 diff；差异只在平台 API。

## 4. common 的职责

| 类 | 职责 |
|---|---|
| `CuddleConstants` | 所有常量（见 §8） |
| `CuddleIds` | id 校验、`normal`→`letscuddle:normal` 迁移、内容版本/命名空间工具 |
| `CuddlePlacement` | 落点公式、朝向 yaw、`body` 位移到世界坐标的换算 |
| `CuddleCaps` | 客户端能力快照（`id → lock/contentVersion/nameKey/name`），清洗与协商 |
| `CuddleAnimDef` / `CuddleAnimManifest` | manifest 数据模型与解析/校验 |
| `CuddleAnimClip` / `CuddlePose` / `Bone` | `emote` 动画 JSON 的采样（**只服务相机位移**） |
| `CuddleAnimNames` | 内置动作的语言键回退 |
| `Vec3d` | 纯数据三元组 |

## 5. 运行时架构

### 5.1 服务端状态

三张表，全部只在服务端主线程读写：

| 表 | 键 | 内容 | 生命周期 |
|---|---|---|---|
| `REQUESTS` | 有序 `Pair<UUID,UUID>` | 发起者、接受者、过期 tick、动作 id、lockTicks、contentVersion | 接受/拒绝/过期/掉线 |
| `CUDDLES` | 有序 `Pair<UUID,UUID>` | 双方位置、广播名单、剩余 tick、蹲下计数、动作 id/version | 倒计时结束/蹲下/掉线 |
| `CAPS` | `UUID` | 客户端上报的能力 + 加入 tick + 是否已提示超时 | 加入/上报/掉线 |

有序 Pair 是刻意的：A→B 与 B→A 是两个独立请求（1.x 的 K12）。

### 5.2 请求 → 接受

```
startCuddleRequest(sender, receiver, requestedAnimId)
  ├─ 已存在同向请求 → 拒绝
  ├─ 对方 caps 无此动作 / 内容版本不一致 → 回退 letscuddle:normal + 提示（名字来自 caps）
  ├─ lockTicks = min(双方) 夹紧 [20,400]；contentVersion 取发起者侧
  ├─ 登记 REQUESTS（expireAt = 当前 tick + 1200）
  └─ 给对方发可点击消息；发起者收到「已发送」，券 -1

accept（命令）依次校验：
  请求存在且接受者匹配 → 不在贴贴中 → 同维度 → 距离 ≤5 → 落点安全
落点 = sender + 1.3 × normalize(sender − receiver)
落点安全 = 脚下 0.3 半宽 AABB 有碰撞 且 身体 0.1–1.7 无碰撞
→ 传送接受者、双方 lookAt → 才播「已接受」→ 广播 Render(start)
```

### 5.3 锁定 tick

每 tick（`ServerTickEvent` END / `ServerTickEvents.END_SERVER_TICK`）：
1. 双方传送回登记位置（位置被强制的代价见 §9）。
2. 任一方潜行累计 5 tick → 取消（`cancelled=true`）。
3. 倒计时归零 → 正常结束（`cancelled=false`）。
4. caps 超时（100 tick 未上报）→ 发一次提示，按内置动作处理。

### 5.4 网络协议 v4

| # | 方向 | 包 | 字段 |
|---|---|---|---|
| 0 | C2S | Caps | `protocol, format, count, (id, lock, version)×N`，可选尾段 `count, (id, nameKey, name)×N` |
| 1 | C2S | Select | `animId, hand` |
| 2 | S2C | Render | `sender, receiver, done, cancelled, animId, contentVersion` |
| 3 | S2C | Selected | `animId` |
| 4 | S2C | RequestCaps | 空 |

- Forge：`SimpleChannel` 消息 id 0–4，通道协议字符串 `"4"`（1.x 客户端在通道层被拒）。
- Fabric：payload id `letscuddle:{caps,select,render,selected,request_caps}`，没有通道版本，靠 caps 的 `protocol` 判断。
- Caps 的名字尾段是可选的：没有尾段时服务端退回语言键/id，因此 beta1 与 beta2 可互连。

### 5.5 客户端注册表与热重载

`cuddle_animations/*.json`（manifest）在每个资源重载时被重新扫描 → 构建不可变快照（`volatile`）
→ 排序（`order` asc, id）→ 计算 caps → 重新上报。若重载后缺少 `letscuddle:normal`，保留旧快照并 `ERROR`。

`player_animation(s)/` 目录由动画库读取（Forge: playerAnimator；Fabric: PAL）。
Fabric 另外解析同一批 JSON 供 `CuddleAnimClip` 采样，**仅用于相机位移**。

### 5.6 渲染与相机

**1.20.1 Forge —— 取消 + 自绘**

- `RenderPlayerEvent.Pre` 取消整条 `PlayerRenderer#render`，自己施加矩阵链并把 `PlayerModel` 画一遍：
  `Rx(180) · T(0,-1.5,0) · Ry(targetYaw) · [body 位移+旋转]`，模型高 2.0（不乘 0.9375）。
- 阴影：本帧 `setInvisible(true)`，`RenderLevelStageEvent.AFTER_ENTITIES` 恢复。
- 第一人称隐藏自己的头（K10），解锁恢复。
- 相机：`ViewportEvent.ComputeCameraAngles` + AT 打开的 `Camera#setPosition`（SRG `m_90581_`）。
- **代价**：接管期间只画基础模型，盔甲/手持物/披风/名字牌都不画。

**1.21.11 Fabric —— 包一层 + PAL**

- 3 个 client mixin：
  - `PlayerEntityRendererMixin`：`setupTransforms` 末尾 `Ry(bodyYaw − targetYaw)`；`scale` 头取消（模型 2.0）；
    `renderLabelIfPresent` 头取消（不画名字牌）。
  - `EntityRendererMixin`：`updateShadow` 头取消（不生成阴影）。
  - `CameraMixin`：`Camera#update` 末尾加 `bodyOffset`（AW 打开 `Camera#setPos`）。
- 骨骼与 `body` 位移由 PAL 在模型内部施加（我们不再自己写骨骼 mixin）。
- 皮肤/盔甲/手持物/披风由原版渲染器继续画。

两端共用同一套数学（`CuddlePlacement`、1.3、2.0、120），保证观感与数值一致。

### 5.7 客户端锁定表

Fabric 以**实体 id** 为键（渲染状态只有 id），Forge 以 UUID 为键（事件里有实体）。
两端都有 `CLIENT_LOCK_FALLBACK_TICKS`（200 tick）兜底：服务端结束包丢失时自动解锁。

## 6. 线程模型

| 位置 | 规则 |
|---|---|
| 服务端 | 所有状态只在主线程；包处理 `enqueueWork` / `server.execute` 切回主线程 |
| 客户端 | 网络处理切到客户端线程；注册表用不可变快照，重载线程只写新对象 |
| 禁止 | `ScheduledExecutorService`、自建线程（1.x 的 K1/K2 教训） |

## 7. 关键不变量

1. 落点 `sender + 1.3·normalize(sender−receiver)`；两人 XZ 重合视为无法站立。
2. 模型高 2.0；接受者压进 1 格；Forge 的矩阵顺序不可调换。
3. 动画 JSON 不加 `version`（否则 `torso` 不再当根节点，贴合消失）。
4. 默认锁定 120 tick、速度 0.5×；`lock_ticks` 夹紧 [20,400]。
5. 蹲下 5 tick 取消；请求 60 s 过期；广播 64 格；跨维度拒绝。
6. 请求键有序；A→B 与 B→A 并存。
7. 结束/掉线走同一条清理路径；掉线必须广播结束包。
8. 动作 id 全程字符串；旧短 id 读时迁移。
9. `letscuddle:normal` 永远可用（重载缺失 → 保留旧快照）。
10. caps 里的 id 必须过正则/长度/数量校验，lock 夹紧。

## 8. 常量（`CuddleConstants`）

| 常量 | 值 | 含义 / 谁在用 |
|---|---|---|
| `MODID` | `letscuddle` | 命名空间 |
| `PROTOCOL` | `4` | 协议版本：Forge 通道字符串、caps 校验 |
| `PACK_FORMAT` | `1` | **动作包 manifest 的 `format` 字段**（不是原版 `pack_format`） |
| `REQUEST_TIMEOUT_TICKS` | 1200 | 请求过期（tick） |
| `DEFAULT_LOCK_TICKS` | 120 | 默认锁定；manifest 缺省值；caps 缺省值 |
| `MIN/MAX_LOCK_TICKS` | 20 / 400 | manifest 与 caps 的夹紧范围 |
| `SNEAK_CANCEL_TICKS` | 5 | 连续潜行多少 tick 提前结束 |
| `MAX_DISTANCE` | 5.0 | 接受时的最大距离（格） |
| `NEARBY_RADIUS` | 64.0 | 广播半径（格） |
| `STAND_OFFSET` | 1.3 | 接受者落点距离 |
| `STAND_HALF_WIDTH` | 0.3 | 站位/通过检测的 AABB 半宽 |
| `CAPS_TIMEOUT_TICKS` | 100 | 加入后等 caps 的时限 |
| `MAX_ANIMS_PER_CLIENT` | 256 | 单客户端 caps 条数上限 |
| `MAX_TOTAL_ANIMS` | 512 | 注册表扫描 manifest 上限 |
| `MAX_ANIM_ID_LEN` | 128 | id 长度上限（线格式 + 校验） |
| `MAX_CONTENT_VERSION_LEN` | 32 | 内容版本长度上限 |
| `MAX_NAME_KEY_LEN` / `MAX_NAME_LEN` | 128 / 64 | 显示名（语言键 / 纯文本） |
| `MAX_ICON_LEN` | 128 | 保留字段 `icon` 的长度上限 |
| `MAX_MANIFEST_BYTES` | 16 KiB | manifest 文件大小上限 |
| `MAX_ANIMATION_BYTES` | 512 KiB | 动画 JSON 大小上限 |
| `DEFAULT_ANIM_ID` / `BUILTIN_NORMAL` | `letscuddle:normal` | 默认动作（两者同值） |
| `BUILTIN_TOUCH_HEAD` | `letscuddle:touch_head` | 内置动作 2 |
| `DEFAULT_SPEED` | 0.5 | 默认播放速度倍率 |
| `MIN_SPEED` / `MAX_SPEED` | 0.1 / 2.0 | manifest `speed` 夹紧范围 |
| `CLIENT_LOCK_FALLBACK_TICKS` | 200 | 客户端解锁兜底 |
| `STARTER_TICKETS` | 16 | 首登发券数量 |

**tick 与真实时间**：以上带 `TICKS` 的都是**游戏 tick**。默认 20 tick/s，因此 120 tick = 6 s；
若用 Carpet 之类改变 tick 速率，锁定/过期会按 tick 数等比例伸缩（服务端 tick 与客户端动画 tick
可能不同步，见 §9）。

## 9. 已知限制与风险（做兼容前先读）

1. **1.20.1 接管渲染**：贴贴期间不画盔甲/手持物/披风/名字牌；其它模组加在 `PlayerRenderer` 上的层同样看不到。
2. **1.21.11 隐藏名字牌与阴影**，模型按 2.0 格渲染。
3. **每 tick 强制传送**：飞行/坐骑/位移类模组会打架；Fabric 已用相对旋转标志避免抢视角。
4. **tick 驱动的计时**：Carpet `/tick warp`/`freeze` 会让服务端的锁定与客户端的动画进度不同步。
5. **动画层冲突**：两端都是 `letscuddle:animations`、优先级 42；更高优先级的模组会盖住我们。
6. **没有对外 API**：目前只有内部类；要做兼容/扩展，建议新增 `xyz.skyofsimba.api`（事件 + 注册表访问）。
7. **协议 v4**：两端必须同为 2.0；1.x 不能互连（Forge 通道层拒绝，Fabric 按「只有内置动作」降级）。

## 10. 扩展点（现状）

| 需求 | 现在怎么做 |
|---|---|
| 新增动作 | 资源包 manifest（见格式文档），不需要改代码 |
| 改默认动作/内置动作 | 覆盖同路径 manifest（`test-packs/override-normal` 是例子） |
| 知道"谁在贴贴" | 目前无公开 API；Fabric 客户端可读 `CuddleAnimBackend.isLocked(id)`（内部类） |
| 给贴贴加钩子 | 需要在服务端状态机里加事件（Forge 事件总线 / Fabric 自建 `Event<>`） |
| 兼容其它动画库 | 改 `AnimationManager`（Forge）/ `CuddlePalBridge`（Fabric），保持层 id、优先级、速度语义 |

## 11. 阅读索引

| 想了解 | 看 |
|---|---|
| 服务端行为 | `forge-1.20.1/.../command/CuddleCommandHandler.java`（Fabric 同名类） |
| 协议 | 两端的 `network/` 目录 + 本文 §5.4 |
| 1.20.1 渲染 | `events/RenderPlayerEventHandler.java`、`client/CuddleCameraHandler.java` |
| 1.21.11 渲染 | `mixin/client/*`、`client/CuddlePalBridge.java`、`client/CuddleAnimBackend.java` |
| 动作包 | [动作包格式](animation-pack-format.md)、`common/.../CuddleAnimManifest.java` |
| 构建/发布 | `README.md`、`.github/workflows/build.yml`、计划 §9 |
