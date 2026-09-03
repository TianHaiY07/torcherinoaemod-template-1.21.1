# 项目记忆文档（MEMORY）

每次调用前必须通读本文件并严格遵守。

## 0. 模组定位

- 这是一个 **Applied Energistics 2（AE2）附属模组**，含两个方块与一组物品：
  - 「AE加速器」（AE Accelerator）：AE2 机器（接入网络 + AE 能量供电 + 4 格升级卡插槽）。
  - 「AE加速火把」（AE Torcherino）：独立范围扫描方块，GUI 调加速倍率与 X/Z/Y 扫描范围（上限受服务端配置约束）。
  - 「加速器配置卡」：把一台加速器与网络内设备绑定，插入卡槽后按绑定关系自动注入/撤销加速。
- 玩法：将本模组的 3 张「AE加速器升级卡」（I=×2、II=×4、III=×8，可重复插入，复合累乘）插入加速器，加速器接入 AE 网络后，可在其 GUI 中勾选网络内的 AE2 机器进行加速（每台设备可独立调节加速倍数）；GUI 还会列出网络的合成 CPU，选中后可开启「智能加速」，在 CPU 合成期间联动加速参与合成的机器。
- 因此 **AE2 是强制运行时依赖**（通过 Modrinth Maven 引入，dev 环境亦需存在），并在 `neoforge.mods.toml` 模板中声明 `ae2`、`guideme` 两个 required 依赖。

## 1. 语言要求

- 所有与用户的交流一律使用**简体中文**。
- 代码中的**注释必须使用简体中文**，禁止英文注释（全局规则）。

## 2. 文本本地化要求

- 所有游戏内文字（物品名、方块名、创造栏 Tab 名、GUI 状态/提示文字等）**严禁硬编码**。
- 必须通过 lang 文件统一调配：
  - `src/main/resources/assets/torcherino_ae_mod/lang/en_us.json`（英文）
  - `src/main/resources/assets/torcherino_ae_mod/lang/zh_cn.json`（中文）
- 键名约定：物品 `item.<modid>.<name>`、方块 `block.<modid>.<name>`、创造栏 `itemGroup.<modid>`、GUI 文字 `gui.<modid>.<screen>.<key>`。

## 3. 运行环境与版本

- 平台：NeoForge **1.21.1** / Java 21（Mojang 面向玩家发行 Java 21）。
- `gradle.properties` 关键值：
  - modid：`torcherino_ae_mod`
  - group：`com.tianhai.torcherino_ae`
  - mod_name：`Torcherino ae mod`
  - mod_version：`1.0.0-1.21.1`
  - minecraft_version：`1.21.1`；neo_version：`21.1.248`
  - parchment 映射：`1.21.1 / 2024.11.17`
- 依赖（`build.gradle`）：
  - AE2（Modrinth Maven）：`maven.modrinth:XxWD5pD3:DUSBnYm0`
  - GuideME（AE2 的强制运行时依赖）：`maven.modrinth:Ck4E7v7R:9aIv5HxH`
  - **本地 mods**：项目根目录 `libs/` 下的 jar 会被自动加载进开发环境（`implementation fileTree(dir: 'libs', include: ['*.jar'])`），`/libs/` 已被 .gitignore 忽略。
  - 单元测试：JUnit 5。`src/test/java` 下已有 7 个纯逻辑单测类（倍率/能耗/预算/设备身份/登记表/目标缓存/配置卡数据），`gradlew test` 全绿。**注意**：build.gradle 末段把 main 的 Minecraft/NeoForge 类路径叠加给了 test sourceSet——引用 `net.minecraft.*` 值类型的测试（BlockPos/ResourceLocation/NbtOps）依赖这份类路径，删不得。

## 4. 当前实际代码结构

> **说明**：两个方块与整套物品均已完整实现。权威结构见 `docs/architecture.md`
> （§3 分层模型、§7 配置清单、§10 单测、§11 各阶段完成记录）；本文件如与其冲突，以架构文档为准。
> 分层思想：`api`（纯契约/值类型，无 MC/AE2 运行时依赖）→ `core`（纯逻辑引擎/状态表）→
> `config`（横切：默认值 → Spec → 运行时生效快照）→ `network`（AE2 网格桥接）→
> `blockentity`/`menu`/`item`/`block`（服务端协调与外露）→ `client/*`（渲染与 GUI）。

### 4.1 入口与注册

- `com/tianhai/torcherino_ae/Torcherinoaemod.java`
  - `@Mod(MOD_ID)` 注解，`MOD_ID` / `LOGGER` 常量；构造器签名 `(IEventBus, ModContainer)`。
  - 构造器注册五个 DeferredRegister + **配置**：`modContainer.registerConfig(...)` 注册
    Server/Client 两段 Spec（NeoForge 1.21 起配置注册移入 `ModContainer`，旧
    `ModLoadingContext` 同名方法不可用）；注册后立即刷入 `RuntimeConfig`，并监听
    `ModConfigEvent.Loading/Reloading` 热刷新（同时同步 `DebugLog.setEnabled(debug.enabled)`）。
  - `registerCapabilities`：为方块实体注册 `IN_WORLD_GRID_NODE_HOST` 能力——AE2 只为其自身
    方块注册该能力，第三方方块必须自注册，否则无法接线。
  - `commonSetup`：`Upgrades.add(升级卡, 方块, 4)` × 3 声明支持升级卡（参数顺序「卡在前、
    机器在后」，颠倒卡片放不进插槽）；`AEBaseBlockEntity.registerBlockEntityItem(...)` 登记
    代表物品。ticker 注入在 `ModBlockEntities` 注册阶段（见 4.2），**不在** commonSetup 里
    `setBlockEntity`（四参签名易把 serverTicker 误传 clientTicker 位，导致服务端 ticker 为
    null、加速永不生效）。

### 4.2 方块与方块实体

- `block/`：`ModBlocks`（注册 `AE_ACCELERATOR`、`AE_TORCHERINO`）、`AEAcceleratorBlock`
  （状态 `ONLINE`/`WORKING` + `HORIZONTAL_FACING`；`getOcclusionShape` 返回 `Shapes.empty()`，
  否则镂空框架模型的相邻面会被剔除）、`AETorcherinoBlock`（范围扫描方块）。
- `blockentity/ModBlockEntities.java`：注册两个方块实体时**必须手动注入 ticker**
  （`setBlockEntity(class, type, clientTick, serverTick)`，委托
  `ClientTickingBlockEntity` / `ServerTickingBlockEntity`）；不注入则原版区块 tick 循环不会
  调 `commonTick()`，加速与 working/范围扫描都不更新。
- `blockentity/AEAcceleratorBlockEntity.java`（AE2 机器：`AENetworkedPoweredBlockEntity`）：
  接入网络 + AE 能量供电 + 升级卡库存（4 格）+ online/working 流同步 + 每 tick
  `core/AccelerationEngine.pulse`；加速目标登记表入口与 `targetRegistryVersion()`（供菜单
  设备列表缓存失效，见 4.4）。能耗与最高倍数一律走 `core/PowerModel` /
  `core/MultiplierCalculator`（倍率 cap 与能量系数来自运行时配置，默认 = 现网基线）。
- `blockentity/AETorcherinoBlockEntity.java`（范围扫描源）：倍率与 X/Z/Y 范围 clamp 自配置
  上限；`maxSpeed()/maxXzRange()/maxYRange()` 暴露给菜单 `@GuiSync` 下发滑块上限。
- `blockentity/ConfigCardBinding.java`：配置卡全职责（库存/注入撤销/移除清理/NBT）。

### 4.3 纯逻辑与配置域（可单测）

- `api/`：`DeviceId`、`DeviceKind`、`IAccelerationSource`、`AccelerationTarget`、
  `AccelerationResult`、`AccelSource`、`BudgetMeter`。
- `core/`：`AccelerationEngine`（唯一脉冲执行器）、`TargetCache`、`MultiplierCalculator`、
  `PowerModel`、`TargetRegistry`（单状态表 + 来源撤销 + NBT；`version()` 供缓存失效判断）。
- `config/`：`ConfigDefaults`（默认值 = 现网基线，单一事实来源）→ `ModConfig`
  （Server/Client 两段 Spec）→ `RuntimeConfig`（volatile 生效快照，**逻辑层唯一读取口**）。
  服务端段：加速/能量/黑名单/合成机器附加类型/菜单设备刷新节流/预算/火把上限/debug；
  客户端段：`cacheFilteredList`（列表过滤缓存开关）、`renderBracketHighlight`（高亮渲染开关）。

### 4.4 菜单、网格桥接与物品

- `menu/`：
  - `AEAcceleratorMenu`（继承 AE2 `AEBaseMenu` 自主创建）：`collectDevices(host)` 静态可复用
    （采集设备 + 合成 CPU 列表）；**设备列表带缓存**——到达刷新周期时比较
    `host.targetRegistryVersion()` 与网格拓扑签名（`topologySignature`，宿主 identity + 激活 +
    合成 CPU），未变则复用上次 `DeviceList`，稳态零重建。动作载荷 `DeviceTarget` /
    `MultiplierTarget` 是**带无参构造器的普通类**（非 record），保证 GSON 序列化。
  - `AETorcherinoMenu`：4 个滑块字段 + `@GuiSync` 上限字段
    `maxSpeed/maxXzRange/maxYRange`（服务端配置 `torcherino.*` 下发，初值为运行时默认）。
  - `DeviceList`/`DeviceEntry`：`PacketWritable` record；`DeviceEntry.craftingCpu` 区分合成 CPU。
- `network/`：`DeviceScanner`（可加速谓词/设备标识解析；黑名单来自
  `RuntimeConfig.acceleratableBlacklist()`）、`crafting/CraftingSupport`（合成 CPU 标识与
  合成机器三级判定；额外兜底类型来自 `RuntimeConfig.craftingMachineExtras()`）。
- `item/`：3 张升级卡（`AcceleratorUpgradeCardItem` 传入 2/4/8，tooltip 描述放大效果）、
  `AcceleratorConfigCardItem`（壳：注册 + tooltip，绑定/注入静态入口走 `ConfigCardData`）、
  `ConfigCardData`（Data Component 编解码契约 + `MAX_BOUND_DEVICES`）、`ModCreativeTabs`。

### 4.5 客户端界面与渲染

- `client/screen/`：`AEAcceleratorScreen`（设备列表 + 弹窗 + 升级面板；样式见 4.6）、
  `AETorcherinoScreen`（4 个滑块，**范围上限来自菜单 `@GuiSync` 字段而非方块实体静态常量**）。
- `client/widget/`：`DeviceListWidget`（行过滤带缓存、行尾两态状态图标 Blitter 构造期预建、
  悬浮高亮、左键切换/右键弹窗）、`DeviceConfigPopup`（前景层绘制）、`SettingSliderWidget`
  （max 动态：经 `Function<菜单,Integer>` 每 tick 从 `@GuiSync` 上限字段刷新并钳制当前值，
  配置变更后开新界面即生效）。
- `client/render/`：`AEAcceleratorRenderer`（接电工作叠加全亮度发光带 + 流光粒子；粒子节奏
  在渲染器内维护，不污染方块实体）、`ConfigCardHighlightPass`（配置卡绑定高亮；受客户端
  配置 `client.renderBracketHighlight` 开关控制）。
- 其它：`client/ModScreens.java`（仿 AE2 `StyleManager` 的自定义样式加载器，从本模组命名空间
  读样式 JSON）、`client/AEGuiMetrics.java`（界面度量常量，集中行高/贴图素材区域等）。
- `TorcherinoaemodClient.java`：注册菜单 Screen、方块实体渲染器、附加模型
  （发光带模型不在任何方块状态里，必须单独注册）。

### 4.6 资源与数据（手写，datagen 未启用）

- `lang/en_us.json` + `lang/zh_cn.json`：方块/物品/创造栏/GUI 全部文案
  （含加速器 GUI 状态文案、火把滑块、配置卡提示、智能加速 tooltip 等）。
- `blockstates/`、`models/block/`、`models/item/`：加速器按 online × working 组合切
  基础 / `on` / `inactive` 三种模型；火把与卡片模型同规则；发光带模型单独存在并单独注册。
- `screens/*.json`：各 GUI 的自定义界面样式（palette、background、images、slots、widgets、text）。
- `textures/`：`block/`、`gui/`、`item/` 三类贴图（含加速器 GUI、设备条目/滑块素材等）。
- `data/`：方块 loot_table（掉落自身）+ 合成配方。
- 混入配置 `torcherino_ae_mod.mixins.json`：当前无任何 mixin。
- `src/main/templates/META-INF/neoforge.mods.toml`：占位符模板，声明 ae2/guideme required 依赖。
- 根目录 `_img/`：开发期贴图分析脚本（ps1/py）与结果，非模组资源。

## 5. 已知注意事项 / 待办

- `src/generated/resources/` 数据生成目录尚未建立（datagen 未启用，模型/blockstates/lang 均为手写）。
- 加速逻辑依赖 AE2 网格 tick（`IGridTickable`），**不会加速**存储总线、能量元件、P2P 隧道等基础设施（有意排除）。
- 客户端 `commonTick` 提前 return 是**刻意设计**，勿删（否则模型切换会失效）。
- 诊断日志一律走 `util/DebugLog` 门面（默认关闭，开关已接入服务端配置 `debug.enabled`，经 `ModConfigEvent` 热重载生效）：禁止在每 tick 路径或玩家交互路径直接写 `LOGGER.info`。排查时改配置或调 `DebugLog.setEnabled(true)`。
- 加速脉冲中每次调用 `tickingRequest` 都要检查返回值，等于 `TickRateModulation.SLEEP` 立即结束该设备本 tick 的加速。注意 `tickingRequest`（返回 `TickRateModulation` 枚举）与 `getTickingRequest`（返回 `TickingRequest`，才有 `isSleeping()`）**不是一回事，不可混用**。
- 客户端动作的服务端处理器必须校验载荷合法性（目标是否真在网格内、值域是否越界），不可直接采信客户端传来的标识写入持久化状态。
- `TargetRegistry` 语义（写代码勿破坏）：每条 `DeviceId` 单记录、后写来源覆盖先写来源（玩家
  显式设置优先）；`clearBySource` 按来源精确撤销（取出配置卡不误伤玩家勾选）。
- 配置读取纪律：逻辑层一律经 `config/RuntimeConfig` 读生效快照，**禁止**直接引用
  `ModConfig` 的 Spec 值或另起默认常量；改默认值先改 `ConfigDefaults`（保证行为零变更的单一来源）。
- 客户端 UI 缓存的「加速中/倍率」展示以服务端下发的 `DeviceEntry` 为准；设备列表采集缓存失效
  由 `TargetRegistry.version()` + 网格拓扑签名共同驱动，**勿改为无条件每刷新周期全量重建**。

## 6. 构建与运行命令

- **环境**：`JAVA_HOME` 需显式指向 Java 21 JDK（系统 PATH 未安装）。本机为 `C:\Users\<用户>\.gradle\jdks\eclipse_adoptium-21-amd64-windows.2`，构建前用 `$env:JAVA_HOME=<路径>` 设置即可。
- 运行客户端（开发调试）：`gradlew runClient`
- 运行服务端：`gradlew runServer`（无 GUI）
- 运行数据生成：`gradlew runData`（输出到 `src/generated/resources/`）
- 运行游戏内测：`gradlew runGameTestServer`
- 构建产物：`gradlew build`
- 运行单元测试：`gradlew test`
- 生成 mod 元数据：`generateModMetadata`（在 `src/main/templates/META-INF/neoforge.mods.toml` 基础上展开占位符；每次 IDE 重载自动执行）

## 7. 其他约定

- 注释风格：每个代码模块/类都要有简洁中文注释说明职责与用途；代码注释用中文。
- 实现新逻辑前先检索项目，优先复用已有逻辑；通用逻辑尽量放入 `common` 共享模块。
- 一律使用 `gradlew` 而非裸 `gradle`。
- 需查阅 AE2 内部实现时，从 Gradle 缓存中的 AE2 依赖 jar 反编译（项目根目录**没有** `appeng/` 目录，旧记录的该路径已不存在）。

## 8. 架构设计与重构

- **目标架构设计文档：`docs/architecture.md`**（分层模型、模块职责、核心契约、NBT 格式、性能预算、配置清单、P0–P3 路线图）。**新增功能或重构前先读该文档。**
- **已完成 P0 止血**（纯风险消除，不改结构、数值零变更）：
  1. 新增 `util/DebugLog`：诊断日志统一门面，默认关闭。原先每台加速器每秒 2 条 `LOGGER.info("[DBG]...")` 与配置卡交互日志全部改走门面。
  2. `AEAcceleratorBlockEntity.syncConfigCardDevices`：改为单次网格遍历求交集，复杂度由 **O(绑定数 × 节点数)** 降为 **O(节点数)**（该方法挂在节点状态变化事件上，满绑定时原为 64 次全网格遍历）。原 `isCardDeviceInGrid` 方法已删除。
  3. 加速脉冲补返回值早退：加速器两处 + 火把一处，`tickingRequest` 返回 `TickRateModulation.SLEEP` 即停止该设备本 tick 的后续调用。
  4. `AETorcherinoBlock.getTicker`：补 `BlockEntityType` 类型校验，避免强转异常。
  5. 客户端动作补服务端校验：`AEAcceleratorMenu` 的 toggle / setMultiplier 校验目标是否在网格内 + 倍数区间（CPU 走单独枚举校验）；`AETorcherinoMenu` 四个 setter 增加方块实体有效性检查。
- **已完成 P1 抽象**（引入 `api` + `core` 两层纯逻辑域，数值零变更）：
  1. 新增 `api` 包：`DeviceId`（维度+坐标+朝向+种类的值类型身份，含 NBT/网络/stableKey 三种编解码）、`DeviceKind`、`IAccelerationSource`（加速源契约）、`AccelerationTarget` / `AccelerationResult`、`AccelSource`（PLAYER/CONFIG_CARD）、`BudgetMeter`（预算计数器，无 MC 运行时依赖）。
  2. 新增 `core` 包：`AccelerationEngine`（**唯一**脉冲执行器，两份手抄已删除）、`TargetCache`（统一两处缓存三件套）、`MultiplierCalculator` / `PowerModel`（公式原样移出方块实体）、`TargetRegistry`（单一状态表 + 来源撤销 + NBT 读写，**新存档格式，旧档断档**）。
  3. 方块实体退化为加速源：`AEAcceleratorBlockEntity` 与 `AETorcherinoBlockEntity` 都实现 `IAccelerationSource`，每 tick 调 `AccelerationEngine.pulse(this)`。
  4. 状态收敛：`acceleratedDevices` / `deviceMultipliers` / `configCardDevices` 三份状态 → `TargetRegistry`（按来源持久化）；卡片契约修复——卡注入随存档保留来源，重启后取出配置卡能精确撤销（旧 bug）。
  5. 字符串主键 → `DeviceId`，顺带修复跨维度同坐标误判（含配置卡绑定加速器）。**GUI/网络/载荷层仍传 `DeviceId.stableKey()` 字符串**，服务端 `DeviceId.parse` 统一校验。
  6. 主键相关的 API 签名变化：`AE2GridSupport.deviceIdOf(Object)→DeviceId`、`cpuDeviceId(dim, cpu)→DeviceId`（删 `isCpuDeviceId`/`resolveDeviceIdPos`/`"cpu:"` 前缀）；`AcceleratorConfigCardItem` 各方法、`ConfigCardData`（codec 化）、`AEAcceleratorMenu`/`ConfigCardEvents`/`ConfigCardHighlightPass` 同步适配。
  7. 实施偏差：`BudgetMeter` 落在 `api`（避免 `api` 反向依赖 `core`）；未立 `IAccelerationPolicy`（策略并入 `multiplierFor`）；引擎按「先倍率后睡眠」判定。详见 `docs/architecture.md` §11 P1 注记。
- **已完成 P2 分层**（打包拆分与依赖方向归正，行为零变更）：
  1. `menu` 包纯净：4 个客户端类迁出——`AEAcceleratorScreen` / `AETorcherinoScreen` → `client/screen`；
     `DeviceListWidget` / `DeviceConfigPopup` / `SettingSliderWidget`（从 AETorcherinoScreen 内部类独立）→ `client/widget`。
  2. 方块实体拆分：配置卡全职责（库存/注入撤销/移除清理/NBT）→ 新同包组件
     `blockentity/ConfigCardBinding`；加速领域职责由 P1 的 `core/*` 承担；方块实体保留为
     协调者（~490 行，含必须 override 的 AE 网络生命周期与 online/working 流同步）。
  3. `common/AE2GridSupport` 拆并迁出：可加速谓词/单方块节点解析/设备标识 →
     `network/DeviceScanner`；CPU 标识与内部类强转隔离 + 合成机器三级判定 →
     `network/crafting/CraftingSupport`；`common` 包已清空。
  4. 渲染层反向依赖消除：配置卡绑定读写静态方法下沉为数据契约 `item/ConfigCardData`
     （Data Component 编解码 + 读写，含 `MAX_BOUND_DEVICES` 上限）；`AcceleratorConfigCardItem`
     瘦身为物品壳（注册 + tooltip）；高亮 pass / 模型注册 / 服务端绑定组件统一依赖 `ConfigCardData`。
  5. 偏差：`TargetResolver`（DeviceId→IGridNode）P1 后无消费方，未落地；`AcceleratorState`
     与 `AcceleratorCore` 职责分别由流同步段与 P1 core 包承担，不再单独立类。
- **已完成 P3 配置化与质量**（行为零变更，所有默认值 = 现网基线，即「配置落地后不变」）：
  1. 新增 `config/` 包三件套：`ConfigDefaults`（单一事实来源）→ `ModConfig`（Server/Client 两段
     Spec）→ `RuntimeConfig`（volatile 生效快照）；构造器经 `modContainer.registerConfig` 注册
     后立即刷新，并监听 `ModConfigEvent.Loading/Reloading` 热刷新；`DebugLog` 开关接入
     `debug.enabled`。
  2. 数值全面配置化：加速器基础倍率/三种卡系数/倍率上限 cap、能耗 base/每卡/每设备、每源调用
     预算（`budget.tickCallsPerSource`）、目标缓存重建间隔、菜单设备刷新节流、火把
     `maxSpeed/maxXzRange/maxYRange` clamp；黑名单与合成机器附加类型改为字符串 FQCN 列表
     （`Class.forName` 解析，坏条目只告警跳过，绝不崩溃）。
  3. 火把 GUI 动态上限：方块实体静态常量删除；`AETorcherinoMenu` 新增
     `@GuiSync(5/6/7) maxSpeed/maxXzRange/maxYRange`；`SettingSliderWidget` 的 max 改为经
     `Function<AETorcherinoMenu,Integer>` 从 `@GuiSync` 字段动态刷新并钳制当前值。
  4. 单测补齐：`src/test` 新增 7 个纯逻辑测试类；因引用 `net.minecraft.*` 值类型，build.gradle
     末段把 main 的 MC/NeoForge 类路径叠加给 test sourceSet（勿删）。
  5. UI 缓存优化（§8.4）三处落地：设备列表行过滤缓存（`client.cacheFilteredList` 开关可回退）、
     行尾两态图标 Blitter 构造期预建、菜单设备列表「登记版本 + 网格拓扑签名」缓存（稳态零重建）。
  6. 单测顺带修复：`MultiplierCalculator.compute` 中间量 long 溢出不再返回负数（先判负再钳 int 上限）。
- **已完成 P4 可测性收尾**（§10.2 收口，行为零变更）：
  1. `client/render/util/CornerBracketRenderer` 抽取纯几何：顶点计算下沉为公开静态
     `computeSegmentQuads(x1…z2, t)`（纯 double 数学、无渲染对象依赖、退化返回 null，
     顶点排布与 `drawSegment` 提交顺序一致），渲染路径只消费顶点数组。
     新增 `CornerBracketRendererTest` 100% 覆盖（轴向方帽尺寸/斜线延长/退化/面积守恒）。
  2. `client/AEGuiMetrics` 新增 `AEGuiMetricsTest`：固化「手柄垂直居中轨道、轨道范围落入
     面板素材、行尾两状态图标贴图竖排相接、图标适配行高」等布局不变量，防改贴图错位。
  3. 取舍：`DeviceEntry`/`DeviceList` 拆纯 POJO + PacketWritable 适配器**不执行**——`@GuiSync`
     字段类型须实现 `PacketWritable`（AE2 平台契约，§9.1 A 类），拆分仍无法纯 JVM 直测
     网络包往返，净收益有限（结论回填 docs §10.2）。
- **后续阶段**：`budget.tickCallsGlobal`（全网格总预算）暂无需求未落地；`DeviceEntry` 等网络包
  往返与真实注册表基建直测需游戏运行时，未纳入纯 JVM 单测。详见 `docs/architecture.md` §11。
