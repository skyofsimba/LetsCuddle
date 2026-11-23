# 贴贴动作包格式 v1

> 状态：**冻结（2.0.0-beta1）**。实现可以变，字段与语义不再变；要改必须升 `format`。
> 读者：动作包作者、实现者、审核者。
> 配套：[2.0.0-beta1 实施计划](2.0.0-beta1-plan.md) · [动作包制作指南](animation-pack-authoring.md) · [1.x 动作数据规格](archive/1.x/original-design.md)

---

## 0. 一句话

一个**动作包**就是一个普通的 Minecraft **资源包**：里面放一组 `manifest`，每个 manifest 指向两段玩家动画（发起者 + 接受者）。
LetsCuddle 在客户端扫描所有资源包，把 manifest 变成可选动作；服务端只负责在两人之间同步**动作 id / 内容版本**与**能力列表**，从不解析包内容。

---

## 1. 目录与 id

```
<资源包根>/
├─ pack.mcmeta
└─ assets/
   └─ <namespace>/
      ├─ cuddle_animations/
      │  └─ <path>.json                 ← manifest；动作 id = <namespace>:<path>
      ├─ player_animation/              ← 1.20.1 的 playerAnimator 读取
      │  └─ <file>.json                 ← 动画 id = 文件内 "name" 字段（推荐 <namespace>:<name>）
      └─ player_animations/             ← 1.21.11 的 PAL 读取
         └─ <file>.json
```

规则：

1. **manifest 的 id 由路径决定**，不是内容里的字段：`assets/heartpack/cuddle_animations/warm.json` → `heartpack:warm`。
   manifest 里**不要**写 `id` 字段（写了也忽略，避免和路径不一致）。
2. 命名空间用你自己的（`heartpack`），**不要**往 `letscuddle` 里塞新动作——那是内置包的保留命名空间；要覆盖内置动作才用同路径（见 §5）。
3. 一个动作 = **两段**玩家动画：`sender`（发起者）与 `receiver`（接受者）。两段必须同时存在。
4. 同一个包同时支持 1.20.1 与 1.21.11 时，同一份动画 JSON 要分别放进 `player_animation/` 与 `player_animations/`（目录名是库的硬编码，模组不能替它改）。manifest 只写一份。
5. 目录/文件名只允许 `[a-z0-9_./-]`，**不要**用大写、空格、中文、`..`。

---

## 2. manifest

### 2.1 最小示例

```json
{
  "format": 1,
  "content_version": "1",
  "sender": "heartpack:warm_sender",
  "receiver": "heartpack:warm_receiver",
  "name_key": "heartpack.animation.warm",
  "name": "暖暖",
  "speed": 0.5,
  "lock_ticks": 120,
  "order": 10
}
```

### 2.2 字段表

| 字段 | 类型 | 必填 | 默认 | 范围 / 说明 |
|---|---|---|---|---|
| `format` | int | **是** | — | 必须 `1`；`> 1` 的包会被拒绝并记日志（前向兼容留给未来） |
| `content_version` | string | 否 | `"1"` | ≤ 32 字符；**内容版本**：改动画数值/替换文件就 +1。两端不一致时回退默认（见 §6） |
| `sender` | string | **是** | — | 发起者动画 id，`<ns>:<path>`，≤ 128 字符 |
| `receiver` | string | **是** | — | 接受者动画 id，同上 |
| `name_key` | string | 否 | — | 语言键；存在时优先用 `Component.translatableWithFallback(name_key, name)` |
| `name` | string | 否 | 动作 id | 纯文本回退名；`name_key` 也没有时显示 id |
| `speed` | number | 否 | `0.5` | `0.1`–`2.0`；播放速度倍率（1.0 = 动画 JSON 的原始速度） |
| `lock_ticks` | int | 否 | `120` | `20`–`400`；服务端锁定两人的 tick 数。**必须 ≥ 动画实际时长**，否则动作会被截断 |
| `order` | int | 否 | `0` | 排序权重，小的在前；同权重按 id 字典序 |
| `icon` | string | 否 | — | **v1 保留字段**，当前忽略；给未来的动作选择 GUI 用 |

未知字段一律**忽略**（允许未来加字段而不升 format），但会在 debug 日志里提示一次。

### 2.3 校验（加载时）

| 情况 | 行为 |
|---|---|
| 不是合法 JSON / 顶层不是对象 | 拒绝该 manifest，`WARN` 带路径与原因 |
| `format` 缺失或 ≠ 1 | 拒绝；`> 1` 额外提示"需要更新的 LetsCuddle" |
| `content_version` 不是字符串或超长 | 用 `"1"` 并 `WARN`（不整包拒绝） |
| `sender` / `receiver` 缺失、不是字符串、不是合法 id | 拒绝 |
| `speed` / `lock_ticks` / `order` 类型不对或越界 | **夹紧到范围内**并 `WARN`（不整包拒绝，避免一个笔误让整个包消失） |
| `name_key` / `name` 都缺 | 允许，显示 id |
| 同一资源包内两个文件算出同一个 id | 不可能（路径唯一） |
| 不同资源包同一个 id | 资源包优先级高的覆盖低的（由原版资源栈决定，见 §5） |
| 文件 > 16 KiB | 拒绝（manifest 应该 < 2 KiB） |
| 单包 manifest 数 > 256，或总数 > 512 | 拒绝超出部分并 `WARN` |
| 引用的动画 id 在播放时找不到 | 该动作**不播**，`WARN`（不在加载时报错：库的重载顺序不保证） |

### 2.4 引用的玩家动画

`sender` / `receiver` 指向的 id，必须能被对应平台的动画库解析：

- 1.20.1：`assets/<ns>/player_animation/<file>.json`，文件里的 `"name"` 就是 id 的 path 部分。
- 1.21.11：`assets/<ns>/player_animations/<file>.json`，同上（PAL 的规则）。

动画 JSON 的硬性要求（来自 1.x 的实测，见 [original-design §9](archive/1.x/original-design.md)）：

1. **不要加 `"version"` 字段**：缺省时解析器把 `torso` 当作整模根节点 `body`，这是"贴进怀里"的基础；加了之后贴合会消失。
2. `"degrees": false` 必须保留（旋转是弧度）。
3. `stopTick` 与 `lock_ticks` 对齐：`stopTick=61`、`speed=0.5` → 约 122 tick，锁定 120 tick 刚好。
4. `body`/`torso` 的 `z = -1.0`（米）是"推进对方怀里 1 格"的关键；1.21.11 的 PAL 在**代码 API** 里以像素为单位，但加载 PlayerAnimator 格式时会做换算——**beta1 的 P7 必须用数值对比确认这一点**（见计划 §8.4）。

---

## 3. 内置包

模组 jar 内自带一个最低优先级的包，保证任何情况下都有动作可放：

| id | manifest | sender | receiver | lock | speed |
|---|---|---|---|---|---|
| `letscuddle:normal` | `assets/letscuddle/cuddle_animations/normal.json` | `letscuddle:cuddle_normal_sender` | `letscuddle:cuddle_normal_receiver` | 120 | 0.5 |
| `letscuddle:touch_head` | `assets/letscuddle/cuddle_animations/touch_head.json` | `letscuddle:cuddle_touch_sender` | `letscuddle:cuddle_touch_receiver` | 120 | 0.5 |

- 内置动作**永远不能被删掉**：重载后如果 `letscuddle:normal` 缺失，客户端保留上一份可用注册表并 `ERROR` 日志（防止一个坏包让所有人不能贴贴）。
- 外部包可以用同路径**覆盖**内置 manifest（例如把 `normal` 换成自己的动作），但要自己保证 sender/receiver 存在。

---

## 4. 加载与热重载

| 项 | 行为 |
|---|---|
| 触发 | 客户端 **F3+T**（资源重载）。`/reload` 只重载数据包，**不会**重载 `assets/`，不要用它测动作包 |
| 线程 | 资源重载线程解析 → 构建不可变快照 → 原子替换；不在渲染线程做 I/O |
| 重载时正在播放 | 已开始的贴贴继续播完（后端持有动画对象）；注册表只影响"下一次播放" |
| 重载后 | 客户端把新的能力列表（id → lock_ticks + content_version）发给服务端 |
| 失败 | 单个包失败不影响其它包；全部失败时保留旧快照 |
| 首次进服 | 客户端收到服务端的"请求能力"包后回复；服务端在 100 tick 内没收到就按"只有内置动作"处理并提示 |

---

## 5. 合并与覆盖

原版资源包栈的规则就是本格式的规则：

1. 同路径 → **高优先级包覆盖低优先级包**（内置包在 jar 里，优先级最低）。
2. 不同路径 → 两个动作共存。
3. 资源包列表顺序由玩家决定（`options.txt` 的 `resourcePacks`），模组不提供额外开关。
4. 覆盖内置 `normal` 是允许的；覆盖后如果 manifest 坏了，加载会失败 → 见 §2.3 与 §3 的"保留旧快照"。

---

## 6. 部署形态与服务端行为

### 6.1 谁需要装什么

| 角色 | 装 letscuddle | 装动作包 | 说明 |
|---|---|---|---|
| 服务端 | 必须 | **不需要** | 只转发 id / content_version / 能力，不解析包 |
| 发起者 / 接受者 | 必须 | 要玩自定义动作就必须，且**同一个包、同一个 content_version** | 服务端校验；不一致回退内置 |
| 旁观者 | 必须 | 可选 | 没有包或版本不同 → 本地回退内置默认动作 |

- 不装任何动作包：内置 `letscuddle:normal` / `letscuddle:touch_head` 可用。
- 客户端还需要对应的动画库：1.20.1 是 playerAnimator（客户端依赖），1.21.11 是 PAL（`suggests` + 守卫）；**服务端不需要**。
- 包的分发不由模组负责：整合包、服务器 `resource-pack=`、手动放进 `resourcepacks/` 都可以；模组不会主动下发。
- 服务端**无法**校验包内容，只能校验客户端上报的 id / content_version / lock_ticks；所以"两人同包同版本"是玩家侧的要求。

### 6.2 服务端做什么

服务端**不读 `assets/`、不解析包**。它只知道每个在线客户端上报的：

```
protocol = 4
format   = 1
animations = { "<id>": { "lock_ticks": 120, "content_version": "1" }, ... }   // 最多 256 条
```

用途：

1. 发起请求时，检查**接受者**是否也有这个 id；没有 → 回退到 `letscuddle:normal` 并提示发起者。
2. 双方 `content_version` 不同 → 同样回退默认并提示（避免"同 id 不同动作"）。
3. 接受时，`lock_ticks = min(发起者值, 接受者值)`，夹紧到 `[20, 400]`。
4. 广播给 64 格内的旁观者；旁观者缺这个 id 或版本不同 → **本地回退到内置默认动作**（宁可看到另一个拥抱，也不要两个人僵住）。

服务端**不**因为某个客户端缺包而踢人；只有协议版本不匹配（Forge 通道版本 / Fabric 能力包里的 `protocol`）才拒绝。

---

## 7. 安全边界

资源包是**不受信输入**：

- manifest ≤ 16 KiB；单包 ≤ 256 条；全局 ≤ 512 条；id ≤ 128 字符。
- id 正则：`^[a-z0-9_.-]+:[a-z0-9_./-]+$`，且 path 不允许以 `/` 开头、不允许 `..` 段。
- 只读 JSON，不加载类、不执行脚本、不解析 Molang（v1 不支持 Molang）。
- 服务端只接受"能力列表"里的 id，且做同样的正则/长度/数量校验；不接受客户端发来的任意长字符串。
- 客户端把 manifest 里的文本当**纯文本**显示（`Component.literal` 或 `translatableWithFallback`），不解析点击/悬浮事件。

---

## 8. 版本演进

- `format: 1` 是 beta1 的唯一格式。
- 加字段：不升 format，旧客户端忽略未知字段。
- 改语义/必填项：升 `format: 2`，旧客户端拒绝并提示。
- `icon` 在 v1 保留但不生效；GUI 上线时再启用。

---

## 9. 作者检查单

- [ ] `pack.mcmeta` 的 `pack_format` 与目标 MC 版本匹配（1.20.1 = 15；1.21.11 查对应版本的表）。
- [ ] manifest 在 `assets/<你的ns>/cuddle_animations/`，id 与路径一致。
- [ ] 改过动画数值/替换文件时，`content_version` 已 +1；两端一致。
- [ ] sender / receiver 两段动画都存在，且 `"name"` 字段与引用一致。
- [ ] 动画 JSON 没有 `version` 字段，`degrees:false`，`stopTick` 与 `lock_ticks` 对得上。
- [ ] 1.20.1 与 1.21.11 的动画文件分别放在 `player_animation/` 与 `player_animations/`。
- [ ] F3+T 后动作出现在循环里；双人测试通过（见[制作指南 §5](animation-pack-authoring.md)）。
- [ ] 删掉包再 F3+T，动作消失且回退正常，不崩。
- [ ] 包内没有可执行文件、没有超大文件、没有非法路径。
