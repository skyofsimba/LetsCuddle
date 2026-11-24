# IDEA 配置（两个平台各一个 Gradle 工程）

> 面向：在本仓库里用 IntelliJ IDEA 看代码 / 构建 / 跑 `runClient` 的人。
> 结论先说：**一个 IDEA 窗口 + 两个「链接的 Gradle 项目」**，各自用自己的 Gradle 版本和 JDK。
> 配套：[架构说明](architecture.md) · [README 构建说明](../README.md)

## 1. 为什么直接打开仓库根目录什么都没有

1. 仓库根**没有** `settings.gradle` / `build.gradle`，它本身不是一个 Gradle 工程。
2. 旧的根 `.idea` 是 1.x 时代的「纯 Java 模块」：`LetsCuddle.iml` 把整个仓库当成一个 content root，
   **没有任何 Gradle 链接**。模块目录后来改名成 `forge-1.20.1/`，旧配置更是对不上。
3. 于是 IDEA 打开根目录时只是一个普通文件夹：没有 MC 依赖、没有源码根、没有 `runClient`。

## 2. 为什么不能加一个根 `settings.gradle` 把两个都包含进来

- 用 `includeBuild`（复合构建）时，**所有子构建都由根的那一个 Gradle 版本执行**，Gradle 不支持给每个子构建指定版本。
- `forge-1.20.1` 用 ForgeGradle 6，必须 **Gradle 8.8 + JDK 17**；
  `fabric-1.21.11` 用 Loom 1.17，必须 **Gradle 9.5+，且跑 Gradle 的 JVM 是 21**。
- 两个要求互相排斥，所以只能是两个独立构建；IDEA 用「Link Gradle Project」把它们挂到同一个窗口。

## 3. 仓库里已经准备好了什么

根目录的 `.idea/gradle.xml`（**本地文件，已被 `.gitignore` 忽略，不进 git**）已经链接两个构建：

| Gradle 项目 | 路径 | Gradle JVM | Gradle 版本 |
|---|---|---|---|
| `letscuddle-forge-1.20.1` | `forge-1.20.1/` | 17 | 8.8（工程自带 wrapper） |
| `letscuddle-fabric-1.21.11` | `fabric-1.21.11/` | 21 | 9.8（工程自带 wrapper） |

分发方式为各自的 wrapper，所以 Gradle 版本天然不同；唯一需要人工确认的是 **Gradle JVM**。

## 4. 打开步骤

1. **先关掉当前 IDEA 窗口**（否则退出时会把旧布局写回去，覆盖下面这些配置）。
2. `File → Open`，选择**仓库根** `D:\data\DSH\LetsCuddlePort\LetsCuddle`，信任项目。
3. 等右下角两次 Gradle sync 完成。Gradle 工具窗里会出现两个项目节点，各自带 `Tasks`。
4. 首次 sync 会下载 MC/依赖（forge 约 1–3 分钟，fabric 视网络 3–10 分钟），属正常。

如果两个项目没有自动出现，手动链接：

1. 打开 Gradle 工具窗 → 左上角 `+`（**Link Gradle Project**）→ 选 `forge-1.20.1/build.gradle`。
2. 再点 `+` → 选 `fabric-1.21.11/build.gradle`。
3. 分别设置 Gradle JVM（见 §5）。

## 5. 设置 Gradle JVM（最容易踩的坑）

`Settings → Build, Execution, Deployment → Build Tools → Gradle`，在左侧选中对应项目：

| 项目 | Gradle JVM |
|---|---|
| `forge-1.20.1` | 17（`C:\Program Files\Java\jdk-17.0.19`） |
| `fabric-1.21.11` | 21（`C:\Program Files\Java\jdk-21.0.12.1`） |

- 本机 IDEA 的 JDK 名称是 `17` / `21` / `25`（见 `jdk.table.xml`），所以 `.idea/gradle.xml` 里直接写的 `17` / `21`。
- 换了机器/JDK 名称不同时，IDEA 会提示 "Gradle JVM is not valid"，下拉重选即可。
- **不要**给两个项目都选 17：fabric 的 Loom 需要 JVM 21，会报
  `Dependency requires at least JVM runtime version 21`。

## 6. 构建与运行

两个项目各自独立，命令与 IDEA 里都一样：

| 目的 | forge-1.20.1 | fabric-1.21.11 |
|---|---|---|
| 构建 | `gradlew build` | `gradlew build` |
| 测试 | `gradlew test`（JDK 17 即可；编译用 17 工具链） | — |
| 客户端 | `runClient` | `runClient` |
| 服务端 | `runServer`（首次改 `run/eula.txt`） | `runServer` |

IDEA 里在 Gradle 工具窗对应项目 → `Tasks → forge/loom`（或直接双击）→ `runClient`。

## 7. 常见问题

| 现象 | 处理 |
|---|---|
| `Dependency requires at least JVM runtime version 21` | fabric 的 Gradle JVM 没设成 21 |
| 提示 ForgeGradle 需要 Gradle 8.x | forge 项目被设成自定义 Gradle 版本了，改回 wrapper |
| 类全是红的、没有 MC 依赖 | 该模块没被 Gradle 导入：Gradle 工具窗里应该有两个项目节点 |
| 没有 `runClient` 任务 | 没 sync 完，或展开错了项目节点 |
| 同名类跳转混乱 | 两个模块都叫 `xyz.skyofsimba.Letscuddle`（跨平台同构是故意的）；跳转后看编辑器的模块标签，或在 Go to Class 里选模块 |
| 想彻底重来 | 关 IDEA → 删除仓库根的 `.idea` → 重新 Open，并按 §4 重新 Link |
| 换了 JDK 路径 | 先改 `.idea/gradle.xml` 或按 §5 下拉重选 |

## 8. 备选：两个窗口

如果同名类干扰太大，也可以分别 Open `forge-1.20.1` 和 `fabric-1.21.11` 两个窗口（各自 `.idea` 独立）。
代价是要对照两端代码时切窗口；好处是「跳转」永远指向同一个平台。

## 9. 为什么不把 `.idea` 提交进仓库

`.idea/` 与 `*.iml` 都在 `.gitignore` 里：IDE 版本、JDK 名称、window 布局因机器而异，
提交上去只会给其他协作者制造冲突。仓库里能提交的是**这份说明**，配置本身每人一次即可。
