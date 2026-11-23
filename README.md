# Let's Cuddle! · 我们贴贴！

![Minecraft](https://img.shields.io/badge/Minecraft-1.20.1-62b47a?style=flat-square)
![Forge](https://img.shields.io/badge/Forge-47.4.29-e6883a?style=flat-square)
![License](https://img.shields.io/badge/License-MIT-3b82f6?style=flat-square)

玩家之间可以相互贴贴。 
Players can cuddle with each other.

你需要安装 [PlayerAnimator 模组](https://modrinth.com/mod/playeranimator) 作为前置！
You need [PlayerAnimator Mod](https://modrinth.com/mod/playeranimator) installed to use this mod!

> **2.0 开发中**：跨版本（1.20.1 Forge + 1.21.11 Fabric）+ 动作包外置（资源包动态加载 / F3+T 热重载）。
> 计划：[doc/2.0.0-beta1-plan.md](doc/2.0.0-beta1-plan.md)；
> 做动作包：[doc/animation-pack-authoring.md](doc/animation-pack-authoring.md)。

> English version below · [中文](#中文) | [English](#english)

---

## 中文

### 从源码构建

```bash
git clone https://github.com/skyofsimba/LetsCuddle.git
cd LetsCuddle/LetsCuddle  # 1.x 的构建目录；2.0 起改为 LetsCuddle/forge-1.20.1
./gradlew build          # Windows: gradlew.bat build
# 产物：build/libs/letscuddle-<版本>.jar（已 reobf，可直接发布）
```

需要 JDK 17。

### 玩法

1. 手持贴贴券，shift + 右键可以切换贴贴动作。
2. 手持贴贴券，直接右键其它玩家。
3. 其他玩家在聊天框中同意请求后二人贴贴。

命令：

```
/letscuddle acceptRequest
/letscuddle rejectRequest
```

### 致谢与许可

- 原版模组 **HugMe**：TuYw（[TunYuntuwuQWQ](https://github.com/TunYuntuwuQWQ)），MIT
- 动作库 **playerAnimator**：[KosmX](https://github.com/KosmX)，MIT
- 1.20.1 Forge 移植：小狮子辛巴 / skyofsimba
- 本仓库代码与资源以 **MIT** 发布，版权声明见 [LICENSE](LICENSE)

作者主页（B 站）：<https://space.bilibili.com/350703330>

---

## English


### Build from source

```bash
git clone https://github.com/skyofsimba/LetsCuddle.git
cd LetsCuddle/LetsCuddle  # 1.x build dir; becomes LetsCuddle/forge-1.20.1 in 2.0
./gradlew build          # Windows: gradlew.bat build
# output: build/libs/letscuddle-<version>.jar (reobfuscated, ready to ship)
```

Requires JDK 17. 

### Usage

1. Holding a cuddle ticket, you can switch between cuddle animations by pressing shift + right-click.
2. Hold the cuddle ticket and right-click on other players.
3. After the other player agrees to the request in the chat box, the two embrace.

Commands:

```
/letscuddle acceptRequest
/letscuddle rejectRequest
```

### Credits & License

- Original mod **HugMe** by TuYw ([TunYuntuwuQWQ](https://github.com/TunYuntuwuQWQ)), MIT
- **playerAnimator** by [KosmX](https://github.com/KosmX), MIT
- 1.20.1 Forge port by 小狮子辛巴 / skyofsimba
- Code and assets in this repository are licensed under **MIT** — see [LICENSE](LICENSE)

Author's homepage (Bilibili): <https://space.bilibili.com/350703330>


---
