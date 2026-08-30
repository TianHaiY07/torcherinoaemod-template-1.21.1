package com.tianhai.torcherino_ae.blockentity;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import appeng.api.config.Actionable;
import appeng.api.config.PowerMultiplier;
import appeng.api.inventories.InternalInventory;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IGridNodeListener;
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
import com.tianhai.torcherino_ae.Torcherinoaemod;
import com.tianhai.torcherino_ae.block.ModBlocks;
import com.tianhai.torcherino_ae.common.AE2GridSupport;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
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

    // 被选中进行加速的设备标识集合（服务端权威，随 NBT 持久化，重启后保留）。
    // 设备标识由 AE2GridSupport.deviceIdOf 生成：方块实体用坐标，部件用「坐标|朝向」，
    // 因此同一坐标上的多个可加速部件也能被各自独立选中、互不串扰。
    private final Set<String> acceleratedDevices = new HashSet<>();

    // 每台被加速设备独立的加速倍数（服务端权威，随 NBT 持久化，重启后保留）。
    // 键 = 设备标识，值 = 该设备的加速倍数；未加速设备不在表中（默认按 1 处理）。
    private final Map<String, Integer> deviceMultipliers = new HashMap<>();

    // 网络诊断计数器：每累计 20 tick（即 1 秒）输出一次完整连接状态，
    // 便于实时判断加速器是否真正连接上 AE 网络（排查「UI 显示未连接」问题）。
    private int diagnosticTimer;

    // 加速脉冲诊断计数器：每累计 20 tick（即 1 秒）输出一次设备命中明细。
    private int pulseDebugTimer;

    public AEAcceleratorBlockEntity(BlockEntityType<?> blockEntityType, BlockPos pos, BlockState state) {
        super(blockEntityType, pos, state);
        // 创建绑定到本机的升级卡库存，并在升级卡变化时通知保存。
        this.upgrades = UpgradeInventories.forMachine(ModBlocks.AE_ACCELERATOR.get(), UPGRADE_SLOTS,
                this::onUpgradesChanged);
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

        // 消耗 AE 内部能量：从网格的能量服务按需提取。
        double needed = getRequiredPowerPerTick();
        IEnergyService energy = grid.getEnergyService();
        double available = energy.extractAEPower(needed, Actionable.MODULATE, PowerMultiplier.ONE);

        // 提取到足够能量才认为处于工作状态（POWER_BUFFER_FRACTION 为缓冲，避免因浮点误差来回抖动）。
        boolean isWorking = available >= needed * POWER_BUFFER_FRACTION;
        setWorking(isWorking);
        if (log) {
            Torcherinoaemod.LOGGER.info(
                    "[DBG][工作诊断] {} | needed={} | available={} | isWorking={} | 加速倍数={} | 选中设备={}",
                    getBlockPos(), needed, available, isWorking, getAccelMultiplier(), acceleratedDevices);
        }

        // 加速脉冲：每个游戏 tick 对被选中的可加速设备执行一次加速
        // （先催促 tick，再在单个游戏 tick 内多次调用其网格 tick 逻辑）。
        // 未安装升级卡时最高 4x，每张速度升级卡额外 +2x。
        if (isWorking) {
            runAccelerationPulse(grid);
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
     */
    private void runAccelerationPulse(IGrid grid) {
        if (acceleratedDevices.isEmpty()) {
            return;
        }
        ITickManager tickManager = grid.getTickManager();
        // 网格未注册 tick 管理器（异常情况）时跳过本次脉冲，避免空指针。
        if (tickManager == null) {
            return;
        }
        // 每 20 tick（1 秒）输出一次加速诊断，避免每次调用都刷屏。
        boolean log = ++pulseDebugTimer >= 20;
        if (log) {
            pulseDebugTimer = 0;
        }
        int accelerated = 0;
        // 明细仅为诊断用，仅在需要打印时懒创建，避免每 tick 分配。
        StringBuilder detail = log ? new StringBuilder() : null;
        for (IGridNode node : grid.getNodes()) {
            // 只加速处于激活状态（已通电、已 Boot、满足通道）且属于可加速机器的设备
            // （筛选逻辑集中在 AE2GridSupport，见 isAcceleratableNode）。
            if (!node.isActive() || !AE2GridSupport.isAcceleratableNode(node, this)) {
                continue;
            }
            Object owner = node.getOwner();
            IGridTickable tickable = node.getService(IGridTickable.class);
            // 生成稳定的设备标识并匹配被选中的加速目标（方块用坐标，部件用「坐标|朝向」，见 AE2GridSupport）。
            String deviceId = AE2GridSupport.deviceIdOf(owner);
            if (deviceId == null || !acceleratedDevices.contains(deviceId)) {
                continue;
            }
            // 每台设备使用各自独立的加速倍数（界面滑块调整，随 NBT 持久化）。
            int deviceMultiplier = getDeviceMultiplier(deviceId);
            // 每个游戏 tick 额外触发的次数 = 该设备加速倍数 - 1（设备自身已按自然节奏 tick 1 次）。
            int extraCalls = Math.max(0, deviceMultiplier - 1);
            // 设备当前期望睡眠（空闲中），催促唤醒没有意义，跳过。
            boolean sleeping = tickable.getTickingRequest(node).isSleeping();
            if (log) {
                detail.append(String.format("[%s@%s active=%s sleeping=%s x%d]",
                        owner.getClass().getSimpleName(), deviceId, node.isActive(), sleeping, deviceMultiplier));
            }
            if (sleeping || extraCalls <= 0) {
                continue;
            }
            // 通过网格 tick 管理器把设备提前到「下一个 tick」触发（对 IO 端口/总线等
            // 自然节奏较慢、每次调用做固定量工作的设备有效）。
            tickManager.alertDevice(node);
            // 真正的加速：单个游戏 tick 内额外多次调用设备的网格 tick 逻辑。
            // 压印机、分子装配室、接口等机器工作期间返回 URGENT（已每游戏 tick 满速），
            // 仅靠 alertDevice 无法再提高频率，多次调用 tickingRequest 才能提升工作速率。
            for (int i = 0; i < extraCalls; i++) {
                tickable.tickingRequest(node, 1);
            }
            accelerated++;
        }
        if (log) {
            Torcherinoaemod.LOGGER.info(
                    "[DBG][加速诊断] {} | 实际加速设备={} | 命中明细={}",
                    getBlockPos(), accelerated, detail);
        }
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

    // ========================= NBT 持久化 =========================

    @Override
    public void saveAdditional(CompoundTag data, HolderLookup.Provider registries) {
        super.saveAdditional(data, registries);
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
