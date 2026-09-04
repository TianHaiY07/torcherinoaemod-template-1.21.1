# 项目记忆文档（MEMORY）

每次调用前必须通读本文件并严格遵守。本文件是模组架构的唯一权威说明（原 `docs/architecture.md` 已删除，代码中不再有对它的交叉引用）。

## 0. 模组定位

- 这是一个 **Applied Energistics 2（AE2）附属模组**，含两个方块与一组物品：
  - 「AE加速器」（AE Accelerator）：AE2 机器（接入网络 + AE 能量供电 + 4 格升级卡插槽 + 1 格配置卡槽）。
  - 「AE加速火把」（AE Torcherino）：独立范围扫描方块（**不接入 AE 网络、不耗 AE 能量**），GUI 调加速倍数与 X/Z/Y 扫描范围（上限受服务端配置约束）。另有两个分级变体「AE加速火把I」（倍率上限固定 64x）与「AE加速火把II」（上限固定 324x），行为与基础火把一致、仅倍率上限不同（不受 `torcherino.maxSpeed` 配置约束）。
  - 「加速器配置卡」：把一台加速器与网络内设备/合成 CPU 组离线绑定，插入卡槽后按绑定关系自动注入/撤销加速（卡在则加速、卡走则停；CPU 组整组一个标记）。
- 玩法：将本模组的 3 张「AE加速器升级卡」（I=×2、II=×4、III=×8，可重复插入，复合累乘）插入加速器，加速器接入 AE 网络后，可在其 GUI 中勾选网络内的 AE2 机器进行加速（每台设备可独立调节加速倍数）；GUI 还会列出网络的合成 CPU，选中后可开启「智能加速」，在 CPU 合成期间联动加速参与合成的机器。
- 因此 **AE2 是强制运行时依赖**（通过 Modrinth Maven 引入，dev 环境亦需存在），并在 `neoforge.mods.toml` 模板中声明 `ae2`、`guideme` 两个 required 依赖。

## 1. 语言要求

- 所有与用户的交流一律使用**简体中文**。
- 代码中的**注释必须使用简体中文**，禁止英文注释（全局规则）。

## 2. 文本本地化要求

- 所有游戏内文字（物品名、方块名、创造栏 Tab 名、GUI 状态/提示文字等）**严禁硬编码**。
- 必须通过 lang 文件统一调配：`src/main/resources/assets/torcherino_ae_mod/lang/en_us.json`（英文）与 `lang/zh_cn.json`（中文）。
- 键名约定：物品 `item.<modid>.<name>`、方块 `block.<modid>.<name>`、创造栏 `itemGroup.<modid>`、GUI 文字 `gui.<modid>.<screen>.<key>`。玩家即时提示走 `item.<modid>.accelerator_config_card.*`（如 bind_success/unbind_success/bind_device_success/bind_device_fail/not_bound_hint）。

## 3. 运行环境与版本

- 平台：NeoForge **1.21.1** / Java 21。`gradle.properties` 关键值：modid `torcherino_ae_mod`、group `com.tianhai.torcherino_ae`、mod_version `1.01`、neo_version `21.1.248`、parchment `1.21.1 / 2024.11.17`。
- 依赖（`build.gradle`）：AE2（Modrinth Maven `maven.modrinth:XxWD5pD3:DUSBnYm0`）+ GuideME（`maven.modrinth:Ck4E7v7R:9aIv5HxH`，AE2 强制运行时依赖）+ **本地 mods**（项目根 `libs/*.jar` 自动加载进 dev 环境，可放测试用附属 mod）。
- 单元测试：JUnit 5，现有 **11 个纯逻辑测试类**（见 §9）。**注意**：build.gradle 末段把 main 的 Minecraft/NeoForge 类路径叠加给了 test sourceSet——引用 `net.minecraft.*` 值类型的测试（BlockPos/ResourceLocation/NbtOps）依赖这份类路径，删不得。

## 4. 当前实际代码结构

分层思想（依赖方向只允许单向，写代码勿破坏）：
`api`（纯契约/值类型，不反向依赖 core/config）→ `core`（纯逻辑引擎/状态表）→ `config`（横切：默认值 → Spec → 运行时生效快照）→ `network`（AE2 网格桥接）→ `blockentity`/`menu`/`item`/`block`（服务端协调与外露）→ `client/*`（渲染与 GUI）。

### 4.1 入口与注册

- `com/tianhai/torcherino_ae/Torcherinoaemod.java`
  - `@Mod(MOD_ID)`，`MOD_ID`/`LOGGER` 常量；构造器签名 `(IEventBus, ModContainer)`。
  - 构造器注册**六个** DeferredRegister：`ModBlocks.BLOCKS`、`ModItems.ITEMS`、`ModDataComponents.DR`、`ModBlockEntities.BLOCK_ENTITY_TYPES`、`ModCreativeTabs.CREATIVE_MODE_TABS`、`ModMenus.MENUS`。
  - 配置注册：`modContainer.registerConfig` 排队注册 Server 段 + 仅客户端注册 Client 段。**registerConfig 只是排队**——FML 在 mod 构造完成后统一加载配置文件，构造器内不可读 `ConfigValue`（抛 "Cannot get config value before config is loaded"），故构造器不做 refresh，RuntimeConfig 字段初值即 `ConfigDefaults` 基线。随后监听 `ModConfigEvent.Loading` / `Reloading` → `applyConfig`（按 Spec 匹配分别 `RuntimeConfig.refreshServer/refreshClient`；服务端段刷新时同步 `DebugLog.setEnabled`）。
  - `registerCapabilities`：为加速器方块实体注册 `IN_WORLD_GRID_NODE_HOST` 能力——AE2 只为其自身方块注册该能力，第三方方块必须自注册，否则无法接线（火把不是网络方块，不注册）。
  - `commonSetup`：`Upgrades.add(升级卡, 机器, UPGRADE_SLOTS)` × 3 声明升级卡支持（**参数顺序「卡在前、机器在后」**，颠倒会以机器作键、卡片放不进插槽）；仅登记加速器方块实体的代表物品（`AEBaseBlockEntity.registerBlockEntityItem`）；**绝不在此再调 `setBlockEntity`**（其四参签名 clientTicker/serverTicker 顺序极易传错，且 FMLCommonSetupEvent 晚于 RegisterEvent 会覆盖注册阶段注入的 ticker）。
- `TorcherinoaemodClient.java`（`@EventBusSubscriber(value = Dist.CLIENT)`）：注册两个菜单 Screen（背景复用 AE2 `guis/background.png`，样式经自定义加载器 `ModScreens.loadStyleDoc("/screens/ae_*.json")` 读取）；注册 `AEAcceleratorRenderer`；`ModelEvent.RegisterAdditional` 单独注册 `AEAcceleratorRenderer.LIGHTS_MODEL`（发光带模型不在任何方块状态里）。GUI 文字与暗色 UI 材质包的兼容机制见「4.5 暗色 UI 材质包兼容」。

### 4.2 方块与方块实体

- `block/ModBlocks.java`：注册 `AE_ACCELERATOR`、`AE_TORCHERINO`、`AE_TORCHERINO_I`、`AE_TORCHERINO_II`。
- `block/AEAcceleratorBlock`：继承 AE2 `AEBaseEntityBlock`；状态 `ONLINE`/`WORKING` + 水平朝向（`horizontalFacing()` 朝向策略，支持扳手旋转）；`updateBlockStateFromBlockEntity` 依方块实体 online/working 切 on/inactive/基础三态模型；`getOcclusionShape` 返回 `Shapes.empty()`（否则镂空框架模型的相邻面会被剔除）。
- `block/AETorcherinoBlock`：`FACING` 六向 + `ENABLED`（总开关，放置默认 true）+ 随朝向旋转的选择箱 + 无碰撞体积；模型按 `ENABLED` 在 `block/ae_torcherino`（点亮）与 `block/ae_torcherino_off`（熄灭）间切换，配套三个 blockstates JSON 均为 `enabled×facing` 12 变体（键按字母序 `enabled=...,facing=...`）。**服务端 ticker 由本块的 `getTicker` 提供**（先判 `!level.isClientSide()` 且校验 `BlockEntityType` 再强转 `AETorcherinoBlockEntity`，客户端返回 null）。**基础/分级三个方块共用本类**，构造时以「方块实体类型 Supplier + 工厂」参数化（Supplier 避免在 ModBlocks 静态初始化阶段解析 ModBlockEntities 的 DeferredHolder），`newBlockEntity` 与 `getTicker` 据此分派到对应子类；静态 `applyEnabledState` 供方块实体把总开关写入状态，旧档状态缺 `enabled` 属性时以默认状态为底复制旧属性重建（不抛 setValue 异常）。
- `blockentity/ModBlockEntities.java`（三个条目，ticker 注入方式**不同，勿互换**）：
  - `AE_ACCELERATOR`：注册时**手动注入 ticker**——`setBlockEntity(class, type, (e)->((ClientTickingBlockEntity)e).clientTick(), (e)->((ServerTickingBlockEntity)e).serverTick())`。不注入则原版区块 tick 循环不调 `commonTick()`，加速与 working 永不更新。
  - `AE_TORCHERINO` / `AE_TORCHERINO_TIER_I` / `AE_TORCHERINO_TIER_II`：只建类型、不注入（由方块 `getTicker` 提供），三者各自为独立类型。
- `blockentity/AEAcceleratorBlockEntity.java`（继承 AE2 `AENetworkedPoweredBlockEntity`，实现 `IUpgradeableObject`/`CommonTickingBlockEntity`/`IAccelerationSource`）：
  - 升级卡库存 4 格（`UpgradeInventories.forMachine`，`UPGRADE_SLOTS=4`）+ 配置卡库存 1 格（委托 `ConfigCardBinding`）。字段 `targetRegistry` 包内可见（同包绑定组件做卡来源注入用）。
  - **网格安全取值**：AE2 `GridNode.getGrid()` 在节点未入网/销毁时抛 `IllegalStateException` 而非返回 null——全项目统一经 `util/AeGrid.gridOf(node)` 转译为 null（`grid()` 即其薄委托），调用方只判空。`isActive()` 直接 `getMainNode().isActive()`（AE2 对未入网节点内部短路安全），`onMainNodeStateChanged` 里 `setOnline(grid != null && getMainNode().isOnline())` 以权威事件驱动 online，**不在 commonTick 重算**（该处 getGrid() 时机不可靠会把 online 覆写回 false）；setOnline 的调试日志经安全取的网格能量服务读 isPowered，不裸调 `getMainNode().isPowered()`（内部走 getGrid() 会抛 ISE）。
  - `commonTick()`（服务端）：客户端直接 return（刻意设计）；经 `grid()` 取值，`grid == null || !isActive` → `setWorking(false)` 返回；否则**先 `AccelerationEngine.pulse(this)` 统计真实工作量**，仅当 `result.didWork()` 才 `grid.getEnergyService().extractAEPower(needed, ...)` 并按 `available >= needed * powerBufferFraction` 判定 working——空闲时不耗电不标记工作（节能，方块停 in `on` 模型）。每 `debug.sampleIntervalTicks` tick 输出一次网络/工作诊断（DebugLog）。
  - `getAccelMultiplier()`：`MultiplierCalculator.compute(基础4 × 2^I × 4^II × 8^III)` 再套 `accelerator.maxMultiplierCap` 硬上限（-1 表示不限制）。能耗 `PowerModel.requiredPerTick(每tick固定 + 每卡线性 + 每登记设备)`，与倍率复合累乘无关（刻意脱钩）。
  - 目标管理（供菜单）：`targetRegistryVersion()`、`isAccelerating(id)`、`getDeviceMultiplier(id)`（未登记按当前最高倍数返回，供界面展示与滑块初值）、`setDeviceMultiplier(id, mult)`（≤1 移除该 PLAYER 来源登记；>1 钳到当前最高倍数；**若设备仍被卡注入，下次卡片同步会恢复——玩家要彻底停卡管理的设备应取卡**）、`toggleAcceleratedDevice(id)`。
  - 智能加速：`rebuildTargets()` 单次遍历网格产出「已登记设备」与「智能联动目标」（记入 `craftingLinkedIds`，范围由配置 `crafting.smartAccelerateScope` 控制：`ALL_ACCELERATABLE` 默认联动网内全部可加速设备—零配置兼容任意第三方 AE 工作机器；`CRAFTING_MACHINES` 仅联动 ICraftingProvider 服务宿主与 `CraftingSupport.isCraftingMachineType` 命中者）；`multiplierFor` 对已登记返回登记值，否则若在 `craftingLinkedIds` 且 `crafting.smartAccelerateEnabled` 开启则返回「当前智能倍率」（以游戏时间为键每 tick 缓存：扫描被选中 `isAccelerated` 且 `isBusy()` 的 CPU 的登记倍率最大值）；否则 1。
  - 状态流：online/working 经 `writeToStream/readFromStream` 同步客户端并驱动模型切换（`markForUpdate` 切方块状态、`markForClientUpdate` 同步 GUI）。NBT：`saveAdditional/loadTag` 持久化配置卡库存（binding）与 `TargetRegistry`（含来源标记）；加载后 `markTargetsDirty()`。
- `blockentity/AETorcherinoBlockEntity.java`（普通 BlockEntity，**不实现 `IAccelerationSource`、不经过 `AccelerationEngine`**；服务端 tick 由方块 `getTicker` 提供，客户端不执行；目标扫描与三条加速路径已拆至同包 `TorchTargetScanner`/`TorchAccelerator`）：
  - 独立范围扫描（原始 Torcherino 式）：X/Z/Y 范围默认 3/3/2、`speed` 默认取 `ConfigDefaults.TORCHERINO_MAX_SPEED`；setter 一律 `clampRange(v, RuntimeConfig 上限)`，变化后置 `scanCooldown=0` 强制下一 tick 重扫 + 存档 + `sendBlockUpdated`。NBT 键 `x_range/z_range/y_range/speed/enabled`，加载后 clamp 并保底 speed≥1、旧档无开关标签默认开启。`isActive() = enabled && speed > 1 && !isRangeEmpty()`。**模型开关同步**：`setEnabled` 变化即时经 `AETorcherinoBlock.applyEnabledState` 写入方块状态（`level.setBlock(..., Block.UPDATE_CLIENTS)`）驱动 off/点亮模型切换；每次加载首帧 `syncEnabledBlockStateOnce` 兜底一次，修复旧档状态缺 `enabled` 属性导致的变体缺失（正常状态零开销空操作）。
  - 目标扫描（委托 `TorchTargetScanner.scan`）：每 `SCAN_INTERVAL`（20）tick 重扫 `betweenClosed` 立方体（排除自身），把候选方块（AE 网格宿主 `IActionHost`/`IGridConnectedBlockEntity`、带方块实体 ticker、或随机 tick 方块）缓存为 `Target` 不可变快照；执行前校验方块实体类型未变，防止误加速被替换方块。
  - 三条加速路径委托 `TorchAccelerator.accelerate`（同一目标各自并行生效）：AE 网格 tick（`IGridTickable` 重复 `tickingRequest(node,1)`，返回 `SLEEP` 时 `ITickManager.sleepDevice` 早退）、方块实体 ticker（`EntityBlock.getTicker` 重复调用）、随机 tick（`BlockState.randomTick`）。
  - **性能预算**：每 tick 先 `budget().resetTick()`——`budget()` 经 `core/SourceBudget` 读生效值 = 配置 `budget.tickCallsPerSource` 经 `AdaptiveThrottle.INSTANCE.adjust`（实例仅在生效预算变化时重建）；三条路径每次调用前 `budget.request(1)` 按次申请，额度耗尽即停止剩余调用。火把因此与加速器共享同一套 TPS 自适应节流（拖垮 TPS 时逐档削峰）。
  - **分级变体**：`AETorcherinoTier1BlockEntity`/`AETorcherinoTier2BlockEntity` 继承本类，经受保护的四参构造器注入各自 `BlockEntityType` 与固定默认倍率，覆写 `maxSpeed()` 返回各自上限（64/324），不受 `torcherino.maxSpeed` 配置约束；其余逻辑（范围/倍率可调、加速路径、预算）全部复用基类。`getSpeed()` 读的是被构造器赋值的 `speed` 字段，因此分级火把放置时默认即为其上限。
- `blockentity/TorchTargetScanner.java`：火把影响范围目标扫描器（`scan(level, self, x/y/zRange)` 产出不可变 `Target` 快照列表），判定候选为「AE 网格宿主 / 带方块实体 ticker / 随机 tick」，排除自身与其它火把（防互相递归）。与加速路径解耦、独立可测。
- `blockentity/TorchAccelerator.java`：火把「三条加速路径」执行器（`accelerate(level, target, speed, budget)`）：AE 网格 tick / 方块实体 ticker / 随机 tick 对同一目标并行叠加；所有调用共享 `budget`（逐次按需申请）并按 `speed-1` 上限反复推进；面向任意方块，每目标包一层防御性 `try/catch`，防止某异常方块拖垮服务端单 tick。节点解析（`getGridNode`/`AeGrid.gridOf`）纳入本类。
- `blockentity/ConfigCardBinding.java`（与宿主同包协作：网格经 `host.grid()`、登记表经包内 `host.targetRegistry`）：
  - 单格配置卡库存过滤器：仅接受「本模组配置卡 && 已绑定本机」（`isBoundToSelf` 绑定比较含维度与坐标，异地卡片与未绑定卡一律拒绝）。
  - `syncConfigCardDevices`（触发点：卡槽内容变化 `onHostInventoryChanged`、宿主 `onMainNodeStateChanged` 以卡槽为参显式重调）：**单次遍历网格**收集本网络内可加速设备标识与卡上绑定集合求交集（满绑定 64 条时不退化为 64 次全网格遍历）；**并追加 `grid.getCraftingService().getCpus()` 枚举的合成 CPU 组**（CPU 多块组经 `CraftingSupport.cpuDeviceId` 取组标识，与卡上绑定交集合入同一 `inNetwork` 集——绑定的 CPU 在卡入槽后即等同「选中该 CPU 智能加速」）；注入「卡上且网络内且当前未被任何来源加速」的设备、按当前最高倍数记 `CONFIG_CARD` 来源；撤销「已是 CONFIG_CARD 来源但已不在卡上/网络内」的设备；变化才 `markTargetsDirty + saveChanges`。
  - `onHostRemoved`（宿主 `setRemoved` 调，仅服务端）：清空槽位内卡片绑定 + 经 `util/ConfigCardScanner` 扫描在线玩家全部物品槽清理绑定本机的卡（防「即插即用」配置指向已摧毁的加速器）。
  - `save/load`：库存 NBT 持久化。
- `blockentity/ConfigCardCleanup.java`（服务端「设备/合成 CPU 组被破坏」的即时清理协调器，监听 `BlockEvent.BreakEvent`、爆炸事件、方块实体 `setRemoved` 与 `ServerTickEvent.Post`）：
  - 普通设备（方块实体/部件）拆除：`recordRemoval` 捕获方块状态+网格 → 延迟 1 tick 确认「即时拆除」未被取消/替换 → 经 `util/ConfigCardScanner` 清理玩家背包与网络内加速器配置卡上的该设备绑定并精确撤销 `CONFIG_CARD` 登记。
  - **合成 CPU 组（AE2 原版 + AdvancedAE 大型 CPU 软兼容）**：成员块拆除经 `CraftingSupport.cpuGroupOf(be,true)` 快照整组成员坐标走 `CpuRemoval` 整组通道，等 `CPU_SETTLE_TICKS`(4) tick 让集群「整体解散+剩余重算再成型」结算完成后观察剩余成员真实成型状态：无残留或残留拆成多个独立组 → `ConfigCardData.removeCpuBinding` 删 CPU 条目连带裁剪几何；恰一组残留 → `replaceCpuBinding` 改写为新集群最小角标识与新几何（部分破坏仍成型的自适应，卡入槽即触发重注入）。裁决只依赖结构标识/坐标，不持有集群对象引用（集群销毁后不再可信）。
  - 自清理：卡槽内卡片经 `inventory.setItemDirect` 改写触发 `onHostInventoryChanged → syncConfigCardDevices()` 自动精确重同步；玩家背包内卡片在裁决时同步改写。

### 4.3 纯逻辑与配置域（可单测）

- `api/`：
  - `DeviceId`（record：`ResourceKey<Level> dimension` + `BlockPos pos` + `Direction side` + `DeviceKind kind`）：值类型身份，含三种编解码——`CODEC`（NBT）、`write`+`read`（网络/流）、`stableKey()`+`parse()`（GUI/动作载荷字符串）；工厂 `ofBlock/ofPart/ofCpu`。
  - `DeviceKind`：`BLOCK_ENTITY`（方块实体，标识=维度+自身坐标）/ `PART`（线缆部件，标识=维度+线缆坐标+朝向，同坐标不同朝向部件可区分）/ `CRAFTING_CPU`（多块结构，标识=维度+结构最小角坐标，玩家选中即开智能加速）。
  - `AccelSource`：`PLAYER`（玩家 GUI 显式设置）/`CONFIG_CARD`（配置卡注入），持久化来源标记，供精确撤销。
  - `IAccelerationSource`（加速源契约）：`dimension/origin/maxMultiplier/isActive/targets()/multiplierFor(id)/budget()/grid()/markTargetsDirty()`。**当前唯一实现方为 AE 加速器**（火把重写后不再实现本接口，见 4.2）。
  - `AccelerationTarget`（record：id + node + tickable）：`isDetached()`/`belongsTo(grid)` 内部把「节点销毁时 getGrid() 抛 ISE」转译为 null（安全护栏，勿改）。
  - `AccelerationResult`（record：hit/skippedSleeping/skippedInactive/skippedDetached/tickCalls/budgetExhausted + `NONE` + `didWork()`）。
  - `BudgetMeter`：每 tick 调用预算计数器（`UNLIMITED_METER`/`UNLIMITED`），无 MC 运行时依赖。
- `core/`：
  - `AccelerationEngine.pulse(source)`：AE 加速器侧的脉冲执行器（火把已独立实现，见 4.2）。流程：`!source.isActive()` 直接返回 `NONE` → `budget.resetTick()` → 逐目标依次判定：① `target.isDetached() || !target.belongsTo(expectedGrid)` → 计 skippedDetached、`markTargetsDirty`（下轮剔除）；② 节点未激活跳过；③ `multiplierFor(id)-1 <= 0` 跳过（倍率 1 即不加速；**先倍率后睡眠**）；④ `getTickingRequest(node).isSleeping()` 跳过（空闲设备催促无意义）；⑤ 预算耗尽 → 结束脉冲；⑥ `alertDevice` 催促后在**同一 tick 内**循环 `tickingRequest(node, 1)`（该逐次推进循环共用 `GridTickAccelerator.tick`），返回 `TickRateModulation.SLEEP` 立即 break（设备处理中睡眠后继续推进会越过其状态机边界）。**性能约束**：本方法位于每 tick 路径，禁止分配对象、字符串拼接、日志、遍历 `grid.getNodes()`。
  - `AdaptiveThrottle`（TPS 自适应节流，进程级单例 `INSTANCE`）：主类以 `ServerTickEvent.Pre/Post` 配对计时喂样本（不含补帧 sleep），EMA（α=0.25，时间常数约 4 tick）平滑后与 `adaptive.tightenMs`（默认 45，进入）/`adaptive.relaxMs`（默认 35，退出）双阈值滞回比较。**分级递降削峰**：收紧档位 `level>0` 时生效预算 = `floorCallsPerSource >> (level-1)`（每加深一档减半，最深档收敛到 1≈停加速），单 tick 仍超时则每 tick 加深一档、回落则逐档放松，避免临界负载全速/停转振荡；`level=0` 或 `adaptive.enabled=false` 时预算原样 = 静态配置（含 -1 不限）。关闭时事件计时直接旁路并清零全部状态。预算消费方：加速器 `budget()`（§4.2 上）与火把 `budget()`（§4.2 下），二者经 `SourceBudget` 持有器读取。纯函数 `advanceLevel/effectiveLimit/nextEma` 供单测。
  - `SourceBudget`：单加速源「每 tick 预算」持有器（`get()` 把静态配置经 `AdaptiveThrottle.adjust` 合成生效预算，仅在生效值变化时重建 `BudgetMeter`，平时零分配）；加速器与火把各持一个实例，共享同一套刷新语义。
  - `GridTickAccelerator`：网格 tick 黑盒加速的「逐次推进循环」原语（`tick(node, tickable, maxCalls)`，循环 `tickingRequest(node,1)`、`SLEEP` 提前 break、返回含睡眠那次的调用次数）。**不做**预算/调度/异常围栏（那些由调用方按各自语义处理）；`AccelerationEngine` 已复用，火把因需「逐次预算 + `sleepDevice` + 防御性 try/catch」而保留自身循环。
  - `TargetCache`：目标列表 + 置脏即重建 + 周期重建（间隔取 `cache.rebuildIntervalTicks` 当前值，方块每次创建/重开区块读取，默认 20 tick）；`resolve(Supplier)`。
  - `MultiplierCalculator`/`PowerModel`：公式纯函数，默认常量引用 `ConfigDefaults`；`MultiplierCalculator.compute` 中间量先判负再钳 `Integer.MAX_VALUE`（已修 long 溢出返回负数的 bug）。
  - `TargetRegistry`：加速器「谁被加速、多少倍、由谁设置」的唯一状态表，NBT 随档保存来源标记。语义（写代码勿破坏）：每条 `DeviceId` 单记录、`set(id, mult<=1, source)` 即移除该条；`set` 后写覆盖先写（玩家显式设置优先）；`clearBySource` 按来源精确撤销（取卡不误伤玩家勾选）；`version()` 供菜单缓存失效判断；`save/load` 遇旧键名/坏条目忽略（旧档断档，不崩溃）。
- `config/`：
  - `ConfigDefaults`：全部默认值集中地（单一事实来源，逻辑公式/测试也引用它，勿散落数值字面量）。
  - `ModConfig`：Server + Client 两段 Spec。Server 分组与键：`accelerator.baseMultiplier/cardMultipliers/maxMultiplierCap`（-1 不限）、`budget.tickCallsPerSource`（-1 不限）、`adaptive.enabled/floorCallsPerSource/tightenMs/relaxMs`（TPS 自适应节流，默认 true/256/45/35，收紧起点即 floor，超时自动逐档减半）、`power.perTick/perUpgradeCard/perAcceleratedDevice/bufferFraction`、`cache.rebuildIntervalTicks`、`menu.deviceListRefreshTicks`、`grid.acceleratableBlacklist`、`grid.craftingMachineExtraTypes`、`crafting.smartAccelerateEnabled/smartAccelerateScope`、`debug.enabled/sampleIntervalTicks`、`torcherino.maxSpeed/maxXzRange/maxYRange`（默认 4/8/4）。Client 段：`client.cacheFilteredList`、`client.renderBracketHighlight`。
  - `RuntimeConfig`：volatile 生效快照 + 类型表 `Class.forName` 解析（坏条目仅告警跳过，绝不崩溃）；**逻辑层唯一读取口**，初值 = ConfigDefaults 基线。

### 4.4 菜单、网格桥接与物品

- `menu/`：
  - `ModMenus.java`：把两菜单类中 `MenuTypeBuilder.create(...).buildUnregistered(...)` 预构建的 `TYPE` 放入注册表（仅创建未注册，避免重复注册）。
  - `AEAcceleratorMenu`：直接继承 AE2 `AEBaseMenu`（非 UpgradeableMenu）自主建槽。`setupUpgrades` 复用 AE 升级卡槽；`addSlot(new AppEngSlot(host.getConfigCardInventory(), 0), AE_CONFIG_CARD_SLOT)`——`AE_CONFIG_CARD_SLOT = SlotSemantics.register("ae_accelerator_config_card", false)`，位置由界面样式 JSON `slots` 段按 id 定位（x:174,y:5）；再 `createPlayerInventorySlots`。`@GuiSync(1) DeviceList devices`、`@GuiSync(2) int maxMultiplier`（每次广播刷新，非仅刷新周期）。
    - 客户端动作 `toggle_acceleration`/`set_accel_multiplier`（载荷 `DeviceTarget`/`MultiplierTarget` 普通类带无参构造器，GSON 序列化）：服务端处理器 `toggleAcceleration`/`setMultiplier` **必须校验**——`DeviceId.parse` 失败拒绝、`isDeviceInGrid`（普通设备按 `DeviceScanner` 谓词逐节点匹配 + 合成 CPU 经 `getCraftingService().getCpus()` 单独枚举，防止伪造载荷写入持久化状态）、倍数落 `[1, host.getAccelMultiplier()]`；动作后 `lastUpdate = refreshTicks - 1` 强制尽快重发设备列表。
    - `collectDevices(host)` 静态可复用：经 `host.grid()` 安全取网格；`IdentityHashMap<宿主,节点>` 去重（同宿主多节点保留活动者）；再按 `DeviceId` 去重（部件含朝向，同坐标不同部件各自保留）；追加合成 CPU 条目（`AEBlocks.CRAFTING_UNIT.stack()` 图标、`cpu.getName()` 或 lang 占位名、坐标取 `cluster.getBoundsMin()`）；按名称再按与加速器距离平方排序。
    - `broadcastChanges` 每次广播刷新 maxMultiplier，按 `menu.deviceListRefreshTicks` 节流重采集。**设备列表缓存**：`getDeviceList()` 先比较 `host.targetRegistryVersion()` 与 `topologySignature(host)`（宿主 identity + 激活态 + CPU 对象折叠的零分配 long 签名，仅做失效判断），未变直接复用 `cachedDevices`——稳态（无人插拔/无加速状态变更/无 CPU 结构变化）下零重建，**勿改为无条件每周期全量重建**。
  - `AETorcherinoMenu`：`@GuiSync(1..7)` xRange/zRange/yRange/speed/maxSpeed/maxXzRange/maxYRange（上限字段初值取配置默认作客户端兜底，服务端每次 `broadcastChanges` 重拉刷新）；4 个客户端动作（`ValueTarget` 载荷）服务端 `applySetting` 先校验 `host.isRemoved() || host.getLevel()==null`，数值钳制由方块实体 setter 完成；**无任何槽位，必须覆写 `quickMoveStack` 返回空物品**（父类按索引取 slots 必越界，删不得）。
  - `DeviceList`/`DeviceEntry`：`PacketWritable` record；`DeviceEntry(id/name/pos/active/accelerated/multiplier/icon/craftingCpu)`。`@GuiSync` 字段类型必须实现 `PacketWritable`（AE2 平台契约）。
- `network/DeviceScanner`：集中「哪些节点可加速」判定与设备身份解析，供目标缓存重建、配置卡注入、菜单采集、卡片右键绑定共用（勿在调用方各写一份）：
  - `isAcceleratableMachine(owner)`：非空且不在 `RuntimeConfig.acceleratableBlacklist()`（解析后的 Class 集合 noneMatch isInstance）。
  - `isAcceleratableNode(node, self)`：宿主非空非自身 + 可加速机器 + 可解析坐标，且加速载体之一——注册 `IGridTickable` 服务、或宿主方块实体具有服务端原版 tick（`EntityBlock.getTicker` 非空，用于「接了 AE 网络但加工走原版 tick」的机器）；**不判 isActive**（菜单要展示非活动设备，激活判定留给脉冲）。配套 `isVanillaTicking(be)` / `vanillaTicker(be)` 解析原版 tick。
  - `findAcceleratableNode(be, self, preferSide)`：Level 层 `getCapability(IN_WORLD_GRID_NODE_HOST, pos, null)` 拿宿主；宿主为线缆等多部件宿主（`IPartHost`）时**逐侧枚举部件、以部件自身 `getGridNode()` 判定**——AE2 线缆宿主按方向查询只回线缆自身节点、不暴露部件本体节点，输入/输出总线、破坏面板等部件型可加速设备必须走部件枚举才能命中；多个候选时优先 `preferSide`（玩家点击面）。非部件宿主走原六向遍历取第一个可加速节点。
  - `deviceIdOf(owner)`：经 `network/GridOwner.idOf` 委托——方块实体 → `DeviceId.ofBlock`；AE2 部件 → `DeviceId.ofPart`（所在线缆坐标 + 朝向）；标识带维度（防跨维度同坐标误判，含配置卡绑定）。
  - `resolveDevicePos` 私有实现为 `GridOwner.posOf` 薄委托（不对外暴露）。
- `network/GridOwner`：网格宿主（方块实体 / AE2 部件）的「世界坐标 / 世界 / 设备标识」统一解析——`posOf/levelOf/idOf`（方块实体用自身；部件用其线缆宿主坐标，`idOf` 另带朝向）。收敛了此前在 `DeviceScanner`、`CraftingSupport.levelPosOf`、`AEAcceleratorMenu.toDeviceEntry` 重复出现的「`instanceof BlockEntity / AEBasePart`」分支。
- `network/crafting/CraftingSupport`：合成体系辅助，**全项目唯一解析「大型合成 CPU 结构」坐标的地方**（其余一律面向统一的 `CpuGroup` 结构视图，不感知实现）：大型 CPU 有两类来源——AE2 原版合成 CPU（成员方块 `CraftingBlockEntity`、集群 `CraftingCPUCluster`，同时实现 `IAECluster`/`ICraftingCPU`）与 **AdvancedAE 大型 CPU（可选附属，软反射兼容）**（成员方块 `AdvCraftingBlockEntity`、集群 `AdvCraftingCPUCluster` 同样实现 AE2 公共 `IAECluster`，AE2 网格经 `getCpus()` 以 `AdvCraftingCPU` 条目暴露——每条是其巨型集群的「单个任务/剩余容量」视图，同一集群多条、结构标识相同）；本文件对 AdvancedAE **零编译依赖**：`Class.forName(name,false,本类加载器)` 探测类名 + 反射其私有字段 `cluster`（条目→集群）与 `isFormed/getCluster/isActive` 方法，任一步失败视为未安装、行为与未装 AdvancedAE 完全一致；对集群只做 `IAECluster` 层面的坐标读取，不触碰其合成内部逻辑。公开 API：`cpuGroupOf(dim, cpu)`（网格条目侧结构视图：AE2 走 `instanceof CraftingCPUCluster`、Adv 走反射字段，均转 `IAECluster` 读 `getBoundsMin/Max`）、`cpuGroupOf(be, includeMembers)`（方块实体侧，仅服务端返回所属集群；`includeMembers=true` 时快照整组成员坐标，供拆除裁决观察集，常规解析传 false 免整组遍历）、`cpuDeviceId(dim, cpu)`（归一到结构最小角的稳定标识，Adv 同集群多条网格条目天然解析出同 id，与登记表/绑定卡「整组一个标记」一致）、`isCpuGroupMember(be)`（是否为已成形的合成 CPU 组成员，AE2/Adv 客户端方块状态与服务端集群判定两端一致）、`isCpuActive(cpu)`（条目在线状态，供加速器界面展示）、`isCraftingMachineType(owner)` 三级判定——实现 `appeng.api.implementations.blockentities.ICraftingMachine` → 宿主所在块 `AECapabilities.CRAFTING_MACHINE` 能力（能力查询坐标由宿主类型推导：方块实体用自身坐标、部件用其线缆宿主坐标，兼容第三方部件型合成机）→ `grid.craftingMachineExtraTypes` 类型表兜底。
- `item/`：
  - `ModItems.java`：8 个物品（三个方块 BlockItem + 配置卡 + 3 张升级卡，卡构造传系数 2/4/8）。
  - `ModDataComponents.java`：注册 `CONFIG_CARD_DATA`（`ConfigCardData`，`persistent(CODEC).networkSynchronized(STREAM_CODEC)`）。
  - `ConfigCardData.java`：record(accelerator, devices, cpuBounds)，配置卡绑定数据的**纯数据契约**（读写静态方法集中于此，含 `MAX_BOUND_DEVICES=64`、`bindOrUnbindAccelerator`、`toggleBoundDevice`、`toggleBoundCpu`、`cpuBoundsMaxOf`、`isBoundTo` 等）。`cpuBounds` 为已绑定合成 CPU 组的外包围盒几何（`CpuBounds(cpu, max)`，最小角由 CPU 设备标识坐标承载），供高亮 pass 按 CPU 组外围画线框；紧凑构造器统一做「去重 + 稳定排序 + 几何/设备一致性裁剪」，`CpuBounds.CODEC` 使 NBT 可持久化。客户端渲染与服务端逻辑都只依赖它，不触碰物品类。
  - `ConfigCardEvents.java`（`@EventBusSubscriber`，无客户端限定，双端同逻辑）：`UseItemOnBlockEvent` 的 `ITEM_BEFORE_BLOCK` 阶段拦截手持配置卡交互——Shift+右键本模组加速器绑/解绑；右键可加速设备（方块实体或线缆上的部件，复用 `findAcceleratableNode(be, self, event.getFace())`）绑/解绑设备；右键已成形的合成 CPU 组（`CraftingSupport.isCpuGroupMember`，多块连成的结构，AE2 原版与 AdvancedAE 大型 CPU 均兼容）按**整组**绑/解绑（经 `cpuGroupOf(be,false)` 取统一结构视图 `CpuGroup`、组最小角为标识、组最大角随卡记录，客户端经方块状态 FORMED 判定与服务端结论一致）；未绑定加速器的卡右键设备提示并拦截（`not_bound_hint`）防误开设备界面；非 Shift 右键加速器放行（打开 GUI）。客户端同样 `cancelWithResult(CONSUME)` 保持结果一致、仅服务端写数据；提示走 lang + `DebugLog.info`。
  - `AcceleratorUpgradeCardItem`：**必须继承 AE2 `UpgradeCardItem`**（AE2 升级卡插槽 mayPlace 硬编码 `instanceof UpgradeCardItem`，不继承放不进插槽），tooltip 追加放大效果。`AcceleratorConfigCardItem`：瘦身壳（注册 + tooltip 显示绑定状态）。
  - `ModCreativeTabs.java`：`TORCHERINO_AE_TAB` 创造栏（6 物品）。

### 4.5 客户端界面与渲染

- `client/ConfigCardModelRegistration.java`（`@EventBusSubscriber(bus = MOD, value = CLIENT)`，`FMLClientSetupEvent` + `enqueueWork`）：注册物品模型属性 `torcherino_ae_mod:bound`——卡片绑定过加速器返回 1.0F（`getBoundAccelerator(stack) != null`），驱动 `models/item/accelerator_config_card.json` 的 overrides 切换绑定态贴图 `accelerator_config_card_work`；绑定数据在 Data Component，客户端直读，无需发包。
- `client/screen/`：`AEAcceleratorScreen`（设备列表 + 弹窗 + 升级面板）、`AETorcherinoScreen`（4 个滑块，范围上限来自菜单 `@GuiSync` 字段而非方块实体静态常量）。
- `client/widget/`：`DeviceListWidget`（行过滤带缓存、行尾两态状态图标 Blitter 构造期预建、悬浮高亮、左键切换/右键弹窗）、`DeviceConfigPopup`、`SettingSliderWidget`（max 经 `Function<菜单,Integer>` 每 tick 从 `@GuiSync` 上限字段刷新并钳制当前值）。
- `client/render/`（配置卡手持高亮管线，移植自 RTSBuilding RenderPass/Pipeline 的最小实现）：
  - `RenderPass` 接口（`shouldRender(mc)` 默认 true / `render(mc, alloc, pose, partialTick, frameIndex)` / `requiredBuffers()` 位标志 4=角括号 8=无深度）+ `BufferAllocator` record（`brackets`、`noDepth` 两个 `VertexConsumer` 通道）。
  - `ConfigCardRenderPipeline`：持所有 pass（构造时 `registerPass(new ConfigCardHighlightPass())`），自带两种 RenderType——`BRACKET_QUADS`（LEQUAL 深度、半透明、不剔除背面）与 `NO_DEPTH_QUADS`（NO_DEPTH_TEST）；每个通道用 `ByteBufferBuilder`（初始 1024KB，超量自动扩容）+ `BufferBuilder`；`onRenderFrame` 逐帧「重置各通道缓冲 → 按注册顺序逐个 pass → 按通道 `RenderType.draw(MeshData)` flush」。**新增世界内渲染内容时在此注册 pass**。
  - `ConfigCardRenderHandler`（`@EventBusSubscriber(value = CLIENT)`）：在 `RenderLevelStageEvent` 的 `AFTER_TRANSLUCENT_BLOCKS` 阶段（世界几何完成、深度缓冲含世界内容）以相机位置平移 PoseStack 原点，驱动单例 `PIPELINE`（pass 无状态跨帧复用）。
  - `render/pass/ConfigCardHighlightPass`：手持配置卡（主手优先副手）时，绑定的加速器画**蓝色** `0xFF4D99FF` 角括号、绑定的设备逐台画**绿色** `0xFF3ADB3A` 角括号；方块实体按整格框；**线缆部件**（`DeviceKind.PART`）经 `partBoxInWorld` 按**实际碰撞箱**单独画框——`IPartHost.getPart(side)` 取绑定朝向的部件，复用 `BusCollisionHelper`（以附着面为局部 z 轴）把其 1/16 局部盒换算成线缆格内世界盒、取并集再平移到坐标（与 AE2 选择框同口径），取不到部件/无几何退化为整格；合成 CPU 类绑定目标按卡上 `cpuBounds` 记录的**整组外包围盒**画线框（`drawBracket(min,max)`，无几何记录/包围盒区块未加载退化为单格，标记合并：一组只出一个框）；`INFLATE=0.03` 向外膨胀；同 AABB 深浅双通道（brackets alpha 0.9 正常遮挡 + noDepth `DEFAULT_NO_DEPTH_ALPHA=0.10` 穿透隐约可见，`depthTestEnabled` 静态开关可关穿透作调试）；门控：配置 `client.renderBracketHighlight`（关闭连 render 阶段都跳过）+ `mc.screen == null`（开 GUI 时停止世界内高亮防穿透干扰）+ 只画当前维度目标 + `mc.level.hasChunkAt`。
  - `render/util/CornerBracketRenderer`：把 AABB 轮廓渲染成 12 条带厚度的「块状」粗线段（顶环 + 底环 + 4 棱），每段由两端方帽 + 4 侧面共 6 个四边形组成（POSITION_COLOR）；厚度随距离自适应（基准 0.04 格，16 格内不变、超出线性加粗，带最小系数下限）。纯几何顶点计算下沉为公开静态 `computeSegmentQuads(...)`（退化返回 null），渲染路径只消费顶点数组，有单测覆盖（见 §10）。
  - `client/render/` 另有 `AEAcceleratorRenderer`（方块实体渲染器：接电工作叠加全亮度发光带 `LIGHTS_MODEL` + 流光粒子，粒子节奏在渲染器内维护，不污染方块实体）。
- 其它：`client/ModScreens.java`（自定义样式加载器 `loadStyleDoc`：仿 AE2 StyleManager 实现 includes 递归合并与段落按键合并，支持跨命名空间 include，例如样式 JSON include `ae2:screens/common/palette.json` 使调色板与 AE2 主题包联动；注意本类在 `client` 包，非横切配置层）、`client/GuiTheme.java`（GUI 文字自动对比度工具：按实际背景贴图采样明暗并做对比色调整，见下）、`client/AEGuiMetrics.java`（界面度量常量集中地，有单测固化布局不变量，见 §10）。
- **GUI 文字在暗色 UI 材质包下的兼容（双层机制，勿单删任一）**：
  - 数据联动：两个样式 JSON 以 `"includes": ["ae2:screens/common/palette.json"]` 继承 AE2 调色板（经 `ModScreens` 跨命名空间 includes 合并实现），AE2 主题包改写 palette 时界面文字基准色随之变化。
  - 运行时兜底：`GuiTheme` 直接采样<b>实际生效</b>的背景贴图（`NativeImage` 读资源文件、按区域求平均亮度、进程级缓存，`invalidateCache()` 供资源热重载清理；读取失败按亮背景安全降级）；`ensureContrast(base, darkBg)` 对调色板基准色向白提亮或向黑压暗（纯计算、无色值硬编码；默认亮底 + 深色调色板下返回原色，观感零变化）。
  - 已接入点：两个 Screen 的标题与物品栏标题（样式 text 条目 `dialog_title`/`player_inventory_title` 在构造器 `setTextHidden` 后由 `drawFG` 自绘，坐标仍从样式 JSON resolve，见 `drawStyledText`）、`DeviceListWidget` 行文字（分别采样「普通行条 / 悬浮·加速高亮条」两种底色）、`DeviceConfigPopup`（面板底色）、`SettingSliderWidget`（火把主背景 `ae2:guis/background.png`）。**新增自定义自绘界面文字时务必同样走「取调色板基准色 → GuiTheme.ensureContrast → 绘制」流程**。

### 4.6 资源与数据（手写，datagen 未启用）

- `lang/en_us.json` + `lang/zh_cn.json`：全部文案。
- `blockstates/` + `models/block/`：`ae_accelerator.json` 按 online×working 组合切基础 / `on` / `inactive` 三种模型；`ae_accelerator_lights.json` 发光带模型单独存在单独注册；`ae_torcherino.json` 按 FACING 六向；`models/item/` 含配置卡 `accelerator_config_card.json`（overrides 引用绑定态贴图）。
- `screens/ae_accelerator.json` + `ae_torcherino.json`：自定义界面样式（**不含 palette 段**——经顶层 `includes: ["ae2:screens/common/palette.json"]` 继承 AE2 调色板，改 palette 前务必确认 include 引用仍指向 AE2 的对应资源；slots 按 id 定位槽位等）；背景复用 AE2 `guis/background.png` 生成纯背景。
- `textures/`：block/gui/item 三类贴图。
- `data/torcherino_ae_mod/`：三个方块的 loot_table（掉落自身）+ 8 份合成配方（三个方块 + 配置卡 + 三升级卡；两个分级火把为 9@基础→1@I、9@I→1@II）。
- `torcherino_ae_mod.mixins.json`：当前无任何 mixin。
- `src/main/templates/META-INF/neoforge.mods.toml`：占位符模板，声明 ae2/guideme required 依赖；已填 `displayURL`（GitHub 仓库）与 `authors`（Tian_Hai, 五世桃花亭）。
- `src/generated/resources/` 尚未建立（datagen 未启用，模型/blockstates/lang 均手写）。根目录 `_img/` 为开发期贴图分析产物，非模组资源。

## 5. 已知注意事项 / 待办

- **AE2 `GridNode.getGrid()` 在「节点未入网/已销毁」时抛 `IllegalStateException` 而非返回 null**——所有读网格处必须经 `util/AeGrid.gridOf(node)` 安全转译（`AEAcceleratorBlockEntity.grid()`、`AccelerationTarget.gridOf`、火把 `TorchAccelerator`、`ConfigCardCleanup` 等均委托），调用方只判空；`setOnline`/诊断勿裸调 `getMainNode().isPowered()`（内部走 getGrid()）。此护栏勿改回直连。
- `tickingRequest`（返回 `TickRateModulation` 枚举）与 `getTickingRequest`（返回 `TickingRequest`，才有 `isSleeping()`）**不是一回事，不可混用**。加速脉冲循环内每次检查返回值，`SLEEP` 立即 break（设备空闲后继续推进会越过其状态机边界）。
- 加速载体分两类：AE2 网格 tick（`IGridTickable`）经网格 tick 管理器催促；或「接了 AE 网络但加工走原版 `BlockEntity` tick」的机器，由 `AccelerationEngine` 按倍率反复执行其原版 tick（ticker 经 `EntityBlock.getTicker` 解析，见 `DeviceScanner`）。仍**不会加速**存储总线、P2P 隧道、能量元件等基础设施（刻意排除，谓词在 `DeviceScanner` + `grid.acceleratableBlacklist`）。
- 客户端 `commonTick` 提前 return 是**刻意设计**（网络/能量/加速只在服务端；客户端 online/working 全由 writeToStream/readFromStream 同步），勿删。
- 诊断日志一律走 `util/DebugLog` 门面（默认关闭，开关接 `debug.enabled`，热重载生效；`warn` 不受开关控制，用于存档损坏等必须告警场景）。禁止在每 tick/交互路径直接写 `LOGGER.info`；高频路径先 `isEnabled()` 守卫、开销大用 `info(Supplier)` 懒构造。
- 客户端动作服务端处理器必须校验载荷（目标真在网格内、值域不越界），不可采信客户端标识写持久化状态；GSON 载荷类须为带无参构造器的普通类（`DeviceTarget`/`MultiplierTarget`/`ValueTarget`），设备标识走 `DeviceId.stableKey()` 字符串 + 服务端 `DeviceId.parse`（非法返回 null 即拒绝）。
- `TargetRegistry` 语义（勿破坏）：单记录、后写来源覆盖、`clearBySource` 精确撤销、`set(mult<=1)` 等价移除。`AccelerationEngine` 性能约束（不分配/不拼接/不遍历 getNodes）与「先倍率后睡眠」判定勿改。
- 配置纪律：逻辑层一律经 `config/RuntimeConfig` 读生效快照；改默认值先改 `ConfigDefaults`；类型表字符串解析失败只告警跳过。
- 菜单设备列表缓存由 `targetRegistryVersion()` + `topologySignature` 双条件驱动，**勿改为无条件每刷新周期全量重建**（稳态零重建是刻意设计）。
- `AcceleratorUpgradeCardItem` 必须继承 AE2 `UpgradeCardItem`（插槽 mayPlace instanceof 判定）；`AETorcherinoMenu` 无槽位必须覆写 `quickMoveStack`。
- ticker 注入纪律：加速器在 `ModBlockEntities` 注册阶段注入（clientTick/serverTick 分别强转自 AE2 两个 Ticking 接口）；火把由 `AETorcherinoBlock.getTicker` 提供（含类型校验）。两处方式不同，**勿互换**；`commonSetup` 勿再次 `setBlockEntity`。
- 未装升级卡默认最高 4x（基础倍率 4），能耗按卡数线性叠加与倍率无关（刻意脱钩），能量缓冲 `power.bufferFraction`（默认 0.9）防浮点抖动停机。
- `src/generated/resources/` 数据生成目录尚未建立（datagen 未启用）。

## 6. 构建与运行命令

- **环境**：`JAVA_HOME` 需显式指向 Java 21 JDK（系统 PATH 未安装）。本机常位于 `C:\Users\<用户>\.gradle\jdks\eclipse_adoptium-21-amd64-windows.2`，构建前用 `$env:JAVA_HOME=<路径>` 设置即可。
- 运行客户端：`gradlew runClient`；运行服务端：`gradlew runServer`；数据生成：`gradlew runData`（当前无 datagen 类）；游戏内测：`gradlew runGameTestServer`；构建：`gradlew build`；单测：`gradlew test`。
- 生成 mod 元数据：`generateModMetadata`（在 `src/main/templates/META-INF/neoforge.mods.toml` 基础上展开占位符；每次 IDE 重载自动执行）。

## 7. 其他约定

- 注释风格：每个代码模块/类都要有简洁中文注释说明职责与用途；代码注释用中文。
- 实现新逻辑前先检索项目，优先复用已有逻辑；公共判定（可加速谓词、设备身份解析、CPU 内部类隔离）集中在 `network/`。
- 一律使用 `gradlew` 而非裸 `gradle`。
- 需查阅 AE2 内部实现时，从 Gradle 缓存中的 AE2 依赖 jar 反编译（项目根目录**没有** `appeng/` 目录，旧记录的该路径已不存在）。

## 8. 架构演进记录（已完成阶段，均为行为零变更 / 纯风险消除）

- **P0 止血**：`util/DebugLog` 门面（替换散落的每 tick/交互路径 `LOGGER.info`）；配置卡同步改单次网格遍历求交集（O(绑定数×节点数) → O(节点数)）；加速脉冲补 `SLEEP` 返回值早退；`AETorcherinoBlock.getTicker` 补类型校验；客户端动作补服务端校验。
- **P1 抽象**：引入 `api`（`DeviceId` 值类型取代字符串主键，修复跨维度同坐标误判；`AccelSource`、`IAccelerationSource`、`AccelerationTarget/Result`、`BudgetMeter`）+ `core`（`AccelerationEngine` 唯一脉冲执行器、`TargetCache`、`MultiplierCalculator`/`PowerModel` 公式下沉、`TargetRegistry` 单一状态表 + 来源撤销 + 新 NBT 格式，旧档断档）。实施偏差：`BudgetMeter` 落 `api`（避免 api 反向依赖 core）；未立 `IAccelerationPolicy`（策略并入 `multiplierFor`）；引擎按「先倍率后睡眠」判定。
- **P2 分层**：`menu` 包纯净（screen/widget 迁 `client/`）；配置卡职责拆 `blockentity/ConfigCardBinding`；`common/AE2GridSupport` 拆并为 `network/DeviceScanner` + `network/crafting/CraftingSupport`（common 包清空）；渲染反向依赖消除（`ConfigCardData` 数据契约 + 物品壳瘦身）。
- **P3 配置化**：`config` 三件套落地，全部数值配置化（默认值 = 现网基线）；黑名单/合成机器兜底类型改字符串 FQCN（坏条目告警跳过）；火把 GUI 上限经 `@GuiSync` 下发；UI 缓存优化三处落地（行过滤缓存、行尾图标 Blitter 预建、菜单设备列表登记版本 + 拓扑签名缓存）。
- **P4 可测性收尾**：`CornerBracketRenderer` 纯几何抽取（`computeSegmentQuads`）+ `AEGuiMetrics` 布局不变量固化，各带单测。取舍：`DeviceEntry`/`DeviceList` 拆纯 POJO + 适配器**不执行**——`@GuiSync` 字段类型须实现 `PacketWritable`（AE2 平台契约），拆分仍无法纯 JVM 直测网络包往返，净收益有限。
- **后续演进（当前代码已含，待提交）**：批量清理代码注释与文档中对已删除 `docs/architecture.md` 的交叉引用；`neoforge.mods.toml` 模板补齐 `displayURL`/`authors`；配置卡高亮重构为「`RenderPass` 接口 + `ConfigCardRenderPipeline` + `ConfigCardRenderHandler`（单例、AFTER_TRANSLUCENT_BLOCKS 帧驱动）+ `render/pass/ConfigCardHighlightPass` + `render/util/CornerBracketRenderer`」管线；绑定态贴图切换迁至独立 `client/ConfigCardModelRegistration`（`torcherino_ae_mod:bound` 属性）；注册容器补全（`ModItems`/`ModDataComponents`/`ConfigCardEvents`/`ModMenus` 显式化，DeferredRegister 由 4 个增到 6 个）。
- **P5 公共件收敛（行为零变更）**：AE2 网格安全取值集中到 `util/AeGrid`（原 4 处内联 try/catch 统一委托）；预算「限额变化才重建」的持有器 `core/SourceBudget`；网格 tick 黑盒「逐次推进+ SLEEP 早退」原语 `core/GridTickAccelerator`（引擎复用，火把因其预算粒度/sleepDevice/异常围栏差异保留自身循环）；宿主（方块实体/部件）→ 坐标/世界/设备标识统一解析 `network/GridOwner`；在线玩家背包配置卡遍历 `util/ConfigCardScanner`；火把目标扫描与三条加速路径拆为 `blockentity/TorchTargetScanner`/`TorchAccelerator`；删除孤儿接口 `api/AccelerationShortcut`（方案 C 白盒快路径已回退、无使用方）。

## 9. 单元测试清单（`src/test/java`，共 11 个，`gradlew test` 全绿）

| 测试类 | 包 | 覆盖对象 |
|---|---|---|
| `DeviceIdTest` | api | 三种编解码往返、stableKey/parse、ofBlock/ofPart/ofCpu |
| `BudgetMeterTest` | api | 预算计数/重置/UNLIMITED |
| `MultiplierCalculatorTest` | core | 复合累乘公式、上限钳制、long 溢出不返负 |
| `PowerModelTest` | core | 能耗公式、缓冲判定 |
| `TargetCacheTest` | core | 周期重建、置脏即重建 |
| `TargetRegistryTest` | core | 单记录/覆盖/来源撤销/NBT 往返 |
| `AdaptiveThrottleTest` | core | EMA 收敛、分级档位推进、生效预算合成 |
| `GridTickAcceleratorTest` | core | 黑盒推进循环（全额调用/SLEEP 早退/零调用/空参防御/不解析节点字段） |
| `ConfigCardDataTest` | item | 数据契约读写、MAX_BOUND_DEVICES、绑/解绑、CPU 组外包围盒几何（记录/裁剪/编解码） |
| `AEGuiMetricsTest` | client | 布局不变量（图标竖排相接、行高、轨道居中） |
| `CornerBracketRendererTest` | client/render/util | 轴向方帽尺寸/斜线延长/退化/面积守恒 |
