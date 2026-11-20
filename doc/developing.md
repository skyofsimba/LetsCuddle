# 开发环境、构建与排错

> **文档定位**：接手这个仓库后，照着本文就能把项目跑起来、改起来。
> 行为规格见 [`behavior-spec.md`](behavior-spec.md)，代码结构见 [`architecture.md`](architecture.md)。
> 本文与 `LetsCuddle/README.md` 有少量重复：那篇是"打开项目先看什么"的速查，本文是完整版。

---

## 1. 环境要求

| 项 | 要求 | 说明 |
|---|---|---|
| JDK | **17** | 工程用 toolchain 固定 17；IDEA 里 Project SDK 与 Gradle JVM 都选 17 |
| Gradle | 用仓库自带的 wrapper（8.8） | **不要**用系统 Gradle，ForgeGradle 6 对版本敏感 |
| 网络 | 能访问 minecraftforge / maven.kosmx.dev / mojang | 首次构建要下载 Forge userdev、反编译 MC、下载资源 |
| 磁盘 | 预留 3–5 GB | Gradle 缓存 + MC 资源 + 构建产物 |
| IDE | IntelliJ IDEA（推荐） | 仓库不含 `.idea/`，导入后自动生成 |

> Windows 上所有命令用 `gradlew.bat`；Linux/macOS 用 `./gradlew`。
> `gradle.properties` 里 `org.gradle.daemon=false`：每次构建会起一个一次性 daemon，这是模板设定。

---

## 2. 第一次跑起来

```powershell
git clone <仓库地址>
cd LetsCuddle
.\gradlew.bat build          # 首次 10–30 分钟：下载 Forge userdev + 反编译 MC
```

IDEA 导入：

1. `File → Open` 选择 **`LetsCuddle/build.gradle`**（不是仓库根目录），选 "Open as Project"；
2. 等 Gradle sync 完成；改动 `build.gradle` / `gradle.properties` 后点大象图标 **Reload All Gradle Projects**；
3. Project SDK 设为 17（`File → Project Structure → Project`），Gradle JVM 也设为 17（`Settings → Build Tools → Gradle`）。

运行入口（任选其一）：

- Gradle 工具窗口 → `letscuddle → Tasks → forgegradle runs` → 双击 `runClient` / `runClient2` / `runServer`；
- 或终端直接跑 `.\gradlew.bat runClient`。

---

## 3. 运行任务

| 任务 | 工作目录 | 用途 |
|---|---|---|
| `runClient` | `LetsCuddle/run/` | 开发客户端 |
| `runClient2` | `LetsCuddle/run2/` | 第二个开发客户端，用户名 `Dev2`，专门用来双人测试 |
| `runServer` | `LetsCuddle/run/` | 开发服务端（`--nogui`） |
| `runData` | `LetsCuddle/run-data/` | 数据生成；本模组没有 datagen provider，一般不用 |

**服务端首次启动**：`run/eula.txt` 默认不存在或为 `eula=false`，改成 `eula=true` 再启动。
联机调试建议在 `run/server.properties` 里设 `online-mode=false`（仓库里这份是 dev 配置，属于 `run/`，不进版本库）。

> 注意 `runClient` 与 `runServer` 共用 `LetsCuddle/run/`：同时跑会互相覆盖 `logs/latest.log`。
> 双人测试优先用「`runClient` + `runClient2` 开局域网」，这样两边日志分开。

---

## 4. 双人测试

需要「两名玩家 + 最好一个旁观者」。两条路径：

**A. 两个开发客户端（推荐）**

1. 同时启动 `runClient` 与 `runClient2`（两个窗口，工作目录分别是 `run/`、`run2/`）；
2. 在其中一个里「对局域网开放」（建议开作弊，方便 `/gamemode creative`）；
3. 另一个「多人游戏 → 扫描局域网」加入。

**B. 服务端 + 两个客户端**

1. 先 `.\gradlew.bat runServer`（`eula=true`、`online-mode=false`）；
2. 两个客户端连 `localhost`。

**拿券**：创造模式在「工具与实用物品」里拿；服务端测试也可让新玩家首登自动获得 16 张。
**验收**：按 [`behavior-spec.md`](behavior-spec.md) §10 的 A/B/C/D/E 逐条打勾。

---

## 5. 构建产物与自检

```powershell
.\gradlew.bat clean build
# 产物: build/libs/letscuddle-<版本>.jar  （已 reobf，可直接放进 mods/）
```

发布前的自检（三分钟）：

```powershell
# 1) jar 里该有的东西都在
jar tf build\libs\letscuddle-1.5.1.jar | findstr /I "mods.toml accesstransformer player_animation textures lang recipes"

# 2) 元数据展开正确（版本、依赖、displayURL）
#    直接解压 META-INF/mods.toml 检查 ${...} 是否都已替换

# 3) dev 版与发布版的区别：发布 jar 的类引用是 SRG 名，dev 的是官方名
#    拿 dev jar 发布会在正式游戏里报 NoSuchMethodError
```

> `build/libs` 里只应有一个不带 `-sources` 的 jar；`jar` 任务已 `finalizedBy reobfJar`。

---

## 6. 排错表

| 症状 | 原因 | 处理 |
|---|---|---|
| `Invalid access transformer line` | AT 语法错误：方法名与 `(` 之间有空格，或用了官方名 | 只能写 `public net.minecraft.client.Camera m_90581_(Lnet/minecraft/world/phys/Vec3;)V` |
| 加了 AT 但编译仍报 `setPosition 是 protected` | 同上（官方名静默不生效） | 换成 SRG 名；改完先 `clean` 一次 |
| `Could not delete output file ... binpatched.jar` | 上次构建残留被文件锁占用 | 删 `LetsCuddle/build/fg_cache` 再构建；必要时关掉占用进程 |
| 客户端崩溃：`MixinApplyError ... PlayerEntityMixin ... m_8119_()V` | 删掉了 MixinGradle（它给 run 任务注入依赖 refmap 重映射参数） | 恢复 `buildscript` + `apply plugin: 'org.spongepowered.mixin'`，**不要**删 |
| `Could not find dev.kosmx.player-anim:...` | 缺 kosmx maven 或版本号写错 | 检查 `repositories` 的 `maven.kosmx.dev` 与 `player_animation_version` |
| 连接时红叉 / `Incompatible mod set` | 双端协议版本或模组版本不同 | 两端用同一个 jar（协议 `PROTOCOL` 见 `HugNetwork`） |
| 专用服务端报缺 `playeranimator` | `mods.toml` 里该依赖的 `side` 被改成 BOTH | 保持 `side="CLIENT"` |
| 动作不播放 | 动作 id / JSON `name` / 资源未打进 jar | 看客户端日志的 `LetsCuddle:` warn；确认 `assets/letscuddle/player_animation` 与 `name` 字段 |
| 两人不贴合（空约 1 格） | 渲染里的 body 5 行被删或顺序被改 | 对照 [`architecture.md`](architecture.md) §6.3 |
| 拥抱时镜头仍偏移 | AT 没生效 / 事件里提前 return | 确认 `Camera#setPosition` 在 AT 后是 public；`camera.isDetached()` 为真 |
| 中文注释乱码 | 编译缺 UTF-8 | `tasks.withType(JavaCompile) { options.encoding = 'UTF-8' }` 已配置，别删 |
| 构建卡住无输出 | 首次构建在下载/反编译 | 给 10–30 分钟，用 `--info` 看进度 |
| `gradlew` 无法执行（Linux/macOS） | Windows 提交没有可执行位 | `chmod +x gradlew`（CI 里已处理） |

---

## 7. 目录结构

```
HugMe-1.20.1-Port/            ← 仓库根
├── README.md                 用户向（中英双语）
├── CHANGELOG.md              版本历史
├── doc/                      设计与规格文档（见下）
└── LetsCuddle/               工程本体（IDEA 打开这一层）
    ├── build.gradle          FG6 + kosmx maven + client2 运行配置 + AT
    ├── gradle.properties     版本矩阵与 mod 元信息（改版本号在这里）
    ├── settings.gradle
    ├── gradlew / gradlew.bat / gradle/wrapper/
    └── src/main/
        ├── java/xyz/skyofsimba/…        9 个类（见 architecture §3）
        └── resources/
            ├── META-INF/mods.toml       元数据（${} 由 processResources 展开）
            ├── META-INF/accesstransformer.cfg
            ├── assets/letscuddle/{lang,models,textures,player_animation}
            ├── data/letscuddle/recipes/
            ├── hug_ticket.png           jar 根的 logo
            └── pack.mcmeta
```

`doc/` 下的文档：

| 文件 | 用途 |
|---|---|
| `architecture.md` | 当前实现架构（**改代码先读**） |
| `behavior-spec.md` | 当前行为规格 + 验收清单（**发版前跑**） |
| `developing.md` | 本文 |
| `original-design.md` | 1.21 原版逆向规格（上游参考，勿当验收依据） |
| `port-plan.md` | v1.3 迁移方案（历史） |

---

## 8. 开发约定

1. **协议**：改 `HugRenderPayload` 字段或语义 → `HugNetwork.PROTOCOL` +1 → 更新 `architecture.md` §5 与 `behavior-spec.md` §5；
2. **AT**：只增不改；新增行必须是 SRG 名；改完 `clean build` 验证；
3. **动作数据**：4 份 JSON 字节级保持，`name`/`stopTick`/`returnTick`/`version` 不动（见 `behavior-spec.md` §8）；
4. **客户端类隔离**：任何只有客户端能加载的类，只能从 `DistExecutor` 包裹的 lambda 里引用；
5. **文档同步**：改了行为改 `behavior-spec.md`；改了结构改 `architecture.md`；每版在 `CHANGELOG.md` 记一条；
6. **提交**：`feat|fix|docs|chore(scope): 中文摘要`，正文写清动机与验证方式；
7. **版本号**：只改 `gradle.properties` 的 `mod_version`（产物名随之变化），并在 `CHANGELOG.md` 顶部加一节。

---

## 9. 调试技巧

- **日志**：`Letscuddle.LOGGER` 的输出在 `run/logs/latest.log`；客户端收包时会打 `Hug start: A <-> B anim=...`（debug 级）。
  控制台级别在 `build.gradle` 的 `runs.configureEach` 里（`forge.logging.console.level`）。
- **只想测客户端表现、不想双开**：临时把 `HugRenderPayload` 的开始包逻辑做成命令？不建议改代码；
  更快的方式是 `runClient` + `runClient2`，两个窗口放在两个显示器上。
- **测动作缺失兜底（K19）**：临时把某个 `player_animation/*.json` 改名 → 发起拥抱 → 客户端应只 warn 不崩，然后改回来。
- **测协议不匹配**：一端用旧 jar（改动 `PROTOCOL` 后构建两份）→ 连接应直接红叉而不是进游戏后出错。
- **看渲染矩阵**：`RenderPlayerEventHandler.onRenderPlayer` 里的注释与 `architecture.md` §6.2 一一对应；改之前先读那节。
- **Inspect 服务端是否真的没加载客户端类**：专用服务端启动后搜日志中的 `NoClassDefFoundError`，并在发布包上跑一次真实服务端（不带 playeranimator）。

---

## 10. 发布前检查清单

- [ ] `gradle.properties` 的 `mod_version` 与 `CHANGELOG.md` 顶部一致
- [ ] `.\gradlew.bat clean build` 通过，`build/libs` 只有一个 jar
- [ ] jar 内 `mods.toml` 的版本、依赖、`displayURL` 正确
- [ ] 按 `behavior-spec.md` §10 的 A/B/C/D/E 跑过一轮（至少 A 与 D）
- [ ] 专用服务端（不装 playeranimator）能启动
- [ ] 打 tag（`v1.5.1`）并写 Release 说明；Modrinth 上传时把 playeranimator 声明为 Required 依赖
