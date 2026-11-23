# 贴贴动作包制作指南

> 面向：想给 LetsCuddle 做动作包的作者（会一点 Blockbench 或愿意手改 JSON 即可）。
> 配套：[动作包格式 v1](animation-pack-format.md)（字段的权威定义）· [2.0.0-beta1 计划](2.0.0-beta1-plan.md) · [1.x 动作数据规格](archive/1.x/original-design.md)
> 状态：2.0.0-beta1 定稿。工具链结论来自 2.0 的实测与源码比对；**格式与验收标准已经冻结**。

---

## 1. 你要做的到底是什么

一个"贴贴动作"不是一段动画，而是**两段互补的玩家动画 + 一张说明文件**：

| 角色 | 谁在动 | 典型内容 |
|---|---|---|
| **sender（发起者）** | 右键发起请求的那个人 | 站立、微微前倾、手臂环抱、头靠过去 |
| **receiver（接受者）** | 被请求、点「同意」的那个人 | 被摆到发起者另一侧 1.3 格，身体前压 1 格"贴进怀里"，手臂回抱 |

两人**面对面**，模型高 2.0 格（没有原版 0.9375 的缩放），锁定 6 秒（120 tick）。
动作文件本身约 2 秒（61 tick），以 **0.5 倍速**播放 → 约 122 tick，刚好比锁定长一点点，看起来是"自然收尾"。

你要交付的就是：

```
assets/<你的命名空间>/
├─ cuddle_animations/<动作名>.json          ← manifest（说明文件）
├─ player_animation/<文件>.json             ← 1.20.1 用
└─ player_animations/<文件>.json            ← 1.21.11 用（同一份内容）
```

加一个 `pack.mcmeta`，打成 zip，就是资源包。

---

## 2. 先读懂内置动作（最省事的起点）

模组自带四份动画，是最可靠的模板：

| 文件 | 动作 id | 用到的骨骼 |
|---|---|---|
| `normal_s.json` | `letscuddle:cuddle_normal_sender` | head、leftArm、rightArm |
| `normal_r.json` | `letscuddle:cuddle_normal_receiver` | head、leftArm、rightArm、leftLeg、rightLeg、**torso.z** |
| `touch_s.json` | `letscuddle:cuddle_touch_sender` | head、leftArm、rightArm |
| `touch_r.json` | `letscuddle:cuddle_touch_receiver` | head、leftArm、rightArm、leftLeg、rightLeg、**torso**（含 z 位移与三轴旋转） |

它们的共同点：

- `"emote"` 结构：`beginTick` / `endTick` / `stopTick` / `returnTick` / `degrees:false` / `moves[]`。
- 每个 `move` 有 `tick`、`easing`、`turn`，以及按骨骼分组的 `yaw/pitch/roll`（弧度）。
- **没有 `"version"` 字段**。这是有意的：缺省时解析器把 `torso` 当作整模根节点 `body`，`receiver` 的 `torso.z = -1.0` 才会把纸片人推进对方怀里。
- 只有一种缓动：`EASEINOUTQUAD`。

> **最重要的一条：先复制 `normal_s.json` / `normal_r.json`，改数值，别从零写。**
> 从零写最容易踩的坑是坐标系、`torso`→`body` 的语义、以及 61 tick 与 120 tick 的对齐。

---

## 3. 工具链

### 3.1 三种做法

| 做法 | 适合 | 风险 |
|---|---|---|
| **A. 复制内置 JSON 手改**（推荐给第一次做的人） | 会看数字、想做小改动 | 没有可视化，但**一定能被 1.20.1 的 playerAnimator 加载** |
| **B. Blockbench + Emotecraft 插件** | 想要可视化摆姿势 | 导出结构要与内置 JSON 对齐（`emote`/`moves`/`degrees`），导出后按 §4 检查 |
| **C. Blockbench / GeckoLib / Bedrock 格式** | 只做 1.21.11 | PAL 能读（`animations` 结构），但 **1.20.1 的 playerAnimator 读不了**；跨版本包不要用 |

> 两个平台读同一份文件：1.20.1 交给 playerAnimator，1.21.11 交给 PAL。
> 两条路径都按同一套 `emote` 语义解析（首帧前插值、两帧之间用前一帧缓动、`stopTick` 回落），所以 **A 是唯一有保证的路径**。

### 3.2 建议的准备

- 一个干净的世界 + 两个客户端（或一个客户端 + 一个开发服务端），见 §7。
- 打开 `run/logs/latest.log`，过滤 `letscuddle`：注册表加载、拒绝原因、缺少动画都会打日志。
- 装一个能看模型坐标的资源包/模组不是必需的；用 F3 的 hitbox 和第三人称就能对齐。

---

## 4. 分步做一套动作

### 4.1 定命名空间与 id

```
命名空间：heartpack
动作 id：heartpack:warm          ← 路径决定，不要写进 manifest
sender ：heartpack:warm_sender   ← 动画文件里的 "name"
receiver：heartpack:warm_receiver
```

动画文件里的 `"name"` 必须等于 `sender`/`receiver` 的 path 部分；命名空间来自文件夹。

### 4.2 做 sender

从 `normal_s.json` 复制，改：

1. `"name": "warm_sender"`（不要带命名空间）。
2. `stopTick` 保持 61，`degrees:false` 保持。
3. **不要**加 `"version"`。
4. 骨骼：`head`、`leftArm`、`rightArm`；需要腿部动作就加 `leftLeg`/`rightLeg`（参照 `normal_r`）。
5. `turn` 是身体朝向（弧度）；内置动作基本都是 0，因为朝向由代码统一处理。**不要**用 `turn` 去实现"转身"。

坐标约定（1.x 实测）：

- 旋转单位是**弧度**（`degrees:false`）。
- `head.yaw` / `pitch` 的正方向与坐标系有关（不同工具导出的符号可能相反）；不要凭感觉，拿内置值做对照，一次只改一个轴。
- 手臂的 `yaw/pitch/roll` 是相对肩部枢轴的旋转；内置 `normal_s` 已经把"环抱"调好了，直接沿用最稳。

### 4.3 做 receiver

从 `normal_r.json` 复制，改：

1. `"name": "warm_receiver"`。
2. **`torso.z` 是贴合的关键**：内置是 `-1.0`（米），表示"向自己身体的 −Z 方向移动 1 格"。
   两人的朝向由代码摆好，所以最终效果是"压进对方怀里 1 格"。
   - 0 → 两人之间空 1 格；
   - -1.0 → 正好贴合；
   - -1.2 以下 → 会穿模。
3. `torso` 的 `yaw/pitch/roll` 用来做"歪头/侧身"；不要用它做整体位移（整体位移用 `z`）。
4. 腿部（`leftLeg`/`rightLeg`）只做轻微弯曲，避免两人腿部穿模。

### 4.4 对齐检查（不用进游戏就能算）

| 项 | 值 | 说明 |
|---|---|---|
| 落点 | 发起者另一侧 **1.3 格** | 服务端把接受者传送到 `sender + 1.3 * normalize(sender - receiver)` |
| 模型高 | **2.0 格** | 1.20.1 的接管渲染不乘 0.9375 |
| 锁定 | **120 tick（6 秒）** | 服务端固定；由 manifest 的 `lock_ticks` 覆盖（20–400） |
| 播放速度 | **0.5×**（manifest `speed`） | 61 tick 的动作 → 约 122 tick |
| 动作收尾 | `stopTick=61` | 锁定结束后约 2 tick 自然收尾 |

如果 `lock_ticks` 比动作实际时长短，动作会被硬切；如果长太多，两人会保持最后一帧站着不动。**让 `lock_ticks ≈ stopTick / speed`**。

### 4.5 放文件

```
assets/heartpack/
├─ cuddle_animations/warm.json
├─ player_animation/warm_s.json      ← 内容里的 name = warm_sender
├─ player_animation/warm_r.json      ← name = warm_receiver
├─ player_animations/warm_s.json     ← 同一份内容（1.21.11）
└─ player_animations/warm_r.json
```

### 4.6 写 manifest

```json
{
  "format": 1,
  "content_version": "1",
  "sender": "heartpack:warm_sender",
  "receiver": "heartpack:warm_receiver",
  "name_key": "heartpack.animation.warm",
  "name": "暖暖",
  "speed": 0.5,
  "lock_ticks": 122,
  "order": 10
}
```

`name_key` 需要在 `assets/heartpack/lang/zh_cn.json` / `en_us.json` 里有对应条目；没有就退回 `name`。

`content_version` 是作者声明的**内容版本**：改动作数值、换文件就 +1。两端不一致时服务端会回退默认动作（见[格式规范 §6](animation-pack-format.md)）。注意它和动画 JSON 里的 `"version"` 不是一回事——后者**不要写**。

### 4.7 安装与热重载

1. 把整个文件夹（或 zip）放进 `.minecraft/resourcepacks/`。
2. 游戏内 **F3+T**（不要用 `/reload`，那只重载数据包）。
3. 看日志：`letscuddle` 应打印加载了几个动作、拒绝了哪些。
4. `潜行 + 右键`（对空气或玩家）循环切换；或看物品 tooltip 当前动作。

### 4.8 双人测试

至少两个人（或一个客户端 + 一个开发客户端）：

1. A 选 `heartpack:warm`，右键 B 发起；B 收到可点击消息。
2. B 点「同意」：两人面对面、B 被摆到 A 另一侧 1.3 格。
3. 看：贴合（无空隙/不穿模）、朝向、高度、手臂是否穿模。
4. 6 秒后自然结束；蹲下 0.25 秒提前结束；中途掉线不卡。
5. 第三人称镜头应以贴贴为中心（1.20.1 已实现；1.21.11 见计划 P6）。

---

## 5. 调试：症状 → 原因 → 处理

| 症状                   | 最可能的原因                                          | 处理                                                         |
| -------------------- | ----------------------------------------------- | ---------------------------------------------------------- |
| 动作不在循环里              | manifest 没被扫描到 / JSON 坏了 / `format` 不是 1        | 看日志；确认路径 `assets/<ns>/cuddle_animations/*.json`；用 JSON 校验器 |
| 动作在，但播放时没反应          | `sender`/`receiver` 指向的动画 id 不存在                | 确认动画文件在 `player_animation(s)/`，且 `"name"` 与引用一致            |
| 只有一方动                | 只做了 sender 或 receiver                           | 两段都要                                                       |
| 一方看到 A、另一方看到 B       | 两端包版本不同，或改了动作没 bump `content_version`           | 让两人装同一个包；改动作就 +1                                           |
| 两人之间空 1 格            | `torso.z` 是 0，或加了 `"version"` 导致 `torso` 不再是根节点 | 用 `torso.z=-1.0`，删掉 `version`                              |
| 位移大了 16 倍            | 用了"像素"语义去写 PlayerAnimator 格式                    | 本格式的位移单位是**米**，`0.0625` 才是 1 像素                            |
| 动作被硬切                | `lock_ticks` 小于实际时长                             | 调到 `stopTick / speed` 附近                                   |
| 结束后保持最后一帧            | `lock_ticks` 远大于动作时长                            | 同上                                                         |
| 两人脸对脸但姿势错位           | 用了 `turn` 或整体位移去调朝向                             | 朝向由代码处理；只用骨骼旋转与 `torso.z`                                  |
| 1.20.1 能播、1.21.11 不能 | 文件只放了 `player_animation/`                       | 再放一份到 `player_animations/`                                 |
| 1.21.11 能播、1.20.1 不能 | 文件只放了 `player_animations/`                      | 再放一份到 `player_animation/`（内容相同）                            |
| 重载后动作消失              | 包被移出/改坏，注册表回退                                   | 看日志；修好后 F3+T                                               |

### 5.1 没有"眼睛"时怎么调

- **静帧**：在 tick 10 / 30 / 50 / 61 截图，和内置动作并排看。姿势问题（手臂高了、头歪了）在静帧里比视频更容易看出来。
- **数值**：用内置动作做 oracle——同一份 JSON 在 1.20.1 与 1.21.11 上逐 tick 对比 6 根骨骼的旋转/位移，误差 < 1e-3 即认为一致（见计划 §8.4）。
- **二分**：一次只改一个通道（先头、再手臂、最后 torso.z）。

---

## 6. 发布

1. 目录结构见 §4.5；根目录加 `pack.mcmeta`。**一个包同时支持 1.20.1 与 1.21.11 的写法**：
   ```json
   {
     "pack": {
       "description": "HeartPack for LetsCuddle",
       "pack_format": 15,
       "supported_formats": { "min_inclusive": 15, "max_inclusive": 75 },
       "min_format": 15,
       "max_format": 75
     }
   }
   ```
   1.20.1 只读 `pack_format`（资源格式 15）；1.21.11 的资源格式是 **75**（1.21.9 起改用
   `min_format`/`max_format`，`supported_formats` 的上界不能超过 64，否则会被判定"未知版本"）。
   只发单版本时，保留 `pack_format` 与对应版本的 `min_format`/`max_format` 即可。
2. zip 的根必须是 `pack.mcmeta` + `assets/`，不要多套一层文件夹。
3. 写 README：支持的动作 id、需要的 LetsCuddle 版本、截图、许可证。
4. 版本号：`1.0.0` 起；改 manifest 字段就升 minor，改动作数值升 patch；同时把 manifest 的 `content_version` +1（它才是运行时比较的版本，包版本号只是给人看的）。
5. 不要打包可执行文件、不要引用外部 URL、不要超过 10 MB。

---

## 7. 作者检查单

- [ ] `pack.mcmeta` 存在，`pack_format` 对。
- [ ] manifest 在 `assets/<ns>/cuddle_animations/`，id 与路径一致。
- [ ] sender/receiver 都存在，`"name"` 与引用一致。
- [ ] 没有 `"version"`，`degrees:false`，`stopTick` 与 `lock_ticks` 对得上。
- [ ] manifest 的 `content_version` 在改动作后已 +1；两人装的是同一个包。
- [ ] 1.20.1 与 1.21.11 的动画文件各放一份。
- [ ] F3+T 后动作出现。
- [ ] 双人测试：贴合、朝向、高度、6 秒、蹲下取消、掉线。
- [ ] 删包后回退正常。
- [ ] 日志无 `ERROR`（`WARN` 可以接受，但要知道原因）。

---

## 8. 常见问题

**Q：我能用 GeckoLib 的动画吗？**
A：可以，但只在 1.21.11 上（PAL 能读 GeckoLib/Bedrock 的 `animations` 结构）；1.20.1 的 playerAnimator 只认 PlayerAnimator/Emotecraft 的 `emote` JSON。要做**跨版本**包，用 Blockbench 的 Emotecraft 插件导出 `emote` 格式。

**Q：能加音效/粒子吗？**
A：v1 不支持；manifest 里没有对应字段，加了也会被忽略。

**Q：能改锁定时间/速度吗？**
A：能，`lock_ticks`（20–400）与 `speed`（0.1–2.0）。改之前先确认两边客户端都装了同一个包，否则服务端会回退到 `letscuddle:normal`。

**Q：能覆盖内置的 `letscuddle:normal` 吗？**
A：能，同路径覆盖；但请保留一个可用的内置回退，别让服务器在缺包时没有动作可放。

**Q：服务端需要装包吗？**
A：不需要。服务端只收客户端上报的"能力列表"；包只装在客户端（或通过服务器资源包下发）。
