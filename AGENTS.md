# 项目记忆文档（MEMORY）

每次调用前必须通读本文件并严格遵守。

## 0. 模组定位

- 这是一个 **Applied Energistics 2（AE2）附属模组**：添加一台「AE加速器」（AE Accelerator）方块机器。
- 玩法：将 AE2 的「速度升级卡」插入加速器，加速器接入 AE 网络后，可在其 GUI 中勾选网络内的 AE2 机器进行加速（每台设备可独立调节加速倍数）；GUI 还会列出网络的合成 CPU，选中后可开启「智能加速」，在 CPU 合成期间联动加速参与合成的机器。
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
  - 单元测试：JUnit 5（`src/test` 目录存在但当前无测试类）。

## 4. 当前实际代码结构（骨架）

> 说明：模组第一个方块「AE加速器」已完整实现：AE2 机器（接入网络 + AE 能量供电 + 升级卡插槽 + 自定义 GUI 设备列表 + 自定义渲染特效）。

### 4.1 主入口

- `com/tianhai/torcherino_ae/Torcherinoaemod.java`
  - `@Mod(MOD_ID)` 注解，`MOD_ID` / `LOGGER` 常量。
  - 构造器注册 `ModBlocks/ModItems/ModBlockEntities/ModCreativeTabs/ModMenus` 五个 DeferredRegister。
  - `registerCapabilities(RegisterCapabilitiesEvent)`：为方块实体注册 `AECapabilities.IN_WORLD_GRID_NODE_HOST` 能力——**AE2 只为其自身方块实体注册该能力，第三方方块必须自行注册否则无法接线**。
  - `commonSetup`：`Upgrades.add(方块, AEItems.SPEED_CARD, 4)` 声明支持的升级卡（仅 AE2 原版速度升级卡）；`AEBaseBlockEntity.registerBlockEntityItem(...)` 登记方块实体的代表物品。
  - **注意**：`commonSetup` 中不再调用 `block.setBlockEntity(...)`。ticker 注入改到 `ModBlockEntities` 的注册阶段（见 4.3），因为在 commonSetup 里手动注入时 `AEBaseEntityBlock.setBlockEntity(class, type, clientTicker, serverTicker)` 四参签名易把 serverTicker 误传到 clientTicker 位置导致服务端 ticker 为 null、加速永不生效。

### 4.2 方块

- `block/ModBlocks.java`：`DeferredRegister.createBlocks`，注册 `AE_ACCELERATOR`（材质用 `AEBaseBlock.metalProps()`）。
- `block/AEAcceleratorBlock.java`（继承 `AEBaseEntityBlock<AEAcceleratorBlockEntity>`）
  - 方块状态属性：`ONLINE`（`online`，是否接入网络）、`WORKING`（`working`，是否工作）+ 原版 `HORIZONTAL_FACING`。
  - `getOrientationStrategy()` 返回 `OrientationStrategies.horizontalFacing()`（东南西北朝向 + 扳手旋转）。
  - `updateBlockStateFromBlockEntity` 依据方块实体的 `isOnline()/isWorking()` 更新方块状态，驱动客户端切换 基础 / `on` / `inactive` 三种模型（见 blockstates）。
  - `useWithoutItem` / `useItemOn` 右键打开菜单（`MenuOpener.open` + `MenuLocators.forBlockEntity`）。
  - `getOcclusionShape` 返回 `Shapes.empty()`：模型四周带镂空框架，若不关闭遮挡剔除，相邻方块的面会被剔除导致穿透渲染。

### 4.3 方块实体

- `blockentity/ModBlockEntities.java`
  - 注册 `AE_ACCELERATOR` 时**必须手动注入 ticker**：`ModBlocks.AE_ACCELERATOR.get().setBlockEntity(AEAcceleratorBlockEntity.class, type, clientTick, serverTick)`，分别委托给 `ClientTickingBlockEntity.clientTick()` / `ServerTickingBlockEntity.serverTick()`。不注入则原版区块 tick 循环不会调用 `commonTick()`，加速与 working 状态都不更新。
  - 注册时打日志 `[INIT] AE 加速器 ticker 已注入` 便于确认。
- `blockentity/AEAcceleratorBlockEntity.java`（继承 `AENetworkedPoweredBlockEntity`，实现 `IUpgradeableObject` + `CommonTickingBlockEntity`）
  - **接入网络**：`getMainNode()`；**供电**：`grid.getEnergyService().extractAEPower(needed, MODULATE, ONE)`。
  - 常量：`UPGRADE_SLOTS = 4`；**`BASE_ACCEL_MULTIPLIER = 100`（当前仅用于测试，正式发布前必须改回 4）**；`ACCEL_PER_SPEED_CARD = 2`；`POWER_PER_TICK = 1.0`；每台被加速设备额外 `0.5 AE/t`。
  - `commonTick()`：**客户端直接 return**（客户端 `getGrid()` 恒为 null，若走到下方逻辑会把同步来的 working 覆盖回 false，导致模型卡在「未工作」变体）；服务端判断 `getMainNode().isActive()` → 提能量 → `setWorking(...)` → 调用 `runAccelerationPulse`。工作逻辑已合并为单段（每 20 tick 的诊断日志只是叠加观察，不再复制一份逻辑）。
  - **加速原理**（`runAccelerationPulse`）：遍历网格节点，过滤出「激活 + 实现 `IGridTickable` + 属于可加速机器 + 已被玩家选中」的设备，先 `ITickManager.alertDevice(node)` 催促，再在单个游戏 tick 内额外多次调用 `tickable.tickingRequest(node, 1)`（次数 = 设备独立倍数 - 1），使设备内部工作进度成倍推进。
  - 目标缓存：`cachedTargets`（被选中设备）与 `cachedCpuTargets`（智能联动目标）两个 `List<AccelTarget>`，由 `rebuildTargetCache` 周期性（`CACHE_REBUILD_INTERVAL = 20` tick + 选中集合变化/节点失效时）重建，避免每 tick 全网格扫描。
  - **设备筛选与坐标解析已抽取到 `common/AE2GridSupport.java`（见 4.8）**：`isAcceleratableNode` 等谓词供加速脉冲与菜单列表采集两处复用，避免重复实现；黑名单（`StorageBusPart`、`P2PTunnelPart`、`EnergyCellBlockEntity` 等基础设施）集中管理。
  - **每设备独立加速倍数**：设备身份用**稳定字符串标识**（`AE2GridSupport.deviceIdOf`：方块实体用坐标，部件用「坐标|朝向」，从而区分同一坐标上的多个部件）：`deviceMultipliers`（`Map<String, Integer>`）+ `acceleratedDevices`（`Set<String>`），随 NBT 持久化（`saveAdditional`/`loadTag` 用 `ListTag<String>`，兼容旧 `long[]` 坐标存档，重启保留）；`getDeviceMultiplier` 默认返回最高倍数，`setDeviceMultiplier`（≤1 视为取消加速）/`toggleAcceleratedDevice` 由菜单服务端动作处理器调用。
  - 状态同步：`online` 由 `onMainNodeStateChanged` 权威更新（`getMainNode().getGrid() != null && isOnline()`，避免在 commonTick 里算导致 UI 恒显示未连接）；`working` 由 `commonTick` 更新；两者经 `writeToStream`/`readFromStream` 同步客户端并 `markForUpdate` 驱动模型切换。
  - 升级卡库存：`UpgradeInventories.forMachine(方块, 4, listener)`，`getInternalInventory()` / `getUpgrades()` 返回同一库存，变化时 `saveChanges()` + `markForUpdate()`。

### 4.4 菜单与服务端数据

- `menu/ModMenus.java`：注册 `AE_ACCELERATOR` 菜单类型（`AEAcceleratorMenu.TYPE`）。
- `menu/AEAcceleratorMenu.java`（**继承 `AEBaseMenu` 自主创建**，非 AE2 通用 UpgradeableMenu）
  - `TYPE` 用 `MenuTypeBuilder.create(...).buildUnregistered(...)` 创建（仅创建不注册），再放入注册表。
  - 构造器：`setupUpgrades(host.getUpgrades())` + `createPlayerInventorySlots(playerInventory)`；注册两个客户端动作：`toggle_acceleration`（`DeviceTarget` 载荷）、`set_accel_multiplier`（`MultiplierTarget` 载荷，坐标+倍数）。
  - `@GuiSync(1) public DeviceList devices`：服务端采集的网格设备列表，经网络包同步到客户端。
  - `collectDevices(host)`（静态，可复用）：遍历网格节点，用 `IdentityHashMap` 按宿主对象去重（同宿主导出多节点时优先保留活动节点），过滤「实现 `IGridTickable` + 可加速机器 + 非自身」，**再按设备标识去重**（设备标识含部件朝向，因此同一坐标上的多个部件会被保留为不同设备），转成 `DeviceEntry`（图标 = 方块/部件的 ItemStack，部件坐标取所在线缆）；随后经 `collectCpus` 追加网格中的合成 CPU 条目（见 4.9），再排序：先名称再距离。
  - `broadcastChanges()`：服务端每 20 tick 重新采集一次设备列表（节流）。
  - `DeviceTarget` / `MultiplierTarget` 用**带无参构造器的普通类**而非 record，保证 GSON 可靠序列化/反序列化。
- `menu/DeviceList.java` / `menu/DeviceEntry.java`：纯数据载体 record，实现 `PacketWritable`，手写 `writeToPacket` / 包读取构造器（Component 用 `ComponentSerialization.TRUSTED_STREAM_CODEC`，ItemStack 用 `OPTIONAL_STREAM_CODEC`）。`DeviceEntry` 含 `craftingCpu` 字段（boolean）区分普通设备与合成 CPU。客户端 `DeviceListWidget` 对 CPU 行用「智能加速」tooltip 文案（`smart_accelerate`/`smart_accelerating`）。

### 4.5 客户端界面

- `TorcherinoaemodClient.java`：`RegisterMenuScreensEvent` 注册菜单屏幕；`EntityRenderersEvent.RegisterRenderers` 注册方块实体渲染器；`ModelEvent.RegisterAdditional` 注册发光带模型（`AEAcceleratorRenderer.LIGHTS_MODEL`，该模型不在任何方块状态里，必须单独注册）。
- `client/ModScreens.java`：**自定义样式加载器**。AE2 的 `StyleManager.loadStyleDoc` 固定读 `ae2` 命名空间，故仿照其内部实现，复用公开的 `ScreenStyle.GSON` 从本模组命名空间读取样式 JSON；样式里不带命名空间的贴图会自动补 `ae2:` 前缀。
- `client/AEGuiMetrics.java`：客户端界面布局度量常量类，集中设备列表/倍数弹窗的尺寸、贴图素材区域与像素偏移（`ROW_HEIGHT`、`MARK_*`、`TRACK_*`、`HANDLE_*` 等），供 `DeviceListWidget` / `DeviceConfigPopup` 引用，随贴图统一调整，消除散落的魔法数字。
- `menu/AEAcceleratorScreen.java`（**继承 `AEBaseScreen` 自主创建**）
  - 背景与设备列表视觉用本模组自定义 GUI 贴图 `ae_accelerator_gui.png`（样式 JSON 的 `background` 与 `images` 驱动）。
  - 组件：搜索栏 `AETextField`、设备列表 `DeviceListWidget`、滚动条 `Scrollbar`（BIG）、升级卡 `UpgradesPanel`（AE 标准右侧面板）、倍数配置弹窗 `DeviceConfigPopup`，均注册进 `widgets` 样式系统，坐标相对界面原点。
  - `shouldAddToolbar()` 返回 false（样式 JSON 未定义 verticalToolbar，不关闭会崩溃，参考 SkyChestScreen）。
  - `drawFG` 绘制状态文字（未接入网络 / 网络中暂无设备 / 等待能量 / 点击设备开始加速 / 加速 N 台 · 最高 Mx），与物品栏标题同水平线靠右对齐（右边缘 x=170，y=91）。
- `menu/DeviceListWidget.java`（`ICompositeWidget`）：绘制设备列表行（背景条 139x22、行高 22 + 1px 间隙、整体上移 2px）、搜索过滤（名称/坐标）、滚动条联动（`setCaptureMouseWheel(false)` 自管滚轮）、悬浮高亮、加速中行持续铺高亮、行尾状态图标（贴图 (0,230,12,12)/(0,242,12,11)）；左键点击切换加速、右键打开倍数弹窗；`setEnabled(false)` 用于弹窗打开时禁用交互。**不用 scissor**（列表高度是行高整数倍，只画完整行）。
- `menu/DeviceConfigPopup.java`（`ICompositeWidget`）：右键设备行弹出，素材 `device_entry_gui.png`（203x32 横向面板条，中段内嵌滑块轨道 x=70..168,y=12..17，手柄贴图 (0,32,15,12)）；拖动/滚轮/点轨道实时改倍数并经 `sendSetAccelMultiplier` 发服务端；点弹窗外关闭；面板必须在**前景层**（`drawForegroundLayer`）绘制，否则被插槽/列表文字盖住；`wantsAllMouseDownEvents/Up/Wheel` 返回 isOpen 以拦截穿透。
- `client/render/AEAcceleratorRenderer.java`（参考 AE2 分子装配机渲染器）
  - 接电工作时：叠加全亮度半透明「发光带」模型（`RenderType.tripwire()` 渲染，模型 face 带 `neoforge_data` block_light/sky_light=15），并在方块中心按节奏生成向内收敛的「炫彩流光」粒子（复用 `ParticleTypes.CRAFTING`）。粒子生成节奏（`particleCountdown`）在渲染器内维护，**不污染方块实体**。

### 4.6 物品/创造栏

- `item/ModItems.java`：`AE_ACCELERATOR` 的 BlockItem。
- `item/ModCreativeTabs.java`：模组专属创造栏 `TORCHERINO_AE_TAB`（icon 为加速器物品）。

### 4.7 资源现状

- `lang/en_us.json` + `lang/zh_cn.json`：方块/物品/创造栏/GUI 全部文案（含 `gui.*.ae_accelerator.search/empty/accelerate/accelerating/right_hint/popup_hint/accel_hint/accel_status` 等）。
- `blockstates/ae_accelerator.json`：4 种朝向 x 4 种状态组合（online × working）→ 模型 `ae_accelerator`（基础）/ `ae_accelerator_on`（online）/ `ae_accelerator_inactive`（其余）。
- `models/block/`：`ae_accelerator.json`（Blockbench 导出，`format_version` 1.21.11，render_type cutout_mipped）、`ae_accelerator_on.json`、`ae_accelerator_inactive.json`、`ae_accelerator_lights.json`（发光带，`render_type` cutout + `neoforge_data` 全亮度）。
- `models/item/ae_accelerator.json`：parent 指向方块模型。
- `screens/ae_accelerator.json`：自定义界面样式（palette、background、images.deviceListBg/deviceListSlotSelected、slots、widgets.search/deviceList/scrollbar/upgrades/deviceConfigPopup、text）。
- `textures/`：`block/`（ae_accelerator、ae_accelerator_lights、animation、into、into_inactive、into_on + mcmeta）、`gui/`（ae_accelerator_gui.png、device_entry_gui.png）、`item/`。
- `data/`：`loot_table/blocks/ae_accelerator.json`（掉落自身）；`recipe/ae_accelerator.json`（3×3 合成：四角 `ae2:logic_processor`、边中 `ae2:energy_cell`、中心 `ae2:speed_card`）。
- 混入配置：`src/main/resources/torcherino_ae_mod.mixins.json`（当前无任何 mixin）。
- `src/main/templates/META-INF/neoforge.mods.toml`：占位符模板，声明 ae2/guideme 依赖。
- 根目录 `_img/`：开发时分析 GUI 贴图用的脚本（ps1/py）与结果 txt、参考图 ref.png，非模组资源。

### 4.8 共享工具类

- `common/AE2GridSupport.java`：AE2 网格辅助（供方块实体加速脉冲与菜单设备列表采集复用）。
  - `isAcceleratableMachine(Object)`：黑名单判断（`StorageBusPart` / `P2PTunnelPart` / `EnergyCellBlockEntity` 等基础设施不可加速，集中于此以便后续扩展）。
  - `resolveDevicePos(Object)`：解析宿主坐标（方块实体用自身坐标，部件用所在线缆/宿主坐标）。
  - `deviceIdOf(Object)`：生成稳定、可持久化的设备标识（方块实体用坐标，部件用「坐标|朝向」），作为选中设备集合与倍数表的身份键，也是 GUI 点击载荷的设备身份。
  - `isAcceleratableNode(IGridNode, self)`：判断节点是否为可加速设备——注册了 `IGridTickable` 服务、宿主非空非自身、属于可加速机器、能解析出坐标。**不判断 `isActive()`**（菜单需展示非活动设备，仅加速脉冲要求激活，由调用方叠加）。
  - **合成 CPU 辅助**：`cpuDeviceId(ICraftingCPU)` 用带 `cpu:` 前缀的结构 min 坐标生成稳定设备标识（与普通坐标标识互不冲突）；`isCpuDeviceId(String)` / `asCpuCluster(ICraftingCPU)` 解析 CPU；`isCraftingMachineType(Object)` 判断宿主是否为合成执行机器——三级判定：①实现 `ICraftingMachine` 的方块（分子装配室及第三方直接实现者）；②向世界注册 `AECapabilities.CRAFTING_MACHINE` 能力的方块（第三方 AE 附属通常这样接入）；③兜底登记在 `CRAFTING_MACHINE_TYPES` 的压印机 / 充能器。
  - **合成相关机器判定**：智能加速的目标集合由两类组成——①节点注册了 `ICraftingProvider` 服务的 pattern provider（接口、样板供应器）；②`isCraftingMachineType` 的合成执行机器。两者都由 `AEAcceleratorBlockEntity.isCraftingRelated` 统一判定并缓存进 `cachedCpuTargets`。

## 4.9 智能加速（合成 CPU）

- 加速器 GUI 设备列表除普通 `IGridTickable` 机器外，还会采集**合成 CPU（Crafting CPU）**：经 `ICraftingService.getCpus()` 枚举，图标用 `AEBlocks.CRAFTING_UNIT`，名称用 CPU 自定义名或默认文案，`DeviceEntry.craftingCpu = true` 区分。
- 合成 CPU 不属于 `IGridTickable`，**本身不能被直接加速**；玩家选中它（左键/右键设倍数）即开启「智能加速」。当被选中的 CPU 处于合成状态（`isBusy()`）时，加速器会**联动加速当前参与合成的机器**：`getSmartCpuMultiplier` 取选中且 busy 的 CPU 最高倍率；`cachedCpuTargets` 缓存「合成相关机器」（`ICraftingProvider` 服务的接口/样板供应器，或 `isCraftingMachineType` 的合成执行机器——实现 `ICraftingMachine` 的分子装配室等 + 压印机/充能器），逐台按该倍率脉冲（运行时以 sleep 判断兜底，空闲机器不空转）。

## 5. 已知注意事项 / 待办

- **`BASE_ACCEL_MULTIPLIER = 100` 仅为测试值，正式发布前必须改回 4**（代码注释已标注）。
- 目前升级卡仅支持 AE2 原版速度升级卡（`AEItems.SPEED_CARD`）；本模组自定义升级卡物品尚未实现。
- `src/generated/resources/` 数据生成目录尚未建立（datagen 未启用，模型/blockstates/lang 均为手写）。
- 加速逻辑依赖 AE2 网格 tick（`IGridTickable`），**不会加速**存储总线、能量元件、P2P 隧道等基础设施（有意排除）。
- 客户端 `commonTick` 提前 return 是**刻意设计**，勿删（否则模型切换会失效）。

## 6. 构建与运行命令

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
- 本项目根目录有 `appeng/` 反编译源码目录，作为 AE2 内部实现参考，勿直接修改。
