# test-packs

给动作包作者和验收用的最小示例包。每个目录本身就是一个 **Minecraft 资源包**：
把整个目录（或压成 zip）放进 `.minecraft/resourcepacks/`，在游戏里 F3+T 重载即可。
它们同时也是**可以照着改的起点**：`valid-new/` 就是"新增一个动作"的最小完整结构。

`pack.mcmeta` 同时写了三套版本字段，两个版本都认：

```json
"pack_format": 15,                                   // 1.20.1（资源格式 15）
"supported_formats": { "min_inclusive": 15, "max_inclusive": 75 },
"min_format": 15, "max_format": 75                   // 1.21.9+ 的写法，1.21.11 资源格式 = 75
```

只写 `pack_format: 15` 时 1.21.11 会提示"为未知版本的 Minecraft 打造"——1.21.9 起要求
`min_format`/`max_format`，且 `supported_formats` 的上界不能超过 64。

## 包清单

| 目录 | 装上去会发生什么 | 对应验收 |
|---|---|---|
| `valid-new/` | 循环里多出 `heartpack:warm`（名字来自 `heartpack.animation.warm`），双人可播 | N1 |
| `override-normal/` | 内置 `letscuddle:normal` 被同路径覆盖：id 不变、`content_version` 变 2、锁定 160 tick | N2 / N11 |
| `broken-json/` | 该 manifest 被拒绝，日志有原因；其它动作照常 | N4 |
| `missing-sender/` | manifest 能加载、会出现在循环里，但播放时找不到动画 → 只警告、不崩 | N4 / N10 |
| `bad-id/` | `sender` 不是合法 id → 整个 manifest 被拒绝 | N4 / N8 |

`valid-new` 与 `override-normal` 都同时放了 `player_animation/`（1.20.1）和
`player_animations/`（1.21.11），所以同一份包在两个平台都能用。

## 没放进来的场景

- **删除包**（N3）：直接移出 `resourcepacks/` 再 F3+T 即可，不需要单独的包。
- **超量/超大**（N8）：手工复制 `valid-new` 的 manifest 到 257 个不同路径即可造出来，不必提交进仓库。
- **热重载中正在播放**（N5）：开始一段贴贴，然后 F3+T，动作应播完、结束后再切动作不受影响。

## 期望日志

```
LetsCuddle: loaded N animations
LetsCuddle: rejected animation manifest brokenpack:bad: invalid JSON: ...
LetsCuddle: manifest brokenpack:badid: 'sender' is not a valid id
```
