# Let's Cuddle! · 抱抱

![Minecraft](https://img.shields.io/badge/Minecraft-1.20.1-62b47a?style=flat-square)
![Forge](https://img.shields.io/badge/Forge-47.4.29-e6883a?style=flat-square)
![License](https://img.shields.io/badge/License-MIT-3b82f6?style=flat-square)
![playerAnimator](https://img.shields.io/badge/requires-playerAnimator-8b5cf6?style=flat-square)

一件消耗品「抱抱券」：右键另一名玩家发起拥抱请求，对方点 `[同意]` 后两人被摆成面对面、原地抱 6 秒，
周围 64 格的玩家都能看到动作。纯表现型社交模组，没有伤害、没有 buff。

> English version below · [中文](#中文) | [English](#english)

<!-- 截图 / GIF 占位：把文件放到 doc/images/ 下，然后取消下面两行注释 -->
<!-- ![demo](doc/images/hug-demo.gif) -->
<!-- ![crafting](doc/images/recipe.png) -->

---

## 中文

### 特性

- **抱抱券**：右键另一名玩家 → 对方聊天栏收到带 `[同意] / [拒绝]` 可点击按钮的请求，双方都不需要打指令
- **两种拥抱动作**：普通拥抱 / 摸头拥抱，由**发起者自己选**（`潜行 + 右键` 切换，物品提示里会显示当前动作）
- **64 格广播**：同一维度、64 格内的所有玩家都能看到这段拥抱
- **自动第三人称**：拥抱开始时当事人自动切到第三人称；追尾视角下自己按 `F5` 改过视角的，结束后不会被打回原样
- **蹲下提前结束**：任一当事人按住潜行 0.25 秒即可结束（动作立刻收掉，不用等 6 秒）
- **不打扰服务端**：专用服务端不需要安装 playerAnimator，也不会加载任何客户端类

### 需要什么

| 项 | 要求 |
|---|---|
| Minecraft | **1.20.1** |
| 加载器 | **Forge 47.4.29**（任意 47.x 均可） |
| 客户端依赖 | [playerAnimator](https://modrinth.com/mod/playeranimator) `1.0.2-rc1+1.20`（**仅客户端需要**） |
| 服务端依赖 | 无（playerAnimator 是 client-side 依赖） |

### 安装

1. 安装 Forge 1.20.1（47.x）；
2. 把 `letscuddle-<版本>.jar` 放进 `mods/`；
3. **客户端**还需要把 playerAnimator 放进 `mods/`（服务端不用）；
4. 双方都要装本模组 —— 协议版本必须一致，否则连接时会直接报版本不匹配。

### 怎么玩

- 创造模式：物品栏「工具与实用物品」里有抱抱券；
- 生存模式：3×3 合成台摆成十字（中间粉染料，上下淡蓝染料，四角纸），一次 4 张：

  ```
  纸   淡蓝  纸
  纸   粉染  纸
  纸   淡蓝  纸
  ```

- 每个存档**首次进入**会自动获得 16 张（判定 `world/playerdata/<uuid>.dat` 不存在）；
- `潜行 + 右键` 切换动作（空中或玩家身上都行），聊天栏上方会提示当前动作；
- 右键另一名玩家发起请求，对方点 `[同意]` 开始；
- 请求 60 秒过期；接受距离上限 5 格；两人必须在同一维度；
- 拥抱中按住潜行 0.25 秒可以提前结束。

### 命令

| 命令 | 说明 |
|---|---|
| `/letscuddle acceptRequest <玩家>` | 同意某人的请求（聊天栏按钮会自动执行） |
| `/letscuddle rejectRequest <玩家>` | 拒绝某人的请求 |

### 已知限制

- 拥抱采用「每 tick 拉回原位」实现，没有无敌/摔落保护，也没有权限与冷却；
  会被传送拦截、反作弊、位移校正类模组或插件打断；
- 拥抱中隐藏了双方的影子（模型有约 1 格的动画位移，原版影子不会跟着走，宁可不画也不错位）；
- 拥抱状态只存在内存里，服务端重启即清空。

### 从源码构建

```bash
git clone https://github.com/skyofsimba/LetsCuddle.git
cd LetsCuddle
./gradlew build          # Windows: gradlew.bat build
# 产物：build/libs/letscuddle-<版本>.jar（已 reobf，可直接发布）
```

需要 JDK 17；首次构建会下载 Forge userdev 并反编译 Minecraft（10–30 分钟）。
开发运行、双人测试、以及开发环境特有的 MixinGradle 注意事项见 [`LetsCuddle/README.md`](LetsCuddle/README.md)。

### 致谢与许可

- 原版模组 **HugMe**：TuYw（[TunYuntuwuQWQ](https://github.com/TunYuntuwuQWQ)），MIT
- 动作库 **playerAnimator**：[KosmX](https://github.com/KosmX)，MIT
- 1.20.1 Forge 移植：小狮子辛巴 / skyofsimba
- 本仓库代码与资源以 **MIT** 发布，版权声明见 [LICENSE](LetsCuddle/LICENSE)

作者主页（B 站）：<https://space.bilibili.com/350703330>

---

## English

### Features

- **Hug Ticket** — right-click another player to send a hug request; they click `[Accept]` or `[Reject]` in chat
- **Two animations** — normal hug and head-pat hug, **chosen by the sender** (`sneak + right-click` to switch, shown in the item tooltip)
- **64-block broadcast** — every player within 64 blocks in the same dimension sees the hug
- **Auto third person** — participants switch to third person when the hug starts; if you press `F5` yourself during the hug, your choice is respected
- **Cancel by sneaking** — hold sneak for 0.25 s to end the hug early (the animation stops immediately)
- **Server friendly** — dedicated servers do not need playerAnimator and never load client classes

### Requirements

| Item | Value |
|---|---|
| Minecraft | **1.20.1** |
| Loader | **Forge 47.4.29** (any 47.x) |
| Client dependency | [playerAnimator](https://modrinth.com/mod/playeranimator) `1.0.2-rc1+1.20` (**client only**) |
| Server dependency | none |

### Installation

1. Install Forge 1.20.1 (47.x);
2. Drop `letscuddle-<version>.jar` into `mods/`;
3. **Clients** also need playerAnimator in `mods/` (servers do not);
4. Both sides must run this mod with the same protocol version, otherwise the connection is rejected.

### Usage

- Creative: the ticket is in *Tools & Utilities*;
- Survival: shaped recipe (paper corners, light blue dye top/bottom, pink dye in the middle) → 4 tickets;
- Every player receives 16 tickets on their **first join of a world** (checked via `world/playerdata/<uuid>.dat`);
- `sneak + right-click` cycles the animation (in air or on a player), the current one is shown in the action bar and tooltip;
- Right-click a player to send a request; they accept by clicking `[Accept]`;
- Requests expire after 60 s; max accept distance is 5 blocks; both players must be in the same dimension;
- Hold sneak for 0.25 s during a hug to end it early.

### Commands

| Command | Description |
|---|---|
| `/letscuddle acceptRequest <player>` | Accept a request (the chat button runs this for you) |
| `/letscuddle rejectRequest <player>` | Reject a request |

### Known limitations

- The hug is implemented by teleporting both players back every tick: no invulnerability, no fall protection,
  no permission/cooldown system, and teleport-blocking or anti-cheat mods can break it;
- Player shadows are hidden during the hug (the model is offset by ~1 block by the animation and vanilla shadows
  do not follow it);
- Hug state lives in memory only and is cleared on server restart.

### Building

```bash
git clone https://github.com/skyofsimba/LetsCuddle.git
cd LetsCuddle
./gradlew build          # Windows: gradlew.bat build
# output: build/libs/letscuddle-<version>.jar (reobfuscated, ready to ship)
```

Requires JDK 17. The first build downloads Forge userdev and decompiles Minecraft (10–30 min).
Development runs, two-client testing and the MixinGradle note for dev environments are in [`LetsCuddle/README.md`](LetsCuddle/README.md).

### Credits & License

- Original mod **HugMe** by TuYw ([TunYuntuwuQWQ](https://github.com/TunYuntuwuQWQ)), MIT
- **playerAnimator** by [KosmX](https://github.com/KosmX), MIT
- 1.20.1 Forge port by 小狮子辛巴 / skyofsimba
- Code and assets in this repository are licensed under **MIT** — see [LICENSE](LetsCuddle/LICENSE)

Author's homepage (Bilibili): <https://space.bilibili.com/350703330>

