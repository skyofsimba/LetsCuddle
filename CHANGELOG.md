# 更新日志 · Changelog

本项目的版本号跟随模组自身功能演进。1.x 为 1.20.1 Forge；2.0 起同时支持 1.20.1 Forge 与 1.21.11 Fabric。

## [Unreleased] — 2.0.0-beta1（计划中，未发布）

> 规划与验收见 [doc/2.0.0-beta1-plan.md](doc/2.0.0-beta1-plan.md)；动作包格式见 [doc/animation-pack-format.md](doc/animation-pack-format.md)。
> 以下为**计划**，发布时按实际情况改写。

### 将新增
- 跨版本：1.20.1 Forge + 1.21.11 Fabric（1.21.1 NeoForge 视情况）。
- 动作包外置：资源包里的 `cuddle_animations` manifest、F3+T 热重载、能力协商、内置包回退。
- 动作包格式规范与制作指南。

### 将变更（破坏性）
- 网络协议 `3 → 4`；2.0 与 1.x 的客户端/服务端不能互连。
- 动作选择由枚举改为数据驱动 id；旧 NBT `normal` / `touch_head` 自动迁移。
- 1.21.11 的渲染/相机实现与 1.20.1 不同（1.20.1 行为保持不变）。

### 计划中不包含
- 26.x、GeckoLib、动作选择 GUI、服务端资源包分发、新玩法。

## 1.6.0 — 2026-11-21

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

## 1.5.1 — 2026-11-21

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

## 1.5 — 2026-11-21

**新增**
- 拥抱动作由发起者自选：抱抱券用 NBT 记住所选动作，`潜行 + 右键`（空中或玩家身上）循环切换，
  聊天栏上方提示当前动作，物品提示里显示已选动作。
- 请求消息与发送成功消息带上动作名，例如「Steve 向你发起了『摸头拥抱』拥抱请求!」。

**变更**
- 动作在网络上的表示由枚举序号（`ordinal`）改为稳定字符串 id（`normal` / `touch_head`），
  协议号 `2 → 3`；以后新增动作不再依赖枚举顺序（解决 K18）。

## 1.4 — 2026-11-21

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

## 1.3 — 2026-11-20

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
