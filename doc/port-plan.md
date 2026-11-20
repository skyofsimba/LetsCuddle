# HugMe 1.20.1 Forge 迁移实施方案（port-plan）

> **配套文档**：`doc/original-design.md`（原模组的设计与行为规格）。本文只写「怎么搬」，行为语义、动作数据、常量来源一律以那份文档为准，不重复解释。
> **目标读者**：执行迁移的 AI/工程师。读完本文应能独立产出可编译、可双人联机验证的 Forge 1.20.1 版本。
> **本文假设**：原仓库 `D:\data\DSH\HugMe`（NeoForge 1.21，HEAD `e5fd1b3`）可读；工作目录是移植目标 `D:\data\DSH\HugMe-1.20.1-Port`。

---

## 0. 决策：先迁移，不重新设计

### 0.1 结论

**先做 1:1 语义迁移，把「反正要重写的平台胶水层」里的缺陷顺手修掉，其余缺陷按清单显式接受或延后。**

具体分成四档（完整裁决见第 8 章）：

| 档 | 数量 | 处置 |
|---|---|---|
| **PORT-FIX** | 9 项 | 这次迁移必须修（都在你本来就要重写的代码里，边际成本≈0） |
| **KEEP** | 8 项 | 明确**保持原行为**，写进验收，不做「顺手改进」 |
| **DEFER** | 3 项 | 记录在案，基线跑通后再单独做 |
| **P-\*** | 5 项 | 迁移过程本身的风险（库版本、API 代际等），见 8.4 |

### 0.2 为什么不是「先重新设计」

1. **迁移要重写的是胶水层，不是业务层。** 注册、事件、网络、渲染入口这四类代码必须换；而请求状态机、落点公式、120 tick 锁定、动作编排、渲染矩阵这些**决定手感**的东西与平台无关，可以逐行搬。重新设计等于把已经验证过的部分重新摸索一遍。
2. **两个未知量不能同时动。** 如果一边换平台一边改玩法，一旦「拥抱位置不对」，你无法判断是平台差异、还是新设计的问题。1:1 基线先跑通 `original-design.md` 的 C1–C17，就得到一个**已知正确**的参照点；之后任何改动都能二分定位。
3. **缺陷分两类，成本完全不同。** K1/K2（跨线程调度）、K9（掉线不解锁）、K15（服务端加载客户端类）、K19（空动作 NPE）、K5（除零）都落在**你正在重写的文件**里，修它们只是换个写法，不修反而要在新平台上再抄一遍坏代码。而 K4（落点方向）、K7（模型高度）、K14（无保护/无权限）是**玩法取舍**，要产品决策，且都能独立验证——放到基线之后。
4. **动作数据的正确性依赖原实现。** 四份 JSON 的 `torso→body` 语义、61 tick 与 120 tick 锁定的对齐、`SpeedModifier(0.5)` 的位置，都是靠原实现反推出来的结论（见原文档 7.8.4 / 9.4）。重设计会把这些锚点全部作废。

### 0.3 一句话版本

> 先把「原模组在 1.20.1 上跑起来并且画面一致」，再谈「它应该是什么样」。这次迁移的产出是**等价基线**，不是最终形态。

### 0.4 什么情况下应该推翻这个决定

出现下列任一情况，停下来重新评估，不要硬迁：

- 1.20.1 的 playerAnimator 无法复现 `body` 根节点位移（7.8.4 的贴合机制失效），且无法用替代方案补偿 → 动作层需要重新设计；
- 目标平台没有 1.20.1 可用的 playerAnimator（例如必须支持 Forge 以外的加载器）→ 动画方案整体重做；
- 需求方明确要求「拥抱时不能被攻击/不能被传送打断」这类状态级语义 → 那是新功能，应先设计再实现。

---

## 1. 目标与完成定义（DoD）

一次迁移算完成，需要同时满足：

1. `gradlew.bat build` 产出 `build/libs/hugme-1.3.jar`，无编译错误、无 `reobf` 失败；
2. 客户端 + 服务端都能加载，日志无 `NoClassDefFoundError` / `NoSuchMethodError` / 缺依赖报错；
3. `original-design.md` 第 13 章的 **C1–C17 全部通过**（双人联机，含旁观者）；
4. 本文第 8 章标记为 PORT-FIX 的缺陷**逐项验证已修**；
5. 标记为 KEEP 的缺陷**行为与原版一致**（不是「碰巧没触发」）；
6. 动作文件四份、贴图、语言文件与源工程**字节一致**（动作与贴图可用哈希校验）。

---

## 2. 目标环境

### 2.1 版本矩阵（照 `mc_scaffold` 钉选值，不要手写）

| 项 | 值 |
|---|---|
| Minecraft | `1.20.1` |
| Forge | `1.20.1-47.3.0` |
| ForgeGradle | `6.0.54`（`plugins { id 'net.minecraftforge.gradle' version '6.0.54' }`） |
| Gradle wrapper | `8.8`（`gradle-8.8-bin.zip`） |
| JDK | 17（`java.toolchain.languageVersion = 17`；`settings.gradle` 的 foojay 0.7.0 可自动装） |
| 映射 | `official` / `1.20.1` |
| 元数据 | `META-INF/mods.toml`（`modLoader="javafml"`、`mandatory=true`）+ `pack.mcmeta`（`pack_format: 15`） |
| 动作库 | `dev.kosmx.player-anim:player-animation-lib-forge:1.0.2-rc1+1.20`（maven `https://maven.kosmx.dev/`） |
| 产出 jar | `hugme-1.3.jar` |

> 依赖坐标已核实：`1.0.2-rc1+1.20` 是 1.20 线上唯一产物（maven 目录 `dev/kosmx/player-anim/player-animation-lib-forge/1.0.2-rc1+1.20/`，2023-06-14 发布），Modrinth 标注 `game_versions: ["1.20","1.20.1"]`。**不存在 `+1.20.1` 后缀的版本。**

### 2.2 与源工程的版本对照

| 项 | 源（NeoForge） | 目标（Forge） | 影响 |
|---|---|---|---|
| MC | 1.21 | 1.20.1 | 网络 API、`ResourceLocation` 构造、`TooltipContext`、`Model#renderToBuffer`、`recipe` 目录名 |
| 加载器 | NeoForge 21.0.114-beta | Forge 47.3.0 | `@Mod` 构造、事件总线常量（`GAME`→`FORGE`）、`DeferredRegister.Items`→`DeferredRegister<Item>` |
| 动作库 | player-animator 2.0.0+1.21.1 | 1.0.2-rc1+1.20 | 三处签名差异（见第 6 章） |
| 构建 | moddev 1.0.14 | FG 6.0.54 | `fg.deobf(...)` 包装 mod 依赖 |

### 2.3 平台差异总表（照这张表逐条改，别凭记忆）

| 源（NeoForge 1.21） | 目标（Forge 1.20.1） |
|---|---|
| `@Mod(MODID)` + `(IEventBus, ModContainer)` 构造 | `@Mod(MODID)` + **无参构造**，`FMLJavaModLoadingContext.get().getModEventBus()` |
| `@EventBusSubscriber(bus = Bus.GAME)` | `@Mod.EventBusSubscriber(modid = ..., bus = Bus.FORGE)` |
| `@EventBusSubscriber(bus = Bus.MOD, value = Dist.CLIENT)` | `@Mod.EventBusSubscriber(modid = ..., bus = Bus.MOD, value = Dist.CLIENT)` |
| `DeferredRegister.Items.createItems(MODID)` + `DeferredItem` | `DeferredRegister.create(ForgeRegistries.ITEMS, MODID)` + `RegistryObject<Item>` |
| `ResourceLocation.fromNamespaceAndPath(ns, p)` | `new ResourceLocation(ns, p)` |
| `CustomPacketPayload` + `StreamCodec` + `PayloadRegistrar` | `SimpleChannel` + `FriendlyByteBuf` 手写编解码（第 5.5 节） |
| `PacketDistributor.sendToPlayer(p, payload)` | `CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), msg)` |
| `IPayloadContext.enqueueWork` | `NetworkEvent.Context.enqueueWork` + `setPacketHandled(true)` |
| `ServerTickEvent.Post` | `TickEvent.ServerTickEvent` + `event.phase == TickEvent.Phase.END` |
| `ServerStartingEvent` 里手工注册命令 | **`RegisterCommandsEvent`**（更规范，行为等价） |
| `Item.TooltipContext` | `Level`（`appendHoverText(ItemStack, @Nullable Level, List, TooltipFlag)`） |
| `model.renderToBuffer(ps, vc, light, overlay, -1)` | 1.20.1 是**浮点颜色**重载（见 5.8 与附录 A.5） |
| `data/hugme/recipe/` + `"result":{"id":...}` | `data/hugme/recipes/` + `"result":{"item":...}` |
| `neoforge.mods.toml`，依赖 `neoforge` | `mods.toml`，依赖 `forge` |

---

## 3. 工程骨架（P0）

### 3.1 引导

工作目录 `D:\data\DSH\HugMe-1.20.1-Port` 目前只有 `doc/`。**不要**直接把这 8 个 Java 文件丢进空目录 —— 先拿到 wrapper。

推荐做法（有 `mc_scaffold` 时）：

```
mc_scaffold platform=forge targetDir=<workspace>/.bootstrap \
            name=hugme packageName=nya.tuyw.hugme minecraftVersion=1.20.1
```

然后把 `.bootstrap/` 下除 `.git` 外的**全部文件**移到项目根，删掉 `.bootstrap`。
（脚手架要求 targetDir 为空，而项目根已经有 `doc/`，所以必须中转一次。）

没有脚手架时：手写 3.2–3.5 的文本文件，wrapper 从任意 1.20.1 Forge MDK 复制 `gradlew`、`gradlew.bat`、`gradle/wrapper/gradle-wrapper.jar`、`gradle/wrapper/gradle-wrapper.properties`。

> **wrapper 是关键**：`gradle-8.8-bin.zip` + FG 6.0.54 是钉选组合，别升级 Gradle（FG6 对 Gradle 版本敏感），也别用系统 Gradle。

### 3.2 `build.gradle`（完整，含两处必须改的：kosmx maven 与 `fg.deobf`）

```groovy
plugins {
    id 'net.minecraftforge.gradle' version '6.0.54'
    id 'eclipse'
    id 'idea'
    id 'maven-publish'
}

version = mod_version
group = mod_group_id
archivesBaseName = mod_id

java.toolchain.languageVersion = JavaLanguageVersion.of(17)

minecraft {
    mappings channel: mapping_channel, version: mapping_version
    copyIdeResources = true
    runs {
        client { workingDirectory project.file('run') }
        server { workingDirectory project.file('run') }
        // 双人验证用（见 7.4）：第二个客户端走独立工作目录
        client2 {
            client()
            programArguments.addAll '--username', 'Dev2'
            workingDirectory project.file('run2')
        }
    }
}

repositories {
    mavenCentral()
    maven {
        name = 'KosmX'
        url = 'https://maven.kosmx.dev/'
        content { includeGroup 'dev.kosmx.player-anim' }
    }
}

dependencies {
    minecraft "net.minecraftforge:forge:${minecraft_version}-${forge_version}"
    // 关键：Forge 的 mod 依赖必须 fg.deobf 包一层，否则引用到的是 SRG 名，编译期找不到方法
    implementation fg.deobf("dev.kosmx.player-anim:player-animation-lib-forge:${player_animation_version}")
}

def replaceProperties = [
    minecraft_version      : minecraft_version,
    minecraft_version_range: minecraft_version_range,
    forge_version          : forge_version,
    forge_version_range    : forge_version_range,
    loader_version_range   : loader_version_range,
    mod_id                 : mod_id,
    mod_name               : mod_name,
    mod_license            : mod_license,
    mod_version            : mod_version,
    mod_authors            : mod_authors,
    mod_description        : mod_description,
]

processResources {
    inputs.properties replaceProperties
    filesMatching(['META-INF/mods.toml', 'pack.mcmeta']) {
        expand replaceProperties
    }
}

tasks.withType(JavaCompile).configureEach {
    options.encoding = 'UTF-8'      // 源码注释里有中文，必须
}

jar {
    manifest {
        attributes([
            'Specification-Title'    : mod_id,
            'Specification-Version'  : '1',
            'Implementation-Title'   : mod_name,
            'Implementation-Version' : project.version,
        ])
    }
    finalizedBy 'reobfJar'
}
```

### 3.3 `gradle.properties`

```properties
org.gradle.jvmargs=-Xmx3G
org.gradle.daemon=false

minecraft_version=1.20.1
forge_version=47.3.0
minecraft_version_range=[1.20.1,1.21)
forge_version_range=[47,)
loader_version_range=[47,)
mapping_channel=official
mapping_version=1.20.1

mod_id=hugme
mod_name=Hug Me!
mod_license=MIT
mod_version=1.3
mod_group_id=nya.tuyw.hugme
mod_authors=TuYw
mod_description=Hug other players!

player_animation_version=1.0.2-rc1+1.20
```

### 3.4 `settings.gradle`

```groovy
pluginManagement {
    repositories {
        gradlePluginPortal()
        maven { url = 'https://maven.minecraftforge.net/' }
    }
}

plugins {
    id 'org.gradle.toolchains.foojay-resolver-convention' version '0.7.0'
}

rootProject.name = "hugme"
```

### 3.5 `mods.toml` 与 `pack.mcmeta`

`src/main/resources/META-INF/mods.toml`：

```toml
modLoader="javafml"
loaderVersion="${loader_version_range}"
license="${mod_license}"

[[mods]]
modId="${mod_id}"
version="${mod_version}"
displayName="${mod_name}"
authors=["TuYw"]
description='''${mod_description}'''
logoFile="hug_ticket.png"

[[dependencies.${mod_id}]]
modId="forge"
versionRange="${forge_version_range}"
mandatory=true
ordering="NONE"
side="BOTH"

[[dependencies.${mod_id}]]
modId="minecraft"
versionRange="${minecraft_version_range}"
mandatory=true
ordering="NONE"
side="BOTH"

[[dependencies.${mod_id}]]
modId="playeranimator"
versionRange="[1.0.2-rc1+1.20,)"
mandatory=true
ordering="NONE"
side="CLIENT"
```

要点：
- `modId="playeranimator"` 是 playerAnimator 的 **modid**（Forge 侧），不是 maven ArtifactId；Fabric 侧它叫 `player-animator`，别写错。
- `side="CLIENT"` 表示专用服务端不强制安装它 —— 这是原设计的既有语义（原文档 4.4）。
- `authors=["TuYw"]` 是数组；1.20.1 的 mods.toml 认这个写法。

`src/main/resources/pack.mcmeta`：

```json
{
  "pack": {
    "description": "${mod_name} resources",
    "pack_format": 15
  }
}
```

### 3.6 目标目录树

```
HugMe-1.20.1-Port/
├── build.gradle / gradle.properties / settings.gradle
├── gradlew / gradlew.bat / gradle/wrapper/*
├── doc/{original-design.md, port-plan.md}
└── src/main/
    ├── java/nya/tuyw/hugme/
    │   ├── HugMe.java
    │   ├── animation/{AnimationManager,HugAnimationEnum}.java
    │   ├── command/HugCommandHandler.java
    │   ├── events/RenderPlayerEventHandler.java
    │   ├── item/HugTicketItem.java
    │   └── network/{HugNetwork,HugRenderPayload,HugRenderClientHandler}.java
    └── resources/
        ├── META-INF/mods.toml
        ├── pack.mcmeta
        ├── hug_ticket.png                       # logoFile，与物品贴图同一份字节
        ├── assets/hugme/lang/{zh_cn,en_us}.json
        ├── assets/hugme/models/item/hug_ticket.json
        ├── assets/hugme/textures/item/hug_ticket.png
        ├── assets/hugme/player_animation/*.json  # 4 份，逐字节复制
        └── data/hugme/recipes/hug_ticket.json    # 注意 recipes 复数
```

> **命名空间必须是 `hugme`**：动作注册 id 是 `<资源命名空间>:<文件内 name>`，改成别的命名空间会让 `hugme:hug_normal_sender` 取不到（原文档 9.2）。

### 3.7 P0 检查点：构建（+ 可选的 API 复核）

第一次 `gradlew.bat build` 会下载 Forge userdev 并反编译 MC，**10–30 分钟**，没有输出不代表卡死。

本文用到的 Forge API（网络经典 SimpleImpl、`TickEvent`、`BuildCreativeModeTabContentsEvent`、`RenderPlayerEvent`、`DeferredRegister`）**均已按 Forge 47.3 的源码核实**（附录 A），正常情况下一次编译就能过。

只有在**换了 Forge 版本**（不是 47.3.0）时，才需要复核网络 API 代际。方法（30 秒）：

```bat
:: 首次构建后，在 Gradle 缓存里定位 universal jar
javap -classpath <...>\forge-1.20.1-47.3.0-universal.jar net.minecraftforge.network.NetworkRegistry
javap -classpath <...>\forge-1.20.1-47.3.0-universal.jar net.minecraftforge.network.simple.SimpleChannel
```

看有没有 `newSimpleChannel` / `registerMessage`；没有就是换了新 API，按附录 B 处理。

---

## 4. 资源迁移（P1）

逐文件处置。**动作数据、贴图、语言文件是字节级复制，不要重画、不要重排版。**

| 源文件（相对 `src/main/resources`） | 目标路径 | 处置 |
|---|---|---|
| `assets/hugme/player_animation/normal_s.json` | 同路径 | **原样复制** |
| `assets/hugme/player_animation/normal_r.json` | 同路径 | **原样复制** |
| `assets/hugme/player_animation/touch_s.json` | 同路径 | **原样复制** |
| `assets/hugme/player_animation/touch_r.json` | 同路径 | **原样复制** |
| `assets/hugme/models/item/hug_ticket.json` | 同路径 | **原样复制**（`item/generated` 两版本通吃） |
| `assets/hugme/textures/item/hug_ticket.png` | 同路径 | **原样复制**（64×64 RGBA，SHA-256 `25089C58…36EC`） |
| `assets/hugme/lang/zh_cn.json` | 同路径 | **原样复制**（20 键） |
| `assets/hugme/lang/en_us.json` | 同路径 | **原样复制** |
| `hug_ticket.png`（resources 根） | 同路径 | **原样复制**（`logoFile` 用） |
| `data/hugme/recipe/hug_ticket.json` | `data/hugme/recipes/hug_ticket.json` | **改字段**（见下） |
| `META-INF/neoforge.mods.toml` | `META-INF/mods.toml` | **重写**（3.5） |

1.20.1 的配方（目录 `recipes` 复数、`result.item`）：

```json
{
  "type": "minecraft:crafting_shaped",
  "category": "misc",
  "pattern": ["PBP", "PIP", "PBP"],
  "key": {
    "P": { "item": "minecraft:paper" },
    "B": { "item": "minecraft:light_blue_dye" },
    "I": { "item": "minecraft:pink_dye" }
  },
  "result": { "item": "hugme:hug_ticket", "count": 4 }
}
```

**四份动作 JSON 的注意事项（违反任一，画面就不对）**：

1. 不要动 `"name"` 字段（`hug_normal_sender` 等 4 个 id 是代码里写死的）；
2. **不要**给文件加 `"version"` 字段 —— 缺省(=1) 时解析器把 `torso` 重命名为 `body`（整模根节点），这是「前冲贴进怀里」的实现基础；加了 `"version": 3` 贴合会消失（原文档 K6）；
3. `"degrees": false` 必须保留（旋转是弧度）；
4. `stopTick=61`、`returnTick=2` 不要改（与 120 tick 锁定对齐）。

校验命令（复制完成后跑一次）：

```powershell
$src='D:\data\DSH\HugMe\src\main\resources'
$dst='D:\data\DSH\HugMe-1.20.1-Port\src\main\resources'
foreach($f in @('assets\hugme\player_animation\normal_s.json','assets\hugme\player_animation\normal_r.json',
                'assets\hugme\player_animation\touch_s.json','assets\hugme\player_animation\touch_r.json',
                'assets\hugme\models\item\hug_ticket.json','assets\hugme\textures\item\hug_ticket.png',
                'assets\hugme\lang\zh_cn.json','assets\hugme\lang\en_us.json','hug_ticket.png')){
  $a=(Get-FileHash (Join-Path $src $f) -Algorithm SHA256).Hash
  $b=(Get-FileHash (Join-Path $dst $f) -Algorithm SHA256).Hash
  "{0}  {1}" -f ($(if($a -eq $b){'OK  '}else{'DIFF'}), $f)
}
```

---

## 5. 代码实现

> 下面给的是**可落盘**的完整代码。与源工程的差异只有平台 API 和第 8 章的 PORT-FIX，业务逻辑逐行对应。
> 包名、类名、字段名尽量与源工程一致，方便对照 `original-design.md` 的附录 A。

### 5.1 `HugMe.java`

```java
package nya.tuyw.hugme;

import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import net.minecraftforge.server.ServerLifecycleHooks;
import nya.tuyw.hugme.command.HugCommandHandler;
import nya.tuyw.hugme.item.HugTicketItem;
import nya.tuyw.hugme.network.HugNetwork;
import org.slf4j.Logger;

import java.io.File;

@Mod(HugMe.MODID)
public class HugMe {

    public static final String MODID = "hugme";
    public static final Logger LOGGER = LogUtils.getLogger();

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, MODID);

    public static final RegistryObject<Item> HUG_TICKET = ITEMS.register("hug_ticket",
            () -> new HugTicketItem(new Item.Properties().stacksTo(64)));

    public HugMe() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        ITEMS.register(modBus);
        modBus.addListener(this::addCreative);
        MinecraftForge.EVENT_BUS.register(this);
    }

    private void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(HUG_TICKET);   // RegistryObject 是 Supplier<Item>，可直接传
        }
    }

    /** 原实现在 ServerStartingEvent 里手工注册；这里改用官方事件，行为等价且更稳。 */
    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        HugCommandHandler.register(event.getDispatcher());
    }

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;

        File dataFile = new File(server.getWorldPath(LevelResource.PLAYER_DATA_DIR).toFile(),
                player.getUUID() + ".dat");
        if (dataFile.exists()) return;                 // 本存档不是首次登录

        ItemStack stack = new ItemStack(HUG_TICKET.get(), 16);
        if (!player.getInventory().add(stack)) {
            player.drop(stack, false);
        }
    }
}
```

> `event.accept(HUG_TICKET)` 依赖 `RegistryObject implements Supplier`。若编译器不认，改成 `event.accept(HUG_TICKET::get)`（同样满足 `Supplier<? extends ItemLike>`）。

### 5.2 `HugTicketItem.java`

```java
package nya.tuyw.hugme.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import nya.tuyw.hugme.command.HugCommandHandler;

import javax.annotation.Nullable;
import java.util.List;

public class HugTicketItem extends Item {

    public HugTicketItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult interactLivingEntity(ItemStack stack, Player player, LivingEntity target, InteractionHand hand) {
        if (!(target instanceof Player targetPlayer)) return InteractionResult.PASS;

        if (!player.level().isClientSide && player instanceof ServerPlayer serverPlayer
                && targetPlayer instanceof ServerPlayer serverTarget) {
            if (HugCommandHandler.startHugRequest(serverPlayer, serverTarget)) {
                sendHugRequest(player, targetPlayer);
                player.sendSystemMessage(Component
                        .translatable("hugme.message.sendsuccess", targetPlayer.getName().getString())
                        .withStyle(ChatFormatting.GREEN));
                stack.shrink(1);
            }
        }
        return InteractionResult.sidedSuccess(player.level().isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.hugme.hug_ticket.desc").withStyle(ChatFormatting.GRAY));
    }

    private void sendHugRequest(Player sender, Player target) {
        String senderName = sender.getName().getString();
        Component message = Component
                .translatable("hugme.message.sendrequest", sender.getName())
                .withStyle(ChatFormatting.BLUE)
                .append(Component.translatable("hugme.message.acceptrequest").withStyle(style -> style
                        .withColor(ChatFormatting.GREEN)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                                "/hugme acceptRequest " + senderName))))
                .append(Component.translatable("hugme.message.rejectrequest").withStyle(style -> style
                        .withColor(ChatFormatting.RED)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                                "/hugme rejectRequest " + senderName))));
        target.sendSystemMessage(message);
    }
}
```

### 5.3 `HugCommandHandler.java`

这是唯一改动较大的类：去掉 `ScheduledExecutorService`，改成 **tick 计时的过期队列**（PORT-FIX K1/K2），并补上掉线清理（K9）与除零保护（K5）。

```java
package nya.tuyw.hugme.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.datafixers.util.Pair;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;
import nya.tuyw.hugme.HugMe;
import nya.tuyw.hugme.animation.HugAnimationEnum;
import nya.tuyw.hugme.network.HugNetwork;
import nya.tuyw.hugme.network.HugRenderPayload;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Mod.EventBusSubscriber(modid = HugMe.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class HugCommandHandler {

    private static final long REQUEST_TIMEOUT_TICKS = 20L * 60L;   // 60 秒
    private static final int ANIM_TICKS = 20 * 6;                  // 拥抱锁定 6 秒
    private static final double MAX_DISTANCE = 5.0D;
    private static final double NEARBY_RADIUS = 64.0D;
    private static final double STAND_OFFSET = 1.3D;
    private static final double STAND_HALF_WIDTH = 0.3D;
    private static final Random RANDOM = new Random();

    private static final Map<Pair<UUID, UUID>, HugRequest> REQUESTS = new ConcurrentHashMap<>();
    private static final Map<Pair<UUID, UUID>, HugStatus> HUGS = new ConcurrentHashMap<>();

    private HugCommandHandler() {}

    // ---------------------------------------------------------------- 状态对象

    public record HugRequest(UUID senderId, UUID receiverId, long expireAt) {}

    private static final class HugStatus {
        final UUID senderId;
        final UUID receiverId;
        final Vec3 senderPos;
        final Vec3 receiverPos;
        final List<ServerPlayer> nearbyPlayers;
        int ticksRemaining;

        HugStatus(UUID senderId, UUID receiverId, Vec3 senderPos, Vec3 receiverPos,
                  int ticksRemaining, List<ServerPlayer> nearbyPlayers) {
            this.senderId = senderId;
            this.receiverId = receiverId;
            this.senderPos = senderPos;
            this.receiverPos = receiverPos;
            this.ticksRemaining = ticksRemaining;
            this.nearbyPlayers = nearbyPlayers;
        }
    }

    // ---------------------------------------------------------------- 命令

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("hugme")
                .then(Commands.literal("acceptRequest")
                        .then(Commands.argument("sender", StringArgumentType.string())
                                .executes(ctx -> {
                                    ServerPlayer sender = ctx.getSource().getServer().getPlayerList()
                                            .getPlayerByName(StringArgumentType.getString(ctx, "sender"));
                                    ServerPlayer receiver = ctx.getSource().getPlayerOrException();
                                    if (sender == null) {
                                        ctx.getSource().sendFailure(Component
                                                .translatable("hugme.message.sendernotfound")
                                                .withStyle(ChatFormatting.RED));
                                        return 0;
                                    }
                                    handleHugAcceptance(sender, receiver);
                                    return 1;
                                })))
                .then(Commands.literal("rejectRequest")
                        .then(Commands.argument("sender", StringArgumentType.string())
                                .executes(ctx -> {
                                    ServerPlayer sender = ctx.getSource().getServer().getPlayerList()
                                            .getPlayerByName(StringArgumentType.getString(ctx, "sender"));
                                    ServerPlayer receiver = ctx.getSource().getPlayerOrException();
                                    if (sender == null) {
                                        ctx.getSource().sendFailure(Component
                                                .translatable("hugme.message.sendernotfound")
                                                .withStyle(ChatFormatting.RED));
                                        return 0;
                                    }
                                    handleHugRejection(sender, receiver);
                                    return 1;
                                }))));
    }

    // ---------------------------------------------------------------- 发起 / 接受 / 拒绝

    public static boolean startHugRequest(ServerPlayer sender, ServerPlayer receiver) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return false;

        Pair<UUID, UUID> key = key(sender, receiver);
        if (REQUESTS.containsKey(key)) {
            sender.sendSystemMessage(Component.translatable("hugme.message.have_request")
                    .withStyle(ChatFormatting.RED));
            return false;
        }
        REQUESTS.put(key, new HugRequest(sender.getUUID(), receiver.getUUID(),
                server.getTickCount() + REQUEST_TIMEOUT_TICKS));
        return true;
    }

    private static void handleHugAcceptance(ServerPlayer sender, ServerPlayer receiver) {
        Pair<UUID, UUID> key = key(sender, receiver);
        HugRequest request = REQUESTS.get(key);

        if (request == null || !request.receiverId().equals(receiver.getUUID())) {   // PORT-FIX K13
            receiver.sendSystemMessage(red("hugme.message.invalid_request"));
            return;
        }
        if (HUGS.containsKey(key)) {
            sender.sendSystemMessage(red2("hugme.message.hug_in_progress_sender", receiver));
            receiver.sendSystemMessage(red2("hugme.message.hug_in_progress_receiver", sender));
            return;
        }
        if (sender.level() != receiver.level()) {
            receiver.sendSystemMessage(red2("hugme.message.invalid_level", sender));
            return;
        }
        if (receiver.distanceTo(sender) > MAX_DISTANCE) {
            receiver.sendSystemMessage(red2("hugme.message.too_far", sender));
            return;
        }

        Vec3 stand = computeStandPosition(sender, receiver);
        if (stand == null || !isPositionSafe(stand, sender.level())) {         // PORT-FIX K5
            receiver.sendSystemMessage(red2("hugme.message.unsafe_position", sender));
            return;
        }

        // PORT-FIX K3：先校验、后播「已接受」，避免「接受了但站不住」的双消息
        sender.sendSystemMessage(green2("hugme.message.sender_accepted", receiver));
        receiver.sendSystemMessage(green2("hugme.message.receiver_accepted", sender));

        receiver.teleportTo(stand.x, stand.y, stand.z);
        receiver.lookAt(EntityAnchorArgument.Anchor.EYES,
                new Vec3(sender.getX(), sender.getEyeY(), sender.getZ()));

        broadcastHugStart(sender, receiver);
        REQUESTS.remove(key);
    }

    private static void handleHugRejection(ServerPlayer sender, ServerPlayer receiver) {
        Pair<UUID, UUID> key = key(sender, receiver);
        HugRequest request = REQUESTS.get(key);

        if (request != null && request.receiverId().equals(receiver.getUUID())) {
            sender.sendSystemMessage(red2("hugme.message.sender_rejected", receiver));
            receiver.sendSystemMessage(red2("hugme.message.receiver_rejected", sender));
            REQUESTS.remove(key);
        } else {
            receiver.sendSystemMessage(red("hugme.message.invalid_request"));
        }
    }

    // ---------------------------------------------------------------- 落点与安全检查

    /**
     * 原式：R' = C + 1.3 * normalise(R - C)，即把接受者摆到**发起者另一侧** 1.3 格处。
     * 语义按原实现保留（KEEP K4）；XZ 完全重合时返回 null（PORT-FIX K5）。
     */
    private static Vec3 computeStandPosition(ServerPlayer sender, ServerPlayer receiver) {
        Vec3 delta = sender.position().subtract(receiver.position());
        double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        if (horizontal < 1.0E-4D) return null;

        double offsetX = delta.x / horizontal * STAND_OFFSET;
        double offsetZ = delta.z / horizontal * STAND_OFFSET;
        return new Vec3(sender.getX() + offsetX, sender.getY(), sender.getZ() + offsetZ);
    }

    private static boolean isPositionSafe(Vec3 pos, Level level) {
        return canStand(pos, level) && canPass(pos, level);
    }

    private static boolean canStand(Vec3 pos, Level level) {
        AABB box = new AABB(
                pos.x - STAND_HALF_WIDTH, pos.y - 0.1D, pos.z - STAND_HALF_WIDTH,
                pos.x + STAND_HALF_WIDTH, pos.y, pos.z + STAND_HALF_WIDTH);
        return level.getBlockCollisions(null, box).iterator().hasNext();
    }

    private static boolean canPass(Vec3 pos, Level level) {
        AABB box = new AABB(
                pos.x - STAND_HALF_WIDTH, pos.y + 0.1D, pos.z - STAND_HALF_WIDTH,
                pos.x + STAND_HALF_WIDTH, pos.y + 1.7D, pos.z + STAND_HALF_WIDTH);
        return !level.getBlockCollisions(null, box).iterator().hasNext();
    }

    // ---------------------------------------------------------------- 广播与状态

    private static void broadcastHugStart(ServerPlayer sender, ServerPlayer receiver) {
        List<ServerPlayer> nearby = getNearbyPlayers(sender);
        HUGS.put(key(sender, receiver), new HugStatus(
                sender.getUUID(), receiver.getUUID(),
                sender.position(), receiver.position(),
                ANIM_TICKS, nearby));

        // 关键：整批只抽一次，所有接收者拿到同一个动画号（原仓库 93f9487 的教训）
        int animation = RANDOM.nextInt(HugAnimationEnum.values().length);
        HugRenderPayload payload = new HugRenderPayload(
                sender.getStringUUID(), receiver.getStringUUID(), false, animation);
        for (ServerPlayer player : nearby) {
            HugNetwork.sendTo(player, payload);
        }
    }

    private static void broadcastHugEnd(HugStatus status) {
        HugRenderPayload payload = new HugRenderPayload(
                status.senderId.toString(), status.receiverId.toString(), true, 0);
        for (ServerPlayer player : status.nearbyPlayers) {
            if (player == null || player.hasDisconnected()) continue;
            HugNetwork.sendTo(player, payload);
        }
    }

    private static List<ServerPlayer> getNearbyPlayers(ServerPlayer sender) {
        List<ServerPlayer> players = new ArrayList<>();
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return players;

        Level level = sender.level();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.level() != level) continue;
            if (player.distanceTo(sender) < NEARBY_RADIUS) players.add(player);
        }
        return players;
    }

    // ---------------------------------------------------------------- tick

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;      // 只在 tick 末做一次
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;

        expireRequests(server);
        tickHugs(server);
    }

    private static void expireRequests(MinecraftServer server) {
        if (REQUESTS.isEmpty()) return;
        long now = server.getTickCount();
        Iterator<Map.Entry<Pair<UUID, UUID>, HugRequest>> it = REQUESTS.entrySet().iterator();
        while (it.hasNext()) {
            HugRequest request = it.next().getValue();
            if (now < request.expireAt()) continue;
            it.remove();

            ServerPlayer sender = server.getPlayerList().getPlayer(request.senderId());
            ServerPlayer receiver = server.getPlayerList().getPlayer(request.receiverId());
            if (sender == null || receiver == null) continue;      // 有人不在线就不打扰
            sender.sendSystemMessage(red2("hugme.message.request_expired", receiver));
            receiver.sendSystemMessage(red2("hugme.message.request_expired", sender));
        }
    }

    private static void tickHugs(MinecraftServer server) {
        if (HUGS.isEmpty()) return;
        Iterator<Map.Entry<Pair<UUID, UUID>, HugStatus>> it = HUGS.entrySet().iterator();
        while (it.hasNext()) {
            HugStatus status = it.next().getValue();
            ServerPlayer sender = server.getPlayerList().getPlayer(status.senderId);
            ServerPlayer receiver = server.getPlayerList().getPlayer(status.receiverId);

            if (sender == null || receiver == null) {          // PORT-FIX K9
                it.remove();
                broadcastHugEnd(status);                        // 让旁观者解锁
                continue;
            }

            sender.teleportTo(status.senderPos.x, status.senderPos.y, status.senderPos.z);
            receiver.teleportTo(status.receiverPos.x, status.receiverPos.y, status.receiverPos.z);

            if (--status.ticksRemaining <= 0) {
                it.remove();
                broadcastHugEnd(status);
            }
        }
    }

    /** PORT-FIX K9：掉线立刻清理，不等到 tick 循环发现。 */
    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        UUID id = player.getUUID();

        Iterator<Map.Entry<Pair<UUID, UUID>, HugRequest>> requests = REQUESTS.entrySet().iterator();
        while (requests.hasNext()) {
            Pair<UUID, UUID> key = requests.next().getKey();
            if (key.getFirst().equals(id) || key.getSecond().equals(id)) requests.remove();
        }

        Iterator<Map.Entry<Pair<UUID, UUID>, HugStatus>> hugs = HUGS.entrySet().iterator();
        while (hugs.hasNext()) {
            HugStatus status = hugs.next().getValue();
            if (!status.senderId.equals(id) && !status.receiverId.equals(id)) continue;
            hugs.remove();
            broadcastHugEnd(status);
        }
    }

    // ---------------------------------------------------------------- 小工具

    private static Pair<UUID, UUID> key(ServerPlayer sender, ServerPlayer receiver) {
        return new Pair<>(sender.getUUID(), receiver.getUUID());
    }

    private static Component red(String key) {
        return Component.translatable(key).withStyle(ChatFormatting.RED);
    }

    private static Component green2(String key, ServerPlayer other) {
        return Component.translatable(key, other.getName().getString()).withStyle(ChatFormatting.GREEN);
    }

    private static Component red2(String key, ServerPlayer other) {
        return Component.translatable(key, other.getName().getString()).withStyle(ChatFormatting.RED);
    }
}
```

> 注意 `Component.translatable(key, String)`：原实现有的地方传 `getName()`（`Component`）、有的传 `getName().getString()`。两种都能编译（`Object...` 参数），**文本结果相同**，统一传 `String` 即可。

### 5.4 `HugAnimationEnum.java`

```java
package nya.tuyw.hugme.animation;

public enum HugAnimationEnum {
    NORMALHUG,
    TOUCHHEADHUG;
}
```

> 顺序即线上 `int` 值（0 / 1）。**不要重排**（KEEP K18）。

### 5.5 网络：`HugNetwork.java` + `HugRenderPayload.java`

`HugNetwork.java`（新增文件，承载 `SimpleChannel`）：

```java
package nya.tuyw.hugme.network;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import nya.tuyw.hugme.HugMe;

@Mod.EventBusSubscriber(modid = HugMe.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class HugNetwork {

    private static final String PROTOCOL = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(HugMe.MODID, "main"),
            () -> PROTOCOL,
            PROTOCOL::equals,
            PROTOCOL::equals);

    private HugNetwork() {}

    @SubscribeEvent
    public static void commonSetup(FMLCommonSetupEvent event) {
        int id = 0;
        CHANNEL.registerMessage(id++, HugRenderPayload.class,
                HugRenderPayload::encode,
                HugRenderPayload::decode,
                HugRenderPayload::handle);
    }

    public static void sendTo(ServerPlayer player, HugRenderPayload message) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), message);
    }
}
```

`HugRenderPayload.java`：

```java
package nya.tuyw.hugme.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 线上字段顺序固定：sender(String) -> receiver(String) -> done(bool) -> animation(VarInt)。
 * 与源工程 1.21 的 STREAM_CODEC 顺序逐字段一致。
 */
public record HugRenderPayload(String sender, String receiver, boolean done, int animation) {

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(sender);
        buf.writeUtf(receiver);
        buf.writeBoolean(done);
        buf.writeVarInt(animation);
    }

    public static HugRenderPayload decode(FriendlyByteBuf buf) {
        return new HugRenderPayload(buf.readUtf(), buf.readUtf(), buf.readBoolean(), buf.readVarInt());
    }

    public static void handle(HugRenderPayload message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() ->
                // PORT-FIX K15：包类在双端都会加载，客户端处理类只允许在物理客户端加载
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                        () -> () -> HugRenderClientHandler.handle(message)));
        context.setPacketHandled(true);
    }
}
```

> **已经在 Forge 47.3 的源码上核实**：`NetworkRegistry.newSimpleChannel(...)` 与 `SimpleChannel#registerMessage(...)` 在这个版本都存在（见附录 A.9）。**注意包名是 `net.minecraftforge.network.simple.SimpleChannel`**，不是 `net.minecraftforge.network.SimpleChannel`（后者只在 1.20.6+ 的 Forge 上出现）。
> 换 Forge 版本时才需要复核，方法见 3.7。

### 5.6 `HugRenderClientHandler.java`

```java
package nya.tuyw.hugme.network;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.player.Player;
import nya.tuyw.hugme.HugMe;
import nya.tuyw.hugme.animation.AnimationManager;
import nya.tuyw.hugme.animation.HugAnimationEnum;
import nya.tuyw.hugme.events.RenderPlayerEventHandler;

import java.util.UUID;

/** 仅物理客户端：由 HugRenderPayload.handle 经 DistExecutor 调入。 */
public final class HugRenderClientHandler {

    private HugRenderClientHandler() {}

    public static void handle(HugRenderPayload payload) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) return;

        Player sender = client.level.getPlayerByUUID(UUID.fromString(payload.sender()));
        Player receiver = client.level.getPlayerByUUID(UUID.fromString(payload.receiver()));
        if (!(sender instanceof AbstractClientPlayer from) || !(receiver instanceof AbstractClientPlayer to)) return;

        try {
            if (!payload.done()) {
                RenderPlayerEventHandler.lockPlayers(from, to);
                HugAnimationEnum[] values = HugAnimationEnum.values();
                int index = payload.animation();
                if (index < 0 || index >= values.length) index = 0;          // PORT-FIX K18 兜底
                HugMe.LOGGER.debug("Hug start: {} <-> {} anim={}", from.getName().getString(),
                        to.getName().getString(), values[index]);
                AnimationManager.playHugAnimation(from, to, values[index]);
            } else {
                RenderPlayerEventHandler.unlockPlayers(from, to);
            }
        } catch (Exception e) {
            // 原实现在这里断开连接；1.20.1 上没必要，记日志即可
            HugMe.LOGGER.error("HugMe: failed to handle hug render payload", e);
        }
    }
}
```

### 5.7 `AnimationManager.java`

```java
package nya.tuyw.hugme.animation;

import dev.kosmx.playerAnim.api.firstPerson.FirstPersonConfiguration;
import dev.kosmx.playerAnim.api.firstPerson.FirstPersonMode;
import dev.kosmx.playerAnim.api.layered.IAnimation;
import dev.kosmx.playerAnim.api.layered.KeyframeAnimationPlayer;
import dev.kosmx.playerAnim.api.layered.ModifierLayer;
import dev.kosmx.playerAnim.api.layered.modifier.SpeedModifier;
import dev.kosmx.playerAnim.core.data.KeyframeAnimation;
import dev.kosmx.playerAnim.minecraftApi.PlayerAnimationAccess;
import dev.kosmx.playerAnim.minecraftApi.PlayerAnimationFactory;
import dev.kosmx.playerAnim.minecraftApi.PlayerAnimationRegistry;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import nya.tuyw.hugme.HugMe;

@Mod.EventBusSubscriber(modid = HugMe.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class AnimationManager {

    /** 自定义动画层 id，必须与播放时取层的 id 一致。 */
    public static final ResourceLocation LAYER_ID = new ResourceLocation(HugMe.MODID, "animations");

    private AnimationManager() {}

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> PlayerAnimationFactory.ANIMATION_DATA_FACTORY.registerFactory(
                LAYER_ID, 42, player -> {
                    ModifierLayer<IAnimation> layer = new ModifierLayer<>();
                    layer.addModifierBefore(new SpeedModifier(0.5F));   // 全局 50% 速度
                    return layer;
                }));
    }

    @SuppressWarnings("unchecked")
    public static void playHugAnimation(AbstractClientPlayer sender, AbstractClientPlayer receiver,
                                        HugAnimationEnum kind) {
        ModifierLayer<IAnimation> senderLayer = (ModifierLayer<IAnimation>)
                PlayerAnimationAccess.getPlayerAssociatedData(sender).get(LAYER_ID);
        ModifierLayer<IAnimation> receiverLayer = (ModifierLayer<IAnimation>)
                PlayerAnimationAccess.getPlayerAssociatedData(receiver).get(LAYER_ID);
        if (senderLayer == null || receiverLayer == null) {
            HugMe.LOGGER.warn("HugMe: animation layer {} missing", LAYER_ID);
            return;
        }

        KeyframeAnimation senderAnim = lookup(kind, true);
        KeyframeAnimation receiverAnim = lookup(kind, false);
        if (senderAnim == null || receiverAnim == null) {         // PORT-FIX K19
            HugMe.LOGGER.warn("HugMe: animation data missing for {}", kind);
            return;
        }

        senderLayer.setAnimation(newPlayer(senderAnim));
        receiverLayer.setAnimation(newPlayer(receiverAnim));
    }

    /** 1.20 线的 getAnimation 直接返回 KeyframeAnimation（可为 null），无需强转。 */
    private static KeyframeAnimation lookup(HugAnimationEnum kind, boolean sender) {
        String path = switch (kind) {
            case TOUCHHEADHUG -> sender ? "hug_touch_sender" : "hug_touch_receiver";
            default -> sender ? "hug_normal_sender" : "hug_normal_receiver";
        };
        return PlayerAnimationRegistry.getAnimation(new ResourceLocation(HugMe.MODID, path));
    }

    private static KeyframeAnimationPlayer newPlayer(KeyframeAnimation animation) {
        return new KeyframeAnimationPlayer(animation)
                .setFirstPersonMode(FirstPersonMode.THIRD_PERSON_MODEL)
                .setFirstPersonConfiguration(new FirstPersonConfiguration()
                        .setShowLeftArm(true).setShowLeftItem(false)
                        .setShowRightArm(true).setShowRightItem(false));
    }
}
```

### 5.8 `RenderPlayerEventHandler.java`

移植时**只改平台 API，公式一个符号都别动**（原文档 7.8.3 有完整推导）。

```java
package nya.tuyw.hugme.events;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.kosmx.playerAnim.api.TransformType;
import dev.kosmx.playerAnim.core.util.Vec3f;
import dev.kosmx.playerAnim.impl.IAnimatedPlayer;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderPlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import nya.tuyw.hugme.HugMe;
import org.joml.Quaternionf;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Mod.EventBusSubscriber(modid = HugMe.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RenderPlayerEventHandler {

    /** 兜底：服务端结束包丢失时，客户端最多锁这么久（tick）。 */
    private static final long LOCK_TIMEOUT_TICKS = 200L;

    private static final Map<UUID, Lock> LOCKS = new HashMap<>();

    private RenderPlayerEventHandler() {}

    private record Lock(UUID partner, boolean hideHead, long expireAt) {}

    @SubscribeEvent
    public static void onRenderPlayer(RenderPlayerEvent.Pre event) {
        if (!(event.getEntity() instanceof AbstractClientPlayer player)) return;
        Lock lock = LOCKS.get(player.getUUID());
        if (lock == null) return;

        Minecraft client = Minecraft.getInstance();
        if (client.level == null) {
            LOCKS.remove(player.getUUID());
            return;
        }
        if (client.level.getGameTime() > lock.expireAt()) {        // PORT-FIX K9 客户端兜底
            LOCKS.remove(player.getUUID());
            return;
        }
        Player partnerEntity = client.level.getPlayerByUUID(lock.partner());
        if (!(partnerEntity instanceof AbstractClientPlayer partner)) return;

        PoseStack poseStack = event.getPoseStack();
        float targetYaw = calculateYawToTarget(player, partner);

        poseStack.pushPose();
        // 注意顺序：PoseStack 是后乘，最后调用的最先作用到顶点（原文档 7.8.3）
        poseStack.mulPose(Axis.XP.rotationDegrees(180.0F));
        poseStack.translate(0.0D, -1.5D, 0.0D);
        poseStack.mulPose(new Quaternionf().rotationY((float) Math.toRadians(targetYaw)));

        var animation = ((IAnimatedPlayer) player).playerAnimator_getAnimation();
        animation.setTickDelta(event.getPartialTick());
        if (animation.isActive()) {
            Vec3f position = animation.get3DTransform("body", TransformType.POSITION, Vec3f.ZERO);
            poseStack.translate(position.getX(), position.getY() + 0.7D, position.getZ());
            Vec3f rotation = animation.get3DTransform("body", TransformType.ROTATION, Vec3f.ZERO);
            poseStack.mulPose(Axis.ZP.rotation(rotation.getZ()));
            poseStack.mulPose(Axis.YP.rotation(rotation.getY()));
            poseStack.mulPose(Axis.XP.rotation(rotation.getX()));
            poseStack.translate(0.0D, -0.7D, 0.0D);
        }

        renderPlayerModel(event.getRenderer(), player, poseStack,
                event.getMultiBufferSource(), event.getPackedLight(), lock.hideHead());

        poseStack.popPose();
        event.setCanceled(true);
    }

    private static void renderPlayerModel(PlayerRenderer renderer, AbstractClientPlayer entity, PoseStack poseStack,
                                          MultiBufferSource buffer, int packedLight, boolean hideHead) {
        PlayerModel<AbstractClientPlayer> model = renderer.getModel();
        model.young = entity.isBaby();
        model.head.visible = Minecraft.getInstance().options.getCameraType() != CameraType.FIRST_PERSON
                || !hideHead
                || !(entity instanceof LocalPlayer);

        model.setupAnim(entity, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F);   // 四肢摆动清零 → 冻结姿势
        VertexConsumer consumer = buffer.getBuffer(
                RenderType.entityCutoutNoCull(renderer.getTextureLocation(entity)));

        // PlayerModel 继承自 Model 的 renderToBuffer：1.20.1 是 8 参浮点颜色（1.20.2+ 才是单个 int）
        model.renderToBuffer(poseStack, consumer, packedLight, OverlayTexture.NO_OVERLAY,
                1.0F, 1.0F, 1.0F, 1.0F);
    }

    private static float calculateYawToTarget(AbstractClientPlayer from, AbstractClientPlayer to) {
        double deltaX = to.getX() - from.getX();
        double deltaZ = to.getZ() - from.getZ();
        return (float) Math.toDegrees(Math.atan2(deltaZ, deltaX)) - 90.0F;
    }

    public static void lockPlayers(AbstractClientPlayer sender, AbstractClientPlayer receiver) {
        long until = (Minecraft.getInstance().level != null
                ? Minecraft.getInstance().level.getGameTime() : 0L) + LOCK_TIMEOUT_TICKS;
        LOCKS.put(sender.getUUID(), new Lock(receiver.getUUID(), true, until));
        LOCKS.put(receiver.getUUID(), new Lock(sender.getUUID(), false, until));
    }

    public static void unlockPlayers(AbstractClientPlayer sender, AbstractClientPlayer receiver) {
        LOCKS.remove(sender.getUUID());
        LOCKS.remove(receiver.getUUID());
    }
}
```

未使用的 `import net.minecraft.world.entity.Entity;` 可删（保留会有 warning，不影响编译）。

---

## 6. playerAnimator 1.20.1 接入

### 6.1 坐标与依赖

- maven：`https://maven.kosmx.dev/`，group `dev.kosmx.player-anim`；
- 坐标：`player-animation-lib-forge:1.0.2-rc1+1.20`；
- `build.gradle` 里必须 `fg.deobf(...)`；
- `mods.toml` 依赖 `modId="playeranimator"`、`side="CLIENT"`。

### 6.2 与 2.0.0（源工程用的版本）的三处签名差异

| API | 2.0.0（源） | 1.0.2-rc1+1.20（目标） | 迁移动作 |
|---|---|---|---|
| `PlayerAnimationRegistry.getAnimation(id)` | 返回类型被放宽，需 `(KeyframeAnimation)` 强转 | 直接返回 `@Nullable KeyframeAnimation` | **去掉强转** |
| `PlayerAnimationFactory.ANIMATION_DATA_FACTORY.registerFactory(...)` | 第三参 `Function<Player, IAnimation>` 风格的 SAM | 第三参是 SAM 接口 `PlayerAnimationFactory`，入参 `AbstractClientPlayer` | lambda 写法不变；`instanceof` 判断可省 |
| `setTickDelta(float)` / `get3DTransform(part, type, Vec3f)` | 都在 `playerAnimator_getAnimation()` 的返回对象上 | 同左（applier/processor 上） | 不变 |

**如果 `get3DTransform` 三参重载在 1.0.x 上不存在**，用四参版本 `get3DTransform("body", TransformType.POSITION, tickDelta, Vec3f.ZERO)`（`KeyframeAnimationPlayer` 上是这个签名），并把 `tickDelta` 从 `event.getPartialTick()` 传进去。编译报错时优先按这条改。

### 6.3 必须保持的三个参数

1. 层 id `hugme:animations`，优先级 `42`；
2. `SpeedModifier(0.5F)` —— 去掉它动作会比 6 秒锁定快一倍结束；
3. 动作 id 四个：`hugme:hug_normal_sender` / `hug_normal_receiver` / `hug_touch_sender` / `hug_touch_receiver`。

### 6.4 `body` 通道为什么必须自己补

取消 `RenderPlayerEvent.Pre` 会跳过整条 `PlayerRenderer.render`，其中包含 playerAnimator 自己在 `setupRotations` 阶段做的 **body 根节点变换**（`translate(x, y+0.7, z)` → Z/Y/X 旋转 → `translate(0,-0.7,0)`）。`hug_normal_receiver` 的 `torso.z = -1.0` 正是靠它把纸片人推进对方怀里。所以 5.8 里那 5 行**不能删、顺序不能改**（原文档 7.8.4④）。

一个容易忽略的点：`PlayerRenderer#render` 在触发 `Pre` 之前已经执行过 `setModelProperties(player)`（只改 `model.crouching`/`slim` 之类的标志，不碰 `PoseStack`）。所以事件里拿到的 `PoseStack` 仍然只有实体平移，5.8 的三步变换可以直接用。

---

## 7. 实施顺序与检查点

### 7.1 P0 骨架（0.5–1 h，首次构建 10–30 min）

1. 3.1 引导 → 3.2–3.5 文件；
2. `gradlew.bat build`（空模组应通过）；
3. **网络 API 探针**（3.7）→ 决定 5.5 用哪套写法；
4. 检查点：`build/libs/hugme-1.3.jar` 存在，`runClient` 能进主界面且日志无报错。

### 7.2 P1 物品与资源（1 h）

1. 第 4 章的资源全部就位（含哈希校验）；
2. 5.1 + 5.2 两个类；
3. 检查点：创造栏「工具与实用物品」里能找到抱抱券、贴图正常、能合成 4 张、tooltip 有灰字。

### 7.3 P2 服务端状态机（2–3 h）

1. 5.3 + 5.4 + 5.5（先只保证「发得出去」，客户端 handler 可先只打日志）；
2. `runServer`（`run/eula.txt` 改 `eula=true`，`run/server.properties` 里 `online-mode=false`）；
3. 检查点：右键玩家 → 对方收到可点击消息 → 点「同意」→ 接受者被瞬移到 1.3 格、双方 6 秒不能动、服务端日志有发包；点「拒绝」/超时 60 秒各有对应消息。

### 7.4 P3 动作（1–2 h）

1. 5.7；
2. 检查点：同意后两人播放动作（第三人视角）；**接受者明显往对方怀里压约 1 格**（这是 `body` 生效的证据，见 6.4）。若没有贴合，优先查 `get3DTransform` 的调用与 `SpeedModifier` 是否生效。

### 7.5 P4 渲染接管（2–4 h，最容易卡）

1. 5.8；
2. 检查点：两人面对面、模型互相贴合；拥抱结束后恢复原版渲染（护甲/手持物回来）。

### 7.6 P5 收尾

1. 第 8 章逐项过一遍；
2. 跑第 9 章的全部验收项；
3. 出 `build/libs/hugme-1.3.jar`（**必须是 reobf 后的**，见 10.2）。

### 7.7 双人验证怎么做

- **两个开发客户端**：`build.gradle` 的 `client2` 运行配置（3.2 已给）用独立 `run2/` 目录和 `--username Dev2`；两个终端分别 `gradlew.bat runClient` 与 `gradlew.bat runClient2`。
- **一个服务端 + 两个客户端**：先 `gradlew.bat runServer`，两个客户端都连 `localhost`；服务端 `online-mode=false`。
- 若 `client2` 因 ForgeGradle 的 run 配置推导报错，把 `client` 块复制一份改名并显式调用 `client()` 即可。
- 至少要有一个**旁观者**（第三个客户端或同屏第三人称），否则 C7 的「64 格广播」项无法验证。

---

## 8. 缺陷裁决表

编号沿用 `original-design.md` 第 11 章（K1–K20）。

### 8.1 PORT-FIX：本次迁移必须修（9 项）

| # | 缺陷 | 迁移时的修法 | 验证 |
|---|---|---|---|
| K1 | 60 秒超时回调在调度线程上访问 `ServerPlayer` | 删掉 `ScheduledExecutorService`，`HugRequest` 带 `expireAt`，在 `ServerTickEvent` 里扫（5.3） | 超时提示正常，日志无跨线程异常 |
| K2 | 非守护线程池永不关闭、强引用玩家 | 同上，线程池整个消失 | 关服无残留线程（`jstack`/关服日志） |
| K3 | 「已接受」消息在站位校验之前发 | 把两条消息移到校验与瞬移之间（5.3） | 站不住时**只**收到「无法站立」 |
| K5 | 两人 XZ 重合时除零 → NaN | `computeStandPosition` 返回 `null`，走「无法站立」分支 | 用 `/tp` 把两人叠在一起后接受，不崩 |
| K9 | 拥抱中掉线：状态清了但客户端仍锁定 | ① `PlayerLoggedOutEvent` 立刻清理并广播结束包；② 客户端 `Lock.expireAt` 兜底 200 tick（5.3、5.8） | 拥抱中踢掉一人，另一人 10 秒内恢复原版渲染 |
| K10 | `model.head.visible` 写共享模型实例不恢复 | 每次渲染都显式赋值（原实现已如此），并在解锁后再渲染一帧确认可见 | 拥抱结束后第一人称头部正常 |
| K11 | `HugStatus.active` 恒真、`endHug` 只在双方在线时删状态 | 删掉 `active` 字段，用迭代器 `remove()` + 统一 `broadcastHugEnd` | 结束/掉线两条路径都从表里移除 |
| K15 | 服务端注册包时引用客户端类 | 处理器里用 `DistExecutor.unsafeRunWhenOn(Dist.CLIENT, ...)` 包一层（5.5） | 专用服务端（不装 playeranimator）能启动、能发包 |
| K19 | 动作缺失时 `new KeyframeAnimationPlayer(null)` NPE | `lookup()` 返回 null 时打日志并放弃播放（5.7） | 临时删掉一个动作 JSON，客户端不崩、只警告 |

### 8.2 KEEP：必须保持原行为（8 项）

| # | 行为 | 为什么保持 |
|---|---|---|
| K4 | 接受者被摆到发起者**另一侧** 1.3 格 | 第三视角画面完全一样（纸片人由客户端摆正），改动只影响玩家自己的相机侧；保留原语义，避免迁移期引入「手感变化」 |
| K6 | 动作 JSON 的 `torso` 依赖 `version<3` 被当成 `body` | 数据级行为，改了贴合就没了 |
| K7 | 接管渲染的模型高 2.0 格（无 `0.9375` 缩放） | 与原版一致 < 6.7% 差异，属视觉特征 |
| K12 | 请求键是**有序** `Pair`，A→B 与 B→A 可并存 | 原行为，无玩家可感危害 |
| K16 | 发起失败仍返回 `sidedSuccess`（客户端播挥手） | 原行为，改动无收益 |
| K17 | 状态不持久化，重启即清空 | 社交模组可接受；持久化是新功能 |
| K18 | 枚举 `ordinal()` 直接上线 | 只要枚举不重排就安全；迁移期不引入新协议 |
| K20 | 首登发券用 `.dat` 存在性判定 | 原行为；换成持久化标记属新需求 |

### 8.3 DEFER：基线之后再单独做（3 项）

| # | 事项 | 为什么不放进迁移 |
|---|---|---|
| K14 | 无权限、无冷却、无游戏模式/无敌/摔落保护；`teleportTo` 钉人可能被传送拦截 | 需要产品决策（是否无敌？是否限创造模式？是否允许被推送打断？），且要新增配置系统 |
| K8 | body 变换的调用顺序陷阱 | 不是缺陷而是实现约束；已在 5.8 标注，按注释写即可 |
| — | 加配置、加音效、加第三种拥抱（`FLYHUG`） | 纯增量功能，基线稳定后做更快 |

### 8.4 新增迁移专属风险（P-*）

| # | 风险 | 处置 |
|---|---|---|
| P-PORT-1 | 网络 API 代际：47.3 的经典 SimpleImpl 已核实存在，但换 Forge 版本就会失效 | 钉死 47.3.0；换版本时按 3.7 用 `javap` 复核，按附录 B 处理 |
| P-PORT-2 | playerAnimator `1.0.2-rc1+1.20` 是 1.20.0 编译的，跑在 1.20.1 | Modrinth 标注支持 1.20.1；若运行期报 `NoSuchMethodError`，把 Forge 降到 47.1.x 或改用 1.20.1 上更常配的库版本 |
| P-PORT-3 | `Model#renderToBuffer` 的颜色参数 1.20.1 与 1.21 不同（8 参 float ↔ 5 参 int） | 已按 1.20.1 写死 8 参浮点版（A.5 已核实）；1.20.1 **不存在** int 颜色重载，别照抄 1.21 代码 |
| P-PORT-4 | `RegistryObject` 传给 `accept(Supplier<? extends ItemLike>)` 的兼容性 | 编译报错就改 `event.accept(HUG_TICKET::get)` |
| P-PORT-5 | 专用服务端加载客户端类 | 已由 K15 的 `DistExecutor` 规避；验收时**必须**真跑一次不装 playeranimator 的服务端 |

---

## 9. 验收清单

先跑 `original-design.md` 第 13 章的 **C1–C17**（原样适用），再跑下面这些迁移专属项。

| # | 检查项 | 期望 |
|---|---|---|
| M1 | `gradlew.bat build` | 成功，产物 `build/libs/hugme-1.3.jar` |
| M2 | 客户端加载 | 无 `NoClassDefFoundError`；`playeranimator` 缺失时报**依赖缺失**而不是崩溃 |
| M3 | 专用服务端（不装 playeranimator） | 能启动、能登录、能收请求、能瞬移；只是不播动作 |
| M4 | 资源完整性 | 4 份动作 + 贴图 + 2 份语言 + logo 与源工程 SHA-256 一致 |
| M5 | 动作贴合 | 接受者明显压进对方怀里约 1 格（`body` 生效） |
| M6 | 动作时长 | 动作在 6 秒锁定结束后才收尾（约慢 2 tick） |
| M7 | 跨维度 / 距离 / 站不住 | 三种拒绝路径消息正确，且**不会**先收到「已接受」 |
| M8 | 60 秒过期 | 双方各收到过期提示；**控制台无跨线程异常** |
| M9 | 掉线 | 拥抱中一方退出：另一方 10 秒内恢复原版渲染 |
| M10 | 重载资源（F3+T） | 动作注册表重建后仍能正常播放（playerAnimator 会在资源重载时重扫） |
| M11 | 与第三方模组共存 | 装一个其它用 playerAnimator 的表情/动作模组，互不干扰（自定义层隔离） |

---

## 10. 排错手册

### 10.0 开工前必读：9 个会直接编译失败的坑

按 Forge 47.3 / MC 1.20.1 的真实签名核对过，写错任一条就是 `cannot find symbol`：

| # | 错误写法 | 正确写法 |
|---|---|---|
| 1 | `import net.minecraftforge.network.SimpleChannel;` | `import net.minecraftforge.network.simple.SimpleChannel;`（`network.SimpleChannel` 是 1.20.6+ 的） |
| 2 | `someModelPart.renderToBuffer(...)` | `ModelPart` 上只有 `render(poseStack, consumer, light, overlay[, r,g,b,a])`；`renderToBuffer(...)` 在 `Model`/`PlayerModel` 上（本方案调的是后者） |
| 3 | `event.getEnvironment()`（`RegisterCommandsEvent`） | `event.getCommandSelection()` |
| 4 | `ServerTickEvent.Pre` / `ServerTickEvent.Post` | 47.3 只有 `TickEvent.ServerTickEvent`，用 `event.phase == TickEvent.Phase.END` 过滤 |
| 5 | `public HugMe(IEventBus bus)` | `@Mod` 类**必须无参构造**：`public HugMe()` + `FMLJavaModLoadingContext.get().getModEventBus()`（`FMLModContainer` 走 `getDeclaredConstructor().newInstance()`） |
| 6 | `DeferredRegister.Items` / `DeferredItem` / `registerItem(...)` | `DeferredRegister.create(ForgeRegistries.ITEMS, MODID)` + `register(String, Supplier<Item>)` + `RegistryObject<Item>` |
| 7 | `AbstractClientPlayer p = (AbstractClientPlayer) event.getEntity();` 后直接用 | `RenderPlayerEvent.Pre#getEntity()` 的静态类型是 `Player`，先 `instanceof` 判断 |
| 8 | `List<VoxelShape> shapes = level.getBlockCollisions(...)` | 返回 `Iterable<VoxelShape>`（本方案只用 `.iterator()`） |
| 9 | 把 `ctx.enqueueWork(...)` 当 void 用或赋给 void 变量 | 返回 `CompletableFuture<Void>`，语句式调用即可 |

### 10.1 构建期

| 症状 | 原因 | 处理 |
|---|---|---|
| `Could not find dev.kosmx.player-anim:...` | 缺 kosmx maven 或版本号写错 | 检查 3.2 的 `repositories`；确认版本是 `1.0.2-rc1+1.20`（`+` 在 Groovy 字符串里无需转义） |
| `package dev.kosmx.playerAnim... does not exist` | 依赖没 `fg.deobf` 或没进 `implementation` | 加上 `fg.deobf(...)` |
| `cannot find symbol: method newSimpleChannel` | 该 Forge 版本已换新网络 API | 见附录 B |
| `cannot find symbol: method renderToBuffer(...int)` | 用了 1.21 的 int 颜色重载 | 改 8 参浮点版（或 4 参） |
| `error: unmappable character` / 中文乱码 | 缺 `options.encoding='UTF-8'` | 3.2 已含，别删 |
| `Unsupported class file major version` | JDK 不是 17+ | 用 17；foojay 会自动装 toolchain |
| Gradle 卡在下载 | 首次构建 | 给 30 分钟；看 `--info` |

### 10.2 产物

- **只有 reobf 后的 jar 能发**：`jar.finalizedBy 'reobfJar'` 必须在；`build/libs` 里出现 `-sources` 之外的第二个 jar 时，以 `reobf` 那个为准（FG 会原地替换）。
- 如果客户端加载报 `NoSuchMethodError`/`NoSuchFieldError` 且方法名是 `m_xxxxx_`，说明拿的是 dev 版 jar。

### 10.3 运行期

| 症状 | 排查顺序 |
|---|---|
| 两人不面对面 | ① `LOCKS` 有没有写入（`lockPlayers` 是否被调到）② `RenderPlayerEvent.Pre` 是否真的在客户端 fires（`@EventBusSubscriber` 的 `bus` 是否 `FORGE`）③ 目标玩家是否在同一个 `client.level` |
| 模型位置整体偏移/飘在空中 | `poseStack` 的调用顺序被改了（必须 Rx → translate(-1.5) → Ry） |
| 两人不贴合（空 1 格） | `body` 变换没生效：`isActive()` 是否为 true、`get3DTransform` 是否返回 `z=-1.0`、`SpeedModifier` 是否注册成功 |
| 动作不播 | ① 动作 id 是否 `hugme:hug_normal_sender` 等 ② `assets/hugme/player_animation/` 是否打进 jar（`jar tf` 检查）③ 服务端发的 `animation` 是否越界 |
| 动作播了但姿势不对 | 动作 JSON 被动过（`version` 字段、`degrees`、`torso` 键名） |
| 拥抱期间玩家还能走动 | `TickEvent.ServerTickEvent` 的 `phase` 判断写反，或 `teleportTo` 被别的模组拦截 |
| 拥抱结束不恢复渲染 | 结束包没到（`nearbyPlayers` 快照里没有该玩家）或客户端兜底超时前就重登 |

---

## 11. 附录

### 附录 A：已核实签名与来源

> 标 ✅ 的已按原始来源核对；标 ⚠️ 的需要在 P0 用 `javap` 或编译结果确认（本文已给出兜底写法）。

| # | 项 | 结论 | 来源 |
|---|---|---|---|
| A.1 | `RenderPlayerEvent.Pre` | ✅ `@Cancelable`；`getEntity():Player`（**不是** `AbstractClientPlayer`）、`getRenderer():PlayerRenderer`、`getPoseStack():PoseStack`、`getMultiBufferSource():MultiBufferSource`、`getPackedLight():int`、`getPartialTick():float`。它由 `PlayerRenderer#render` 在 `setModelProperties(player)` 之后、`super.render(...)` 之前触发；取消即 `return`，既不渲染也不触发 `Post` —— 此时 `PoseStack` 只有实体平移，无旋转/缩放 | Forge tag `47.3` `RenderPlayerEvent.java` + `PlayerRenderer.java.patch` |
| A.2 | `EventBusSubscriber` | ✅ `@Mod.EventBusSubscriber(modid=…, bus=Bus.MOD\|Bus.FORGE, value=Dist.CLIENT)` | Forge 官方文档 + 脚手架模板 |
| A.3 | `TickEvent` / `ServerTickEvent` | ✅ 基类 `public final Type type; LogicalSide side; Phase phase;`；`ServerTickEvent` 另有 `getServer()`、`haveTime()`；47.3 **没有** `ServerTickEvent.Pre/Post` | Forge tag `47.3` `TickEvent.java` + 47.3.0 API 索引（javap） |
| A.4 | `BuildCreativeModeTabContentsEvent` | ✅ `getTabKey():ResourceKey<CreativeModeTab>`；`accept(Supplier<? extends ItemLike>)` | Forge 47.3.0 API 索引（javap） |
| A.5 | `Model#renderToBuffer`（`PlayerModel` 继承） | ✅ 1.20.1 是 8 参浮点颜色 `(PoseStack, VertexConsumer, int light, int overlay, float r, float g, float b, float a)`；5 参 `int color` 版本在 1.20.1 **不存在**（1.20.2+ 才有）。注意 `ModelPart` 上的方法叫 `render(...)`，**没有** `renderToBuffer` | mappings.dev 1.20.1 `Model` / `ModelPart` |
| A.6 | `PlayerEvent.PlayerLoggedInEvent` | ✅ 取玩家用 `getEntity()`（`PlayerEvent` 提供协变 `getEntity():Player`）；**没有** `getPlayer()` | Forge 47.3.0 API 索引（javap） |
| A.7 | `RegisterCommandsEvent` | ✅ `getDispatcher():CommandDispatcher<CommandSourceStack>`、`getCommandSelection()` | Forge 47.3.0 API 索引（javap） |
| A.8 | `DeferredRegister` / `RegistryObject` | ✅ `create(IForgeRegistry<T>, String)`、`register(String, Supplier<? extends I>)`、`register(IEventBus)`；**没有** `DeferredItem` / `DeferredRegister.Items` | Forge 47.3.0 API 索引（javap） |
| A.9 | 网络（经典 SimpleImpl） | ✅ `net.minecraftforge.network.NetworkRegistry.newSimpleChannel(ResourceLocation, Supplier<String>, Predicate<String>, Predicate<String>)`；`net.minecraftforge.network.simple.SimpleChannel#registerMessage(int, Class<MSG>, BiConsumer<MSG,FriendlyByteBuf>, Function<FriendlyByteBuf,MSG>, BiConsumer<MSG, Supplier<NetworkEvent.Context>>)`；`NetworkEvent.Context#enqueueWork(Runnable):CompletableFuture<Void>`、`setPacketHandled(boolean)`、`getSender():@Nullable ServerPlayer`；`SimpleChannel#send(PacketDistributor.PacketTarget, MSG)`、`PacketDistributor.PLAYER.with(Supplier<ServerPlayer>)` | Forge tag `47.3` 源码 `NetworkRegistry.java` / `simple/SimpleChannel.java` / `NetworkEvent.java` / `PacketDistributor.java` |
| A.10 | playerAnimator 产物 | ✅ `dev.kosmx.player-anim:player-animation-lib-forge:1.0.2-rc1+1.20`（maven 目录实测存在，jar 181 KB） | `https://maven.kosmx.dev/.../1.0.2-rc1+1.20/` |
| A.11 | playerAnimator API | ✅ `PlayerAnimationFactory.ANIMATION_DATA_FACTORY.registerFactory(ResourceLocation,int,PlayerAnimationFactory)`、`PlayerAnimationAccess.getPlayerAssociatedData(AbstractClientPlayer).get(id)`、`PlayerAnimationRegistry.getAnimation(id):@Nullable KeyframeAnimation`、`ModifierLayer#addModifierBefore`、`SpeedModifier(float)`、`KeyframeAnimationPlayer#setFirstPersonMode/setFirstPersonConfiguration` | playerAnimator `1.20` 分支源码（上一轮核对） |
| A.12 | 构建模板 | ✅ FG 6.0.54 / Gradle 8.8 / Forge 47.3.0 / official 1.20.1 / `pack_format 15` / `mandatory=true` | `mc_scaffold platform=forge 1.20.1` 生成的模板 |

### 附录 B：换 Forge 版本时的网络 API 复核

47.3 上已核实存在经典 SimpleImpl（附录 A.9），**本方案不需要备选写法**。只有当有人把 Forge 版本改成别的（例如 1.20.6+ 的 50.x/51.x）时才需要处理：那些版本移除了 `NetworkRegistry.newSimpleChannel` 与 `SimpleChannel#registerMessage`，改成 `net.minecraftforge.network.ChannelBuilder` + `SimpleChannel#messageBuilder(...).codec(...).consumer(...)` 的流式 API，`SimpleChannel` 的包名也从 `net.minecraftforge.network.simple` 移到 `net.minecraftforge.network`。

遇到这种情况：

1. **不要照抄博客**。网上流传的多是 1.18/1.19 的中间形态（`NetworkRegistry.ChannelBuilder` + `messageBuilder` + `.consumer(...)`），在 47.3 上是另一组重载，在新版本上又全被删了；
2. 直接看本地 jar 的真实签名：

```bat
javap -classpath <...>\forge-<版本>-universal.jar net.minecraftforge.network.NetworkRegistry
javap -classpath <...>\forge-<版本>-universal.jar net.minecraftforge.network.simple.SimpleChannel
javap -classpath <...>\forge-<版本>-universal.jar net.minecraftforge.network.ChannelBuilder
```

3. 只改 `HugNetwork` 一个文件 —— 通道怎么建、消息怎么注册都是胶水；`HugRenderPayload` 的 4 个字段、编解码顺序、`DistExecutor` 调用**一律不动**（那是协议，改了就和旧版本不兼容）。

若目标就是「1.20.1 上跑通」，**不要动 Forge 版本**：47.3.0 + 本文代码即可。

### 附录 C：与 `original-design.md` 的差异登记

迁移后与原实现**有意不同**的点，全部已在第 8 章登记，汇总如下（便于写 CHANGELOG）：

1. 命令注册从 `ServerStartingEvent` 改为 `RegisterCommandsEvent`（等价，更规范）；
2. 60 秒过期从线程池改为 tick 计时（K1/K2）；
3. 「已接受」消息改到校验之后（K3）；
4. 落点计算加零长度保护（K5）；
5. 掉线时广播结束包 + 客户端 200 tick 兜底（K9）；
6. `HugStatus` 去掉恒真的 `active` 字段，结束/掉线统一走 `broadcastHugEnd`（K11）；
7. 包处理不再在异常时断开连接，改为记日志（1.20.1 无 `CompletableFuture` 上下文）；
8. 动画 id 缺失时放弃播放而不是 NPE（K19）；
9. 新增 `HugNetwork.java`（原工程由 `CustomPacketPayload` 承担，1.20.1 需要独立承载 `SimpleChannel`）。

**没有改**：落点方向、1.3 格间距、120 tick、60 秒、64 格、0.5 速度、动作数据、渲染矩阵、语言文案、配方。
