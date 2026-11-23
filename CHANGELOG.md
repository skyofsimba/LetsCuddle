# 更新日志 · Changelog

本项目的版本号跟随模组自身功能演进。1.x 为 1.20.1 Forge；2.0 起同时支持 1.20.1 Forge 与 1.21.11 Fabric。
格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)。

## [2.0.0-beta1] — 2026-11-23

> 计划与验收见 [doc/2.0.0-beta1-plan.md](doc/2.0.0-beta1-plan.md)；动作包格式见
> [doc/animation-pack-format.md](doc/animation-pack-format.md)；做包见
> [doc/animation-pack-authoring.md](doc/animation-pack-authoring.md)。

### Added

- **跨版本**：同一份 `common/` 源码产出 `letscuddle-1.20.1-forge-*.jar` 与
  `letscuddle-1.21.11-fabric-*.jar`（1.21.1 NeoForge 明确不做）。
- **动作包外置**：动作来自资源包（`assets/<ns>/cuddle_animations/*.json` manifest），
  客户端扫描所有资源包构建注册表，**F3+T 热重载**；内置两个动作作为 jar 内最低优先级包。
- **服务端能力握手**：协议 v4 的 `RequestCaps` / `Caps` / `Select` / `Selected` 四个新包；
  服务端只保存每个客户端上报的「id → 锁定时间 + 内容版本」，不解析包。
- **回退规则**：接受者没有该动作、或两端 `content_version` 不一致 → 回退内置默认动作并提示发起者；
  旁观者缺包时本地回退；锁定时间取双方最小值。
- **1.21.11 渲染**：不取消原版渲染，用 mixin 在原版玩家渲染链上叠加朝向、前压与骨骼姿势，
  皮肤 / 盔甲 / 手持物 / 披风全部保留；第三人称镜头跟随模型偏移。
- **文档**：2.0 计划、动作包格式 v1、制作指南、`test-packs/` 示例包；1.x 文档归档到
  `doc/archive/1.x/`。

### Changed

- 动作选择由客户端发起（只有客户端知道自己装了哪些包），服务端校验后写入物品。
- 动作 id 全程字符串（`letscuddle:normal`）；旧的短 id（`normal` / `touch_head`）在读取时自动迁移，
  旧存档里的贴贴券继续可用。
- 1.20.1 的动作播放速度改由 manifest 的 `speed` 决定（内置仍是 0.5×），不再写死在代码里。
- 仓库布局改为 `forge-1.20.1/` + `fabric-1.21.11/` + `common/`；CI 拆成两个 job。

### Removed

- `CuddleAnimationEnum`（动作两选一的枚举）与写死在 jar 里的硬编码动作映射。
- 对 PAL（Player Animation Library）的依赖计划：1.21.11 改为模组内置的采样器
  （`common/CuddleAnimClip`），因此 1.21.11 客户端**不需要**任何动画库。

### Fixed

- 1.21.11 首次进入存档发券、创造栏、配方、物品模型（1.21.4+ 的 `items/` 模型定义）全部补齐。
- 1.x 的 PORT-FIX 全部保留：过期在主线程 tick、先校验后播「已接受」、重合退化、掉线立刻清理。

### Breaking

- **网络协议 3 → 4**：2.0 与 1.x 的客户端/服务端不能互连（Forge 通道版本会直接拒绝；
  Fabric 用能力包里的 `protocol` 判断并按「只有内置动作」处理）。
- 动作 id 从短 id 变为完整 id；自定义资源包里的 `player_animation/*.json` 仍然有效，
  但想让它出现在选择列表里需要一个 manifest。

### 已知差异（1.21.11 vs 1.20.1）

- 1.20.1 由 playerAnimator 驱动动作；1.21.11 用模组内置采样器按同一份 JSON 逐 tick 复刻
  （语义对齐 playerAnimator 1.0.2，见 `doc/2.0.0-beta1-plan.md` §8.4）。
- 接管期间隐藏名字牌与阴影（模型被推离实体坐标约 1 格）；模型高度与 1.20.1 一样是 2.0 格。
- 1.21.11 没有「通道版本」概念，旧客户端不会被拒绝，只会被当成「只有内置动作」。

## [1.6.0] — 2026-11-21

**破坏性改名：`hug` → `cuddle`（抱抱 → 贴贴）**

- 物品 id：`letscuddle:hug_ticket` → `letscuddle:cuddle_ticket`（旧物品会变成未知物品，需重新获取）
- 语言键：`letscuddle.message.hug_*` → `cuddle_*`，`item.letscuddle.hug_ticket*` → `item.letscuddle.cuddle_ticket*`
- 动作注册 id：`hug_normal_sender` 等 4 个 → `cuddle_normal_sender` 等（只改了 4 份动作 JSON 的 `name` 字段，
  `torso`、`stopTick`、`version` 等数值一律未动）
- Java 类：7 个 `HugXxx` → `CuddleXxx`（如 `HugTicketItem` → `CuddleTicketItem`）
- 资源文件：`hug_ticket.png` / `hug_ticket.json` → `cuddle_ticket.png` / `cuddle_ticket.json`
  （贴图、物品模型、配方、logo）
- 模组描述：`Cuddle with other players using a cuddle ticket!`
- 物品名「抱抱券」→「贴贴券」，中英文提示统一为「贴贴 / cuddle」

**兼容性**：协议版本仍是 `3`，但物品 id 与动作 id 都变了，**客户端与服务端必须同时升级到 1.6.0**。
下文 1.5.x 及更早记录中的「拥抱 / 抱抱」即现在的「贴贴」。

**升级提示**：更新前请让玩家用完或丢掉旧券、并清理箱子里的旧券——旧 id 的物品在存档加载时会从背包和容器中
被移除（不会崩服、不会坏档，只会静默消失）。进行中的请求与贴贴状态只存在内存里，重启服务端即清空。
另外，如果服务器上的商店/菜单/数据包引用了 `letscuddle:hug_ticket`，请同步改成 `letscuddle:cuddle_ticket`。

## [1.5.1] — 2026-11-21

**修复**
- 拥抱时第三人称镜头不以拥抱为中心（两人跑到画面外/侧边）：
  - 发起者在接受拥抱后也会转向接受者。原实现只让接受者转向，而接受者被摆到了「发起者身后」，
    自动切第三人称后发起者的镜头仍朝着旧方向，整段拥抱都在画面外。
  - 第三人称相机改为跟随**渲染模型的实际位置**。拥抱动画的 `body` 位移会把模型推离实体坐标约 1 格，
    原版相机绕实体旋转，于是画面中心是空地、拥抱偏在一边。现通过 Access Transformer 打开
    `Camera#setPosition(Vec3)`，在 `ViewportEvent.ComputeCameraAngles`（`Camera#setup` 之后、
    `LevelRenderer#renderLevel` 之前）把相机平移到模型位置；第一人称不偏移。
- 新增 `src/main/resources/META-INF/accesstransformer.cfg`（SRG 名 `m_90581_`），并在 `build.gradle`
  启用 `accessTransformer`。首次构建会重新生成 AT 过的 Forge 工件（约 1 分钟）。

## [1.5] — 2026-11-21

**新增**
- 拥抱动作由发起者自选：抱抱券用 NBT 记住所选动作，`潜行 + 右键`（空中或玩家身上）循环切换，
  聊天栏上方提示当前动作，物品提示里显示已选动作。
- 请求消息与发送成功消息带上动作名，例如「Steve 向你发起了『摸头拥抱』拥抱请求!」。

**变更**
- 动作在网络上的表示由枚举序号（`ordinal`）改为稳定字符串 id（`normal` / `touch_head`），
  协议号 `2 → 3`；以后新增动作不再依赖枚举顺序（解决 K18）。

## [1.4] — 2026-11-21

**新增**
- 拥抱开始时当事人自动切第三人称；拥抱期间玩家自己按 `F5` 改过视角的，结束后尊重玩家选择。
- 任一当事人按住潜行 0.25 秒（5 tick）可提前结束拥抱，动作立即收掉，双方收到「拥抱已结束」。

**修复**
- 拥抱期间模型阴影留在原地：1.20.1 的阴影由 `EntityRenderDispatcher` 在渲染器返回后按实体坐标绘制，
  现在渲染前临时隐藏、`AFTER_ENTITIES` 阶段恢复（另加 tick 兜底）。
- K10：第一人称拥抱后切第三人称会「无头」——记录被隐藏过头部的玩家，解锁后恢复。
- 掉线解锁时会同时收掉动作（避免留下一个抱着空气的人）。

**变更**
- 网络包增加 `cancelled` 字段，协议号 `1 → 2`。

## [1.3] — 2026-11-20

首个 **Minecraft 1.20.1 / Forge** 移植版，按 `doc/archive/1.x/port-plan.md` 做 1:1 语义迁移。

**移植修复**（相对原 NeoForge 1.21 版本）
- K1/K2：60 秒请求过期从调度线程池改为服务端 tick 计时（顺带消除永不关闭的非守护线程池）
- K3：「已接受」消息移到站位校验之后
- K5：两人 XZ 重合时不再除零产生 NaN，退化为「无法站立」
- K9：拥抱中掉线立刻清理并广播结束包，客户端另有 200 tick 兜底
- K11：删除恒真的 `active` 字段，结束/掉线统一走同一条清理路径
- K13：接受时校验请求的接收者确实是执行命令的人
- K15：服务端不再引用客户端类（`DistExecutor` 隔离）
- K19：动作数据缺失时只警告，不再 NPE

**保留原行为**：K4 落点方向、K6 动作 `torso→body`、K7 模型高度、K8 变换顺序、
K12 有序请求键、K16 `sidedSuccess`、K17 不持久化、K20 首登用 `.dat` 判定。
