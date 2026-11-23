# Let's Cuddle! · 我们贴贴！

![Minecraft](https://img.shields.io/badge/Minecraft-1.20.1%20%7C%201.21.11-62b47a?style=flat-square)
![Forge](https://img.shields.io/badge/Forge-47.4.29-e6883a?style=flat-square)
![Fabric](https://img.shields.io/badge/Fabric-0.19.5-dbb69c?style=flat-square)
![License](https://img.shields.io/badge/License-MIT-3b82f6?style=flat-square)

玩家之间可以相互贴贴。
Players can cuddle with each other.

> English version below · [中文](#中文) | [English](#english)

---

## 中文

### 版本矩阵

| 构建 | Minecraft | 加载器 | 需要的前置 | 产物 |
|---|---|---|---|---|
| `forge-1.20.1` | 1.20.1 | Forge 47.4.29 | **playerAnimator**（客户端，1.0.2-rc1+1.20） | `letscuddle-1.20.1-forge-<版本>.jar` |
| `fabric-1.21.11` | 1.21.11 | Fabric Loader 0.19.5 + Fabric API | 无（动画由模组自己播） | `letscuddle-1.21.11-fabric-<版本>.jar` |

- 专用服务端**不需要**装动画库：1.20.1 的依赖声明是 `side=CLIENT`，1.21.11 根本没有动画库依赖。
- 1.x（协议 3）与 2.0（协议 4）**不能互连**，两端必须同为 1.x 或同为 2.0。
- 1.21.1 NeoForge、26.x 不在 2.0 范围内，见 [计划 §2](doc/2.0.0-beta1-plan.md)。

### 安装

1. 把对应平台的 jar 放进 `mods/`。
2. 1.20.1 再放一个 [playerAnimator](https://modrinth.com/mod/playeranimator)。
3. 客户端与服务端装同一个版本。

### 玩法

1. 手持贴贴券，`潜行 + 右键`（对空气或玩家都行）循环切换动作，聊天栏上方与物品提示会显示当前动作。
2. 手持贴贴券，直接右键其它玩家发起请求（会消耗 1 张券）。
3. 对方在聊天框点「同意」后两人面对面贴贴，默认锁定 6 秒；按住潜行 0.25 秒可以提前结束。

命令：

```
/letscuddle acceptRequest <发起者>
/letscuddle rejectRequest <发起者>
```

首次进入某个存档会送 16 张贴贴券，也可以合成（1 张纸 ×4 + 淡蓝染料 ×2 + 粉红染料 ×1 → 4 张）。

### 动作包

动作不再写死在模组里：任何资源包都可以新增/覆盖贴贴动作，客户端 **F3+T** 热重载。

- 想做一个动作包：[制作指南](doc/animation-pack-authoring.md)
- 字段与校验规则：[格式规范 v1](doc/animation-pack-format.md)
- 现成的测试包：[`test-packs/`](test-packs/)

### 从源码构建

```bash
git clone https://github.com/skyofsimba/LetsCuddle.git
cd LetsCuddle

cd forge-1.20.1 && ./gradlew build          # 需要 JDK 17 → build/libs/letscuddle-1.20.1-forge-*.jar
cd ../fabric-1.21.11 && ./gradlew build     # 需要 JDK 21 → build/libs/letscuddle-1.21.11-fabric-*.jar
```

Windows 用 `gradlew.bat`。两个工程各自独立（Gradle wrapper、JDK、映射都不同），共用仓库根下的 `common/`。

单元测试（`common/` 的落点、id、能力、manifest、动画采样）跑在 `forge-1.20.1`：

```bash
cd forge-1.20.1 && ./gradlew test
```

### 致谢与许可

- 原版模组 **HugMe**：TuYw（[TunYuntuwuQWQ](https://github.com/TunYuntuwuQWQ)），MIT
- 动作库 **playerAnimator**：[KosmX](https://github.com/KosmX)，MIT（仅 1.20.1 使用）
- 1.20.1 Forge 移植：小狮子辛巴 / skyofsimba
- 本仓库代码与资源以 **MIT** 发布，版权声明见 [LICENSE](LICENSE)

作者主页（B 站）：<https://space.bilibili.com/350703330>

---

## English

### Version matrix

| Build | Minecraft | Loader | Required mods | Artifact |
|---|---|---|---|---|
| `forge-1.20.1` | 1.20.1 | Forge 47.4.29 | **playerAnimator** (client, 1.0.2-rc1+1.20) | `letscuddle-1.20.1-forge-<version>.jar` |
| `fabric-1.21.11` | 1.21.11 | Fabric Loader 0.19.5 + Fabric API | none | `letscuddle-1.21.11-fabric-<version>.jar` |

Dedicated servers need **no** animation library on either platform. Protocol 3 (1.x) and protocol 4 (2.0) cannot
talk to each other; both sides must run the same major version.

### Install

1. Drop the jar for your platform into `mods/`.
2. On 1.20.1 also install [playerAnimator](https://modrinth.com/mod/playeranimator).
3. Keep client and server on the same version.

### Usage

1. Holding a cuddle ticket, `sneak + right-click` cycles through the animations (air or player).
2. Hold the cuddle ticket and right-click another player to send a request (consumes one ticket).
3. After the other player clicks "Accept", the two embrace for 6 seconds by default; hold sneak for 0.25 s to
   end early.

Commands:

```
/letscuddle acceptRequest <sender>
/letscuddle rejectRequest <sender>
```

### Animation packs

Animations are data-driven: any resource pack can add or override cuddle animations and hot-reload with **F3+T**.
See the [authoring guide](doc/animation-pack-authoring.md) and the [format spec](doc/animation-pack-format.md).

### Build from source

```bash
git clone https://github.com/skyofsimba/LetsCuddle.git
cd LetsCuddle

cd forge-1.20.1 && ./gradlew build          # JDK 17 → build/libs/letscuddle-1.20.1-forge-*.jar
cd ../fabric-1.21.11 && ./gradlew build     # JDK 21 → build/libs/letscuddle-1.21.11-fabric-*.jar
```

Run the unit tests with `cd forge-1.20.1 && ./gradlew test`.

### Credits & License

- Original mod **HugMe** by TuYw ([TunYuntuwuQWQ](https://github.com/TunYuntuwuQWQ)), MIT
- **playerAnimator** by [KosmX](https://github.com/KosmX), MIT (1.20.1 only)
- 1.20.1 Forge port by 小狮子辛巴 / skyofsimba
- Code and assets in this repository are licensed under **MIT** — see [LICENSE](LICENSE)

Author's homepage (Bilibili): <https://space.bilibili.com/350703330>
