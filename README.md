# Let's Cuddle! · 我们贴贴！

![Minecraft](https://img.shields.io/badge/Minecraft-1.20.1-62b47a?style=flat-square)
![Forge](https://img.shields.io/badge/Forge-47.4.29-e6883a?style=flat-square)
![License](https://img.shields.io/badge/License-MIT-3b82f6?style=flat-square)
![playerAnimator](https://img.shields.io/badge/requires-playerAnimator-8b5cf6?style=flat-square)

玩家之间可以相互贴贴。 
Players can hug cuddle with other.

> English version below · [中文](#中文) | [English](#english)

---

## 中文

### 从源码构建

```bash
git clone https://github.com/skyofsimba/LetsCuddle.git
cd LetsCuddle
./gradlew build          # Windows: gradlew.bat build
# 产物：build/libs/letscuddle-<版本>.jar（已 reobf，可直接发布）
```

需要 JDK 17。

### 玩法

1. 手持抱抱券，shift + 右键可以切换拥抱动作。
2. 手持抱抱券，直接右键其它玩家。
3. 其他玩家在聊天框中同意请求后二人拥抱。

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
cd LetsCuddle
./gradlew build          # Windows: gradlew.bat build
# output: build/libs/letscuddle-<version>.jar (reobfuscated, ready to ship)
```

Requires JDK 17. 

### Usage

1. Holding a hug ticket, you can switch between hugging actions by pressing shift + right-click.
2. Hold the hug ticket and right-click on other players.
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
