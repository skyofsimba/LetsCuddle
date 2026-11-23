# common

两个平台共用的**纯 Java** 源码目录（没有 build.gradle，由平台工程用 `srcDir` 引入）。

## 边界

可以放：

- 常量、协议号、动作包格式号；
- 纯数学（落点/站位）与纯数据模型（`CuddleAnimDef`、`CuddleCaps`）；
- 动作 id 的校验/规范化/legacy 迁移；
- manifest 与动画 JSON 的解析、采样。

不可以放：

- 任何 `net.minecraft.*` / `net.minecraftforge.*` / `net.fabricmc.*`；
- 平台事件、注册表、渲染、网络类；
- 需要世界/玩家/资源管理器才能做的事。

## 约束

- 必须能用 **Java 17** 编译（1.20.1 是 17）：不要用 record pattern、switch 模式匹配等 21 语法。
- 第三方依赖只用两个平台都有的库：目前只有 Gson。
- 单元测试在 `forge-1.20.1/src/test/java`（1.20.1 是参考平台），测试直接引用这里的类。
