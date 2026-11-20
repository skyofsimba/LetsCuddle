# HugMe 原始设计文档（NeoForge × Minecraft 1.21 × v1.3）

> **文档性质**：对一个既有模组的逆向设计说明 + 可复刻规格。目标读者是「另一个 AI」或没有原始仓库的工程师：仅凭本文即可从零重建一份行为等价的模组。
>
> **被测对象**：`https://github.com/skyofsimba/HugMe.git`（fork），分支 `main`，HEAD = `e5fd1b3 "1.3"`。工作区唯一的本地改动是 `gradle/wrapper/gradle-wrapper.properties`（发行包地址换成了腾讯云镜像），与功能无关。
>
> **分析日期**：2026-11-20。
>
> **许可证**：仓库根 `LICENSE` 是 MIT（`Copyright (c) 2024 TunYuntuwuQWQ`）；而 `gradle.properties` 写的是 `mod_license=unlicensed`，两者不一致。按 LICENSE 文件可视为 MIT —— 代码、动作数据、贴图都可复用，但需保留版权声明。
>
> **本文包含**：完整行为规格、逐类实现细节、网络协议、动作数据、常量表、已知缺陷、可复用资产清单。
> **本文不包含**：向 Minecraft 1.20.1 / Forge 迁移的方案（第 12 节只回答「哪些资产能原样拿走」）。

---

## 0. 阅读约定

| 标记 | 含义 |
|---|---|
| 【事实】 | 直接读源码/资源得到，可逐字复核 |
| 【推导】 | 由源码的数学关系或调用链推出，作者未写明；可能与其本意不同 |
| 【推断】 | 未验证的合理猜测，落地前应实测 |

- 路径一律相对仓库根目录。
- 文中 Java 片段是**原文摘录**，不是重写过的示例代码；完整源码见附录 A。
- 「原实现」= `e5fd1b3`；「复刻」= 你要做的事情。

---

## 1. 一句话概述

HugMe 增加了一件消耗品「抱抱券」：拿它右键另一个玩家，对方聊天栏会收到一条带 `[同意] / [拒绝]` 可点击按钮的请求；同意后服务端把两人按 1.3 格间距摆好、原地钉住 6 秒，并给 64 格内所有玩家广播「开始拥抱」包；客户端用 playerAnimator 播放一对互补的拥抱动作，同时**接管**这两个玩家的模型渲染，让两个纸片人互相面对、身体贴合。

它是纯表现型社交模组：没有伤害、没有 buff、没有物品产出，唯一的「资源」是一张会消耗的券。

---

## 2. 玩家可见行为（端到端）

### 2.1 拿到券

1. 【事实】物品以 64 上限堆叠，加入创造模式物品栏 `工具与实用物品（TOOLS_AND_UTILITIES）`。
2. 【事实】可合成：3×3 台上摆 `纸 / 淡蓝染料 / 粉色染料` 的「十字」图案，一次出 4 张（见 5.3）。
3. 【事实】玩家**在这个存档里第一次进入**时，服务端判定其玩家数据文件不存在 → 直接塞 16 张（背包满则掉落）。语义是「这个存档的首次登录」，不是「账号首次」。

### 2.2 发起请求

1. 手持抱抱券右键另一个玩家（`interactLivingEntity`）。
2. 【事实】服务端登记一条 `(发起者UUID, 接受者UUID) → HugRequest`，并**立刻**扣掉 1 张券。
3. 【事实】接受者收到一条复合消息：
   `"%s 向你发起了拥抱请求! "` + `"[同意] "`（绿色，点击执行 `/hugme acceptRequest <发起者名>`）+ `"[拒绝] "`（红色，点击执行 `/hugme rejectRequest <发起者名>`）。
4. 【事实】发起者收到 `你成功向 %s 发送了拥抱请求`。
5. 【事实】请求 60 秒后自动过期，双方各收到一条过期提示。
6. 【事实】同一对玩家已存在未处理请求时，再次右键**不会**发新请求、**不会**扣券，只给发起者一条「你已经向他发送了一个拥抱请求」。

### 2.3 接受

【事实】前置校验按此顺序执行（顺序很重要，消息会全部发出去）：

1. 请求存在？不存在 → 只给接受者 `无效的拥抱请求`，结束。
2. 这对玩家已有进行中的拥抱？→ 双方各收一条「正在拥抱中」，结束。
3. 两人在不同维度？→ 只给接受者 `你与 %s 不在同一个世界`，结束。
4. 两人距离 > 5 格？→ 只给接受者 `你距离 %s 太远了! [>5]`，结束。
5. 双方各收到「已接受」的绿色提示。
6. 计算落点，做「能否站人」检查；不通过 → 只给接受者 `%s前的区域无法站立!`，结束（**此时「已接受」已经发出去了**）。
7. 把接受者瞬移到落点、让其看向发起者眼睛，广播开始包，删除请求。

**落点公式**（详见 7.3.2）：接受者被摆到「发起者 + 1.3 格 ×（从接受者指向发起者的单位向量）」，即**越过发起者**，落在其身后 1.3 格。

### 2.4 拒绝

【事实】双方各收一条红色「拒绝了」提示，删除请求，不消耗额外物资（券已在发起时扣除，不退还）。

### 2.5 拥抱过程

1. 【事实】服务端为这对玩家建一条 `HugStatus`，剩余 120 tick（6 秒）。
2. 【事实】从 `HugAnimationEnum` 的**全部取值**里随机抽一个（当前是 `NORMALHUG` / `TOUCHHEADHUG` 二选一），把同一个序号广播给**发起者所在维度、距发起者 < 64 格**的全部玩家（含这两名当事人）。
3. 【事实】此后每个服务端 tick 结束时，把两人瞬移回开始时的坐标（不能走动、不能被打飞），并递减计数。
4. 【事实】计数归零时，向同一批玩家广播结束包，删除状态。两人留在被钉住的位置。

### 2.6 客户端表现

1. 【事实】收到开始包：在客户端把两人的 UUID 互相登记为「锁定对象」，并按序号播放对应动作。
2. 【事实】锁定期间，这两个玩家的模型**不再走原版渲染**，改由本模组手绘：只画 `PlayerModel`（皮肤），不画护甲、手持物、披风、鞘翅；模型被旋转成互相面对，姿势冻结在动作上。
3. 【事实】发起者额外带一个 `hideHead=true` 标记：第一人称下如果渲染到自己，把头藏掉。
4. 【事实】收到结束包：解开锁定，动作自然播完（动作比 6 秒略长，见 9.4）。

---

## 3. 设计思路与取舍

这一节回答「为什么这么写」。复刻时如果换了实现手段，这些约束仍然要满足。

### 3.1 服务端只做状态机，客户端只做表现

服务端代码**完全不碰**动画库：它只知道「谁和谁、多久、第几号动作」。动作 id 是 `int`，朝向由客户端根据两个 UUID 的世界坐标自己算。代价是两端必须都装模组；好处是专用服务端不需要 playeranimator（依赖声明为 `side="CLIENT"`）。

### 3.2 用「请求—确认」握手，而不是单向强制

发起动作走物品右键，但真正的开始需要接受者点聊天栏按钮触发命令。发起者拿不到对接受者的控制权，避免「被强行摆姿势」。同时把「接受」做成 **命令** 而不是网络包：这样按钮只需要原版 `ClickEvent(RUN_COMMAND)`，零额外协议。

### 3.3 用「每 tick 回拉」实现不能动，而不是改输入/改状态

没有 mixin、没有改玩家 AI、没有加药水效果。服务端每 tick 把两人 `teleportTo` 回原坐标，玩家能转头、能聊天、能开背包，但位移被吃掉。侵入性最小，且不需要在客户端拦截移动包。
代价：会与任何「传送拦截 / 反作弊 / 位移校正」类模组或插件打架；没有摔落与无敌保护；两人是「被钉住」而非「进入某种状态」，所以外部观察者看来他们在原地站着。

### 3.4 动作放在自定义动画层，并降速 50%

playerAnimator 允许给每个玩家挂多个动画层。本模组注册自己的层 `hugme:animations`（优先级 42），只在这一层上播动作，不占主层，因此可以和表情/其它动作模组共存。
动作文件本身是 61 tick 长（`stopTick`），层上加了一个 `SpeedModifier(0.5F)`，实际播放约 122 tick ≈ 6.1 秒 —— 刚好比服务端的 120 tick 锁定略长，保证锁定期内动作不会先结束露出 T-pose 式静止。

### 3.5 渲染接管：自己摆姿势，而不是相信原版朝向

原版玩家渲染用的是实体自身的 yaw，而拥抱要求「两人恰好面对面」。服务端只给接受者调了 `lookAt`，发起者的 yaw 没动；而且原版渲染不接受「根节点位移」这种需求。所以模组在 `RenderPlayerEvent.Pre` 里取消原版渲染，自己 push 一个「模型空间 → 世界」的变换（180° 翻转 + 目标 yaw），再调一次 `setupAnim` + `renderToBuffer` 把模型画出来。注意两点：① `PoseStack` 是后乘，矩阵顺序写反会得出「模型镜像」的错误结论；② 取消原版渲染同时也会跳过 playerAnimator 自己的 `body` 根节点 mixin，所以必须把那 5 行补一遍（详见 7.8.3、7.8.4）。
附带效果：因为整条 `PlayerRenderer.render` 被跳过，护甲/手持物等附加层也不会被画。

### 3.6 首次登录发券：用「数据文件是否存在」当标记

不写 NBT、不加 Attachment、不改玩家数据格式。只在 `PlayerLoggedInEvent` 里检查 `world/playerdata/<uuid>.dat` 是否存在，不存在就发 16 张。零存档侵入，但语义是「该存档首次」；且如果玩家在首次保存前就崩服/断线，会再发一次。

### 3.7 资源即数据：加一种拥抱不需要改服务端

新增拥抱类型只需：加两个 `player_animation/*.json`（发起者 / 接受者各一）、在枚举里加一项、在 `AnimationManager` 的 switch 里加一个 case。服务端只传 `ordinal()`，天然兼容。

---

## 4. 工程与运行环境

### 4.1 版本矩阵【事实】

| 项 | 值 |
|---|---|
| Minecraft | `1.21`（`minecraft_version_range=[1.21,1.21.1]`） |
| 加载器 | NeoForge `21.0.114-beta`（`neo_version_range=[21.0.0-beta,)`） |
| Gradle 插件 | `net.neoforged.moddev` `1.0.14` |
| Java | 21（toolchain） |
| Gradle wrapper | 8.9 → 本地被改成 8.14.7 镜像（源码 `gradle/wrapper/gradle-wrapper.properties`） |
| 映射 | Parchment `1.21` / `2024.07.07` |
| 第三方依赖 | `dev.kosmx.player-anim:player-animation-lib-forge:2.0.0+1.21.1`（KosmX maven） |
| 产物名 | `HugMe!-NeoForge-1.21-<mod_version>.jar` |

### 4.2 `gradle.properties` 关键项【事实】

```properties
parchment_minecraft_version=1.21
parchment_mappings_version=2024.07.07
minecraft_version=1.21
minecraft_version_range=[1.21,1.21.1]
neo_version=21.0.114-beta
neo_version_range=[21.0.0-beta,)
loader_version_range=[4,)

mod_id=hugme
mod_name=Hug Me!
mod_license=unlicensed
mod_version=1.3
mod_group_id=nya.tuyw.hugme
mod_authors=TuYw
mod_description=Hug other players!
```

### 4.3 `build.gradle` 要点【事实】

- `plugins`：`java-library`、`eclipse`、`idea`、`maven-publish`、`net.neoforged.moddev` `1.0.14`。
- 额外仓库：`https://maven.kosmx.dev/`，`content { includeGroup("dev.kosmx.player-anim") }`。
- `neoForge { version = project.neo_version; parchment {…}; runs { client, server, gameTestServer, data }; mods { "${mod_id}" { sourceSet(sourceSets.main) } } }`。
- `sourceSets.main.resources { srcDir 'src/generated/resources' }`（实际无生成物）。
- `dependencies { implementation "dev.kosmx.player-anim:player-animation-lib-forge:2.0.0+1.21.1" }`。
- `processResources` 只对 `META-INF/neoforge.mods.toml` 做 `expand`，替换键见附录 A.9。
- `base { archivesName = "HugMe!-NeoForge-$minecraft_version" }`。
- `tasks.withType(JavaCompile) { options.encoding = 'UTF-8' }`（**必须**，源码注释里有中文）。

### 4.4 `META-INF/neoforge.mods.toml`【事实】

```toml
modLoader="javafml"
loaderVersion="${loader_version_range}"
license="${mod_license}"

[[mods]]
modId="${mod_id}"
version="${mod_version}"
displayName="${mod_name}"
authors="${mod_authors}"
description='''${mod_description}'''
logoFile="hug_ticket.png"

[[dependencies.${mod_id}]]
    modId="neoforge"
    type="required"
    versionRange="${neo_version_range}"
    ordering="NONE"
    side="BOTH"

[[dependencies.${mod_id}]]
    modId="minecraft"
    type="required"
    versionRange="${minecraft_version_range}"
    ordering="NONE"
    side="BOTH"

[[dependencies.${mod_id}]]
modId="playeranimator"
type="required"
versionRange="[1.0.2-rc1+1.21,)"
ordering="NONE"
side="CLIENT"
```

要点：
- 依赖 modid 写的是 `playeranimator`（playerAnimator 模组自身的 modid），不是 maven 坐标名。
- `side="CLIENT"`：专用服务端不强制安装 playeranimator。
- 无 `pack.mcmeta`（1.21 下 mod jar 的包元信息由加载器推导，原仓库确实没有这个文件）。

### 4.5 目录树【事实】

```
HugMe/
├── build.gradle
├── gradle.properties
├── settings.gradle            # foojay-resolver-convention 0.8.0
├── gradlew / gradlew.bat / gradle/wrapper/*
├── LICENSE                    # MIT
├── README.md                  # 3 行中文说明
└── src/main/
    ├── java/nya/tuyw/hugme/
    │   ├── HugMe.java
    │   ├── animation/AnimationManager.java
    │   ├── animation/HugAnimationEnum.java
    │   ├── command/HugCommandHandler.java
    │   ├── events/RenderPlayerEventHandler.java
    │   ├── item/HugTicketItem.java
    │   ├── network/HugRenderPayload.java
    │   └── network/HugRenderClientHandler.java
    └── resources/
        ├── META-INF/neoforge.mods.toml
        ├── hug_ticket.png                        # 模组 logo（与物品贴图同一张）
        ├── assets/hugme/lang/{en_us,zh_cn}.json
        ├── assets/hugme/models/item/hug_ticket.json
        ├── assets/hugme/textures/item/hug_ticket.png
        ├── assets/hugme/player_animation/{normal_s,normal_r,touch_s,touch_r}.json
        └── data/hugme/recipe/hug_ticket.json
```

包名 `nya.tuyw.hugme`；modid `hugme`。**注意**：`hug_ticket.png` 在 `resources/` 根下和 `textures/item/` 下是**同一份字节**（SHA-256 `25089C58…36EC`），前者供 `logoFile` 使用。

---

## 5. 资源规格

### 5.1 物品模型【事实】

`assets/hugme/models/item/hug_ticket.json`：标准 `item/generated` + `layer0 = hugme:item/hug_ticket`。

### 5.2 贴图【事实】

- `assets/hugme/textures/item/hug_ticket.png`：**64×64**、8-bit RGBA（PNG color type 6）、1026 字节。不是 16×16 —— 作者用了高分辨率贴图。
- 画面：一张斜置约 −30° 的浅蓝/白票券，深蓝描边，票面中央一枚粉色爱心。
- 完整字节以 base64 形式内嵌在附录 B.5，可直接还原。

### 5.3 合成配方【事实】

`data/hugme/recipe/hug_ticket.json`（1.21 的目录名是 `recipe` 单数）：

```json
{
  "type": "minecraft:crafting_shaped",
  "category": "misc",
  "pattern": ["PBP", "PIP", "PBP"],
  "key": {
    "P": { "item": "minecraft:paper" },
    "B": { "item": "minecraft:light_blue_dye" },
    "I": { "item": "minecraft:pink_dye" }
  },
  "result": { "count": 4, "id": "hugme:hug_ticket" }
}
```

即：4 纸 + 2 淡蓝染料 + 1 粉色染料 → 4 张抱抱券。

### 5.4 语言文件【事实】

`zh_cn.json` / `en_us.json` 各 20 个键，全部被代码引用，没有废键。全文见附录 B.3、B.4。键名与用途：

| 键 | 参数 | 用途 |
|---|---|---|
| `hugme.message.sendrequest` | `%s`=发起者名 | 请求消息前缀 |
| `hugme.message.sendsuccess` | `%s`=接受者名 | 发起者回执 |
| `hugme.message.acceptrequest` | – | `[同意] ` 按钮文字 |
| `hugme.message.rejectrequest` | – | `[拒绝] ` 按钮文字 |
| `hugme.message.sender_accepted` | `%s`=接受者名 | 发起者：对方接受 |
| `hugme.message.receiver_accepted` | `%s`=发起者名 | 接受者：我接受 |
| `hugme.message.sender_rejected` | `%s` | 发起者：对方拒绝 |
| `hugme.message.receiver_rejected` | `%s` | 接受者：我拒绝 |
| `hugme.message.sendernotfound` | – | 命令里按名字找不到发起者 |
| `hugme.message.invalid_request` | – | 请求不存在 |
| `hugme.message.have_request` | – | 重复发起 |
| `hugme.message.request_expired` | `%s`=对方名 | 60s 超时 |
| `hugme.message.hug_in_progress_sender` | `%s` | 对方正在拥抱中 |
| `hugme.message.hug_in_progress_receiver` | `%s` | 我正在拥抱中 |
| `hugme.message.invalid_level` | `%s` | 跨维度 |
| `hugme.message.too_far` | `%s` | 距离 > 5 |
| `hugme.message.unsafe_position` | `%s` | 落点站不住 |
| `hugme.networking.failed` | `%s`=异常信息 | 客户端处理包异常断开连接 |
| `item.hugme.hug_ticket` | – | 物品名「抱抱券」/「Hug Ticket」 |
| `item.hugme.hug_ticket.desc` | – | 物品说明（灰色） |

### 5.5 动作文件【事实】

`assets/hugme/player_animation/` 下 4 个 JSON，**文件名只是给人看的**，真正的注册 id 来自文件内的 `name` 字段（见 9.2）：

| 文件 | 内部 `name` | 时长（end/stop） | 语义 |
|---|---|---|---|
| `normal_s.json` | `hug_normal_sender` | 53 / 61 | 普通拥抱·发起者 |
| `normal_r.json` | `hug_normal_receiver` | 53 / 61 | 普通拥抱·接受者 |
| `touch_s.json` | `hug_touch_sender` | 54 / 61 | 摸头拥抱·发起者 |
| `touch_r.json` | `hug_touch_receiver` | 54 / 61 | 摸头拥抱·接受者 |

逐通道数值表见 9.3，原文见附录 B.6。

---

## 6. 架构与数据流

### 6.1 模块职责

| 类 | 侧 | 职责 |
|---|---|---|
| `HugMe` | 双端 | 注册物品；创造栏插入；`ServerStarting` 注册命令；首次登录发券 |
| `HugTicketItem` | 双端 | 右键玩家 → 发起请求、扣券、发可点击消息；tooltip |
| `HugCommandHandler` | 双端（逻辑在服务端） | 请求表 / 拥抱表；`/hugme acceptRequest`、`/hugme rejectRequest`；落点计算与安全检查；`ServerTick` 回拉与计时；广播包 |
| `HugAnimationEnum` | 双端 | 动作种类枚举，`ordinal()` 即线上传的动画号 |
| `AnimationManager` | 仅客户端 | 向 playerAnimator 注册自定义动画层；按枚举挑动作并塞进层 |
| `HugRenderPayload` | 双端 | 自定义包定义 + 注册（`playToClient`） |
| `HugRenderClientHandler` | 仅客户端 | 收包 → 查玩家 → 加/解锁 + 播动作 |
| `RenderPlayerEventHandler` | 仅客户端 | 锁定期间接管这两个玩家的模型渲染 |

### 6.2 侧别与线程【事实】

- 请求/拥抱两张表都在 `ConcurrentHashMap`，键是 `com.mojang.datafixers.util.Pair<UUID,UUID>`（该 `Pair` 实现了 `equals`/`hashCode`）。
- 表的所有读写都发生在**服务端主线程**，唯一例外是 60 秒超时回调（`ScheduledExecutorService` 线程，见 K1）。
- 客户端的 `playerLockMap` 是普通 `HashMap`，只在渲染线程读写。
- 收包处理先 `context.enqueueWork(...)` 切回客户端主线程再碰世界对象。

### 6.3 时序

```
发起者C          服务端                      接受者R          旁观者(≤64格)
  │ 右键R ────────►│                                            │
  │                │ hugRequests[(C,R)] = req                   │
  │                │ schedule(60s, expire)                      │
  │ ◄──sendsuccess │                                            │
  │                │ ────────────── sendrequest+按钮 ──────────►│
  │                │ ◄──────────── /hugme acceptRequest C ──────│
  │                │ 校验(存在/不重复/同维度/≤5格/可站立)         │
  │                │ teleport R → C+1.3·unit(R→C); R.lookAt(C)  │
  │                │ hugStatuses[(C,R)] = {pos…,120tick}        │
  │ ◄──────────────┼──────── HugRenderPayload(C,R,false,n) ────►│
  │  ▓ 每 tick: teleport C,R 回原位；tick-- ▓                   │
  │                │ ──── HugRenderPayload(C,R,true,0) ────────►│
  │                │ hugStatuses.remove                         │
```

---

## 7. 逐组件实现规格

> 完整源码见附录 A；本节只写「必须满足什么」，以及代码里看不出来的顺序与陷阱。

### 7.1 `HugMe`（主类）

- 常量：`MODID="hugme"`、`LOGGER=LogUtils.getLogger()`。
- 物品注册：`DeferredRegister.Items.createItems(MODID)`；`registerItem("hug_ticket", HugTicketItem::new, new Item.Properties().stacksTo(64))`。声明类型写的是**裸 `DeferredItem`**（无泛型参数），能编译但会有 rawtype 警告。
- 构造器 `HugMe(IEventBus modEventBus, ModContainer modContainer)`：
  1. `HUGME_ITEMS.register(modEventBus)`；
  2. `modEventBus.addListener(this::addCreative)`；
  3. `NeoForge.EVENT_BUS.register(this)`（game bus，实例方法订阅）。
- `addCreative(BuildCreativeModeTabContentsEvent)`：`tabKey == CreativeModeTabs.TOOLS_AND_UTILITIES` 时 `event.accept(HUG_TICKET)`。
- `onServerStarting(ServerStartingEvent)`：`HugCommandHandler.register(event.getServer().getCommands().getDispatcher())`。
- `onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent)`：仅 `ServerPlayer`；`ServerLifecycleHooks.getCurrentServer()` 为 null 直接返回；否则：
  ```java
  File f = new File(server.getWorldPath(LevelResource.PLAYER_DATA_DIR).toFile(), player.getUUID() + ".dat");
  if (!f.exists()) {
      ItemStack st = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath("hugme","hug_ticket")), 16);
      if (!player.getInventory().add(st)) player.drop(st, false);
  }
  ```
  注意：这里走注册表查物品而不是直接用 `HUG_TICKET.get()`（等效，但复刻时两种都行）。

### 7.2 `HugTicketItem`

- `extends Item`，构造器透传 `Properties`。
- `interactLivingEntity(stack, player, target, hand)`：
  - `target instanceof Player` 才继续；否则 `InteractionResult.PASS`。
  - `!player.level().isClientSide` 时：
    - `HugCommandHandler.startHugRequest((ServerPlayer)player, (ServerPlayer)target)` 返回 true →
      `sendHugRequest(...)`、给发起者 `sendsuccess`、`stack.shrink(1)`。
      返回 false → **什么都不做**（玩家只看到前面 `have_request` 的红字）。
  - 无条件 `return InteractionResult.sidedSuccess(player.level().isClientSide)`（即使发请求失败，客户端仍会播放挥手动画）。
- `sendHugRequest(sender, target)`：拼一条 `Component`：
  `translatable("hugme.message.sendrequest", sender.getName()).withStyle(BLUE)`
  `.append(translatable("hugme.message.acceptrequest")… color GREEN, ClickEvent(RUN_COMMAND, "/hugme acceptRequest " + sender.getName().getString()))`
  `.append(translatable("hugme.message.rejectrequest")… color RED, ClickEvent(RUN_COMMAND, "/hugme rejectRequest " + sender.getName().getString()))`
  发给 `target`。
- `appendHoverText(stack, TooltipContext, List<Component>, TooltipFlag)`：加一条灰色 `item.hugme.hug_ticket.desc`。

### 7.3 `HugCommandHandler`

#### 7.3.1 命令树【事实】

```
/hugme
├── acceptRequest <sender:String>   # 执行者是接受者
└── rejectRequest <sender:String>   # 执行者是接受者
```

- 用 `StringArgumentType.string()`（可带引号的字符串；玩家名无空格，够用）。
- 两个子命令都**没有** `requires(...)` → 任何玩家可用。
- 找不到 `sender`：`sendFailure(translatable("hugme.message.sendernotfound").red)`，返回 0。
- 执行者必须是人（`getPlayerOrException()`）。

#### 7.3.2 接受流程（`handleHugAcceptance`）

```
key = Pair(sender.uuid, receiver.uuid); req = hugRequests.get(key)
if (req == null)            → receiver: invalid_request; return
if (hugStatuses has key)    → sender: hug_in_progress_sender(receiver.name)
                              receiver: hug_in_progress_receiver(sender.name); return
if (sender.serverLevel() != receiver.serverLevel())
                            → receiver: invalid_level(sender.name); return
if (receiver.distanceTo(sender) > 5)
                            → receiver: too_far(sender.name); return
sender:   sender_accepted(receiver.name)   绿色
receiver: receiver_accepted(sender.name)   绿色
```

落点计算（原文逐字）：

```java
Vec3 senderVec = sender.position().subtract(receiver.position());            // 接受者 → 发起者
double horizontalDistance = Math.sqrt(senderVec.x*senderVec.x + senderVec.z*senderVec.z);
double offsetX = (senderVec.x / horizontalDistance) * 1.3;
double offsetZ = (senderVec.z / horizontalDistance) * 1.3;
double receiverX = sender.getX() + offsetX;
double receiverY = sender.getY();          // 注意：直接取发起者脚部 Y，不做地面吸附
double receiverZ = sender.getZ() + offsetZ;
```

向量形式：`R' = C + 1.3 · normalise(R − C)`，其中 `C`=发起者、`R`=接受者。**方向取反了**——接受者被摆到发起者的另一侧（见 K4）。

带数字的例子：`C=(0,0,0)`，`R=(3,0,0)` → `senderVec=(−3,0,0)`，`|xz|=3`，`offset=(−1.3,0,0)`，`R'=(−1.3,0,0)`。接受者从东边 3 格被瞬移到西边 1.3 格。

随后：

```java
Vec3 standPosition = new Vec3(receiverX, receiverY, receiverZ);
if (!isPositionSafe(standPosition, sender.level())) {
    receiver: unsafe_position(sender.name); return;      // 注意「已接受」已经发过了
}
receiver.teleportTo(receiverX, receiverY, receiverZ);
receiver.lookAt(EntityAnchorArgument.Anchor.EYES, new Vec3(sender.getX(), sender.getEyeY(), sender.getZ()));
sendRenderInfoToNearbyPlayers(sender, receiver);
hugRequests.remove(key);
```

`isPositionSafe` 的两个盒（都以落点为原点，半宽 0.3）：

| 检查 | AABB | 通过条件 | 含义 |
|---|---|---|---|
| `canPositionStand` | `y−0.1 .. y` | **存在**方块碰撞 | 脚下有地 |
| `canPositionPass` | `y+0.1 .. y+1.7` | **没有**方块碰撞 | 身体有空间 |

两个都过才算安全。用的是 `sender.level()`（因为已校验同维度），且碰撞查询的实体参数传 `null`。

#### 7.3.3 拒绝流程（`handleHugRejection`）

```java
HugRequest request = hugRequests.get(key);
if (request != null && request.receiverId().equals(receiver.getUUID())) {
    sender:   sender_rejected(receiver.name)   红色
    receiver: receiver_rejected(sender.name)   红色
    hugRequests.remove(key);
} else {
    receiver: invalid_request 红色
}
```

（`receiverId` 的相等判断是冗余的：key 里已经含 receiver。）

#### 7.3.4 发起（`startHugRequest`）

```java
key = Pair(sender.uuid, receiver.uuid)
if (hugRequests.get(key) != null) { sender: have_request 红; return false; }
hugRequests.put(key, new HugRequest(sender.uuid, receiver.uuid));
scheduler.schedule(() -> {
    if (hugRequests.remove(key) != null) {
        sender:   request_expired(receiver.name)
        receiver: request_expired(sender.name)
    }
}, 60, TimeUnit.SECONDS);
return true;
```

`scheduler` 是类静态的 `Executors.newScheduledThreadPool(1)`，全程未关闭（K2）。去重只看 `(sender,receiver)` 方向，所以 A→B 与 B→A 可并存（K12）。

#### 7.3.5 拥抱状态与 tick

`sendRenderInfoToNearbyPlayers`：

```java
List<ServerPlayer> nearby = getNearbyPlayers(sender);              // 同维度且 distanceTo(sender) < 64
HugStatus status = new HugStatus(sender.uuid, receiver.uuid,
                                 sender.position(), receiver.position(),  // 已在瞬移之后采集
                                 true, (short)(20*6), nearby);
hugStatuses.put(Pair(sender.uuid, receiver.uuid), status);
int enumNumber = new Random().nextInt(HugAnimationEnum.values().length);   // 整批只抽一次
for (p : nearby) PacketDistributor.sendToPlayer(p, new HugRenderPayload(sender.uuidStr, receiver.uuidStr, false, enumNumber));
```

`onServerTickEnd(ServerTickEvent.Post)`：

```java
for (entry : hugStatuses.entrySet()) {
    if (!status.isActive()) hugStatuses.remove(key);              // 死代码：active 从未置 false
    if (status.isActive() && status.getTicksRemaining() > 0) {
        doLock(status);                                           // 两人 teleportTo 回存好的坐标
        status.decrementTicks();
        if (status.getTicksRemaining() == 0) endHug(status);
    }
}
```

`doLock`：两人都查得到就各自瞬移回 `senderPos` / `receiverPos`；任一查不到（掉线）→ 从表里删掉这条，**不发结束包**（K9）。
`endHug`：两人都在线时，给 `status.nearbyPlayers`（发起时快照的那份列表）逐个发 `HugRenderPayload(..., true, 0)`，然后删表。任一不在线则**什么都不做**（表也不删）。

`HugStatus` 不可变字段：两个 UUID、两个 `Vec3`、`nearbyPlayers`；可变字段：`boolean active`（恒 true）、`short ticksRemaining`。

### 7.4 `HugAnimationEnum`

```java
public enum HugAnimationEnum { NORMALHUG, /* FLYHUG, */ TOUCHHEADHUG }
```

`FLYHUG` 被注释掉了（历史上存在过，动作文件从未做出来）。当前 2 个取值，`ordinal()` = 0 / 1，这就是线上传的 `animationEnum`。改动枚举顺序会破坏兼容。

### 7.5 `AnimationManager`（仅客户端）

- 类注解：`@EventBusSubscriber(modid = "hugme", bus = Bus.MOD, value = Dist.CLIENT)`。
- `onClientSetup(FMLClientSetupEvent)`：
  ```java
  PlayerAnimationFactory.ANIMATION_DATA_FACTORY.registerFactory(
      ResourceLocation.fromNamespaceAndPath("hugme","animations"),   // 层 id
      42,                                                            // 优先级
      player -> {
          if (player instanceof AbstractClientPlayer) {
              ModifierLayer<IAnimation> layer = new ModifierLayer<>();
              layer.addModifierBefore(new SpeedModifier(0.5F));       // 全局 50% 速度
              return layer;
          }
          return null;
      });
  ```
  层 id 是 `hugme:animations`，优先级 42（数值越大越靠后/越外层，具体语义由 playerAnimator 决定；照抄即可）。
- `playHugAnimation(sender, receiver, HugAnimationEnum)`：
  ```java
  ModifierLayer<IAnimation> sLayer = (ModifierLayer<IAnimation>) PlayerAnimationAccess
          .getPlayerAssociatedData(sender).get(ResourceLocation.fromNamespaceAndPath("hugme","animations"));
  ModifierLayer<IAnimation> rLayer = …同 receiver…;
  ```
  然后按枚举取动作并构造播放器（三种分支代码完全相同，只有动作 id 不同）：

  | 枚举 | 发起者动作 id | 接受者动作 id |
  |---|---|---|
  | `NORMALHUG` | `hugme:hug_normal_sender` | `hugme:hug_normal_receiver` |
  | `TOUCHHEADHUG` | `hugme:hug_touch_sender` | `hugme:hug_touch_receiver` |
  | `default`（兜底） | 同 `NORMALHUG` | 同 `NORMALHUG` |

  每个播放器都是：
  ```java
  new KeyframeAnimationPlayer((KeyframeAnimation) PlayerAnimationRegistry.getAnimation(id))
      .setFirstPersonMode(FirstPersonMode.THIRD_PERSON_MODEL)
      .setFirstPersonConfiguration(new FirstPersonConfiguration()
          .setShowLeftArm(true).setShowLeftItem(false)
          .setShowRightArm(true).setShowRightItem(false));
  ```
  最后 `sLayer.setAnimation(sPlayer); rLayer.setAnimation(rPlayer);`
- 隐患：`getAnimation` 返回 null 时会在构造/设值时 NPE；`sender == null` 有判空，`receiver == null` 没有（调用方已判）。

### 7.6 `HugRenderPayload`（网络包）

- `record HugRenderPayload(String sender, String receiver, boolean isdone, int animationEnum) implements CustomPacketPayload`。
- `TYPE = new CustomPacketPayload.Type<>(ResourceLocation("hugme","render_hug"))`。
- `STREAM_CODEC = StreamCodec.composite(STRING_UTF8, sender, STRING_UTF8, receiver, BOOL, isdone, INT, animationEnum, HugRenderPayload::new)`。
- 注册（MOD bus）：`event.registrar("1").playToClient(TYPE, STREAM_CODEC, HugRenderClientHandler::handleHugRender)`。协议版本字符串 `"1"`。

### 7.7 `HugRenderClientHandler`（仅客户端）

```java
context.enqueueWork(() -> {
    if (client.level == null) return;
    AbstractClientPlayer s = (AbstractClientPlayer) client.level.getPlayerByUUID(UUID.fromString(payload.sender()));
    AbstractClientPlayer r = (AbstractClientPlayer) client.level.getPlayerByUUID(UUID.fromString(payload.receiver()));
    if (s == null || r == null) return;
    if (!payload.isdone()) {
        RenderPlayerEventHandler.lockPlayers(s, r);
        AnimationManager.playHugAnimation(s, r, HugAnimationEnum.values()[payload.animationEnum()]);
    } else {
        RenderPlayerEventHandler.unlockPlayers(s, r);
    }
}).exceptionally(e -> { context.disconnect(translatable("hugme.networking.failed", e.getMessage())); return null; });
```

要点：
- `values()[animationEnum]` 无越界保护（服务端永远传合法 ordinal）。
- 开始时会 `LOGGER.info("Starting hug animation …")`。
- **结束包不解绑动画**，只解绑渲染锁；动作靠自然播完（K6/9.4）。

### 7.8 `RenderPlayerEventHandler`（仅客户端，最重要的一段）

#### 7.8.1 数据结构

```java
static final Map<UUID, Pair<UUID,Boolean>> playerLockMap = new HashMap<>();   // 自己 → (对方, 是否藏头)
lockPlayers(s, r)   → put(s, (r,true)); put(r, (s,false));
unlockPlayers(s, r) → remove(s); remove(r);
```

发起者 `hideHead=true`，接受者 `false`。

#### 7.8.2 渲染流程【事实】

`@EventBusSubscriber(modid="hugme", value=Dist.CLIENT, bus=Bus.GAME)` → `RenderPlayerEvent.Pre`：

```
player instanceof AbstractClientPlayer?
playerLockMap 含 player.uuid?
target = client.level.getPlayerByUUID(partner); target != null?      // 否则静默走原版
targetYaw = degrees(atan2(target.z−player.z, target.x−player.x)) − 90
poseStack.pushPose()
poseStack.mulPose(Axis.XP.rotationDegrees(180))
poseStack.translate(0, −1.5, 0)
poseStack.mulPose(Quaternionf().rotationY(radians(targetYaw)))
animationPlayer = ((IAnimatedPlayer) player).playerAnimator_getAnimation()
animationPlayer.setTickDelta(partialTicks)
if (animationPlayer.isActive()) {
    pos  = animationPlayer.get3DTransform("body", POSITION, Vec3f.ZERO)
    poseStack.translate(pos.x, pos.y + 0.7, pos.z)
    rot  = animationPlayer.get3DTransform("body", ROTATION, Vec3f.ZERO)
    poseStack.mulPose(Axis.ZP.rotation(rot.z))
    poseStack.mulPose(Axis.YP.rotation(rot.y))
    poseStack.mulPose(Axis.XP.rotation(rot.x))
    poseStack.translate(0, −0.7, 0)
}
poseStack.scale(1,1,1)
renderPlayerModel(...)
poseStack.popPose()
event.setCanceled(true)
```

`renderPlayerModel`：

```java
PlayerModel<AbstractClientPlayer> model = renderer.getModel();
model.young = entity.isBaby();
model.head.visible = client.options.getCameraType() != CameraType.FIRST_PERSON || !hideHead || !(entity instanceof LocalPlayer);
model.setupAnim(entity, 0,0,0, 0,0);                       // 四肢摆动参数全 0 → 冻结姿势
RenderType rt = RenderType.entityCutoutNoCull(renderer.getTextureLocation(entity));
model.renderToBuffer(poseStack, buffer.getBuffer(rt), packedLight, OverlayTexture.NO_OVERLAY, -1);
```

#### 7.8.3 这段变换到底做了什么【推导】

原版在 `PlayerRenderer.render` 进入时（也就是 `RenderPlayerEvent.Pre` 触发的那一刻），`PoseStack` **只有实体的平移**，还没有任何旋转/缩放/翻转——原版的 `setupRotations` + `scale(-1,-1,1)` + `translate(0,-1.501,0)` + `scale(0.9375)` 都在 `super.render` 内部，被 `setCanceled(true)` 跳过了。所以这里必须自己从「模型空间」一路摆到世界。

`PoseStack` 的 `mulPose`/`translate` 是**后乘**（`this = this · M`），顶点按 `v' = A₁·A₂·…·v` 变换，所以**最后调用的最先作用到顶点**——这一点极易写反，写反会得出「模型镜像」的错误结论。按调用顺序（`mulPose(Rx)` → `translate(0,−1.5,0)` → `mulPose(Ry(θ))` → body 变换）合成：

```
P_hug   = Rx(180°) · T(0, −1.5, 0) · Ry(θ) · (body 变换)
旋转块   Rx(180°)·Ry(θ)  ==  Ry(−θ)·Rx(180°)          ← 恒等式，可展开验证
顶点     v' = Rx·Ry(θ)·v + (0, +1.5, 0)               ← 不含 body 时
```

手工核对（θ=0）：脚底 (0,1.5,0) → `Rx·Ry` 后 (0,−1.5,0)，+(0,1.5,0) → **(0,0,0)** ✓；头顶 (0,−0.5,0) → (0,0.5,0)+(0,1.5,0) → **(0,2.0,0)**。朝向：模型脸朝 −Z，`Rx·Ry(θ)` 之后是 **(−sin θ, cos θ)**，正是 Minecraft 的 yaw 约定。

作为对照，原版玩家的合成变换是（调用顺序 `mulPose(Ry)` → `scale(−1,−1,1)` → `scale(0.9375)` → `translate(0,−1.501,0)`）：

```
P_vanilla = Ry(180° − yaw) · S(−1,−1,1) · S(0.9375) · T(0,−1.501,0)
          = Ry(−yaw) · Rx(180°) · S(0.9375) · T(0,−1.501,0)
          （用了 Ry(180°)·S(−1,−1,1) = Rx(180°)）
```

把 θ 取成实体的 body yaw，两者**旋转块完全相同**（`Rx·Ry(θ) = Ry(−θ)·Rx`），朝向、左右、镜像全部一致。真正的差异只有两处：

| 差异 | 原版 | HugMe 原实现 | 后果 |
|---|---|---|---|
| 整体缩放 | `0.9375` | 无（`scale(1,1,1)`） | 纸片人高 2.0 格而非 1.876 格（K7），且比不拥抱的玩家大 6.7% |
| 垂直基准 | −1.501（再 ×0.9375） | −1.5 | 脚底正好在 y=0，原版高 0.00094 格，肉眼无差 |

结论：**变换本身是正确的，四个方位都不会背对目标**。一个很容易踩的坑是把它误判成「yaw 符号反、缺 X 镜像」——那正是把矩阵乘法顺序写反的结果；按后乘正确展开后 `Rx(180°)·Ry(θ)` 与 `Ry(−θ)·Rx(180°)` 恒等，两种写法等价。

#### 7.8.4 `get3DTransform("body")` 分支——它是「贴合」的关键，不是死代码【推导】

这段不是遗留钩子：**接受者往对方怀里压 1 格，靠的就是它。**

① `torso` 在数据里其实被解析成 `body`。四份 JSON 都**没有** `version` 字段 → 解析器默认 `version = 1`，而 playerAnimator 在 `version < 3` 时把 `torso` 重命名为 `body`（`AnimationBuilder` 的行为）。`body` 是**整模根节点**：它的一次性变换是「平移 (x, y, z)，再依次绕 Z、Y、X 旋转」，而不是某根骨骼。
所以 `hug_normal_receiver` 的 `torso.z = −1.0` 等价于 `body.z = −1.0` → 整个纸片人沿自身朝向**前移 1 格**；`hug_touch_receiver` 是 0.75 格。站位间距 1.3 格，前移 1.0 后两人模型中心只差 0.3 格——这就是画面上「抱在一起」的来源。

② 为什么必须在这里手写一遍？playerAnimator 对 `body` 的处理原本挂在 `PlayerRendererMixin`（`setupRotations` 阶段）：`translate(x, y+0.7, z)` → 绕 Z/Y/X 旋转 → `translate(0,−0.7,0)`（`+0.7` 是把旋转枢轴抬到胯部高度）。而本模组在 `RenderPlayerEvent.Pre` 里 `setCanceled(true)`，**整条 `PlayerRenderer.render` 不再执行，那个 mixin 也不会跑到**，所以必须把同样的 5 行照抄到自己渲染前——代码里的 `+0.7 / −0.7` 与 `Z→Y→X` 顺序和 KosmX 的 mixin 一字不差，正是这个原因。

③ 生效前提：`IAnimatedPlayer#playerAnimator_getAnimation()` 返回的是**层栈合成的 applier**（不是「主层」），所以它能读到自定义层 `hugme:animations` 里的 `body` 通道；`isActive()` 也因此为 true。如果复刻后接受者**没有前倾贴合**、两人空出 1 格，第一嫌疑就是这里——检查 `get3DTransform("body", POSITION, ZERO)` 是否返回了 `z = −1.0`。

④ body 平移作用在「模型空间」（在 `Ry` 之前），所以 `z` 是**沿纸片人自身朝向的前后位移**，负值＝朝对方。这一点依赖调用顺序；把 body 变换挪到 `mulPose(Ry)` 之后，语义会变成世界坐标轴，贴合方向就错了。

#### 7.8.5 原版渲染被取消后丢失的东西【推导】

因为整条 `PlayerRenderer.render` 被跳过：
- 不会绘制护甲、手持物、披风、鞘翅、头部装饰层（`hat` 层仍由 `PlayerModel.renderToBuffer` 画，因为它是模型的一部分）；
- 没有受伤闪红（`OverlayTexture.NO_OVERLAY`）、没有变暗/发光（color = −1）；
- 没有阴影、没有「坐下/睡觉」等特殊姿势处理；
- 名牌由 `EntityRenderDispatcher` 在 `renderer.render` **之后**单独绘制，通常仍会显示（未实测）。

---

## 8. 网络协议

只有 1 个包。【事实】

| 项 | 值 |
|---|---|
| 类型 id | `hugme:render_hug` |
| 方向 | `playToClient`（服务端 → 客户端） |
| 协议版本 | 注册器传入 `"1"` |
| 发送方式 | `PacketDistributor.sendToPlayer(player, payload)` 逐个发（不是 `sendToPlayersNear`） |
| 载荷顺序 | `sender:String` → `receiver:String` → `isdone:bool` → `animationEnum:int` |
| 编码 | `STRING_UTF8`（VarInt 长度 + UTF-8），`BOOL`（1 字节），`INT`（VarInt） |
| 字段含义 | `sender`/`receiver` = `UUID.toString()`（带连字符的 36 字符）；`isdone=false` 开始，`true` 结束；`animationEnum` = 枚举 ordinal，结束包恒 0 |

触发点：接受成功时给「发起者所在维度、距发起者 <64 格」的所有玩家各发一次；6 秒后对**同一份快照列表**再发一次结束包。

---

## 9. 动作数据规格

### 9.1 格式【事实】

这是 KosmX playerAnimator（源自 Emotecraft）的 `emote` JSON，放在 `assets/<namespace>/player_animation/` 下：

```json
{
  "name": "hug_normal_sender",
  "author": "TuYw",
  "description": "normal1",
  "emote": {
    "isLoop": "false",
    "returnTick": 2,
    "beginTick": 0,
    "endTick": 53,
    "stopTick": 61,
    "degrees": false,
    "moves": [
      { "tick": 34, "easing": "EASEINOUTQUAD", "turn": 0, "head": { "yaw": 0.2591 } },
      { "tick": 20, "easing": "EASEINOUTQUAD", "turn": 0, "torso": { "z": -1.0 } }
    ]
  }
}
```

字段语义（对照 playerAnimator 1.20 线 `AnimationJson` / `KeyframeAnimation` / `AnimationBuilder` 的实现核对过）：

- `name`：必填（缺失会 NPE），注册 id 的来源；**会被转小写**再拼成 `<文件命名空间>:<name>`。
- `version`：可选，默认 `1`。**必须保持缺省**——`version < 3` 时解析器把 `torso` 重命名为 `body`（见 7.8.4、K6），这正是「前冲贴进怀里」的实现基础。补上 `"version": 3` 会改变这四份文件的语义。
- `emote`：必填。没有 `emote` 键的文件会被当成 GeckoLib 格式解析，直接失败。
- `emote.endTick`：必填且 `> 0`。`emote.stopTick`：可选，默认等于 `endTick`；若 `<= endTick` 会被改写成 `endTick + 3`。本模组 `end=53/54 < stop=61`，按原值生效。
- `emote.beginTick`：可选，默认 0。
- `emote.returnTick`：可选，默认 0；**只有它存在时 `isLoop` 才会被读取**。本模组 `returnTick=2`，所以 `isLoop` 确实生效。
- `emote.isLoop`：写成 JSON 字符串 `"false"` 也行（走 Gson 的 `getAsBoolean()` 强制转换）。本模组是有限次播放。
- `emote.degrees`：**缺省时默认 `true`（角度制）**。本模组显式写 `false` → 所有旋转值都是**弧度**；位置值仍是「模型像素」（16 = 1 格）。
- `emote.moves`：必填数组。每个元素 = 某个 tick 上的一组通道值。
- `moves[].tick`（int，必填）、`moves[].easing`（字符串，可选，默认 `linear`）、`moves[].turn`（int，可选，默认 0）。除这三个键与 `comment` 外，其余键一律当作**部位名**。
- `turn` **不是**身体转向：它只对角轴（`pitch`/`yaw`/`roll`）生效，会在同一 tick 额外插入一个 `值 + 2π·turn` 的关键帧，用来强制该段多转整数圈（走长弧而非短弧）。本模组全为 0，等于不用。
- `easing` 大小写不敏感、可带 `EASE` 前缀；**不认识的名字静默退化为 `LINEAR`**。本模组全部用 `EASEINOUTQUAD`。
- 部位名（解析器认的）：`head` / `body` / `torso` / `rightArm` / `leftArm` / `rightLeg` / `leftLeg` / `leftItem` / `rightItem`。写成 `right_arm` 这类蛇形**不认**，会变成一个永不渲染的空部位。
- 位置通道 `x`/`y`/`z`：部位的**绝对模型坐标**（不是相对偏移），单位 1/16 格，不写就保持默认枢轴。唯一例外是 `body`：它的位置是相对整模的**增量**，单位是格，+Y 向上。
- 旋转通道 `pitch`/`yaw`/`roll`：分别映射到 `xRot`/`yRot`/`zRot`，插值结果会被钳制在 `[−π, π]`。
- 模型空间是 **Y 向下**（腿在 `y=12`、头在 `y=−8`），四肢/头相对父级（躯干）定位。
- 同一部位同一轴的重复 tick 不会被拒绝，**后写的覆盖先写的**（本模组数据里没有重复 tick）。

### 9.2 注册 id 的推导【事实 + 推导】

`PlayerAnimationRegistry`（客户端专用类）会扫描所有命名空间的 `assets/<ns>/player_animation/**.json`，并以**文件内的 `name` 字段**（不是文件名，且转小写）注册成 `<文件命名空间>:<name>`。所以：
`normal_s.json` + `"name":"hug_normal_sender"` → `hugme:hug_normal_sender`。
代码里 `PlayerAnimationRegistry.getAnimation(ResourceLocation("hugme","hug_normal_sender"))` 才能取到；**取不到返回 `null`**，原实现没有判空，会直接 NPE 在 `new KeyframeAnimationPlayer(null)` 处（见 K19）。
扫描发生在**客户端资源重载**时（不是 mod 构造期），每次重载先 `clear()` 再重建。
**复刻时必须保持 `name` 字段不变**，文件名可以随便改。

### 9.3 逐通道关键帧表【事实】

值为弧度；`tick:值` 列表按 tick 升序。
表中 `body.*` 在原 JSON 里写作 `torso.*`——因为文件没有 `version` 字段，解析器按 `version=1` 把它重命名成 `body`（整模根节点，见 7.8.4）。**通道名按解析后的语义写在这里，抄数据时请抄附录 B 的原文（`torso`）。**

**`hug_normal_sender`（normal_s.json）** — `isLoop=false, begin=0, end=53, stop=61, return=2, moves=26`，无 body/腿部通道。

| 通道 | 关键帧 |
|---|---|
| `head.yaw` | 24:0, 34:0.0046, 41:0.4140, 47:−0.0298, 53:0.5755 |
| `head.roll` | 24:0, 34:0.3159, 41:0.3462, 47:0.3161, 53:0.3794 |
| `head.pitch` | 24:0, 34:0.2591, 41:0.4018, 47:0.2479, 53:0.4713 |
| `rightArm.yaw` | 10:0.8727, 20:0.8727, 30:−0.5236 |
| `rightArm.pitch` | 10:−0.6981, 20:−0.6981 |
| `rightArm.roll` | 10:0.0698 |
| `leftArm.yaw` | 10:−0.8727, 20:−0.8727, 30:0.5236 |
| `leftArm.pitch` | 10:−0.6981 |
| `leftArm.roll` | 10:−0.0698 |

**`hug_normal_receiver`（normal_r.json）** — `end=53, stop=61, return=2, moves=32`。

| 通道 | 关键帧 |
|---|---|
| `head.yaw` | 25:0, 34:0.2516, 41:−0.1547, 47:0.1957, 53:−0.1976 |
| `head.roll` | 25:0, 34:0.1332, 41:0.1306, 47:0.1315, 53:0.1316 |
| `head.pitch` | 25:0, 34:0.3261, 41:0.2725, 47:0.3185, 53:0.2668 |
| `body.z` | 20:−1.0（整体前移 1 格） |
| `rightArm.yaw` | 15:0.8727, 25:−0.3840 |
| `rightArm.pitch` | 15:−1.0472 |
| `rightArm.roll` | 15:0.0698 |
| `leftArm.yaw` | 15:−0.8727, 25:0.3840 |
| `leftArm.pitch` | 15:−1.0472 |
| `leftArm.roll` | 15:−0.0698 |
| `rightLeg.pitch` | 5:0.9076, 10:0, 15:−0.8727, 20:0 |
| `leftLeg.pitch` | 5:−0.9076, 10:0, 15:0.8727, 20:0 |

**`hug_touch_sender`（touch_s.json）** — `end=54, stop=61, return=2, moves=14`。

| 通道 | 关键帧 |
|---|---|
| `head.pitch` | 30:−0.1222 |
| `head.roll` | 30:0.1396 |
| `rightArm.yaw` | 30:0.2628, 36:−0.2084 |
| `rightArm.roll` | 30:0.2584, 36:0.6773, 42:0.1886, 48:0.6424, 54:0.0839 |
| `rightArm.pitch` | 30:−2.5123 |
| `leftArm.yaw` | 20:0, 30:0.1396 |
| `leftArm.pitch` | 20:0, 30:−0.7679 |

**`hug_touch_receiver`（touch_r.json）** — `end=54, stop=61, return=2, moves=36`。

| 通道 | 关键帧 |
|---|---|
| `head.yaw` | 30:0, 36:−0.1893, 42:0.2105, 48:−0.3374, 54:0.0694 |
| `head.pitch` | 30:0.3715 |
| `body.z` | 20:−0.75（整体前移 0.75 格） |
| `body.yaw` | 20:0, 30:0 |
| `body.roll` | 30:0 |
| `body.pitch` | 20:0, 30:0 |
| `rightArm.pitch` | 5:−0.7330, 10:0, 15:0.3665, 20:0, 30:−0.8029 |
| `rightArm.yaw` | 30:−0.1745 |
| `leftArm.pitch` | 5:0.7330, 10:0, 15:−0.7330, 20:0, 30:−0.8029 |
| `leftArm.yaw` | 30:0.1745 |
| `rightLeg.pitch` | 5:0.9076, 10:0, 15:−0.9076, 20:0, 30:0 |
| `leftLeg.pitch` | 5:−0.9076, 10:0, 15:0.9076, 20:0, 30:0 |
| `leftLeg.yaw` | 30:0 |
| `leftLeg.roll` | 30:0 |

动作语义（【推导】，用于理解数值意图）：
- **普通拥抱**：发起者双臂前伸（`rightArm.pitch = −0.6981 ≈ −40°`，负 pitch 让手臂向前抬），外展 50°（yaw ±0.8727）后在第 30 tick 收拢（±0.5236/0.3840）；接受者 `body.z = −1.0` 把**整个纸片人**前推 1 格压进对方怀里（站位 1.3 − 前推 1.0 = 模型中心只剩 0.3 格），双臂前伸更平（pitch −1.0472 = −60°），双腿在第 5~20 tick 做一次「迈步贴近」的踏步；两人头部在第 24~53 tick 有轻微不同步的摆动（蹭头）。
- **摸头拥抱**：发起者右手举到头顶（`rightArm.pitch = −2.5123 ≈ −144°`）并左右摇摆（roll 在 0.0839~0.6773 间往复 4 次）；接受者 `body.z = −0.75` 前推 0.75 格、头微低（pitch 0.3715）、双臂做两次小幅抬放（pitch ±0.7330），双腿交替踏步。
- 所有动作的 `endTick`（53/54）早于 `stopTick`（61），最后 7~8 tick 是保持+回归段。
- 两人身体能对上，靠的是「服务端把距离压到 1.3」+「接受者根节点前推 1.0」+「客户端把两人转向对方」三者叠加，缺一不可。

### 9.4 播放参数【事实 + 推导】

- 层 `hugme:animations` 上挂了 `SpeedModifier(0.5F)` → 上述 tick 数在客户端**翻倍**：61 tick → 122 tick ≈ 6.1 s。
- 服务端锁定 120 tick = 6.0 s。所以动作比锁定**晚约 2 tick** 结束，结束时不会看到姿势突然复位。
- 第一人称：`FirstPersonMode.THIRD_PERSON_MODEL` + 左右臂显示、手持物隐藏 → 第一人称能看到自己的身体在拥抱。

---

## 10. 常量总表

| 常量 | 值 | 位置 | 作用 |
|---|---|---|---|
| `MODID` | `hugme` | `HugMe` | 全模组命名空间 |
| 物品 id | `hugme:hug_ticket` | `HugMe` | 抱抱券 |
| `stacksTo` | `64` | `HugMe` | 堆叠上限 |
| 首登赠券 | `16` | `HugMe` | 每个存档首次登录 |
| `REQUEST_TIMEOUT_S` | `60` 秒 | `HugCommandHandler` | 请求过期 |
| `AnimTick` | `short 120`（`20*6`） | `HugCommandHandler` | 拥抱锁定 tick 数 |
| 最大接受距离 | `5.0` 格 | `HugCommandHandler` | `distanceTo > 5` 拒绝 |
| 广播半径 | `< 64` 格 | `HugCommandHandler` | 旁观者范围（同维度） |
| 站位间距 | `1.3` 格 | `HugCommandHandler` | 瞬移落点距离 |
| 站位盒半宽 | `0.3` | `HugCommandHandler` | 安全检查 |
| 脚下检测 | `y−0.1 … y` | `HugCommandHandler` | 需有碰撞 |
| 身体检测 | `y+0.1 … y+1.7` | `HugCommandHandler` | 需无碰撞 |
| 动画层 id | `hugme:animations` | `AnimationManager` | playerAnimator 层 |
| 层优先级 | `42` | `AnimationManager` | registerFactory |
| 播放速度 | `0.5` | `AnimationManager` | `SpeedModifier` |
| 协议版本 | `"1"` | `HugRenderPayload` | `registrar("1")` |
| 包 id | `hugme:render_hug` | `HugRenderPayload` | — |
| `renderPlayerModel` body 变换 | `+0.7 / −0.7` | `RenderPlayerEventHandler` | 复刻 KosmX mixin 的胯部枢轴 + 根节点位移 |
| 模型空间翻转 | `Rx(180°)` 后接 `T(0,−1.5,0)` | `RenderPlayerEventHandler` | 模型空间（Y 向下）→世界（Y 向上） |
| 配方产出 | `4` | recipe json | 一次 4 张 |

---

## 11. 已知缺陷与边界情况

复刻时逐条决策：**保持**（为了行为一致）或**修正**（为了健壮）。已标注建议。

| # | 现象 | 根因（代码位置） | 建议 |
|---|---|---|---|
| K1 | 60 秒过期回调在调度线程上调用 `ServerPlayer.sendSystemMessage`，跨线程访问服务端对象 | `startHugRequest` 的 `scheduler.schedule(...)` | 修正：改成服务端 tick 里扫过期，或 `server.execute(...)` 回主线程 |
| K2 | 调度线程池 1 个**非守护**线程，永不 `shutdown`；任务强引用 `ServerPlayer` 最长 60 s | 静态 `Executors.newScheduledThreadPool(1)` | 修正：用 tick 计时，或守护线程 + 关闭钩子 |
| K3 | 站位检查不通过时，双方已经先收到「已接受」绿字 | `handleHugAcceptance` 里消息在校验之前 | 保持或修正（把消息挪到检查之后更合理） |
| K4 | 接受者被摆到发起者**背后** 1.3 格（越过发起者） | `sender.position().subtract(receiver.position())` 方向取反 | 见 7.3.2：要「拉到自己面前」应改为 `receiver.position().subtract(sender.position())`。视觉上第三视角几乎看不出（纸片人由客户端摆正），第一人称能感觉到 |
| K5 | 两人 XZ 完全重合时 `horizontalDistance = 0` → 除零 → `NaN` 坐标 → 瞬移失败/异常 | 同上，缺零长度保护 | 修正：距离 < 1e-4 时退化为按发起者朝向取偏移 |
| K6 | 动作数据里写的 `torso` 其实被当成 `body`（整模根节点）——因为四份 JSON 都没有 `version` 字段，解析器默认 `version=1`，而 `version<3` 时会把 `torso` 重命名为 `body`。若给文件补上 `"version": 3`，`torso` 会变成一个独立的躯干骨骼，前推贴合直接失效，两人拉开 1 格 | `hug_normal_receiver` / `hug_touch_receiver` 的 `torso.z`（配合 7.8.4） | 保持：**不要给这 4 份文件加 `version` 字段**；若目标库不认这个重命名规则，直接把键名改成 `body` 并保留 `z` 值 |
| K7 | 被接管渲染的玩家比平时高约 6.7% | 缺少 vanilla 的 `0.9375` 缩放 | 保持（换成 0.9375 高度才对，但会整体缩小，需同步验证贴合度） |
| K8 | 复刻时若把 playerAnimator 的 body 变换放到错误位置（例如挪到 `mulPose(Ry)` 之后），位移语义就从「沿纸片人自身朝向」变成「沿世界坐标轴」，朝向一变贴合方向就错，两人可能错开 | `RenderPlayerEventHandler` 里 body 变换必须位于 `mulPose`/`translate` 之后（最内层） | 保持原顺序；改顺序前先确认 `get3DTransform` 返回的是模型空间增量（7.8.4④） |
| K9 | 拥抱中途有人掉线：只删状态、不发结束包 → 其余客户端永久保持锁定，直到那名玩家重新登录把包补上（实际不会补） | `doLock` 里 `hugStatuses.remove(...)` 但没发 `isdone` | 修正：掉线时给旁观者补发结束包，或客户端加超时兜底 |
| K10 | `model.head.visible` 写的是 `PlayerRenderer` 共享的模型实例，退出拥抱后不恢复 | `renderPlayerModel` 直接赋值 | 保持（下一次非第一人称渲染会把它设回 true，影响有限） |
| K11 | `HugStatus.active` 永远是 `true`；`!isActive()` 分支是死代码；`endHug` 在有人离线时连状态都不删 | `onServerTickEnd` / `endHug` | 修正：删掉死代码，离线时也清理 |
| K12 | A→B 与 B→A 两个方向的请求可同时存在；同一对玩家可以叠两条 | 请求表键是**有序** Pair | 保持（原行为）或改成无序键 |
| K13 | 接受时未校验 `request.receiverId` | `handleHugAcceptance`（拒绝路径有校验） | 保持（key 已隐含） |
| K14 | 无权限、无冷却、无游戏模式/无敌/摔落保护；用 `teleportTo` 钉人，可能被传送拦截类模组/反作弊打断 | 整体设计 | 按需加开关 |
| K15 | 服务端注册包时引用了客户端类 `HugRenderClientHandler`，仅因类初始化惰性才没在专用服务端出错 | `HugRenderPayload.register` | 修正：把 `playToClient` 的 handler 放进 `Dist.CLIENT` 才加载的类，或用 `context.enqueueWork` 包一层间接调用 |
| K16 | 物品右键失败（重复请求）时仍返回 `sidedSuccess`，客户端会播挥手动画 | `HugTicketItem.interactLivingEntity` | 保持 |
| K17 | `hugRequests` / `hugStatuses` 无持久化：重启服后清空，进行中的拥抱直接消失（客户端锁定残留到重登） | 全内存状态 | 保持（社交模组可接受）或加清理 |
| K18 | 枚举 `ordinal()` 直接上线；调整枚举顺序会让新旧客户端错位 | `HugAnimationEnum` + payload | 修正（若要长期维护）：改用字符串 id 或显式 id 映射 |
| K19 | 动作 id 不存在时 `PlayerAnimationRegistry.getAnimation` 返回 `null`，随后 `new KeyframeAnimationPlayer(null)` NPE，收包处理直接抛异常 | `AnimationManager.playHugAnimation` 无判空 | 修正：判空并降级为「不播动作」 |
| K20 | 首登发券用「`.dat` 是否存在」判定：玩家首次进入后在存档保存前断线/崩服，下次进来会再发一次 | `HugMe.onPlayerLoggedIn` | 保持（原行为）或改用持久化标记 |

---

## 12. 可复用资产清单（面向 1.20.1 / Forge）

> 本节只做**可复用性判定**，不涉及迁移方案。判定基准：1.20.1 + Forge（ForgeGradle 6 / `mods.toml` / `SimpleChannel` / 官方映射）。

### 12.1 可直接原样复制（零改动）

| 资产 | 路径 | 说明 |
|---|---|---|
| **4 份动作数据** | `assets/hugme/player_animation/*.json` | 这是最有价值的可复用资产。格式由 playerAnimator 决定，与加载器/游戏版本无关；`name` 字段、通道、弧度值全部可保留。唯一依赖是 1.20.1 的 playerAnimator 能解析同一套 `emote` JSON（已核实：该线同样提供 `player_animation` 目录与 `PlayerAnimationRegistry`，见 12.6） |
| 动作数值表 | 本文 9.3 | 若目标库的 JSON 字段名有变，可按表重写数据，无需重新 K 帧 |
| 物品模型 | `assets/hugme/models/item/hug_ticket.json` | `item/generated` + `layer0` 是 1.20.1 也完全一样的格式 |
| 物品贴图 | `assets/hugme/textures/item/hug_ticket.png` | 纯资源，64×64 RGBA |
| 模组 logo | `src/main/resources/hug_ticket.png` | 与物品贴图同一份字节 |
| 语言文件 | `assets/hugme/lang/{zh_cn,en_us}.json` | 20 个键全部保留；1.20.1 与 1.21 的 `Component.translatable` 用法一致 |
| README 文案 | `README.md` | 3 行中文描述 |
| 玩法常量 | 第 10 节 | 60s / 120tick / 5 格 / 64 格 / 1.3 格 / 0.5 速度 等全部与平台无关 |

### 12.2 需改字段名/目录名（数据语义不变）

| 资产 | 差异点 | 1.21 原样 | 1.20.1 需要的样子 |
|---|---|---|---|
| 合成配方 | 目录名 + result 结构 | `data/hugme/recipe/hug_ticket.json`，`"result": {"count":4,"id":"hugme:hug_ticket"}` | `data/hugme/recipes/hug_ticket.json`，`"result": {"item":"hugme:hug_ticket","count":4}` |
| 模组元数据 | 文件名 + 依赖段写法 | `META-INF/neoforge.mods.toml`，依赖 `neoforge` | `META-INF/mods.toml`，必须显式 `modLoader="javafml"`，依赖 `forge`（并保留 `playeranimator` 依赖项） |
| 物品注册 | API 形态 | `DeferredRegister.Items` + `DeferredItem` | `DeferredRegister<Item>` + `RegistryObject<Item>`/`DeferredHolder` |
| 网络包 | 机制 | `CustomPacketPayload` + `StreamCodec` + `playToClient` | `SimpleChannel` + `FriendlyByteBuf` 手写 encode/decode |
| 事件订阅 | 注解形态 | `@EventBusSubscriber(bus=Bus.MOD/GAME, value=Dist.CLIENT)` | `@Mod.EventBusSubscriber(modid=…, bus=…, value=…)` |
| 资源路径 | 维度/坐标取法 | `ResourceLocation.fromNamespaceAndPath(ns,path)` | `new ResourceLocation(ns, path)` |

### 12.3 逻辑可复用、代码需改写（平台耦合点）

| 文件 | 与平台/版本耦合的点 | 逻辑本身 |
|---|---|---|
| `HugMe.java` | `DeferredRegister.Items`、`ModContainer` 构造器、`BuildCreativeModeTabContentsEvent`、`PlayerEvent.PlayerLoggedInEvent`（1.20.1 在 `net.minecraftforge.event.entity.player` 包下，方法名 `getEntity()` 相同）、`ServerLifecycleHooks`、`LevelResource.PLAYER_DATA_DIR` | 「首登发 16 张」的判定法（查 `.dat` 是否存在）可直接照搬 |
| `HugTicketItem.java` | `Item.TooltipContext`（1.20.1 的 `appendHoverText` 第二参是 `Level`）、`InteractionResult.sidedSuccess` 存在与否、`ClickEvent` 构造 | 右键逻辑、消息拼接、扣券顺序全部照搬 |
| `HugCommandHandler.java` | `ServerTickEvent.Post`（1.20.1 是 `TickEvent.ServerTickEvent` 且分 Phase）、`PacketDistributor.sendToPlayer`、`EventBusSubscriber`、`ServerLifecycleHooks` | 请求表/拥抱表、落点公式、安全检查、60s 过期、每 tick 回拉：**纯 Java + MC 通用类，可整体照搬**，只换事件与发包入口 |
| `HugRenderPayload.java` | `CustomPacketPayload`/`StreamCodec` 整个换掉 | 4 个字段的顺序与含义必须保留 |
| `HugRenderClientHandler.java` | `IPayloadContext` → `Supplier<NetworkEvent.Context>` | 查玩家、加/解锁、播动作的顺序照搬 |
| `AnimationManager.java` | `FMLClientSetupEvent`、`EventBusSubscriber`、`ResourceLocation.fromNamespaceAndPath`；playerAnimator 在 1.20 线的三处签名差异见 12.6 | 层注册（id/优先级/0.5 速度）、动作 id、第一人称配置：概念 100% 保留 |
| `RenderPlayerEventHandler.java` | `RenderPlayerEvent.Pre`（1.20.1 在 `net.minecraftforge.client.event` 下，`getPartialTick()` / `getPackedLight()` 同名）、`PoseStack`/`Axis` 相同、`IAnimatedPlayer` 来自 playerAnimator；`ModelPart#renderToBuffer` 的颜色参数形式 1.20.1 与 1.21 不同（1.21 是 `int color`） | 变换数学、locked 表、`renderPlayerModel` 全部照搬 |
| `HugAnimationEnum.java` | 无 | 整体照搬（注意 K18） |

### 12.4 明确不可复用的东西

| 项 | 原因 |
|---|---|
| `META-INF/neoforge.mods.toml` | 文件名与必填字段是 NeoForge 专属 |
| `build.gradle` / `settings.gradle` / `gradle.properties` 的构建部分 | `net.neoforged.moddev` 插件、`neoForge{}` DSL、Parchment 配置、maven 坐标——全部是 NeoForge 生态 |
| 依赖坐标 `dev.kosmx.player-anim:player-animation-lib-forge:2.0.0+1.21.1` | 版本号绑定 MC 1.21.1 |
| `CustomPacketPayload` / `StreamCodec` / `PayloadRegistrar` 相关代码 | 1.20.5+ 的新网络 API |
| 自带动作 JSON 的**解析依赖** | 文件本身可复用，但必须由目标环境的 playerAnimator 提供解析（`PlayerAnimationRegistry`） |

### 12.5 动画数据的复用要点（重点）

1. **不要**改 `name` 字段：代码里按 `hugme:hug_normal_sender` 等 4 个 id 取动作；注册 id 会取 `name` 的**小写**形式。
2. 文件名可改，`player_animation/` 目录名不要改（playerAnimator 的扫描路径）。
3. `degrees:false` + 弧度值不要混用角度（缺省 `degrees` 时按**角度**解析，写错会放大 57 倍）。
4. **不要给这 4 份文件加 `version` 字段**：`version` 缺省(=1) 时 `torso` 被解析成 `body`（整模根节点），这正是「前推贴进怀里」的实现方式；补上 `"version": 3` 会让 `torso` 变成独立骨骼，贴合直接消失（K6）。若目标库不做这个重命名，就把键名从 `torso` 改成 `body`，数值不变。
5. `SpeedModifier(0.5)` 在代码里，不在数据里 —— 目标环境若 API 不同，需要在等价位置补上，否则动作会比 6 秒锁定快一倍结束。
6. 4 份数据的 `stopTick` 都是 61、`returnTick` 都是 2、`endTick` 是 53/54，改任何一项都会改变与 120 tick 锁定的对齐关系。
7. 若目标环境用别的动作库（不是 playerAnimator），这 4 份 JSON 不能直接喂进去，但 9.3 的通道表足以重建（部位/轴/tick/弧度一一对应）。

### 12.6 目标环境的核对结果（1.20.1 线 playerAnimator）

已核实（依据 playerAnimator `1.20` 分支源码、maven 索引、Modrinth 元数据）：

- **产物坐标**：`dev.kosmx.player-anim:player-animation-lib-forge:1.0.2-rc1+1.20`。Modrinth 上该版本标 `game_versions: ["1.20","1.20.1"]` —— **没有 `+1.20.1` 后缀的版本**，1.20.1 就用 `+1.20` 这个。maven 索引里没有 neoforge 专用产物（只有 `player-animation-lib` / `-fabric` / `-forge`）。
- **modid**：Forge 侧 `playeranimator`，Fabric 侧 `player-animator` —— `mods.toml` 依赖项按 Forge 侧写。
- **JSON 格式**：`1.20` 分支即该版本的源码，本文 9.1 的字段与解析规则（含 `version<3` 时 `torso`→`body`）在该分支成立，四份动作文件可原样使用。
- **Java API 全都在**：`PlayerAnimationFactory.ANIMATION_DATA_FACTORY.registerFactory(ResourceLocation, int, PlayerAnimationFactory)`、`PlayerAnimationAccess.getPlayerAssociatedData(AbstractClientPlayer).get(ResourceLocation)`、`PlayerAnimationRegistry.getAnimation(ResourceLocation)`、`ModifierLayer` / `addModifierBefore` / `SpeedModifier(float)`、`KeyframeAnimationPlayer` + `setFirstPersonMode` / `setFirstPersonConfiguration`、`FirstPersonMode.THIRD_PERSON_MODEL`、`TransformType`。

三处**签名差异**（抄代码时要留意）：

1. `PlayerAnimationRegistry.getAnimation(...)` 在 1.20 线**已经**返回 `@Nullable KeyframeAnimation`，所以源码里的 `(KeyframeAnimation)` 强转可以留也可以去（2.0 才把返回类型放宽）。
2. `registerFactory` 第三个参数是 SAM 接口 `PlayerAnimationFactory`（入参 `AbstractClientPlayer`），不是 `Function<Player,…>`；lambda 写法不变，但 `player instanceof AbstractClientPlayer` 在 1.20 线上恒为真。
3. `setTickDelta(float)` **不在** `KeyframeAnimationPlayer` 上，而在 `IAnimatedPlayer#playerAnimator_getAnimation()` 返回的 applier 上（原实现正是这么调的）；`get3DTransform` 的 3 参重载属于 applier，`KeyframeAnimationPlayer` 上是 4 参 `(part, type, tickDelta, value0)`。

两条环境事实：`PlayerAnimationRegistry` 是 `@Environment(CLIENT)`（客户端专用）；动作文件在**客户端资源重载**时扫描并重建（不是 mod 构造期）——本模组在收包时才取动作，此时早已进游戏，不受影响。

---

## 13. 复刻验收清单

按顺序做完，行为就与原版对齐。「可观察」= 两名玩家 + 一个旁观者即可验证。

| # | 检查项 | 期望 |
|---|---|---|
| C1 | 首次进入新存档 | 背包里 16 张抱抱券；退出重进不再发 |
| C2 | 创造物品栏 | 工具与实用物品分类里有抱抱券；有灰色说明文字 |
| C3 | 合成 | `纸×4 + 淡蓝染料×2 + 粉色染料×1`（十字摆法）出 4 张 |
| C4 | 右键另一玩家 | 发起者扣 1 张并收到绿字；接受者收到「发起了拥抱请求 + [同意][拒绝]」；点按钮能执行命令 |
| C5 | 重复右键同一人 | 发起者只收到「你已经向他发送了一个拥抱请求」，券不扣 |
| C6 | 同意后 | 两人相距 1.3 格、动作开始、第三人能看到；**四个方位（东南西北）各测一次**，确认两人都面朝对方、且接受者的模型压进对方怀里约 1 格（K6 / 7.8.4） |
| C7 | 拥抱中 | 两人无法位移（仍能转头/聊天）；持续约 6 秒；旁观者 64 格内都能看到，64 格外看不到 |
| C8 | 拥抱结束 | 动作自然播完、渲染锁解除、两人恢复原版渲染（护甲/手持物回来） |
| C9 | 距离 > 5 格同意 | 只有接受者收到「太远了」，不开始 |
| C10 | 异维度同意 | 只有接受者收到「不在同一个世界」 |
| C11 | 落点被方块堵住（站不下/头顶有方块） | 只有接受者收到「无法站立」，不发生瞬移（注意 K3：会先收到「已接受」） |
| C12 | 60 秒不处理 | 双方各收到过期提示，券不退 |
| C13 | 拒绝 | 双方各收到拒绝提示，无动作 |
| C14 | 两种动作 | 连续触发多次能看到「普通拥抱」与「摸头拥抱」两种（随机） |
| C15 | 第一人称 | 自己能看到自己的身体/手臂在拥抱姿势（不是纯第一人称空手） |
| C16 | 拥抱中一方退出 | 另一方不再被钉住（K9：旁观者可能仍保持锁定，属已知缺陷） |
| C17 | 专用服务端不装 playeranimator | 服务端能启动（依赖声明 side=CLIENT） |

---

## 附录索引

- **附录 A**：8 个 Java 源文件全文（逐字，含注释）。
- **附录 B**：全部资源文件全文（`neoforge.mods.toml`、4 份动作 JSON、2 份语言文件、物品模型、配方、两张 PNG 的 base64 + 摘要）。
- **附录 C**：`build.gradle` / `settings.gradle` / `.gitignore` 全文。
- **附录 D**：版本演进（git 历史里能读出的设计变更）。

---

## 附录 A：Java 源码全文

> 5fd1b3 的逐字内容（UTF-8，行尾统一为 LF）。复刻时这是一份可直接对照的参考实现。

| 文件 | 行数 | 字节 | SHA-256 |
|---|---|---|---|
| `src/main/java/nya/tuyw/hugme/HugMe.java` | 66 | 2909 | `73A76E11E776DD821AA09D357390B998778AE17DA058554A659E7789B0474873` |
| `src/main/java/nya/tuyw/hugme/item/HugTicketItem.java` | 58 | 2779 | `607D339A6F13ACA9925F98D023C248FC7F717AB742DC90D0458023AAC4E6C822` |
| `src/main/java/nya/tuyw/hugme/command/HugCommandHandler.java` | 303 | 15191 | `1172A02883741827861B8C6A97E9EF4052F34CF9F552647DB8618227DCC0F7DE` |
| `src/main/java/nya/tuyw/hugme/animation/HugAnimationEnum.java` | 7 | 119 | `36D04DFC2A495A0ABFBD135BE0D5AFF2456B6C0D1A9E604F0E55FA53468FC6D9` |
| `src/main/java/nya/tuyw/hugme/animation/AnimationManager.java` | 95 | 6999 | `6FAA56841841E593E1636D6106C54CAFDC4B50264FE1E11E58EBDB82EA6FE0B9` |
| `src/main/java/nya/tuyw/hugme/network/HugRenderPayload.java` | 47 | 1966 | `DF4E28CE66C2F0CC0EFD65110E259757A945007EE9D0505F363025AA5AFCD7FB` |
| `src/main/java/nya/tuyw/hugme/network/HugRenderClientHandler.java` | 42 | 2109 | `39037AF721071A9CEA333C89F17C9DB495E3C9B1669D6050F512946807A7791C` |
| `src/main/java/nya/tuyw/hugme/events/RenderPlayerEventHandler.java` | 109 | 5648 | `EF0DA78C18A5B7F878FC13CE9EC8CF7BAAAEB3DBBF650833B0910D4F30E04E31` |

### `HugMe.java`（主类：注册 / 创造栏 / 命令注册 / 首登发券）

```java
package nya.tuyw.hugme;

import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import nya.tuyw.hugme.command.HugCommandHandler;
import nya.tuyw.hugme.item.HugTicketItem;
import org.slf4j.Logger;

import java.io.File;

@Mod(HugMe.MODID)
public class HugMe {
    public static final String MODID = "hugme";
    public static final Logger LOGGER = LogUtils.getLogger();
    public static final DeferredRegister.Items HUGME_ITEMS = DeferredRegister.createItems(HugMe.MODID);
    public static final DeferredItem HUG_TICKET = HUGME_ITEMS.registerItem("hug_ticket", HugTicketItem::new, new Item.Properties().stacksTo(64));

    public HugMe(IEventBus modEventBus, ModContainer modContainer) {
        HUGME_ITEMS.register(modEventBus);
        modEventBus.addListener(this::addCreative);

        NeoForge.EVENT_BUS.register(this);
    }

    private void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES)
            event.accept(HUG_TICKET);
    }

    @SubscribeEvent
    private void onServerStarting(ServerStartingEvent event) {
        HugCommandHandler.register(event.getServer().getCommands().getDispatcher());
    }

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            if (ServerLifecycleHooks.getCurrentServer() == null) return;
            File playerDataFile = new File(ServerLifecycleHooks.getCurrentServer().getWorldPath(LevelResource.PLAYER_DATA_DIR).toFile(), player.getUUID() + ".dat");
            if (!playerDataFile.exists()) {
                ItemStack itemStack = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath("hugme","hug_ticket")),16);
                if (!player.getInventory().add(itemStack)) {
                    player.drop(itemStack, false);
                }
            }
        }
    }
}
```

### `HugTicketItem.java`（抱抱券物品）

```java
package nya.tuyw.hugme.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import nya.tuyw.hugme.command.HugCommandHandler;
import org.jetbrains.annotations.NotNull;

import java.util.List;


public class HugTicketItem extends Item {

    public HugTicketItem(Properties properties) {
        super(properties);
    }

    @Override
    public @NotNull InteractionResult interactLivingEntity(@NotNull ItemStack stack, @NotNull Player player, @NotNull LivingEntity target, @NotNull InteractionHand hand) {
        if (target instanceof Player targetPlayer) {
            if (!player.level().isClientSide) {
                if (HugCommandHandler.startHugRequest((ServerPlayer) player, (ServerPlayer) targetPlayer)) {
                    sendHugRequest(player, targetPlayer);
                    player.sendSystemMessage(Component.translatable("hugme.message.sendsuccess", targetPlayer.getName().getString()).withStyle(ChatFormatting.GREEN));
                    stack.shrink(1);
                }
            }
            return InteractionResult.sidedSuccess(player.level().isClientSide);
        }
        return InteractionResult.PASS;
    }

    @Override
    public void appendHoverText(@NotNull ItemStack stack, Item.@NotNull TooltipContext context, List<Component> tooltip, @NotNull TooltipFlag flag) {
        tooltip.add(Component.translatable("item.hugme.hug_ticket.desc").withStyle(ChatFormatting.GRAY));
    }

    private void sendHugRequest(Player sender, Player target) {
        Component message = (Component.translatable("hugme.message.sendrequest", sender.getName()).withStyle(ChatFormatting.BLUE))
                .append(Component.translatable("hugme.message.acceptrequest").withStyle(style -> style
                        .withColor(ChatFormatting.GREEN)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/hugme acceptRequest " + sender.getName().getString()))))
                .append(Component.translatable("hugme.message.rejectrequest").withStyle(style -> style
                        .withColor(ChatFormatting.RED)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/hugme rejectRequest " + sender.getName().getString()))));

        target.sendSystemMessage(message);
    }
}
```

### `HugCommandHandler.java`（请求/拥抱状态机、命令、落点、tick）

```java
package nya.tuyw.hugme.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.datafixers.util.Pair;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import nya.tuyw.hugme.HugMe;
import nya.tuyw.hugme.animation.HugAnimationEnum;
import nya.tuyw.hugme.network.HugRenderPayload;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@EventBusSubscriber(modid = HugMe.MODID, bus = EventBusSubscriber.Bus.GAME)
public class HugCommandHandler {
    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private static final long REQUEST_TIMEOUT_S = 60;
    private static final short AnimTick = 20 * 6;

    private static final Map<Pair<UUID,UUID>, HugRequest> hugRequests = new ConcurrentHashMap<>();
    private static final Map<Pair<UUID,UUID>, HugStatus> hugStatuses = new ConcurrentHashMap<>();

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("hugme")
                .then(Commands.literal("acceptRequest")
                        .then(Commands.argument("sender", StringArgumentType.string())
                                .executes(context -> {
                                    String senderName = StringArgumentType.getString(context, "sender");
                                    ServerPlayer sender = context.getSource().getServer().getPlayerList().getPlayerByName(senderName);
                                    ServerPlayer receiver = context.getSource().getPlayerOrException();

                                    if (sender != null) {
                                        handleHugAcceptance(sender, receiver);
                                        return 1;
                                    } else {
                                        context.getSource().sendFailure(Component.translatable("hugme.message.sendernotfound").withStyle(ChatFormatting.RED));
                                        return 0;
                                    }
                                })))
                .then(Commands.literal("rejectRequest")
                        .then(Commands.argument("sender", StringArgumentType.string())
                                .executes(context -> {
                                    String senderName = StringArgumentType.getString(context, "sender");
                                    ServerPlayer sender = context.getSource().getServer().getPlayerList().getPlayerByName(senderName);
                                    ServerPlayer receiver = context.getSource().getPlayerOrException();
                                    if (sender != null) {
                                        handleHugRejection(sender,receiver);
                                        return 1;
                                    }else {
                                        context.getSource().sendFailure(Component.translatable("hugme.message.sendernotfound").withStyle(ChatFormatting.RED));
                                        return 0;
                                    }
                                })))
        );
    }
    private static void handleHugRejection(ServerPlayer sender, ServerPlayer receiver) {
        Pair<UUID, UUID> key = new Pair<>(sender.getUUID(), receiver.getUUID());
        HugRequest request = hugRequests.get(key);

        if (request != null && request.receiverId().equals(receiver.getUUID())) {
            sender.sendSystemMessage(Component.translatable("hugme.message.sender_rejected", receiver.getName()).withStyle(ChatFormatting.RED));
            receiver.sendSystemMessage(Component.translatable("hugme.message.receiver_rejected", sender.getName()).withStyle(ChatFormatting.RED));
            hugRequests.remove(key);
        } else {
            receiver.sendSystemMessage(Component.translatable("hugme.message.invalid_request").withStyle(ChatFormatting.RED));
        }
    }

    private static void handleHugAcceptance(ServerPlayer sender, ServerPlayer receiver) {
        Pair<UUID, UUID> key = new Pair<>(sender.getUUID(), receiver.getUUID());

        HugRequest request = hugRequests.get(key);
        if (request != null) {
            if (hugStatuses.containsKey(key)) {
                sender.sendSystemMessage(Component.translatable("hugme.message.hug_in_progress_sender",receiver.getName().getString()).withStyle(ChatFormatting.RED));
                receiver.sendSystemMessage(Component.translatable("hugme.message.hug_in_progress_receiver",sender.getName().getString()).withStyle(ChatFormatting.RED));
                return;
            }

            if (sender.serverLevel() != receiver.serverLevel()) {
                receiver.sendSystemMessage(Component.translatable("hugme.message.invalid_level", sender.getName().getString()).withStyle(ChatFormatting.RED));
                return;
            }
            if (receiver.distanceTo(sender) > 5) {
                receiver.sendSystemMessage(Component.translatable("hugme.message.too_far", sender.getName().getString()).withStyle(ChatFormatting.RED));
                return;
            }

            sender.sendSystemMessage(Component.translatable("hugme.message.sender_accepted", receiver.getName().getString()).withStyle(ChatFormatting.GREEN));
            receiver.sendSystemMessage(Component.translatable("hugme.message.receiver_accepted", sender.getName().getString()).withStyle(ChatFormatting.GREEN));

            Vec3 senderVec = sender.position().subtract(receiver.position());

            double horizontalDistance = Math.sqrt(senderVec.x * senderVec.x + senderVec.z * senderVec.z);
            double offsetX = (senderVec.x / horizontalDistance) * 1.3;
            double offsetZ = (senderVec.z / horizontalDistance) * 1.3;

            double receiverX = sender.getX() + offsetX;
            double receiverY = sender.getY();
            double receiverZ = sender.getZ() + offsetZ;

            /**
             * @author Louis_Quepierts
             * @reason Fixed an issue where players would accept invitations in the wrong place
             */
            Vec3 standPosition = new Vec3(receiverX,receiverY,receiverZ);
            if (!isPositionSafe(standPosition, sender.level())) {
                receiver.sendSystemMessage(Component.translatable("hugme.message.unsafe_position", sender.getName().getString()).withStyle(ChatFormatting.RED));
                return;
            }


            receiver.teleportTo(receiverX, receiverY, receiverZ);
            receiver.lookAt(EntityAnchorArgument.Anchor.EYES, new Vec3(sender.getX(), sender.getEyeY(), sender.getZ()));

            sendRenderInfoToNearbyPlayers(sender, receiver);

            hugRequests.remove(key);
        } else {
            receiver.sendSystemMessage(Component.translatable("hugme.message.invalid_request").withStyle(ChatFormatting.RED));
        }
    }

    /**
     * @author Louis_Quepierts
     * @reason Fixed an issue where players would accept invitations in the wrong place
     */
    private static boolean isPositionSafe(Vec3 vec3, Level level){
        return canPositionStand(vec3,level,0.1f) && canPositionPass(vec3,level);
    }
    private static boolean canPositionStand(Vec3 vec3, Level level, float down){
        AABB box = new AABB(
                vec3.x - 0.3f, vec3.y - down, vec3.z - 0.3f,
                vec3.x + 0.3f, vec3.y, vec3.z + 0.3f);
        return level.getBlockCollisions(null,box).iterator().hasNext();
    }
    private static boolean canPositionPass(Vec3 vec3, Level level){
        AABB box = new AABB(
                vec3.x - 0.3f, vec3.y + 0.1f, vec3.z - 0.3f,
                vec3.x + 0.3f, vec3.y + 1.7f, vec3.z + 0.3f);
        return !level.getBlockCollisions(null, box).iterator().hasNext();
    }

    private static void sendRenderInfoToNearbyPlayers(ServerPlayer sender, ServerPlayer receiver) {
        List<ServerPlayer> nearbyPlayers = getNearbyPlayers(sender);
        HugStatus status = new HugStatus(sender.getUUID(), receiver.getUUID(), sender.position(), receiver.position(), true, AnimTick, nearbyPlayers);
        hugStatuses.put(new Pair<>(sender.getUUID(), receiver.getUUID()), status);

        int enumNumber = new Random().nextInt(HugAnimationEnum.values().length);
        for (ServerPlayer serverPlayer : nearbyPlayers) {
            PacketDistributor.sendToPlayer(serverPlayer, new HugRenderPayload(sender.getStringUUID(), receiver.getStringUUID(), false, enumNumber));
        }
    }

    private static List<ServerPlayer> getNearbyPlayers(ServerPlayer sender) {
        List<ServerPlayer> players = new ArrayList<>();
        ServerLevel level = sender.serverLevel();

        if (ServerLifecycleHooks.getCurrentServer() != null) {
            for (ServerPlayer player : ServerLifecycleHooks.getCurrentServer().getPlayerList().getPlayers()) {
                if (player.level().dimension() == level.dimension()) {
                    double distance = player.distanceTo(sender);
                    if (distance < 64) {
                        players.add(player);
                    }
                }
            }
        }
        return players;
    }

    @SubscribeEvent
    public static void onServerTickEnd(ServerTickEvent.Post event) {
        for (Map.Entry<Pair<UUID, UUID>, HugStatus> entry : hugStatuses.entrySet()) {
            HugStatus status = entry.getValue();
            if (!status.isActive()){hugStatuses.remove(new Pair<>(status.getSenderId(),status.getReceiverId()));}
            if (status.isActive() && status.getTicksRemaining() > 0) {
                doLock(status);
                status.decrementTicks();
                if (status.getTicksRemaining() == 0) {
                    endHug(status);
                }
            }
        }
    }
    private static void doLock(HugStatus status){
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        ServerPlayer sender = null;
        ServerPlayer receiver = null;
        if (server != null) {
            sender = server.getPlayerList().getPlayer(status.getSenderId());
            receiver = server.getPlayerList().getPlayer(status.getReceiverId());
        }
        if (sender != null && receiver != null) {
            sender.teleportTo(status.getSenderPos().x,status.getSenderPos().y,status.getSenderPos().z);
            receiver.teleportTo(status.getReceiverPos().x,status.getReceiverPos().y,status.getReceiverPos().z);
        }else {
            hugStatuses.remove(new Pair<>(status.getSenderId(), status.getReceiverId()));
        }
    }
    private static void endHug(HugStatus status) {
        UUID senderId = status.getSenderId();
        UUID receiverId = status.getReceiverId();

        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        ServerPlayer sender = null;
        ServerPlayer receiver = null;
        if (server != null) {
            sender = server.getPlayerList().getPlayer(senderId);
            receiver = server.getPlayerList().getPlayer(receiverId);
        }
        if (sender != null && receiver != null) {
            for (ServerPlayer player : status.getNearbyPlayers()) {
                PacketDistributor.sendToPlayer(player, new HugRenderPayload(sender.getStringUUID(), receiver.getStringUUID(), true, 0));
            }
            hugStatuses.remove(new Pair<>(senderId, receiverId));
        }
    }

    public static boolean startHugRequest(ServerPlayer sender, ServerPlayer receiver) {
        Pair<UUID, UUID> key = new Pair<>(sender.getUUID(), receiver.getUUID());
        if (hugRequests.get(key) != null){
            sender.sendSystemMessage(Component.translatable("hugme.message.have_request").withStyle(ChatFormatting.RED));
            return false;
        }
        HugRequest request = new HugRequest(sender.getUUID(), receiver.getUUID());
        hugRequests.put(key, request);

        scheduler.schedule(() -> {
            if (hugRequests.remove(key) != null) {
                sender.sendSystemMessage(Component.translatable("hugme.message.request_expired",receiver.getName().getString()).withStyle(ChatFormatting.RED));
                receiver.sendSystemMessage(Component.translatable("hugme.message.request_expired",sender.getName().getString()).withStyle(ChatFormatting.RED));
            }
        }, REQUEST_TIMEOUT_S, TimeUnit.SECONDS);

        return true;
    }

    private static class HugStatus {
        private final UUID senderId;
        private final UUID receiverId;
        private final Vec3 senderPos;
        private final Vec3 receiverPos;
        private final List<ServerPlayer> nearbyPlayers;
        private boolean active;
        private short ticksRemaining;
        public HugStatus(UUID senderId, UUID receiverId, Vec3 senderPos, Vec3 receiverPos, boolean active, short ticksRemaining, List<ServerPlayer> nearbyPlayers) {
            this.senderId = senderId;
            this.receiverId = receiverId;
            this.senderPos = senderPos;
            this.receiverPos = receiverPos;
            this.active = active;
            this.ticksRemaining = ticksRemaining;
            this.nearbyPlayers = nearbyPlayers;
        }
        public UUID getSenderId() {
            return senderId;
        }
        public UUID getReceiverId() {
            return receiverId;
        }
        public Vec3 getSenderPos() {
            return senderPos;
        }
        public Vec3 getReceiverPos() {
            return receiverPos;
        }
        public boolean isActive() {
            return active;
        }
        public void setActive(boolean active) {
            this.active = active;
        }
        public int getTicksRemaining() {
            return ticksRemaining;
        }
        public void decrementTicks() {
            this.ticksRemaining--;
        }
        public List<ServerPlayer> getNearbyPlayers() {
            return nearbyPlayers;
        }
    }
    public record HugRequest(UUID senderId, UUID receiverId) {}
}
```

### `HugAnimationEnum.java`（动作枚举）

```java
package nya.tuyw.hugme.animation;
public enum HugAnimationEnum {
    NORMALHUG,
//    FLYHUG,
    TOUCHHEADHUG
}
```

### `AnimationManager.java`（playerAnimator 层注册与播放，仅客户端）

```java
package nya.tuyw.hugme.animation;

import dev.kosmx.playerAnim.api.firstPerson.FirstPersonConfiguration;
import dev.kosmx.playerAnim.api.firstPerson.FirstPersonMode;
import dev.kosmx.playerAnim.api.layered.IAnimation;
import dev.kosmx.playerAnim.api.layered.KeyframeAnimationPlayer;
import dev.kosmx.playerAnim.api.layered.ModifierLayer;
import dev.kosmx.playerAnim.api.layered.modifier.SpeedModifier;
import dev.kosmx.playerAnim.core.data.KeyframeAnimation;
import dev.kosmx.playerAnim.minecraftApi.PlayerAnimationAccess;
import dev.kosmx.playerAnim.minecraftApi.PlayerAnimationFactory;
import dev.kosmx.playerAnim.minecraftApi.PlayerAnimationRegistry;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import nya.tuyw.hugme.HugMe;

@EventBusSubscriber(modid = HugMe.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class AnimationManager {

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        PlayerAnimationFactory.ANIMATION_DATA_FACTORY.registerFactory(ResourceLocation.fromNamespaceAndPath("hugme", "animations"), 42, (player) -> {
            if (player instanceof AbstractClientPlayer) {
                ModifierLayer<IAnimation> hugAnimation = new ModifierLayer<>();
                hugAnimation.addModifierBefore(new SpeedModifier(0.5F));
                return hugAnimation;
            }
            return null;
        });
    }

    @SuppressWarnings("all")
    public static void playHugAnimation(AbstractClientPlayer sender, AbstractClientPlayer receiver,HugAnimationEnum hugAnimationEnum) {
        if (sender == null) return;
        ModifierLayer<IAnimation> senderAnimation = (ModifierLayer<IAnimation>) PlayerAnimationAccess.getPlayerAssociatedData(sender).get(ResourceLocation.fromNamespaceAndPath("hugme", "animations"));
        ModifierLayer<IAnimation> receiverAnimation = (ModifierLayer<IAnimation>) PlayerAnimationAccess.getPlayerAssociatedData(receiver).get(ResourceLocation.fromNamespaceAndPath("hugme", "animations"));

        KeyframeAnimation sender_animation;
        KeyframeAnimationPlayer sender_animationPlayer = null;
        KeyframeAnimation receiver_animation;
        KeyframeAnimationPlayer receiver_animationPlayer = null;

        switch (hugAnimationEnum) {
            case NORMALHUG -> {
                sender_animation = (KeyframeAnimation) PlayerAnimationRegistry.getAnimation(ResourceLocation.fromNamespaceAndPath("hugme", "hug_normal_sender"));
                sender_animationPlayer = new KeyframeAnimationPlayer(sender_animation)
                        .setFirstPersonMode(FirstPersonMode.THIRD_PERSON_MODEL)
                        .setFirstPersonConfiguration(new FirstPersonConfiguration().setShowLeftArm(true).setShowLeftItem(false).setShowRightArm(true).setShowRightItem(false));
                receiver_animation = (KeyframeAnimation) PlayerAnimationRegistry.getAnimation(ResourceLocation.fromNamespaceAndPath("hugme", "hug_normal_receiver"));
                receiver_animationPlayer = new KeyframeAnimationPlayer(receiver_animation)
                        .setFirstPersonMode(FirstPersonMode.THIRD_PERSON_MODEL)
                        .setFirstPersonConfiguration(new FirstPersonConfiguration().setShowLeftArm(true).setShowLeftItem(false).setShowRightArm(true).setShowRightItem(false));
            }//未实现
//            case FLYHUG -> {
//                sender_animation = PlayerAnimationRegistry.getAnimation(ResourceLocation.fromNamespaceAndPath("hugme", "hug_fly_sender"));
//                sender_animationPlayer = new KeyframeAnimationPlayer(sender_animation)
//                        .setFirstPersonMode(FirstPersonMode.THIRD_PERSON_MODEL)
//                        .setFirstPersonConfiguration(new FirstPersonConfiguration().setShowLeftArm(true).setShowLeftItem(false).setShowRightArm(true).setShowRightItem(false));
//                receiver_animation = PlayerAnimationRegistry.getAnimation(ResourceLocation.fromNamespaceAndPath("hugme", "hug_fly_receiver"));
//                receiver_animationPlayer = new KeyframeAnimationPlayer(receiver_animation)
//                        .setFirstPersonMode(FirstPersonMode.THIRD_PERSON_MODEL)
//                        .setFirstPersonConfiguration(new FirstPersonConfiguration().setShowLeftArm(true).setShowLeftItem(false).setShowRightArm(true).setShowRightItem(false));
//            }
            case TOUCHHEADHUG -> {
                sender_animation = (KeyframeAnimation) PlayerAnimationRegistry.getAnimation(ResourceLocation.fromNamespaceAndPath("hugme", "hug_touch_sender"));
                sender_animationPlayer = new KeyframeAnimationPlayer(sender_animation)
                        .setFirstPersonMode(FirstPersonMode.THIRD_PERSON_MODEL)
                        .setFirstPersonConfiguration(new FirstPersonConfiguration().setShowLeftArm(true).setShowLeftItem(false).setShowRightArm(true).setShowRightItem(false));
                receiver_animation = (KeyframeAnimation) PlayerAnimationRegistry.getAnimation(ResourceLocation.fromNamespaceAndPath("hugme", "hug_touch_receiver"));
                receiver_animationPlayer = new KeyframeAnimationPlayer(receiver_animation)
                        .setFirstPersonMode(FirstPersonMode.THIRD_PERSON_MODEL)
                        .setFirstPersonConfiguration(new FirstPersonConfiguration().setShowLeftArm(true).setShowLeftItem(false).setShowRightArm(true).setShowRightItem(false));
            }
            default -> {
                sender_animation = (KeyframeAnimation) PlayerAnimationRegistry.getAnimation(ResourceLocation.fromNamespaceAndPath("hugme", "hug_normal_sender"));
                sender_animationPlayer = new KeyframeAnimationPlayer(sender_animation)
                        .setFirstPersonMode(FirstPersonMode.THIRD_PERSON_MODEL)
                        .setFirstPersonConfiguration(new FirstPersonConfiguration().setShowLeftArm(true).setShowLeftItem(false).setShowRightArm(true).setShowRightItem(false));
                receiver_animation = (KeyframeAnimation) PlayerAnimationRegistry.getAnimation(ResourceLocation.fromNamespaceAndPath("hugme", "hug_normal_receiver"));
                receiver_animationPlayer = new KeyframeAnimationPlayer(receiver_animation)
                        .setFirstPersonMode(FirstPersonMode.THIRD_PERSON_MODEL)
                        .setFirstPersonConfiguration(new FirstPersonConfiguration().setShowLeftArm(true).setShowLeftItem(false).setShowRightArm(true).setShowRightItem(false));
            }
        }

        senderAnimation.setAnimation(sender_animationPlayer);
        receiverAnimation.setAnimation(receiver_animationPlayer);
    }
}
```

### `HugRenderPayload.java`（网络包定义与注册）

```java
package nya.tuyw.hugme.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import nya.tuyw.hugme.HugMe;
import org.jetbrains.annotations.NotNull;

@EventBusSubscriber(bus = EventBusSubscriber.Bus.MOD,modid = HugMe.MODID)
public record HugRenderPayload(String sender, String receiver, boolean isdone, int animationEnum) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<HugRenderPayload> TYPE = new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("hugme", "render_hug"));

    public static final StreamCodec<FriendlyByteBuf, HugRenderPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8,
            HugRenderPayload::sender,
            ByteBufCodecs.STRING_UTF8,
            HugRenderPayload::receiver,
            ByteBufCodecs.BOOL,
            HugRenderPayload::isdone,
            ByteBufCodecs.INT,
            HugRenderPayload::animationEnum,

            HugRenderPayload::new
    );

    @SubscribeEvent
    public static void register(final RegisterPayloadHandlersEvent event) {
        final PayloadRegistrar registrar = event.registrar("1");
        registrar.playToClient(
                HugRenderPayload.TYPE,
                HugRenderPayload.STREAM_CODEC,
                HugRenderClientHandler::handleHugRender
        );
    }

    @Override
    public CustomPacketPayload.@NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
```

### `HugRenderClientHandler.java`（收包处理，仅客户端）

```java
package nya.tuyw.hugme.network;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import nya.tuyw.hugme.HugMe;
import nya.tuyw.hugme.animation.AnimationManager;
import nya.tuyw.hugme.animation.HugAnimationEnum;
import nya.tuyw.hugme.events.RenderPlayerEventHandler;

import java.util.UUID;

public class HugRenderClientHandler {
    private static final Minecraft client = Minecraft.getInstance();

    public static void handleHugRender(final HugRenderPayload hugRenderPayload, final IPayloadContext context) {
        context.enqueueWork(() -> {
            if (client.level == null) return;
            AbstractClientPlayer sender = (AbstractClientPlayer) client.level.getPlayerByUUID(UUID.fromString(hugRenderPayload.sender()));
            AbstractClientPlayer receiver = (AbstractClientPlayer) client.level.getPlayerByUUID(UUID.fromString(hugRenderPayload.receiver()));
            if (sender == null || receiver == null) return;
            if (!hugRenderPayload.isdone()) {
                RenderPlayerEventHandler.lockPlayers(sender, receiver);
                startHugAnimation(sender, receiver, hugRenderPayload.animationEnum());
            } else {
                RenderPlayerEventHandler.unlockPlayers(sender, receiver);
            }

        }).exceptionally(e -> {
            context.disconnect(Component.translatable("hugme.networking.failed", e.getMessage()));
            return null;
        });
    }

    private static void startHugAnimation(AbstractClientPlayer sender, AbstractClientPlayer receiver, int animationEnum) {
        HugAnimationEnum randomHugAnimationEnum = HugAnimationEnum.values()[animationEnum];
        HugMe.LOGGER.info("Starting hug animation " + randomHugAnimationEnum.toString() + " between " + sender.getName().getString() + " and " + receiver.getName().getString());
        AnimationManager.playHugAnimation(sender, receiver, randomHugAnimationEnum);
    }
}
```

### `RenderPlayerEventHandler.java`（渲染接管，仅客户端）

```java
package nya.tuyw.hugme.events;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.kosmx.playerAnim.api.TransformType;
import dev.kosmx.playerAnim.core.util.Pair;
import dev.kosmx.playerAnim.core.util.Vec3f;
import dev.kosmx.playerAnim.impl.IAnimatedPlayer;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderPlayerEvent;
import nya.tuyw.hugme.HugMe;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@EventBusSubscriber(modid = HugMe.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public class RenderPlayerEventHandler {
    private static final Minecraft client = Minecraft.getInstance();
    private static final Map<UUID, Pair<UUID,Boolean>> playerLockMap = new HashMap<>();

    @SubscribeEvent
    public static void onRenderPlayer(RenderPlayerEvent.Pre event) {
        Player player = event.getEntity();
        PlayerRenderer renderer = event.getRenderer();
        float partialTicks = event.getPartialTick();
        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource buffer = event.getMultiBufferSource();
        int packedLight = event.getPackedLight();

        if (player instanceof AbstractClientPlayer renderPlayer) {
            UUID playerId = player.getUUID();
            if (playerLockMap.containsKey(playerId)) {
                Pair<UUID, Boolean> pair = playerLockMap.get(playerId);
                UUID targetPlayerUUID = pair.getLeft();
                if (targetPlayerUUID == null) return;
                if (client.level == null) return;
                AbstractClientPlayer targetPlayer = (AbstractClientPlayer) client.level.getPlayerByUUID(targetPlayerUUID);
                if (targetPlayer == null) return;
                float targetYaw = calculateYawToTarget(renderPlayer, targetPlayer);

                poseStack.pushPose();

                poseStack.mulPose(Axis.XP.rotationDegrees(180.0F));
                poseStack.translate(0, -1.5, 0);
                poseStack.mulPose(new org.joml.Quaternionf().rotationY((float) Math.toRadians(targetYaw)));

                var animationPlayer = ((IAnimatedPlayer) renderPlayer).playerAnimator_getAnimation();
                animationPlayer.setTickDelta(partialTicks);
                if(animationPlayer.isActive()) {
                    Vec3f vec3d = animationPlayer.get3DTransform("body", TransformType.POSITION, Vec3f.ZERO);
                    poseStack.translate(vec3d.getX(), vec3d.getY() + 0.7, vec3d.getZ());
                    Vec3f vec3f = animationPlayer.get3DTransform("body", TransformType.ROTATION, Vec3f.ZERO);
                    poseStack.mulPose(Axis.ZP.rotation(vec3f.getZ()));
                    poseStack.mulPose(Axis.YP.rotation(vec3f.getY()));
                    poseStack.mulPose(Axis.XP.rotation(vec3f.getX()));
                    poseStack.translate(0, - 0.7d, 0);
                }

                poseStack.scale(1.0F, 1.0F, 1.0F);
                renderPlayerModel(renderer, renderPlayer, poseStack, buffer, packedLight, pair.getRight());

                poseStack.popPose();
                event.setCanceled(true);
            }
        }
    }

    private static void renderPlayerModel(PlayerRenderer renderer, AbstractClientPlayer entity, PoseStack poseStack, MultiBufferSource buffer, int packedLight, boolean hideHead) {
        PlayerModel<AbstractClientPlayer> model = renderer.getModel();
        model.young = entity.isBaby();
        model.head.visible = client.options.getCameraType() != CameraType.FIRST_PERSON || !hideHead || !(entity instanceof LocalPlayer);

        model.setupAnim(entity, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F);
        RenderType renderType = RenderType.entityCutoutNoCull(renderer.getTextureLocation(entity));

        model.renderToBuffer(poseStack, buffer.getBuffer(renderType), packedLight, OverlayTexture.NO_OVERLAY, -1);
    }

    private static float calculateYawToTarget(AbstractClientPlayer fromPlayer, AbstractClientPlayer toPlayer) {
        double deltaX = toPlayer.getX() - fromPlayer.getX();
        double deltaZ = toPlayer.getZ() - fromPlayer.getZ();
        return (float) Math.toDegrees(Math.atan2(deltaZ, deltaX)) - 90.0F;
    }

    public static void lockPlayers(AbstractClientPlayer sender, AbstractClientPlayer receiver) {
        playerLockMap.put(sender.getUUID(), new Pair<>(receiver.getUUID(),true));
        playerLockMap.put(receiver.getUUID(), new Pair<>(sender.getUUID(),false));
        HugMe.LOGGER.debug("Locked " + sender.getName().getString() + " and " + receiver.getName().getString());
    }

    public static void unlockPlayers(AbstractClientPlayer sender, AbstractClientPlayer receiver) {
        playerLockMap.remove(sender.getUUID());
        playerLockMap.remove(receiver.getUUID());
        HugMe.LOGGER.debug("Unlocked " + sender.getName().getString() + " and " + receiver.getName().getString());
    }
}
```

---

## 附录 B：资源文件全文

| 文件 | 行数 | 字节 | SHA-256 |
|---|---|---|---|
| `src/main/resources/META-INF/neoforge.mods.toml` | 36 | 717 | `D131A4EEC032E1968B43757195430A55D00C7C1F414D4D1C91563C753731BDD3` |
| `src/main/resources/assets/hugme/models/item/hug_ticket.json` | 6 | 96 | `E614E0B8C691C404A1887A2A50DF454E9E9A462F090934D60E7B89A297005C5B` |
| `src/main/resources/assets/hugme/lang/zh_cn.json` | 22 | 1400 | `EF53DCC1F0FE63CC3684D805425461E8C187B57232D0C1B6A458AC4CD011B44D` |
| `src/main/resources/assets/hugme/lang/en_us.json` | 22 | 1434 | `5B1C3568E1FD56F49D2E34EE87D4A93BAFF307532EC110958DF7EABEA8FC74AD` |
| `src/main/resources/data/hugme/recipe/hug_ticket.json` | 24 | 379 | `A46B7D5FE45AB4635F04E036E495D1E59C004E19E79957419EE712E8CC3E17C3` |
| `src/main/resources/assets/hugme/player_animation/normal_s.json` | 224 | 6115 | `637DBE114F0802E1B5AD7C236075639C5280D0C933A2779900AD447477A443F3` |
| `src/main/resources/assets/hugme/player_animation/normal_r.json` | 272 | 7409 | `4BB58321E78E9F1456633F366F25456ED82AEAEB4024578D13A6B48AE3377CE5` |
| `src/main/resources/assets/hugme/player_animation/touch_s.json` | 128 | 3450 | `3039968C0D9F73EBD7C8612DF04191A502A896ABCD219B65A2F31EFDF7628B7F` |
| `src/main/resources/assets/hugme/player_animation/touch_r.json` | 304 | 8174 | `CC8372F13C58713EC731A3F8E3E19886BC7CF03835EEBE0785E6CADF12A51757` |

### `META-INF/neoforge.mods.toml`

```toml
modLoader="javafml"

loaderVersion="${loader_version_range}"

license="${mod_license}"


[[mods]]
modId="${mod_id}"
version="${mod_version}"
displayName="${mod_name}"
authors="${mod_authors}"
description='''${mod_description}'''
logoFile="hug_ticket.png"


[[dependencies.${mod_id}]]
    modId="neoforge"
    type="required"
    versionRange="${neo_version_range}"
    ordering="NONE"
    side="BOTH"

[[dependencies.${mod_id}]]
    modId="minecraft"
    type="required"
    versionRange="${minecraft_version_range}"
    ordering="NONE"
    side="BOTH"

[[dependencies.${mod_id}]]
modId="playeranimator"
type="required"
versionRange="[1.0.2-rc1+1.21,)"
ordering="NONE"
side="CLIENT"
```

### `assets/hugme/models/item/hug_ticket.json`

```json
{
  "parent": "item/generated",
  "textures": {
    "layer0": "hugme:item/hug_ticket"
  }
}
```

### `assets/hugme/lang/zh_cn.json`

```json
{
  "hugme.message.sendrequest" : "%s 向你发起了拥抱请求! ",
  "hugme.message.sendsuccess" : "你成功向 %s 发送了拥抱请求",
  "hugme.message.acceptrequest": "[同意] ",
  "hugme.message.rejectrequest": "[拒绝] ",
  "hugme.message.sender_accepted": "%s 接受了你的拥抱请求!",
  "hugme.message.receiver_accepted": "你接受了来自 %s 的拥抱请求!",
  "hugme.message.sender_rejected": "%s 拒绝了你的拥抱请求.",
  "hugme.message.receiver_rejected": "你拒绝了来自 %s 的拥抱请求.",
  "hugme.message.sendernotfound": "找不到发送者!",
  "hugme.networking.failed": "HugMe: 网络连接失败 %s",
  "hugme.message.too_far": "你距离 %s 太远了! [>5]",
  "hugme.message.invalid_level" : "你与 %s 不在同一个世界",
  "hugme.message.unsafe_position" : "%s前的区域无法站立!",
  "hugme.message.request_expired" : "关于 %s 的拥抱请求已经过期",
  "hugme.message.have_request" : "你已经向他发送了一个拥抱请求",
  "hugme.message.invalid_request" : "无效的拥抱请求",
  "hugme.message.hug_in_progress_sender" : "%s 正在拥抱中,无法接受你的请求",
  "hugme.message.hug_in_progress_receiver" : "你正在拥抱中,无法接受 %s 的请求",
  "item.hugme.hug_ticket" : "抱抱券",
  "item.hugme.hug_ticket.desc" : "用我右键你的朋友,对他发起拥抱请求吧！"
}
```

### `assets/hugme/lang/en_us.json`

```json
{
  "hugme.message.sendrequest" : "%s sent a hug request to you! ",
  "hugme.message.sendsuccess" : "You successfully sent %s a hug request",
  "hugme.message.acceptrequest" : "[Accept] ",
  "hugme.message.rejectrequest" : "[Reject] ",
  "hugme.message.sender_accepted" : "%s accepted your hug request!",
  "hugme.message.receiver_accepted" : "You accepted a hug request from %s!",
  "hugme.message.sender_rejected" : "%s rejected your hug request.",
  "hugme.message.receiver_rejected" : "You rejected a hug request from %s.",
  "hugme.message.sendernotfound": "Sender not found!",
  "hugme.networking.failed" : "HugMe: networking failed %s",
  "hugme.message.too_far" : "You're too far away from %s ! [>5]",
  "hugme.message.invalid_level" : "You and %s are not in the same world",
  "hugme.message.unsafe_position" : "The area in front of %s is not able to stand",
  "hugme.message.request_expired" : "The hug request about %s has expired",
  "hugme.message.have_request" : "You already sent him a request",
  "hugme.message.invalid_request" : "Invalid hug request",
  "hugme.message.hug_in_progress_sender" : "%s is in a hug and cannot accept your request",
  "hugme.message.hug_in_progress_receiver" : "You are in a hug, unable to accept a hug request from %s",
  "item.hugme.hug_ticket" : "Hug Ticket",
  "item.hugme.hug_ticket.desc" : "Use me to right click on your friend and send a hug request to him!"
}
```

### `data/hugme/recipe/hug_ticket.json`

```json
{
  "type": "minecraft:crafting_shaped",
  "category": "misc",
  "pattern": [
    "PBP",
    "PIP",
    "PBP"
  ],
  "key": {
    "P": {
      "item": "minecraft:paper"
    },
    "B": {
      "item": "minecraft:light_blue_dye"
    },
    "I": {
      "item": "minecraft:pink_dye"
    }
  },
  "result": {
    "count": 4,
    "id": "hugme:hug_ticket"
  }
}
```

### `player_animation/normal_s.json`（→ `hugme:hug_normal_sender`）

```json
{
    "name": "hug_normal_sender",
    "author": "TuYw",
    "description": "normal1",
    "emote":{
        "isLoop": "false",
        "returnTick": 2,
        "beginTick":0,
        "endTick":53,
        "stopTick":61,
        "degrees":false,
        "moves":[
            
            {
                "tick":24,
                "easing": "EASEINOUTQUAD",
                "turn": 0,
                "head":{
                    "yaw":-0.0
                }
            },
            {
                "tick":34,
                "easing": "EASEINOUTQUAD",
                "turn": 0,
                "head":{
                    "yaw":0.004596982151269913
                }
            },
            {
                "tick":41,
                "easing": "EASEINOUTQUAD",
                "turn": 0,
                "head":{
                    "yaw":0.41403427720069885
                }
            },
            {
                "tick":47,
                "easing": "EASEINOUTQUAD",
                "turn": 0,
                "head":{
                    "yaw":-0.029769137501716614
                }
            },
            {
                "tick":53,
                "easing": "EASEINOUTQUAD",
                "turn": 0,
                "head":{
                    "yaw":0.5755231976509094
                }
            },
            {
                "tick":24,
                "easing": "EASEINOUTQUAD",
                "turn": 0,
                "head":{
                    "roll":-0.0
                }
            },
            {
                "tick":34,
                "easing": "EASEINOUTQUAD",
                "turn": 0,
                "head":{
                    "roll":0.3159203827381134
                }
            },
            {
                "tick":41,
                "easing": "EASEINOUTQUAD",
                "turn": 0,
                "head":{
                    "roll":0.3462390899658203
                }
            },
            {
                "tick":47,
                "easing": "EASEINOUTQUAD",
                "turn": 0,
                "head":{
                    "roll":0.316061794757843
                }
            },
            {
                "tick":53,
                "easing": "EASEINOUTQUAD",
                "turn": 0,
                "head":{
                    "roll":0.3793841004371643
                }
            },
            {
                "tick":24,
                "easing": "EASEINOUTQUAD",
                "turn": 0,
                "head":{
                    "pitch":-0.0
                }
            },
            {
                "tick":34,
                "easing": "EASEINOUTQUAD",
                "turn": 0,
                "head":{
                    "pitch":0.2591223120689392
                }
            },
            {
                "tick":41,
                "easing": "EASEINOUTQUAD",
                "turn": 0,
                "head":{
                    "pitch":0.4017540514469147
                }
            },
            {
                "tick":47,
                "easing": "EASEINOUTQUAD",
                "turn": 0,
                "head":{
                    "pitch":0.24788618087768555
                }
            },
            {
                "tick":53,
                "easing": "EASEINOUTQUAD",
                "turn": 0,
                "head":{
                    "pitch":0.4713078737258911
                }
            },
            {
                "tick":10,
                "easing": "EASEINOUTQUAD",
                "turn": 0,
                "rightArm":{
                    "yaw":0.8726646304130554
                }
            },
            {
                "tick":20,
                "easing": "EASEINOUTQUAD",
                "turn": 0,
                "rightArm":{
                    "yaw":0.8726646304130554
                }
            },
            {
                "tick":30,
                "easing": "EASEINOUTQUAD",
                "turn": 0,
                "rightArm":{
                    "yaw":-0.5235987305641174
                }
            },
            {
                "tick":10,
                "easing": "EASEINOUTQUAD",
                "turn": 0,
                "rightArm":{
                    "roll":0.06981316953897476
                }
            },
            {
                "tick":10,
                "easing": "EASEINOUTQUAD",
                "turn": 0,
                "rightArm":{
                    "pitch":-0.6981316804885864
                }
            },
            {
                "tick":20,
                "easing": "EASEINOUTQUAD",
                "turn": 0,
                "rightArm":{
                    "pitch":-0.6981316804885864
                }
            },
            {
                "tick":10,
                "easing": "EASEINOUTQUAD",
                "turn": 0,
                "leftArm":{
                    "roll":-0.06981316953897476
                }
            },
            {
                "tick":10,
                "easing": "EASEINOUTQUAD",
                "turn": 0,
                "leftArm":{
                    "yaw":-0.8726646304130554
                }
            },
            {
                "tick":20,
                "easing": "EASEINOUTQUAD",
                "turn": 0,
                "leftArm":{
                    "yaw":-0.8726646304130554
                }
            },
            {
                "tick":30,
                "easing": "EASEINOUTQUAD",
                "turn": 0,
                "leftArm":{
                    "yaw":0.5235987305641174
                }
            },
            {
                "tick":10,
                "easing": "EASEINOUTQUAD",
                "turn": 0,
                "leftArm":{
                    "pitch":-0.6981316804885864
                }
            }
        ]
    }
}
```

### `player_animation/normal_r.json`（→ `hugme:hug_normal_receiver`）

```json
{
    "name": "hug_normal_receiver",
    "author": "TuYw",
    "description": "normal2",
    "emote":{
        "isLoop": "false",
        "returnTick": 2,
        "beginTick":0,
        "endTick":53,
        "stopTick":61,
        "degrees":false,
        "moves":[
            
            {
                "tick":25,
                "easing": "EASEINOUTQUAD",
                "turn": 0,
                "head":{
                    "yaw":-0.0
                }
            },
            {
                "tick":34,
                "easing": "EASEINOUTQUAD",
                "turn": 0,
                "head":{
                    "yaw":0.251642644405365
                }
            },
            {
                "tick":41,
                "easing": "EASEINOUTQUAD",
                "turn": 0,
                "head":{
                    "yaw":-0.15474915504455566
                }
            },
            {
                "tick":47,
                "easing": "EASEINOUTQUAD",
                "turn": 0,
                "head":{
                    "yaw":0.19571051001548767
                }
            },
            {
                "tick":53,
                "easing": "EASEINOUTQUAD",
                "turn": 0,
                "head":{
                    "yaw":-0.1976402997970581
                }
            },
            {
                "tick":25,
                "easing": "EASEINOUTQUAD",
                "turn": 0,
                "head":{
                    "roll":-0.0
                }
            },
            {
                "tick":34,
                "easing": "EASEINOUTQUAD",
                "turn": 0,
                "head":{
                    "roll":0.1332109570503235
                }
            },
            {
                "tick":41,
                "easing": "EASEINOUTQUAD",
                "turn": 0,
                "head":{
                    "roll":0.13056056201457977
                }
            },
            {
                "tick":47,
                "easing": "EASEINOUTQUAD",
                "turn": 0,
                "head":{
                    "roll":0.1315164566040039
                }
            },
            {
                "tick":53,
                "easing": "EASEINOUTQUAD",
                "turn": 0,
                "head":{
                    "roll":0.1315673291683197
                }
            },
            {
                "tick":25,
                "easing": "EASEINOUTQUAD",
                "turn": 0,
                "head":{
                    "pitch":-0.0
                }
            },
            {
                "tick":34,
                "easing": "EASEINOUTQUAD",
                "turn": 0,
                "head":{
                    "pitch":0.32610055804252625
                }
            },
            {
                "tick":41,
                "easing": "EASEINOUTQUAD",
                "turn": 0,
                "head":{
                    "pitch":0.27251046895980835
                }
            },
            {
                "tick":47,
                "easing": "EASEINOUTQUAD",
                "turn": 0,
                "head":{
                    "pitch":0.31846436858177185
                }
            },
            {
                "tick":53,
                "easing": "EASEINOUTQUAD",
                "turn": 0,
                "head":{
                    "pitch":0.26676809787750244
                }
            },
            {
                "tick":20,
                "easing": "EASEINOUTQUAD",
                "turn": 0,
                "torso":{
                    "z":-1.0
                }
            },
            {
                "tick":15,
                "easing": "EASEINOUTQUAD",
                "turn": 0,
                "rightArm":{
                    "yaw":0.8726646304130554
                }
            },
            {
                "tick":25,
                "easing": "EASEINOUTQUAD",
                "turn": 0,
                "rightArm":{
                    "yaw":-0.3839724659919739
                }
            },
            {
                "tick":15,
                "easing": "EASEINOUTQUAD",
                "turn": 0,
                "rightArm":{
                    "roll":0.06981316953897476
                }
            },
            {
                "tick":15,
                "easing": "EASEINOUTQUAD",
                "turn": 0,
                "rightArm":{
                    "pitch":-1.0471975803375244
                }
            },
            {
                "tick":15,
                "easing": "EASEINOUTQUAD",
                "turn": 0,
                "leftArm":{
                    "roll":-0.06981319189071655
                }
            },
            {
                "tick":15,
                "easing": "EASEINOUTQUAD",
                "turn": 0,
                "leftArm":{
                    "yaw":-0.8726646304130554
                }
            },
            {
                "tick":25,
                "easing": "EASEINOUTQUAD",
                "turn": 0,
                "leftArm":{
                    "yaw":0.3839724659919739
                }
            },
            {
                "tick":15,
                "easing": "EASEINOUTQUAD",
                "turn": 0,
                "leftArm":{
                    "pitch":-1.0471975803375244
                }
            },
            {
                "tick":5,
                "easing": "EASEINOUTQUAD",
                "turn": 0,
                "rightLeg":{
                    "pitch":0.9075711965560913
                }
            },
            {
                "tick":10,
                "easing": "EASEINOUTQUAD",
                "turn": 0,
                "rightLeg":{
                    "pitch":-0.0
                }
            },
            {
                "tick":15,
                "easing": "EASEINOUTQUAD",
                "turn": 0,
                "rightLeg":{
                    "pitch":-0.8726646304130554
                }
            },
            {
                "tick":20,
                "easing": "EASEINOUTQUAD",
                "turn": 0,
                "rightLeg":{
                    "pitch":-0.0
                }
            },
            {
                "tick":5,
                "easing": "EASEINOUTQUAD",
                "turn": 0,
                "leftLeg":{
                    "pitch":-0.9075711965560913
                }
            },
            {
                "tick":10,
                "easing": "EASEINOUTQUAD",
                "turn": 0,
                "leftLeg":{
                    "pitch":-0.0
                }
            },
            {
                "tick":15,
                "easing": "EASEINOUTQUAD",
                "turn": 0,
                "leftLeg":{
                    "pitch":0.8726646304130554
                }
            },
            {
                "tick":20,
                "easing": "EASEINOUTQUAD",
                "turn": 0,
                "leftLeg":{
                    "pitch":-0.0
                }
            }
        ]
    }
}
```

### `player_animation/touch_s.json`（→ `hugme:hug_touch_sender`）

```json
{
    "name": "hug_touch_sender",
    "author": "TuYw",
    "description": "touch1",
    "emote":{
        "isLoop": "false",
        "returnTick": 2,
        "beginTick":0,
        "endTick":54,
        "stopTick":61,
        "degrees":false,
        "moves":[
            
            {
                "tick":30,
                "easing": "EASEINOUTQUAD",
                "turn": 0,
                "head":{
                    "roll":0.13962633907794952
                }
            },
            {
                "tick":30,
                "easing": "EASEINOUTQUAD",
                "turn": 0,
                "head":{
                    "pitch":-0.12217304855585098
                }
            },
            {
                "tick":30,
                "easing": "EASEINOUTQUAD",
                "turn": 0,
                "rightArm":{
                    "yaw":0.262837678194046
                }
            },
            {
                "tick":36,
                "easing": "EASEINOUTQUAD",
                "turn": 0,
                "rightArm":{
                    "yaw":-0.20840120315551758
                }
            },
            {
                "tick":30,
                "easing": "EASEINOUTQUAD",
                "turn": 0,
                "rightArm":{
                    "roll":0.2584236264228821
                }
            },
            {
                "tick":36,
                "easing": "EASEINOUTQUAD",
                "turn": 0,
                "rightArm":{
                    "roll":0.6773026585578918
                }
            },
            {
                "tick":42,
                "easing": "EASEINOUTQUAD",
                "turn": 0,
                "rightArm":{
                    "roll":0.18861046433448792
                }
            },
            {
                "tick":48,
                "easing": "EASEINOUTQUAD",
                "turn": 0,
                "rightArm":{
                    "roll":0.642396092414856
                }
            },
            {
                "tick":54,
                "easing": "EASEINOUTQUAD",
                "turn": 0,
                "rightArm":{
                    "roll":0.08389073610305786
                }
            },
            {
                "tick":30,
                "easing": "EASEINOUTQUAD",
                "turn": 0,
                "rightArm":{
                    "pitch":-2.512342929840088
                }
            },
            {
                "tick":20,
                "easing": "EASEINOUTQUAD",
                "turn": 0,
                "leftArm":{
                    "yaw":-0.0
                }
            },
            {
                "tick":30,
                "easing": "EASEINOUTQUAD",
                "turn": 0,
                "leftArm":{
                    "yaw":0.13962633907794952
                }
            },
            {
                "tick":20,
                "easing": "EASEINOUTQUAD",
                "turn": 0,
                "leftArm":{
                    "pitch":-0.0
                }
            },
            {
                "tick":30,
                "easing": "EASEINOUTQUAD",
                "turn": 0,
                "leftArm":{
                    "pitch":-0.767944872379303
                }
            }
        ]
    }
}
```

### `player_animation/touch_r.json`（→ `hugme:hug_touch_receiver`）

```json
{
    "name": "hug_touch_receiver",
    "author": "TuYw",
    "description": "touch2",
    "emote":{
        "isLoop": "false",
        "returnTick": 2,
        "beginTick":0,
        "endTick":54,
        "stopTick":61,
        "degrees":false,
        "moves":[
            
            {
                "tick":30,
                "easing": "EASEINOUTQUAD",
                "turn": 0,
                "head":{
                    "yaw":-0.0
                }
            },
            {
                "tick":36,
                "easing": "EASEINOUTQUAD",
                "turn": 0,
                "head":{
                    "yaw":-0.1893286108970642
                }
            },
            {
                "tick":42,
                "easing": "EASEINOUTQUAD",
                "turn": 0,
                "head":{
                    "yaw":0.21053239703178406
                }
            },
            {
                "tick":48,
                "easing": "EASEINOUTQUAD",
                "turn": 0,
                "head":{
                    "yaw":-0.3374362587928772
                }
            },
            {
                "tick":54,
                "easing": "EASEINOUTQUAD",
                "turn": 0,
                "head":{
                    "yaw":0.06939631700515747
                }
            },
            {
                "tick":30,
                "easing": "EASEINOUTQUAD",
                "turn": 0,
                "head":{
                    "pitch":0.3715437352657318
                }
            },
            {
                "tick":20,
                "easing": "EASEINOUTQUAD",
                "turn": 0,
                "torso":{
                    "yaw":0.0
                }
            },
            {
                "tick":30,
                "easing": "EASEINOUTQUAD",
                "turn": 0,
                "torso":{
                    "yaw":0.0
                }
            },
            {
                "tick":30,
                "easing": "EASEINOUTQUAD",
                "turn": 0,
                "torso":{
                    "roll":-0.0
                }
            },
            {
                "tick":20,
                "easing": "EASEINOUTQUAD",
                "turn": 0,
                "torso":{
                    "pitch":0.0
                }
            },
            {
                "tick":30,
                "easing": "EASEINOUTQUAD",
                "turn": 0,
                "torso":{
                    "pitch":0.0
                }
            },
            {
                "tick":20,
                "easing": "EASEINOUTQUAD",
                "turn": 0,
                "torso":{
                    "z":-0.75
                }
            },
            {
                "tick":30,
                "easing": "EASEINOUTQUAD",
                "turn": 0,
                "rightArm":{
                    "yaw":-0.1745329201221466
                }
            },
            {
                "tick":5,
                "easing": "EASEINOUTQUAD",
                "turn": 0,
                "rightArm":{
                    "pitch":-0.7330382466316223
                }
            },
            {
                "tick":10,
                "easing": "EASEINOUTQUAD",
                "turn": 0,
                "rightArm":{
                    "pitch":-0.0
                }
            },
            {
                "tick":15,
                "easing": "EASEINOUTQUAD",
                "turn": 0,
                "rightArm":{
                    "pitch":0.36651915311813354
                }
            },
            {
                "tick":20,
                "easing": "EASEINOUTQUAD",
                "turn": 0,
                "rightArm":{
                    "pitch":-0.0
                }
            },
            {
                "tick":30,
                "easing": "EASEINOUTQUAD",
                "turn": 0,
                "rightArm":{
                    "pitch":-0.8028514385223389
                }
            },
            {
                "tick":30,
                "easing": "EASEINOUTQUAD",
                "turn": 0,
                "leftArm":{
                    "yaw":0.1745329052209854
                }
            },
            {
                "tick":5,
                "easing": "EASEINOUTQUAD",
                "turn": 0,
                "leftArm":{
                    "pitch":0.7330383062362671
                }
            },
            {
                "tick":10,
                "easing": "EASEINOUTQUAD",
                "turn": 0,
                "leftArm":{
                    "pitch":-0.0
                }
            },
            {
                "tick":15,
                "easing": "EASEINOUTQUAD",
                "turn": 0,
                "leftArm":{
                    "pitch":-0.7330383062362671
                }
            },
            {
                "tick":20,
                "easing": "EASEINOUTQUAD",
                "turn": 0,
                "leftArm":{
                    "pitch":-0.0
                }
            },
            {
                "tick":30,
                "easing": "EASEINOUTQUAD",
                "turn": 0,
                "leftArm":{
                    "pitch":-0.8028514385223389
                }
            },
            {
                "tick":5,
                "easing": "EASEINOUTQUAD",
                "turn": 0,
                "rightLeg":{
                    "pitch":0.9075711965560913
                }
            },
            {
                "tick":10,
                "easing": "EASEINOUTQUAD",
                "turn": 0,
                "rightLeg":{
                    "pitch":-0.0
                }
            },
            {
                "tick":15,
                "easing": "EASEINOUTQUAD",
                "turn": 0,
                "rightLeg":{
                    "pitch":-0.9075711965560913
                }
            },
            {
                "tick":20,
                "easing": "EASEINOUTQUAD",
                "turn": 0,
                "rightLeg":{
                    "pitch":-0.0
                }
            },
            {
                "tick":30,
                "easing": "EASEINOUTQUAD",
                "turn": 0,
                "rightLeg":{
                    "pitch":-0.0
                }
            },
            {
                "tick":30,
                "easing": "EASEINOUTQUAD",
                "turn": 0,
                "leftLeg":{
                    "yaw":-0.0
                }
            },
            {
                "tick":30,
                "easing": "EASEINOUTQUAD",
                "turn": 0,
                "leftLeg":{
                    "roll":-0.0
                }
            },
            {
                "tick":5,
                "easing": "EASEINOUTQUAD",
                "turn": 0,
                "leftLeg":{
                    "pitch":-0.9075711965560913
                }
            },
            {
                "tick":10,
                "easing": "EASEINOUTQUAD",
                "turn": 0,
                "leftLeg":{
                    "pitch":-0.0
                }
            },
            {
                "tick":15,
                "easing": "EASEINOUTQUAD",
                "turn": 0,
                "leftLeg":{
                    "pitch":0.9075711965560913
                }
            },
            {
                "tick":20,
                "easing": "EASEINOUTQUAD",
                "turn": 0,
                "leftLeg":{
                    "pitch":-0.0
                }
            },
            {
                "tick":30,
                "easing": "EASEINOUTQUAD",
                "turn": 0,
                "leftLeg":{
                    "pitch":-0.0
                }
            }
        ]
    }
}
```

### `assets/hugme/textures/item/hug_ticket.png`（模组 logo 与之同一份字节）

- 尺寸：64 × 64，8-bit RGBA（PNG color type 6），文件  字节
- SHA-256：`25089C58057E6EBDAEBFA8D963BE3B243844CB01C81978BD290F087A274C36EC`（与 `src/main/resources/hug_ticket.png` 完全一致）
- 画面：斜置约 −30° 的浅蓝色票券，深蓝描边、浅蓝高光，票面中央一枚粉色爱心

完整字节（base64，解码即得原 PNG）：


```text
iVBORw0KGgoAAAANSUhEUgAAAEAAAABACAYAAACqaXHeAAAABGdBTUEAALGPC/xhBQAAAYNpQ0NQSUNDIHByb2ZpbGUAACiRfZE9SMNAHMVfU6VFWhzaQcQhQ3Wyi4o4lioWwUJpK7TqYHLpFzRpSFJcHAXXgoMfi1UHF2ddHVwFQfADxNnBSdFFSvxfUmgR48FxP97de9y9A4R2nanmQAJQNcvIppJiobgqBl4RRBgCIohLzNTTucU8PMfXPXx8vYvzLO9zf46wUjIZ4BOJE0w3LOIN4tlNS+e8TxxlVUkhPieeNOiCxI9cl11+41xxWOCZUSOfnSeOEouVPpb7mFUNlXiGOKaoGuULBZcVzluc1XqTde/JXxgqaSs5rtMcQwpLSCMDETKaqKEOC3FaNVJMZGk/6eEfdfwZcsnkqoGRYwENqJAcP/gf/O7WLE9PuUmhJDD4Ytsf40BgF+i0bPv72LY7J4D/GbjSev5GG5j7JL3V02JHwPA2cHHd0+Q94HIHGHnSJUNyJD9NoVwG3s/om4pA5BYYWnN76+7j9AHIU1fLN8DBITBRoex1j3cH+3v790y3vx+W83K1ElPAzgAAAAZiS0dEAP8A/wD/oL2nkwAAAAlwSFlzAAALEwAACxMBAJqcGAAAAAd0SU1FB+gIDQYtEbrOpvwAAAAZdEVYdENvbW1lbnQAQ3JlYXRlZCB3aXRoIEdJTVBXgQ4XAAABy0lEQVR42u2asUvDQBTGr6JRFNEhQjcDrahz0dXV0UHcXHQV3LMK3f0D9A9wEhd1dBXcI20gugkRGrSCyRCXCL1XyOV5l1aa75vukiZHP97367ukQkAQVGXVdG9w58cp5/O7Jzfymrf7rPU+jq+k+eLFAes81VTVKwAGgAEKBWG8IoR4z6aOFwl/8PzGEm9BL+IxgWZaV5QJ0wWuCR3bSjMzXhGBCVORCqgHYfyWjRsTz4AgjGcd2/rOxgteJD5NZp5eP8SEhlXj9AHczBeJQPw7cGyrj5/BChqwORCHucr1AbS3N515dp/AZIIq84gADIAB+Qw4u08lBhy2klIzb5oJqAAYAAP0NkPXD11ypCnNtpqJ0cyvnn9J85fT+dz70z5FlwmIAAwAA2Q9tddIpjppHhMEkwk081yZZgIiAAOwF+Cp5XZy3wW2j2RGrC8nI/1C3L0DIgADwACzoowYNROeezPS3L3s5vY5iAAMwF7ArGjGXFEuE2jmH31UAAyAAePsA8ruE1SZp880h59voAJgAAwYJwNUTNjbkZmwTf6WpZt5VAAMgAHl7gV0pXo3qZt5VAAMgAH/qw9Q9QWq5w2oABgAAyAIgv6sHwTynd5VZXQMAAAAAElFTkSuQmCC
```

---

## 附录 C：构建脚本全文

| 文件 | 行数 | 字节 | SHA-256 |
|---|---|---|---|
| `build.gradle` | 132 | 3312 | `4CC737308E2E6060443D2B418D1C67AEAF3B964B2FA3A2AB083258FFC74A820A` |
| `settings.gradle` | 12 | 257 | `EFF5AF02BD6429A93C2346682907ACCFD100D891EFB2F8010D6020F7FC6F9A9C` |
| `gradle.properties` | 23 | 525 | `C1B62F2C04A65D9AFEA35B2E32C276E4D7235F81A4A56E2D4F9D86C558CFE628` |
| `gradle/wrapper/gradle-wrapper.properties` | 8 | 259 | `65371D0538B7B7F7BAA648FEA7ECD0EFFB09FC80C5E6D87DEBA3FB0370D7599B` |
| `.gitignore` | 26 | 188 | `67E519A4E31814E85F85B26A9194368062677703FDA925BAE7F7A4E8793A3DDA` |

### `build.gradle`

```groovy
plugins {
    id 'java-library'
    id 'eclipse'
    id 'idea'
    id 'maven-publish'
    id 'net.neoforged.moddev' version '1.0.14'
}

tasks.named('wrapper', Wrapper).configure {
    distributionType = Wrapper.DistributionType.BIN
}

version = mod_version
group = mod_group_id

repositories {
    mavenLocal()
    maven {
        name "KosmX's maven"
        url 'https://maven.kosmx.dev/'
        content {
            includeGroup("dev.kosmx.player-anim")
        }
    }
}

base {
    archivesName = "HugMe!-NeoForge-$minecraft_version"
}

java.toolchain.languageVersion = JavaLanguageVersion.of(21)

neoForge {
    version = project.neo_version

    parchment {
        mappingsVersion = project.parchment_mappings_version
        minecraftVersion = project.parchment_minecraft_version
    }

    runs {
        client {
            client()

            systemProperty 'neoforge.enabledGameTestNamespaces', project.mod_id
        }

        server {
            server()
            programArgument '--nogui'
            systemProperty 'neoforge.enabledGameTestNamespaces', project.mod_id
        }

        gameTestServer {
            type = "gameTestServer"
            systemProperty 'neoforge.enabledGameTestNamespaces', project.mod_id
        }

        data {
            data()

            programArguments.addAll '--mod', project.mod_id, '--all', '--output', file('src/generated/resources/').getAbsolutePath(), '--existing', file('src/main/resources/').getAbsolutePath()
        }

        configureEach {
            systemProperty 'forge.logging.markers', 'REGISTRIES'

            logLevel = org.slf4j.event.Level.DEBUG
        }
    }

    mods {
        "${mod_id}" {
            sourceSet(sourceSets.main)
        }
    }
}

sourceSets.main.resources { srcDir 'src/generated/resources' }

configurations {
    runtimeClasspath.extendsFrom localRuntime
}

dependencies {
    implementation "dev.kosmx.player-anim:player-animation-lib-forge:2.0.0+1.21.1"
}

tasks.withType(ProcessResources).configureEach {
    var replaceProperties = [
            minecraft_version      : minecraft_version,
            minecraft_version_range: minecraft_version_range,
            neo_version            : neo_version,
            neo_version_range      : neo_version_range,
            loader_version_range   : loader_version_range,
            mod_id                 : mod_id,
            mod_name               : mod_name,
            mod_license            : mod_license,
            mod_version            : mod_version,
            mod_authors            : mod_authors,
            mod_description        : mod_description
    ]
    inputs.properties replaceProperties
    filesMatching(['META-INF/neoforge.mods.toml']) {
        expand replaceProperties
    }
}

publishing {
    publications {
        register('mavenJava', MavenPublication) {
            from components.java
        }
    }
    repositories {
        maven {
            url "file://${project.projectDir}/repo"
        }
    }
}

tasks.withType(JavaCompile).configureEach {
    options.encoding = 'UTF-8'
}

idea {
    module {
        downloadSources = true
        downloadJavadoc = true
    }
}
```

### `settings.gradle`

```groovy
pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
        maven { url = 'https://maven.neoforged.net/releases' }
    }
}

plugins {
    id 'org.gradle.toolchains.foojay-resolver-convention' version '0.8.0'
}
```

### `gradle.properties`

```properties
org.gradle.jvmargs=-Xmx3G
org.gradle.daemon=true
org.gradle.parallel=true
org.gradle.caching=true
org.gradle.configuration-cache=true


parchment_minecraft_version=1.21
parchment_mappings_version=2024.07.07
minecraft_version=1.21
minecraft_version_range=[1.21,1.21.1]
neo_version=21.0.114-beta
neo_version_range=[21.0.0-beta,)
loader_version_range=[4,)

mod_id=hugme
mod_name=Hug Me!
mod_license=unlicensed
mod_version=1.3
mod_group_id=nya.tuyw.hugme
mod_authors=TuYw
mod_description=Hug other players!
```

### `gradle/wrapper/gradle-wrapper.properties`（本地被改为镜像，此处为工作区当前内容）

```properties
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://mirrors.cloud.tencent.com/gradle/gradle-8.14.7-bin.zip
networkTimeout=10000
validateDistributionUrl=true
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
```

### `.gitignore`

```text
# eclipse
bin
*.launch
.settings
.metadata
.classpath
.project

# idea
out
*.ipr
*.iws
*.iml
.idea

# gradle
build
.gradle

# other
eclipse
run
runs
run-data

repo
```

---

## 附录 D：版本演进（从 git 历史读设计）

仓库共 16 个提交，除两个修复提交外基本都是「发布版本号 +1」。按时间正序：

| 提交 | 标题 | 内容与设计意义 |
|---|---|---|
| `ae236e7` … `f1bfdbe` | 连续 5 个 `1.0.0` | 首版即包含现在的 8 个类与 4 份动作文件的完整结构，此后架构未变 |
| `8b7fd82` | Create README.md | 三行中文说明 |
| `c449c00` | change Pair import | 换成 `com.mojang.datafixers.util.Pair`（自带 `equals`/`hashCode`，可直接当 `ConcurrentHashMap` 的键；否则每次 `new Pair` 查表都会 miss） |
| `d451f7d` | fix bug when hugging quit | 在 `doLock` 里补 `else { hugStatuses.remove(...) }`：拥抱过程中有人下线时清掉状态，避免那条 `HugStatus` 永远留着。**注意它没有补发结束包**，所以客户端锁定依旧残留（K9） |
| `93f9487` | fix random enum | 两处关键修复：① 把 `FLYHUG` 从枚举和 switch 里注释掉——动作数据从未做出来，随机到它会取到 `null` 动作；② 把 `new Random().nextInt(...)` 从「每个玩家一次」挪到循环外——**所有围观者必须收到同一个动画号**，否则不同客户端各播各的，两人动作对不上 |
| `4801ca9` | `1.1` | 给 `mods.toml` 加 `logoFile="hug_ticket.png"`，并加入模组图标 |
| `86d871d`、`9402d28` | add License | 补 MIT `LICENSE`（与 `gradle.properties` 的 `unlicensed` 不一致） |
| `7efe614` | give hug_ticket at the first join | 新增「首登发 16 张」逻辑（`PlayerLoggedInEvent` + 查 `.dat`） |
| `87c96ab` | update playeranimatorapi 2.0.0 | playerAnimator 升到 `2.0.0+1.21.1`；`PlayerAnimationRegistry.getAnimation(...)` 的返回类型在 2.0 变宽——1.x 时代可直接赋给 `KeyframeAnimation`，2.0 起必须显式 `(KeyframeAnimation)` 强转（diff 里六处强转都是这次加的）；`playeranimator` 依赖范围定为 `[1.0.2-rc1+1.21,)` |
| `1000333` | `1.2` | 只改 `mod_version` |
| `e5fd1b3` | `1.3` | 只改 `mod_version`（当前 HEAD） |

### D.1 两条可直接照搬的经验

1. **动画号只能抽一次**：服务端选定动作后把同一个 `int` 发给所有围观者（含当事人）。任何「每个接收者各抽一次」的写法都会让客户端之间不同步（`93f9487` 修的正是这个）。
2. **枚举里不要留没有动作数据的取值**：`FLYHUG` 被注释掉，就是因为 `PlayerAnimationRegistry.getAnimation` 会返回 `null`，随后在 `new KeyframeAnimationPlayer(null)` 处炸掉。新增动作类型时，先落两份 JSON，再进枚举。

### D.2 未实现 / 遗留

- `FLYHUG`（飞行拥抱）：枚举与 switch 里都只剩注释，**没有任何 `hug_fly_*` 动作文件**。`AnimationManager` 里那段注释代码引用的 `hug_fly_sender` / `hug_fly_receiver` 从未存在过。
- 没有配置文件、没有管理员命令、没有权限节点、没有成就/进度、没有声音。
- 没有 `src/generated/resources` 的实际产物（数据生成跑起来是空的）。
