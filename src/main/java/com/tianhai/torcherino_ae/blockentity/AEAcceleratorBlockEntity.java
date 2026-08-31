package com.tianhai.torcherino_ae.blockentity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import appeng.api.config.Actionable;
import appeng.api.config.PowerMultiplier;
import appeng.api.inventories.InternalInventory;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IGridNodeListener;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.networking.ticking.IGridTickable;
import appeng.api.networking.ticking.ITickManager;
import appeng.api.networking.ticking.TickRateModulation;
import appeng.api.upgrades.IUpgradeInventory;
import appeng.api.upgrades.IUpgradeableObject;
import appeng.api.upgrades.UpgradeInventories;
import appeng.blockentity.CommonTickingBlockEntity;
import appeng.blockentity.grid.AENetworkedPoweredBlockEntity;
import appeng.core.definitions.AEItems;
import appeng.util.inv.AppEngInternalInventory;
import appeng.util.inv.filter.IAEItemFilter;
import com.tianhai.torcherino_ae.Torcherinoaemod;
import com.tianhai.torcherino_ae.block.ModBlocks;
import com.tianhai.torcherino_ae.common.AE2GridSupport;
import com.tianhai.torcherino_ae.item.AcceleratorConfigCardItem;
import com.tianhai.torcherino_ae.item.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * AE 加速器方块实体。
 * <p>
 * 继承 AE2 的 {@link AENetworkedPoweredBlockEntity}：既接入 AE 网络，又通过 AE 内部能量体系供电。
 * 实现 {@link IUpgradeableObject} 以支持升级卡插槽，实现 {@link CommonTickingBlockEntity} 以做周期性工作。
 * 只有当方块接入并激活 AE 网络、且能从网络提取到足够能量时才会工作。
 * <p>
 * 加速功能：玩家在 GUI 中点击设备列表选中目标后，本方块实体会在每个游戏 tick 对选中的
 * 设备执行一次加速——先通过 {@link ITickManager#alertDevice} 催促其网格 tick，
 * 再在单个游戏 tick 内多次调用其 {@link IGridTickable#tickingRequest}，使设备内部工作
 * 进度成倍推进。未安装升级卡时最高加速 4x，每张速度升级卡额外 +2x。
 * 只有注册了网格 tick 服务（{@link IGridTickable}）且处于激活状态的 AE 设备才能被加速。
 */
public class AEAcceleratorBlockEntity extends AENetworkedPoweredBlockEntity
        implements IUpgradeableObject, CommonTickingBlockEntity {

    // 升级卡插槽数量（速度升级卡等）。
    public static final int UPGRADE_SLOTS = 4;

    // 基础加速倍数：未安装任何升级卡时的最高加速倍数。
    // 注意：当前为 100x，仅用于测试；正式发布前应改回 4x。
    public static final int BASE_ACCEL_MULTIPLIER = 100;

    // 每张速度升级卡额外增加的加速倍数。
    public static final int ACCEL_PER_SPEED_CARD = 2;

    // 每 tick 需要消耗的 AE 能量基础值。
    private static final double POWER_PER_TICK = 1.0;

    // 每张速度升级卡额外增加的能耗（基础 1.0 之上）。
    private static final double POWER_PER_SPEED_CARD = 0.5;

    // 每台被选中加速的设备额外消耗的 AE 能量。
    private static final double POWER_PER_ACCELERATED_DEVICE = 0.5;

    // 能量判定缓冲：提取量达到需求的这个比例即认为足够工作，避免因浮点误差来回抖动。
    private static final double POWER_BUFFER_FRACTION = 0.9;

    // NBT 存储键名：配置卡放入的槽位库存。
    private static final String TAG_CONFIG_CARD = "config_card_inventory";

    // NBT 存储键名：被选中进行加速的设备标识。
    private static final String TAG_ACCELERATED_DEVICES = "accelerated_devices";

    // NBT 存储键名：每台被加速设备独立的加速倍数（与 accelerated_devices 一一对应）。
    private static final String TAG_DEVICE_MULTIPLIERS = "device_multipliers";

    // 用于供 GUI 显示的「是否已接入网络」状态（连接状态，供视觉显示，使用 isOnline 计算）。
    private boolean online;

    // 用于供 GUI 显示的「是否正在工作」状态（仅在网络激活且有能量时为 true）。
    private boolean working;

    // 升级卡库存。
    private final IUpgradeInventory upgrades;

    // 配置卡库存：单格，仅允许放入「加速器配置卡」，用于后续存放配置方案（占位实现）。
    private final AppEngInternalInventory configCardInventory;

    // 被选中进行加速的设备标识集合（服务端权威，随 NBT 持久化，重启后保留）。
    // 设备标识由 AE2GridSupport.deviceIdOf 生成：方块实体用坐标，部件用「坐标|朝向」，
    // 因此同一坐标上的多个可加速部件也能被各自独立选中、互不串扰。
    private final Set<String> acceleratedDevices = new HashSet<>();

    // 每台被加速设备独立的加速倍数（服务端权威，随 NBT 持久化，重启后保留）。
    // 键 = 设备标识，值 = 该设备的加速倍数；未加速设备不在表中（默认按 1 处理）。
    private final Map<String, Integer> deviceMultipliers = new HashMap<>();

    // 由配置卡自动注入的设备标识集合（仅服务端内存态，不持久化——卡在则注入、卡走则撤销）。
    // 与 acceleratedDevices 中的「玩家手动勾选」区分维护：卡被取出/更换时只撤销卡注入的
    // 设备，不打断玩家通过 GUI 手动勾选的设备；卡放入后按卡片记录重新注入。
    private final Set<String> configCardDevices = new HashSet<>();

    // 网络诊断计数器：每累计 20 tick（即 1 秒）输出一次完整连接状态，
    // 便于实时判断加速器是否真正连接上 AE 网络（排查「UI 显示未连接」问题）。
    private int diagnosticTimer;

    // 加速脉冲诊断计数器：每累计 20 tick（即 1 秒）输出一次设备命中明细。
    private int pulseDebugTimer;

    // 加速目标缓存：只缓存「当前被选中的可加速节点」，每 tick 仅遍历此缓存而非整张网格，
    // 避免把 getNodes() 全网格扫描的开销分摊到每一个游戏 tick 上。节点失效/换网时会被剔除。
    private final List<AccelTarget> cachedTargets = new ArrayList<>();

    // 智能加速缓存：只缓存「合成相关机器」节点（接口/样板供应器等 ICraftingProvider，
    // 或分子装配室/压印机/充能器等合成执行机器，且未被玩家单独选中）。当有被选中的
    // 合成 CPU 处于合成状态时，加速器会对这些机器联动加速，运行时以睡眠判断兜底。
    // 同样避免每 tick 全网格扫描，随 rebuildTargetCache 一起周期性重建。
    private final List<AccelTarget> cachedCpuTargets = new ArrayList<>();

    // 缓存重建间隔（tick）：每累计该值就重建一次缓存，用于把新增设备纳入、把失效设备剔除。
    private static final int CACHE_REBUILD_INTERVAL = 20;

    // 缓存重建计时器：累计到 CACHE_REBUILD_INTERVAL 就触发一次重建。
    private int cacheRebuildTimer;

    // 缓存是否待重建：初始为 true（首个 tick 立即构建），选中集合变化或缓存节点失效时置为 true，
    // 保证「点击加速」立即生效而不必等待下一个重建周期。
    private boolean cacheDirty = true;

    public AEAcceleratorBlockEntity(BlockEntityType<?> blockEntityType, BlockPos pos, BlockState state) {
        super(blockEntityType, pos, state);
        // 创建绑定到本机的升级卡库存，并在升级卡变化时通知保存。
        this.upgrades = UpgradeInventories.forMachine(ModBlocks.AE_ACCELERATOR.get(), UPGRADE_SLOTS,
                this::onUpgradesChanged);
        // 创建单格配置卡库存：仅接受「加速器配置卡」，且其绑定的加速器必须就是本机
        // （防止异地配置的卡片直接混入；未绑定的卡片同样被拒绝）。
        this.configCardInventory = new AppEngInternalInventory(this, 1, 1, new IAEItemFilter() {
            @Override
            public boolean allowInsert(appeng.api.inventories.InternalInventory inv, int slot, ItemStack stack) {
                return stack.is(ModItems.ACCELERATOR_CONFIG_CARD.get())
                        && AcceleratorConfigCardItem.isBoundTo(stack, AEAcceleratorBlockEntity.this.getBlockPos());
            }
        });
    }

    /**
     * 加速目标缓存条目：在重建时一次性解析好网格节点、设备标识与其网格 tick 服务，
     * 供加速脉冲每 tick 直接使用，避免重复调用 getService / deviceIdOf 等较重解析。
     */
    private record AccelTarget(IGridNode node, String deviceId, IGridTickable tickable) {
    }

    /**
     * 供 {@link net.minecraft.world.level.block.entity.BlockEntityType} 使用的工厂方法：
     * 在运行时读取已注册的方块实体类型并创建实例。
     */
    public static AEAcceleratorBlockEntity create(BlockPos pos, BlockState state) {
        return new AEAcceleratorBlockEntity(ModBlockEntities.AE_ACCELERATOR.get(), pos, state);
    }

    @Override
    public InternalInventory getInternalInventory() {
        return upgrades;
    }

    @Override
    public IUpgradeInventory getUpgrades() {
        return upgrades;
    }

    /**
     * 配置卡库存：单格，仅允许存放「加速器配置卡」。
     */
    public AppEngInternalInventory getConfigCardInventory() {
        return configCardInventory;
    }

    /**
     * 升级卡库存发生变化时的回调：标记方块需要保存，并同步客户端。
     */
    private void onUpgradesChanged() {
        saveChanges();
        markForUpdate();
    }

    @Override
    public void commonTick() {
        // 客户端不执行工作逻辑：网络状态判断、能量消耗与加速脉冲都只在服务端进行。
        // 客户端方块实体的 online/working 完全由服务端经 writeToStream/readFromStream 同步；
        // 若客户端也走到下方判断（客户端 getGrid() 恒为 null），会把同步来的 working 覆盖回
        // false，导致方块状态永远停留在「未工作」变体，模型无法切换到 ae_accelerator_inactive。
        if (level != null && level.isClientSide()) {
            return;
        }

        // 网络是否「已接入」由 AE2 权威事件 onMainNodeStateChanged 驱动更新（见下方实现），
        // 这里不再重复计算 isOnline，以免 getGrid() 在不一致的调用时机返回 null 把 true 覆盖回 false。
        IGrid grid = getMainNode().getGrid();

        // 周期性（每 20 tick，即 1 秒）输出一次网络/工作诊断日志，便于实时定位
        // 「未加速 / UI 显示未连接 / 模型未切换」究竟卡在网格、电力还是设备匹配环节。
        // 诊断日志只是叠加观察（与下方真正的工作分支共用同一套状态判定），不单独跑一份工作逻辑。
        boolean log = ++diagnosticTimer >= 20;
        if (log) {
            diagnosticTimer = 0;
            logNetworkState(grid);
        }

        // 真正的「工作」需要网络已彻底激活 + 网络存在，避免在 boot 期间或未接线时执行工作。
        if (grid == null || !getMainNode().isActive()) {
            setWorking(false);
            return;
        }

        // 节能措施：先执行加速脉冲，统计本 tick「真正被加速」（非睡眠、正处工作状态）的设备数。
        // 只有确实有设备在工作时才消耗能量并标记 working；否则（所有选中设备都已空闲）
        // 不提取能量、不标记工作，方块停留在 ae_accelerator_on（接电但空闲）模型，实现节能。
        double needed = getRequiredPowerPerTick();
        int accelerated = runAccelerationPulse(grid);

        boolean isWorking = false;
        double available = 0;
        if (accelerated > 0) {
            // 消耗 AE 内部能量：从网格的能量服务按需提取（POWER_BUFFER_FRACTION 为缓冲，避免浮点误差来回抖动）。
            available = grid.getEnergyService().extractAEPower(needed, Actionable.MODULATE, PowerMultiplier.ONE);
            isWorking = available >= needed * POWER_BUFFER_FRACTION;
        }
        setWorking(isWorking);
        if (log) {
            Torcherinoaemod.LOGGER.info(
                    "[DBG][工作诊断] {} | needed={} | available={} | isWorking={} | 实际加速设备={} | 加速倍数={} | 选中设备={}",
                    getBlockPos(), needed, available, isWorking, accelerated, getAccelMultiplier(),
                    acceleratedDevices);
        }
    }

    /**
     * 当前最高加速倍数：基础 4x，每张速度升级卡额外 +2x。
     * <p>
     * 注意：这是「滑块可调的上限」，每台设备实际使用的倍数是独立的，
     * 由 {@link #getDeviceMultiplier(String)} 返回，可通过界面中的横向滚动条调整。
     */
    public int getAccelMultiplier() {
        int speedCards = getInstalledUpgrades(AEItems.SPEED_CARD.get());
        return BASE_ACCEL_MULTIPLIER + speedCards * ACCEL_PER_SPEED_CARD;
    }

    /**
     * 执行一次加速：对每个「被选中 + 可加速 + 激活且未睡眠」的设备，
     * 先通过 {@link ITickManager#alertDevice} 把它们提前到下一个 tick 触发，
     * 再在单个游戏 tick 内额外多次调用其 {@link IGridTickable#tickingRequest}。
     * <p>
     * 加速原理：AE2 的压印机、分子装配室、接口等机器在工作期间返回
     * {@link TickRateModulation#URGENT}（已经每个游戏 tick 满速工作），仅靠
     * {@code alertDevice} 催促无法进一步提高频率；真正的加速必须让设备在单个
     * 游戏 tick 内被调用多次，使其内部工作进度（加工时间、充能量、搬运量）成倍推进。
     * 设备每次 tick 内部会根据传入的 tick 数缩放工作量，因此额外调用时传入 1，
     * 表示额外推进 1 tick 的工作量，不会产生重复计量的副作用。
     * <p>
     * 性能优化：此处只遍历 {@link #cachedTargets} 里已解析好的目标节点，而不是每 tick
     * 都对 {@code grid.getNodes()} 全网格扫描。缓存仅在需要时（初建、选中集合变化、
     * 节点失效、{@link #CACHE_REBUILD_INTERVAL} 周期）由 {@link #rebuildTargetCache} 重建。
     * 被加速设备本身推进的工作量不可削减（那就是加速效果本身），这里省的是加速器的维系开销。
     */
    private int runAccelerationPulse(IGrid grid) {
        if (acceleratedDevices.isEmpty()) {
            // 无选中设备：清空缓存，保持每 tick 最低开销。
            cachedTargets.clear();
            cachedCpuTargets.clear();
            return 0;
        }
        ITickManager tickManager = grid.getTickManager();
        // 网格未注册 tick 管理器（异常情况）时跳过本次脉冲，避免空指针。
        if (tickManager == null) {
            cachedTargets.clear();
            cachedCpuTargets.clear();
            return 0;
        }
        // 周期性重建目标缓存：每累计 CACHE_REBUILD_INTERVAL tick，或选中集合变化 / 节点失效
        // 置脏后，重新遍历一次网格收集被选中设备，避免每个 tick 都全网格扫描。
        if (cacheDirty || ++cacheRebuildTimer >= CACHE_REBUILD_INTERVAL) {
            cacheRebuildTimer = 0;
            cacheDirty = false;
            rebuildTargetCache(grid);
        }
        // 智能加速倍率：扫描「被选中且正在合成」的 CPU，取其智能倍率的最大值。
        // 合成 CPU 本身不走 IGridTickable，无法直接被 tickingRequest 加速；但当它处于合成
        // 状态时，网格中所有「正在参与合成」的机器（busy 的 ICraftingProvider 机器）都会
        // 被联动加速——这就是「智能加速」：无需玩家逐个选中参与合成的机器。
        int smartCpuMultiplier = getSmartCpuMultiplier(grid);
        // 每 20 tick（1 秒）输出一次加速诊断，避免每次调用都刷屏。
        boolean log = ++pulseDebugTimer >= 20;
        if (log) {
            pulseDebugTimer = 0;
        }
        int accelerated = 0;
        // 明细仅为诊断用，仅在需要打印时懒创建，避免每 tick 分配。
        StringBuilder detail = log ? new StringBuilder() : null;
        // 只遍历缓存的目标节点，而不是整张网格。
        for (int i = cachedTargets.size() - 1; i >= 0; i--) {
            AccelTarget target = cachedTargets.get(i);
            IGridNode node = target.node();
            // 节点已脱离本网格（被移除或换网）：忽略并标记待重建，下次重建时从缓存剔除。
            if (node.getGrid() != grid) {
                cacheDirty = true;
                continue;
            }
            // 只加速处于激活状态（已通电、已 Boot、满足通道）的设备；非激活则本 tick 不处理。
            if (!node.isActive()) {
                continue;
            }
            // 设备标识已不在选中集合（被取消）：标记待重建，本 tick 跳过。
            String deviceId = target.deviceId();
            if (!acceleratedDevices.contains(deviceId)) {
                cacheDirty = true;
                continue;
            }
            // 每台设备使用各自独立的加速倍数（界面滑块调整，随 NBT 持久化）。
            int deviceMultiplier = getDeviceMultiplier(deviceId);
            // 每个游戏 tick 额外触发的次数 = 该设备加速倍数 - 1（设备自身已按自然节奏 tick 1 次）。
            int extraCalls = Math.max(0, deviceMultiplier - 1);
            // 未产生额外加速（倍数 <= 1）时直接跳过，避免不必要的 getTickingRequest 调用。
            if (extraCalls <= 0) {
                continue;
            }
            IGridTickable tickable = target.tickable();
            // 设备当前期望睡眠（空闲中），催促唤醒没有意义，跳过。
            boolean sleeping = tickable.getTickingRequest(node).isSleeping();
            if (log) {
                Object owner = node.getOwner();
                detail.append(String.format("[%s@%s active=%s sleeping=%s x%d]",
                        owner != null ? owner.getClass().getSimpleName() : "?", deviceId,
                        node.isActive(), sleeping, deviceMultiplier));
            }
            if (sleeping) {
                continue;
            }
            // 通过网格 tick 管理器把设备提前到「下一个 tick」触发（对 IO 端口/总线等
            // 自然节奏较慢、每次调用做固定量工作的设备有效）。
            tickManager.alertDevice(node);
            // 真正的加速：单个游戏 tick 内额外多次调用设备的网格 tick 逻辑。
            // 压印机、分子装配室、接口等机器工作期间返回 URGENT（已每游戏 tick 满速），
            // 仅靠 alertDevice 无法再提高频率，多次调用 tickingRequest 才能提升工作速率。
            for (int c = 0; c < extraCalls; c++) {
                tickable.tickingRequest(node, 1);
            }
            accelerated++;
        }

        // 智能联动加速：仅当有「被选中且正在合成」的 CPU 时才进行。
        // 对缓存的「参与合成机器」逐个按智能倍率触发，跳过节点离线/失效与处于睡眠的设备。
        if (smartCpuMultiplier > 1) {
            for (int i = cachedCpuTargets.size() - 1; i >= 0; i--) {
                AccelTarget target = cachedCpuTargets.get(i);
                IGridNode node = target.node();
                // 节点已脱离本网格（被移除或换网）：忽略并标记待重建。
                if (node.getGrid() != grid) {
                    cacheDirty = true;
                    continue;
                }
                if (!node.isActive()) {
                    continue;
                }
                IGridTickable tickable = target.tickable();
                int extraCalls = Math.max(0, smartCpuMultiplier - 1);
                if (extraCalls <= 0) {
                    continue;
                }
                boolean sleeping = tickable.getTickingRequest(node).isSleeping();
                if (log) {
                    Object owner = node.getOwner();
                    detail.append(String.format("[智能][%s@%s active=%s sleeping=%s x%d]",
                            owner != null ? owner.getClass().getSimpleName() : "?", target.deviceId(),
                            node.isActive(), sleeping, smartCpuMultiplier));
                }
                if (sleeping) {
                    continue;
                }
                tickManager.alertDevice(node);
                for (int c = 0; c < extraCalls; c++) {
                    tickable.tickingRequest(node, 1);
                }
                accelerated++;
            }
        }
        if (log) {
            Torcherinoaemod.LOGGER.info(
                    "[DBG][加速诊断] {} | 智能倍率={} | 实际加速设备={} | 命中明细={}",
                    getBlockPos(), smartCpuMultiplier, accelerated, detail);
        }
        // 返回本 tick 真正被加速的设备数（含智能联动加速），供 commonTick 判断是否工作/耗能。
        return accelerated;
    }

    /**
     * 计算当前「被选中且正在合成」的合成 CPU 的最高智能加速倍率。
     * <p>
     * 只有被玩家选中（进入 {@code acceleratedDevices}）且正有任务在跑（{@code isBusy()}）
     * 的 CPU 才会计入智能加速；空闲 CPU 没有合成任务，联动加速其参与机器没有意义。
     * 若没有任何被选中的 CPU 处于合成状态，返回 0（表示本次脉冲不做智能联动加速）。
     * CPU 数量通常只有几台，此扫描开销可忽略。
     */
    private int getSmartCpuMultiplier(IGrid grid) {
        int max = 0;
        for (ICraftingCPU cpu : grid.getCraftingService().getCpus()) {
            String id = AE2GridSupport.cpuDeviceId(cpu);
            if (id == null || !acceleratedDevices.contains(id) || !cpu.isBusy()) {
                continue;
            }
            max = Math.max(max, getDeviceMultiplier(id));
        }
        return max;
    }

    /**
     * 重新遍历网格，把「当前被选中的可加速设备」收集进 {@link #cachedTargets}。
     * <p>
     * 该遍历原本每 tick 都要执行一次，代价与网络规模成正比；改为仅在需要时执行
     * （初建、选中集合变化、节点失效、周期重建），加速脉冲只需遍历这份小缓存。
     * <p>
     * 筛选条件与菜单设备列表采集保持一致（见 {@link AE2GridSupport#isAcceleratableNode}）：
     * 这里不判断 {@code isActive()}——激活状态每 tick 变化，交给加速脉冲内单独判断，
     * 避免因某设备暂时未激活而把它漏出缓存（否则要等下一个重建周期才能重新纳入）。
     */
    private void rebuildTargetCache(IGrid grid) {
        cachedTargets.clear();
        cachedCpuTargets.clear();
        // 完全没有选中任何设备（含合成 CPU）时无需构建缓存，保持每 tick 最低开销。
        if (acceleratedDevices.isEmpty()) {
            return;
        }
        for (IGridNode node : grid.getNodes()) {
            if (!AE2GridSupport.isAcceleratableNode(node, this)) {
                continue;
            }
            Object owner = node.getOwner();
            String deviceId = AE2GridSupport.deviceIdOf(owner);
            if (deviceId == null) {
                continue;
            }
            IGridTickable tickable = node.getService(IGridTickable.class);
            if (tickable == null) {
                continue;
            }
            // 玩家在列表中直接选中的设备 -> 缓存为常规加速目标。
            if (acceleratedDevices.contains(deviceId)) {
                cachedTargets.add(new AccelTarget(node, deviceId, tickable));
            } else if (isCraftingRelated(node)) {
                // 未被玩家选中、但属于合成相关机器（pattern provider / 分子装配室 / 压印机）
                // -> 缓存为智能联动目标，供选中 CPU 时联动加速。运行时用睡眠判断兜底，
                // 空闲机器不会被空转触发。
                cachedCpuTargets.add(new AccelTarget(node, deviceId, tickable));
            }
        }
    }

    /**
     * 判断网格节点是否属于「合成相关机器」。
     * <p>
     * 参与合成的机器分两类：
     * <ul>
     *   <li>pattern provider（接口、样板供应器）：在节点上注册了 {@link ICraftingProvider}
     *       服务，负责接收 CPU 派发的合成任务；</li>
     *   <li>合成执行机器（见 {@link AE2GridSupport#isCraftingMachineType}）：凡实现
     *       {@link appeng.api.implementations.blockentities.ICraftingMachine} 的方块
     *       （分子装配室及第三方）一律命中；未实现该接口但参与合成的压印机、充能器
     *       通过类型集合兜底。被邻接的 pattern provider 调用、真正执行合成。</li>
     * </ul>
     * 借助该判定，无需回溯 CPU 的内部任务映射（AE2 未公开「CPU → 具体机器」的查询），
     * 即可命中「正在为合成提供服务」的机器。是否「此刻参与」（机器忙碌）由缓存重建时与
     * 加速脉冲内的睡眠判断共同决定。
     */
    private static boolean isCraftingRelated(IGridNode node) {
        if (node.getService(ICraftingProvider.class) != null) {
            return true;
        }
        Object owner = node.getOwner();
        return owner != null && AE2GridSupport.isCraftingMachineType(owner);
    }

    /**
     * 依据已安装的速度升级卡数量与被选中设备数量计算每 tick 能量消耗。
     */
    private double getRequiredPowerPerTick() {
        double speedCards = getInstalledUpgrades(AEItems.SPEED_CARD.get());
        return POWER_PER_TICK + speedCards * POWER_PER_SPEED_CARD
                + acceleratedDevices.size() * POWER_PER_ACCELERATED_DEVICE;
    }

    // ========================= 加速目标管理 =========================

    /**
     * 被选中进行加速的设备标识集合（只读视图）。
     */
    public Set<String> getAcceleratedDevices() {
        return Set.copyOf(acceleratedDevices);
    }

    /**
     * 指定设备标识是否正在被加速。
     */
    public boolean isAccelerating(String deviceId) {
        return acceleratedDevices.contains(deviceId);
    }

    /**
     * 指定设备的当前加速倍数：已在倍数表中则返回其独立设置的倍数，
     * 否则返回最高倍数（未设置过的设备默认按最高加速）。
     */
    public int getDeviceMultiplier(String deviceId) {
        return deviceMultipliers.getOrDefault(deviceId, getAccelMultiplier());
    }

    /**
     * 设置指定设备的加速倍数（界面横向滚动条实时发送）。
     * <p>
     * 倍数大于 1 时把设备加入加速列表；倍数小于等于 1 视为「取消加速」，
     * 从加速列表与倍数表中同时移除。由菜单的服务端动作处理器调用。
     *
     * @param deviceId   设备标识（由 AE2GridSupport.deviceIdOf 生成）
     * @param multiplier 新的加速倍数（1 表示不加速）
     */
    public void setDeviceMultiplier(String deviceId, int multiplier) {
        if (multiplier <= 1) {
            acceleratedDevices.remove(deviceId);
            deviceMultipliers.remove(deviceId);
        } else {
            // 上限钳制到当前最高倍数（受速度升级卡影响）。
            int clamped = Math.min(multiplier, getAccelMultiplier());
            acceleratedDevices.add(deviceId);
            deviceMultipliers.put(deviceId, clamped);
        }
        // 选中集合发生变化：标记缓存待重建，使「点击加速 / 取消加速」立即生效。
        cacheDirty = true;
        saveChanges();
        markForClientUpdate();
    }

    /**
     * 切换指定设备的加速状态（未加速 → 按最高倍数加速；已加速 → 取消加速）。
     * 由菜单的服务端动作处理器调用。
     */
    public void toggleAcceleratedDevice(String deviceId) {
        if (acceleratedDevices.contains(deviceId)) {
            setDeviceMultiplier(deviceId, 1);
        } else {
            setDeviceMultiplier(deviceId, getAccelMultiplier());
        }
    }

    // ========================= 配置卡自动注入 =========================

    /**
     * 按配置卡上的绑定信息同步「卡注入的加速设备」。
     * <p>
     * 卡片放入本机配置卡槽后：把卡上记录、且位于本网络内可加速（非自身）的设备加入
     * {@link #acceleratedDevices}（默认按最高倍数，复用现有加速脉冲与倍数表），
     * 实现「卡在则加速」。卡片取出、更换、绑定数据变化或网格接入变化时重新同步，
     * 把不再有效的卡注入设备撤销（「卡走则停」）。
     * <p>
     * 玩家通过 GUI 手动勾选的设备不受影响：本方法只维护 {@link #configCardDevices}
     * 集合中的设备，其余加速状态由玩家控制；若玩家手动勾选与卡注入重叠，任何一方
     * 取消都会停止该设备——符合直觉。
     */
    private void syncConfigCardDevices() {
        // 客户端没有权威网格，同步只在服务端进行。
        if (level != null && level.isClientSide()) {
            return;
        }
        Set<String> bound = new HashSet<>();
        ItemStack card = configCardInventory.getStackInSlot(0);
        if (AcceleratorConfigCardItem.isConfigCard(card)) {
            bound.addAll(AcceleratorConfigCardItem.getBoundDevices(card));
        }
        // 只注入「本网络内可加速且非自身」的设备（筛选复用与加速脉冲一致的谓词）。
        Set<String> inNetwork = new HashSet<>();
        IGrid grid = getMainNode().getGrid();
        if (grid != null) {
            for (String deviceId : bound) {
                if (isCardDeviceInGrid(grid, deviceId)) {
                    inNetwork.add(deviceId);
                }
            }
        }
        // 新出现的卡设备：按默认最高倍数注入（setDeviceMultiplier 负责持久化与缓存置脏）。
        for (String deviceId : inNetwork) {
            if (!configCardDevices.contains(deviceId) && !acceleratedDevices.contains(deviceId)) {
                setDeviceMultiplier(deviceId, getAccelMultiplier());
            }
        }
        // 从卡上消失（移除绑定/换卡/卡被取出/网络不可用）的卡设备：撤销加速。
        for (String deviceId : configCardDevices) {
            if (!inNetwork.contains(deviceId)) {
                setDeviceMultiplier(deviceId, 1);
            }
        }
        configCardDevices.clear();
        configCardDevices.addAll(inNetwork);
    }

    /**
     * 判断设备标识对应的节点是否位于本网格内且可加速（排除自身）。
     */
    private boolean isCardDeviceInGrid(IGrid grid, String deviceId) {
        for (IGridNode node : grid.getNodes()) {
            if (!AE2GridSupport.isAcceleratableNode(node, this)) {
                continue;
            }
            String id = AE2GridSupport.deviceIdOf(node.getOwner());
            if (id != null && id.equals(deviceId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 库存内容变化回调（由 AppEngInternalInventory 触发）：仅关心配置卡槽位变化，
     * 其余库存变化走超类默认逻辑（保存方块实体）。
     */
    @Override
    public void onChangeInventory(AppEngInternalInventory inv, int slot) {
        super.onChangeInventory(inv, slot);
        if (inv == configCardInventory) {
            // 卡片放入/取出/更换 -> 立即同步卡注入的设备（无需等待下一个重建周期）。
            syncConfigCardDevices();
        }
    }

    /**
     * 方块被移除（破坏、爆炸、活塞等任何途径）时清理绑定本机的配置卡：
     * <ul>
     *   <li>本机槽位内的卡片：清空其绑定（避免「即插即用」配置指向已摧毁的加速器）；</li>
     *   <li>在线玩家背包中绑定本机的卡片：清空其绑定。</li>
     * </ul>
     * 仅服务端执行；客户端区块卸载同样会触发本回调，通过 isClientSide 保护。
     * 同时撤销仍由卡注入的加速设备（方块都没有了，加速目标自然失去意义）。
     */
    @Override
    public void setRemoved() {
        super.setRemoved();
        Level levelNow = level;
        if (levelNow != null && !levelNow.isClientSide()) {
            clearConfigCardBindings(levelNow);
        }
    }

    /**
     * 清空绑定本机的全部配置卡（槽位内 + 在线玩家背包），服务端专用。
     */
    private void clearConfigCardBindings(Level levelNow) {
        // 槽位内的卡：直接改写其 NBT（库存物品引用在服务端是权威对象）。
        ItemStack card = configCardInventory.getStackInSlot(0);
        if (AcceleratorConfigCardItem.isConfigCard(card)
                && AcceleratorConfigCardItem.getBoundAccelerator(card) != null) {
            AcceleratorConfigCardItem.unbindAccelerator(card);
            configCardInventory.setItemDirect(0, card);
        }
        // 在线玩家背包中的卡：扫描全部物品槽（主物品栏/装备/副手），清理绑定本机的卡片。
        BlockPos selfPos = getBlockPos();
        for (Player player : levelNow.players()) {
            Inventory inventory = player.getInventory();
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                ItemStack stack = inventory.getItem(i);
                if (AcceleratorConfigCardItem.isConfigCard(stack)
                        && AcceleratorConfigCardItem.isBoundTo(stack, selfPos)) {
                    AcceleratorConfigCardItem.unbindAccelerator(stack);
                }
            }
        }
    }

    // ========================= NBT 持久化 =========================

    @Override
    public void saveAdditional(CompoundTag data, HolderLookup.Provider registries) {
        super.saveAdditional(data, registries);
        // 持久化配置卡库存内容，重启后保留玩家放入的配置卡。
        configCardInventory.writeToNBT(data, TAG_CONFIG_CARD, registries);
        // 持久化被选中的加速目标（设备标识），重启后保留玩家设置。
        ListTag idList = new ListTag();
        for (String id : acceleratedDevices) {
            idList.add(StringTag.valueOf(id));
        }
        data.put(TAG_ACCELERATED_DEVICES, idList);
        // 持久化每台设备独立的加速倍数（与 accelerated_devices 一一对应）。
        int[] multipliers = acceleratedDevices.stream()
                .mapToInt(id -> deviceMultipliers.getOrDefault(id, getAccelMultiplier())).toArray();
        data.putIntArray(TAG_DEVICE_MULTIPLIERS, multipliers);
    }

    @Override
    public void loadTag(CompoundTag data, HolderLookup.Provider registries) {
        super.loadTag(data, registries);
        // 恢复配置卡库存内容。
        configCardInventory.readFromNBT(data, TAG_CONFIG_CARD, registries);
        acceleratedDevices.clear();
        deviceMultipliers.clear();
        ListTag idList = data.getList(TAG_ACCELERATED_DEVICES, Tag.TAG_STRING);
        int[] multipliers = data.getIntArray(TAG_DEVICE_MULTIPLIERS);
        if (idList.isEmpty() && data.contains(TAG_ACCELERATED_DEVICES)) {
            // 兼容旧存档：旧版本把加速目标以「坐标 long[]」存储，这里转换为新的设备标识。
            long[] positions = data.getLongArray(TAG_ACCELERATED_DEVICES);
            idList = new ListTag();
            for (long p : positions) {
                idList.add(StringTag.valueOf(String.valueOf(p)));
            }
        }
        for (int i = 0; i < idList.size(); i++) {
            String id = idList.getString(i);
            acceleratedDevices.add(id);
            // 老存档可能没有倍数表，缺失时按基础最高倍数补齐（loadTag 阶段升级卡库存尚未加载，用常量）。
            deviceMultipliers.put(id, i < multipliers.length ? multipliers[i] : BASE_ACCEL_MULTIPLIER);
        }
        // 配置卡槽从 NBT 恢复后同步一次卡注入的设备。loadTag 阶段网格尚不可用
        // （grid 为 null），卡设备暂不注入；待节点上线回调 onMainNodeStateChanged 时再补。
        configCardDevices.clear();
        syncConfigCardDevices();
    }

    /**
     * 输出一次完整的网络连接诊断日志，用于检测加速器是否真正接入 AE 网络。
     * <p>
     * 判定链（AE2 默认实现）：
     * <ul>
     *   <li>{@code 有电}（isPowered）= 网格存在且网格能量服务 {@code isNetworkPowered()}，即「网络有电」。</li>
     *   <li>{@code 通道满足}（meetsChannelRequirements）= 未设置 REQUIRE_CHANNEL 时为 true；否则需占用了通道。</li>
     *   <li>{@code 在线}（isOnline）= {@code 有电 && 通道满足}，本 UI 的「已接入网络」正是据此显示。</li>
     *   <li>{@code 已激活}（isActive）= {@code 在线 && 网络已 Boot}。</li>
     * </ul>
     *
     * @param grid 当前节点所在的网格（可能为 null，表示尚未接入任何网格）
     */
    private void logNetworkState(IGrid grid) {
        // 该诊断用于服务端检测网络状态；客户端（Render 线程）没有网格对象，打印只会产生误导性误报，故跳过。
        if (level != null && level.isClientSide()) {
            return;
        }
        var node = getMainNode();

        // 根本未接入网格：节点很可能尚未被 AE 网络客户端扫描到（接线面 / 注册能力问题）。
        if (grid == null) {
            Torcherinoaemod.LOGGER.info("[DBG][网络诊断] {} 未接入任何 AE 网格（grid=null），视为离线",
                    getBlockPos());
            return;
        }

        IEnergyService energy = grid.getEnergyService();
        IGridNode gridNode = node.getNode();
        if (gridNode == null) {
            Torcherinoaemod.LOGGER.info("[DBG][网络诊断] {} 已发现网格，但底层节点仍为空（尚未注册到网格节点列表）",
                    getBlockPos());
            return;
        }
        Torcherinoaemod.LOGGER.info(
                "[DBG][网络诊断] {} | 连接面={} | 已Boot={} | 有电={} | 通道满足={} | 激活={} | 在线={} | 通道={}/{} | 网络能量={}/{} | 加速目标={}",
                getBlockPos(), gridNode.getConnectedSides(), gridNode.hasGridBooted(), gridNode.isPowered(),
                gridNode.meetsChannelRequirements(), gridNode.isActive(), gridNode.isOnline(),
                gridNode.getUsedChannels(), gridNode.getMaxChannels(), energy.getStoredPower(),
                energy.getMaxStoredPower(), acceleratedDevices);
    }

    /**
     * 是否已接入 AE 网络（连接状态，供 GUI 显示）。
     */
    public boolean isOnline() {
        return online;
    }

    /**
     * 是否正在工作（接入网络且网络激活且有能量）。
     */
    public boolean isWorking() {
        return working;
    }

    private void setOnline(boolean online) {
        if (this.online != online) {
            this.online = online;
            Torcherinoaemod.LOGGER.debug("[DBG] setOnline -> {} | grid={} isOnline={} isActive={} isPowered={}",
                    online, getMainNode().getGrid() != null, getMainNode().isOnline(),
                    getMainNode().isActive(), getMainNode().isPowered());
            // 同步给 GUI（writeToStream）显示的连接状态。
            markForClientUpdate();
            // 更新方块状态（online 属性），驱动客户端切换 on 模型，仅当属性变化时才会真正 setBlockAndUpdate。
            markForUpdate();
        }
    }

    private void setWorking(boolean working) {
        if (this.working != working) {
            this.working = working;
            Torcherinoaemod.LOGGER.debug("[DBG] setWorking -> {}", working);
            markForClientUpdate();
            markForUpdate();
        }
    }

    /**
     * 网络节点状态发生变化时（接入/断开、供电变化等）立即向客户端同步状态，
     * 避免仅依赖 commonTick 才能刷新连接显示。与 AE2 自带的机器保持一致。
     */
    @Override
    public void onMainNodeStateChanged(IGridNodeListener.State reason) {
        Torcherinoaemod.LOGGER.debug("[DBG] onMainNodeStateChanged reason={} | grid={} isOnline={} isActive={} isPowered={}",
                reason, getMainNode().getGrid() != null, getMainNode().isOnline(),
                getMainNode().isActive(), getMainNode().isPowered());
        // 「是否已接入网络」以 AE2 权威的节点状态变化事件为准，在这里据此重算 online
        // 并同步客户端。避免只在 commonTick 里计算——因为 IManagedGridNode.getGrid() 在
        // commonTick 调用时机的返回值不可靠（客户端侧永远为 null），导致 online 恒为 false、UI 显示未连接。
        setOnline(getMainNode().getGrid() != null && getMainNode().isOnline());
        // 网格接入状态变化时重新同步「由配置卡注入的设备」（如加载后网格从无到有，
        // 把卡上记录的设备纳入加速；或换网后撤销不再属于本网络的卡设备）。
        syncConfigCardDevices();
        // 无论 online 是否变化都刷新方块状态（驱动 on/off 模型切换）。
        markForUpdate();
    }

    @Override
    protected void writeToStream(RegistryFriendlyByteBuf data) {
        super.writeToStream(data);
        data.writeBoolean(online);
        data.writeBoolean(working);
        Torcherinoaemod.LOGGER.debug("[DBG] writeToStream(server): online={} working={}", online, working);
    }

    @Override
    protected boolean readFromStream(RegistryFriendlyByteBuf data) {
        boolean superResult = super.readFromStream(data);
        this.online = data.readBoolean();
        this.working = data.readBoolean();
        Torcherinoaemod.LOGGER.debug("[DBG] readFromStream(client): online={} working={}", online, working);
        return superResult;
    }
}
