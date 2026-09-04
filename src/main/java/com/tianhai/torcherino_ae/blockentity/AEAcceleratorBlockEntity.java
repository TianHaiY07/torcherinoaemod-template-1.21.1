package com.tianhai.torcherino_ae.blockentity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

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
import appeng.api.upgrades.IUpgradeInventory;
import appeng.api.upgrades.IUpgradeableObject;
import appeng.api.upgrades.UpgradeInventories;
import appeng.blockentity.CommonTickingBlockEntity;
import appeng.blockentity.grid.AENetworkedPoweredBlockEntity;
import appeng.util.inv.AppEngInternalInventory;
import com.tianhai.torcherino_ae.api.AccelerationResult;
import com.tianhai.torcherino_ae.api.AccelerationTarget;
import com.tianhai.torcherino_ae.api.AccelSource;
import com.tianhai.torcherino_ae.api.BudgetMeter;
import com.tianhai.torcherino_ae.api.DeviceId;
import com.tianhai.torcherino_ae.api.IAccelerationSource;
import com.tianhai.torcherino_ae.block.ModBlocks;
import com.tianhai.torcherino_ae.config.RuntimeConfig;
import com.tianhai.torcherino_ae.config.SmartAccelerateScope;
import com.tianhai.torcherino_ae.network.DeviceScanner;
import com.tianhai.torcherino_ae.network.crafting.CraftingSupport;
import com.tianhai.torcherino_ae.core.AccelerationEngine;
import com.tianhai.torcherino_ae.core.AdaptiveThrottle;
import com.tianhai.torcherino_ae.core.MultiplierCalculator;
import com.tianhai.torcherino_ae.core.PowerModel;
import com.tianhai.torcherino_ae.core.TargetCache;
import com.tianhai.torcherino_ae.core.TargetRegistry;
import com.tianhai.torcherino_ae.item.ModItems;
import com.tianhai.torcherino_ae.util.DebugLog;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * AE 加速器方块实体。
 * <p>
 * 继承 AE2 的 {@link AENetworkedPoweredBlockEntity}：既接入 AE 网络，又通过 AE 内部能量体系供电。
 * 实现 {@link IUpgradeableObject} 以支持升级卡插槽，实现 {@link CommonTickingBlockEntity} 以做周期性工作。
 * 只有当方块接入并激活 AE 网络、且能从网络提取到足够能量时才会工作。
 * <p>
 * 职责划分：
 * <ul>
 *   <li><b>加速领域逻辑</b>：本类以 {@link IAccelerationSource} 身份接入统一的
 *       {@link AccelerationEngine} 脉冲执行（倍率见 {@link MultiplierCalculator}、
 *       能耗见 {@link PowerModel}、目标缓存见 {@link TargetCache}、
 *       加速登记表见 {@link TargetRegistry}），只回答「加速谁、每台多少倍、能否工作」；</li>
 *   <li><b>配置卡绑定协调</b>（库存、注入/撤销、移除清理、持久化）→ {@link ConfigCardBinding}；</li>
 *   <li><b>网络与状态</b>：AE 网络生命周期（节点事件 / 供能 / 库存回调等必须 override 的钩子）、
 *       online/working 状态同步（经 writeToStream/readFromStream 驱动模型）与网络诊断。</li>
 * </ul>
 * 未安装升级卡时最高加速 4x（基础倍率与各档系数均可由配置调整，默认分别为 4 / 2 / 4 / 8）；
 * 升级卡按「同档边际收益递减」放大：每档第 1 张（I=×2、II=×4、III=×8）全价生效，
 * 同档重复堆叠时逐张收益按配置 {@code accelerator.cardDiminishing} 递减，抑制指数爆炸
 * （默认满配 4 张 III 卡约 526 倍；保留比设 1.0 即还原旧的指数累乘。公式见 {@link MultiplierCalculator}）。
 * 可被加速的设备分两类：注册了网格 tick 服务（{@link IGridTickable}）经 AE2 网格 tick 管理器加速，
 * 或「接了 AE 网络、但加工走原版 {@code BlockEntity} tick」的机器，由引擎按倍率反复执行其原版 tick。
 */
public class AEAcceleratorBlockEntity extends AENetworkedPoweredBlockEntity
        implements IUpgradeableObject, CommonTickingBlockEntity, IAccelerationSource {

    // 升级卡插槽数量。
    public static final int UPGRADE_SLOTS = 4;

    // 基础加速倍数（默认值，供菜单等无实例场景作占位基准；实际生效值由 RuntimeConfig 提供，
    // 与 MultiplierCalculator / ConfigDefaults 保持同一事实来源）。
    public static final int BASE_ACCEL_MULTIPLIER = MultiplierCalculator.DEFAULT_BASE;

    // 用于供 GUI 显示的「是否已接入网络」状态（连接状态，供视觉显示，使用 isOnline 计算）。
    private boolean online;

    // 用于供 GUI 显示的「是否正在工作」状态（仅在网络激活且有能量时为 true）。
    private boolean working;

    // 升级卡库存。
    private final IUpgradeInventory upgrades;

    // 配置卡绑定协调（库存 + 注入/撤销 + 移除清理 + 持久化）。
    private final ConfigCardBinding configCardBinding;

    // 加速目标登记表：谁被加速、每台多少倍、由谁设置（玩家 / 配置卡），随 NBT 持久化。
    // 可见性为包内默认：同包协作类 ConfigCardBinding 需要做卡来源的注入/撤销。
    final TargetRegistry targetRegistry = new TargetRegistry();

    // 目标缓存：周期性 / 置脏时重建，避免每个 tick 全网格扫描。
    // 重建间隔取配置 cache.rebuildIntervalTicks 的当前值（方块每次创建/重开区块读取，默认 20 tick）。
    private final TargetCache targetCache = new TargetCache(RuntimeConfig.cacheRebuildIntervalTicks());

    // 智能联动目标标识集：rebuildTargets 时收集「未被登记、但被纳入智能联动」（见 shouldSmartLink，
    // 作用域由配置 crafting.smartAccelerateScope 控制）的设备，
    // multiplierFor 据此把这类目标按当前智能加速倍率放行。仅在缓存重建期间修改。
    private final Set<DeviceId> craftingLinkedIds = new HashSet<>();

    // 智能加速倍率缓存：每个游戏 tick 至多计算一次（扫描被选中且正在合成的 CPU）。
    private long smartCheckTick = -1;
    private int smartCpuMultiplier;

    // 单 tick 调用预算计量器：预算值由配置 budget.tickCallsPerSource 决定，变化时重建。
    private BudgetMeter budgetMeter = BudgetMeter.UNLIMITED_METER;
    private int budgetLimitTicks = BudgetMeter.UNLIMITED;

    // 网络诊断计数器：每累计 20 tick（即 1 秒）输出一次完整连接状态，
    // 便于实时判断加速器是否真正连接上 AE 网络（排查「UI 显示未连接」问题）。
    private int diagnosticTimer;

    public AEAcceleratorBlockEntity(BlockEntityType<?> blockEntityType, BlockPos pos, BlockState state) {
        super(blockEntityType, pos, state);
        // 创建绑定到本机的升级卡库存，并在升级卡变化时通知保存。
        this.upgrades = UpgradeInventories.forMachine(ModBlocks.AE_ACCELERATOR.get(), UPGRADE_SLOTS,
                this::onUpgradesChanged);
        // 创建配置卡绑定协调（含单格配置卡库存与绑定生命周期逻辑）。
        this.configCardBinding = new ConfigCardBinding(this);
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
     * 配置卡库存：单格，仅允许存放「绑定本机的加速器配置卡」。
     */
    public AppEngInternalInventory getConfigCardInventory() {
        return configCardBinding.getInventory();
    }

    /**
     * 升级卡库存发生变化时的回调：标记方块需要保存，并同步客户端。
     */
    private void onUpgradesChanged() {
        saveChanges();
        markForUpdate();
    }

    // ========================= 加速源契约（IAccelerationSource） =========================

    @Override
    public ResourceKey<Level> dimension() {
        Level world = level;
        return world != null ? world.dimension() : Level.OVERWORLD;
    }

    @Override
    public BlockPos origin() {
        return getBlockPos();
    }

    @Override
    public int maxMultiplier() {
        return getAccelMultiplier();
    }

    @Override
    public boolean isActive() {
        // AE2 的 isActive() 在节点不存在或未入网时（isPowered 对无网格节点返回 false 短路）
        // 安全返回 false，无需先判网格；直接调用即可覆盖全部状态，也不会触发
        // 「节点未入网时 getGrid() 抛 ISE」的问题（见 grid()）。
        return getMainNode().isActive();
    }

    @Override
    public List<AccelerationTarget> targets() {
        return targetCache.resolve(this::rebuildTargets);
    }

    @Override
    public int multiplierFor(DeviceId id) {
        // 已登记设备（玩家勾选 / 配置卡注入）：用登记表中该设备的独立倍数。
        int registered = targetRegistry.multiplierFor(id, -1);
        if (registered > 1) {
            return registered;
        }
        // 未登记但属于智能联动目标：按当前智能加速倍率放行（受配置 crafting.smartAccelerateEnabled
        // 控制；关闭时返回 1，引擎因 extraCalls<=0 自动跳过，不产生联动加速）。
        if (RuntimeConfig.smartAccelerateEnabled() && craftingLinkedIds.contains(id)) {
            return currentSmartCpuMultiplier();
        }
        return 1;
    }

    @Override
    public BudgetMeter budget() {
        // 预算上限 = 静态配置（budget.tickCallsPerSource，默认 -1 不限）经 TPS 自适应节流调整后
        // 的生效值（AdaptiveThrottle）：健康负载时原样返回配置值，单 tick 逼近 50ms 硬限时
        // 自动收紧到下限，回落自动恢复。计量器实例被缓存：仅当生效预算变化（配置改动或
        // 收紧/恢复切换）时才重建，平时每个 tick 零分配。
        int limit = AdaptiveThrottle.INSTANCE.adjust(RuntimeConfig.budgetTickCallsPerSource());
        if (limit != budgetLimitTicks) {
            budgetLimitTicks = limit;
            budgetMeter = new BudgetMeter(limit);
        }
        return budgetMeter;
    }

    /**
     * 安全读取节点当前所属网格（{@link IAccelerationSource} 契约）。
     * <p>
     * AE2 的 {@code GridNode.getGrid()} 在底层节点存在但未入网时抛
     * {@link IllegalStateException} 而非返回 {@code null}（与
     * {@link AccelerationTarget#gridOf} 转译语义一致）——本方块实体所有内部逻辑与菜单
     * 统一经此方法取网格，把「无网格」一律转译为 {@code null}，调用方只需判空。
     */
    @Nullable
    @Override
    public IGrid grid() {
        try {
            return getMainNode().getGrid();
        } catch (IllegalStateException destroyed) {
            return null;
        }
    }

    @Override
    public void markTargetsDirty() {
        targetCache.markDirty();
    }

    // ========================= 周期性工作 =========================

    @Override
    public void commonTick() {
        // 客户端不执行工作逻辑：网络状态判断、能量消耗与加速脉冲都只在服务端进行。
        // 客户端方块实体的 online/working 完全由服务端经 writeToStream/readFromStream 同步。
        if (level != null && level.isClientSide()) {
            return;
        }

        // 网络是否「已接入」由 AE2 权威事件 onMainNodeStateChanged 驱动更新（见下方实现），
        // 这里不再重复计算 isOnline，以免 getGrid() 在不一致的调用时机返回 null 把 true 覆盖回 false。
        // 经 grid() 安全取值：节点存活但未入网（孤立摆放 / 断缆瞬间）的窗口内 getGrid() 会抛 ISE，
        // 统一转译为 null 后按「未接入」处理，避免方块实体每 tick 路径崩溃。
        IGrid grid = grid();

        // 周期性（每 DEFAULT_SAMPLE_INTERVAL tick，即 1 秒）输出一次网络/工作诊断日志，便于实时定位
        // 「未加速 / UI 显示未连接 / 模型未切换」究竟卡在网格、电力还是设备匹配环节。
        // 开关关闭时（默认）连计数都不推进，每 tick 只剩一次 volatile 布尔读取。
        boolean log = DebugLog.isEnabled() && ++diagnosticTimer >= RuntimeConfig.debugSampleIntervalTicks();
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
        AccelerationResult result = AccelerationEngine.pulse(this);

        boolean isWorking = false;
        double available = 0;
        if (result.didWork()) {
            // 消耗 AE 内部能量：从网格的能量服务按需提取（powerBufferFraction 为缓冲占比，
            // 由配置 power.bufferFraction 提供，默认 0.9，避免浮点误差来回抖动）。
            double bufferFraction = RuntimeConfig.powerBufferFraction();
            available = grid.getEnergyService().extractAEPower(needed, Actionable.MODULATE, PowerMultiplier.ONE);
            isWorking = available >= needed * bufferFraction;
        }
        setWorking(isWorking);
        if (log) {
            DebugLog.info(
                    "[诊断][工作] {} | needed={} | available={} | isWorking={} | 实际加速设备={} | 调用次数={} |"
                            + " 睡眠跳过={} | 未激活跳过={} | 脱离剔除={} | 预算耗尽={} | 最高倍数={} | 登记设备={}"
                            + " | 自适应节流={} | tick耗时EMA={}ms",
                    getBlockPos(), needed, available, isWorking, result.hit(), result.tickCalls(),
                    result.skippedSleeping(), result.skippedInactive(), result.skippedDetached(),
                    result.budgetExhausted(), getAccelMultiplier(), targetRegistry.size(),
                    AdaptiveThrottle.INSTANCE.isThrottled()
                            ? "收紧(档" + AdaptiveThrottle.INSTANCE.throttleLevel() + ")"
                            : "正常",
                    (double) Math.round(AdaptiveThrottle.INSTANCE.emaTickMs() * 10) / 10);
        }
    }

    // ========================= 倍率与能量 =========================

    /**
     * 当前最高加速倍数：基础 4x，随已安装的升级卡放大（同档堆叠边际收益递减）。
     * <p>
     * 放大规则见 {@link MultiplierCalculator}：I/II/III 各档第 1 张按标称系数 2/4/8 全价生效，
     * 同档多张的额外增益按配置保留比逐张衰减，避免 4 张 III 卡直接指数爆炸。
     * 注意：这是「滑块可调的上限」，每台设备实际使用的倍数是
     * 独立的，由 {@link #getDeviceMultiplier(DeviceId)} 返回，可通过界面中的横向滚动条调整。
     */
    public int getAccelMultiplier() {
        // 基础倍率、三档标称系数与同档边际收益保留比均从配置读取（RuntimeConfig 快照）。
        int result = MultiplierCalculator.compute(
                RuntimeConfig.accelBaseMultiplier(),
                RuntimeConfig.accelCardFactor(0), RuntimeConfig.accelCardFactor(1),
                RuntimeConfig.accelCardFactor(2),
                RuntimeConfig.accelCardDiminishing(),
                upgrades.getInstalledUpgrades(ModItems.ACCELERATOR_UPGRADE_CARD_I.get()),
                upgrades.getInstalledUpgrades(ModItems.ACCELERATOR_UPGRADE_CARD_II.get()),
                upgrades.getInstalledUpgrades(ModItems.ACCELERATOR_UPGRADE_CARD_III.get()));
        // 应用最高倍数硬上限（accelerator.maxMultiplierCap，-1 表示不限制）。
        int cap = RuntimeConfig.accelMaxMultiplierCap();
        return cap > 0 ? Math.min(result, cap) : result;
    }

    /**
     * 依据已安装的升级卡数量与被登记的设备数量计算每 tick 能量消耗。
     * <p>
     * 能耗按「三种升级卡数量之和」线性叠加，与倍数的复合累乘无关——升级卡越多，
     * 机器维持加速所需的能量越高。被加速设备数按登记表规模计（玩家勾选 + 配置卡注入）。
     */
    private double getRequiredPowerPerTick() {
        int upgradeCards = upgrades.getInstalledUpgrades(ModItems.ACCELERATOR_UPGRADE_CARD_I.get())
                + upgrades.getInstalledUpgrades(ModItems.ACCELERATOR_UPGRADE_CARD_II.get())
                + upgrades.getInstalledUpgrades(ModItems.ACCELERATOR_UPGRADE_CARD_III.get());
        return PowerModel.requiredPerTick(
                RuntimeConfig.powerPerTick(), RuntimeConfig.powerPerUpgradeCard(),
                RuntimeConfig.powerPerAcceleratedDevice(),
                upgradeCards, targetRegistry.size());
    }

    // ========================= 智能加速倍率 =========================

    /**
     * 读取当前智能加速倍率（每个游戏 tick 只计算一次并缓存）。
     * <p>
     * 智能加速：选中「合成 CPU」后，当 CPU 处于合成状态时，对参与合成的机器做联动加速。
     * 合成 CPU 状态每 tick 变化，联动目标每 tick 都要拿到当前值；而 CPU 数量通常只有几台，
     * 扫描开销可忽略。用游戏时间做缓存键，避免同一 tick 内为每台联动设备重复扫描。
     */
    private int currentSmartCpuMultiplier() {
        Level world = level;
        if (world == null) {
            return 0;
        }
        long now = world.getGameTime();
        if (now != smartCheckTick) {
            smartCheckTick = now;
            smartCpuMultiplier = computeSmartCpuMultiplier();
        }
        return smartCpuMultiplier;
    }

    /**
     * 扫描网格中被玩家选中（已登记）且正有任务在跑（{@code isBusy()}）的合成 CPU，
     * 返回其登记倍率的最大值；没有任何被选中的 CPU 处于合成状态时返回 0。
     */
    private int computeSmartCpuMultiplier() {
        IGrid grid = grid();
        if (grid == null) {
            return 0;
        }
        int max = 0;
        int accelMultiplier = getAccelMultiplier();
        for (ICraftingCPU cpu : grid.getCraftingService().getCpus()) {
            DeviceId id = CraftingSupport.cpuDeviceId(dimension(), cpu);
            if (id == null || !targetRegistry.isAccelerated(id) || !cpu.isBusy()) {
                continue;
            }
            max = Math.max(max, targetRegistry.multiplierFor(id, accelMultiplier));
        }
        return max;
    }

    // ========================= 目标缓存重建 =========================

    /**
     * 重新遍历网格，把加速目标收集进缓存。
     * <p>
     * 只遍历网格一次，产出两类目标并写入同一列表（引擎按每台设备的倍率放行）：
     * <ul>
     *   <li>已登记的设备（玩家勾选 / 配置卡注入）→ 常规加速目标；</li>
     *   <li>未被登记、但被纳入智能联动的设备（见 {@link #shouldSmartLink}，作用域由配置
     *       {@code crafting.smartAccelerateScope} 控制：默认联动网内全部可加速设备以兼容任意
     *       第三方 AE 工作机器；保守模式仅联动 ICraftingProvider 与合成执行机器）→
     *       标识记入 {@link #craftingLinkedIds}，仅当有正在合成的被选中 CPU 时才被放行。</li>
     * </ul>
     * 筛选谓词与菜单设备列表采集保持一致（见 {@link DeviceScanner#isAcceleratableNode}）。
     */
    private List<AccelerationTarget> rebuildTargets() {
        IGrid grid = grid();
        if (grid == null) {
            craftingLinkedIds.clear();
            return List.of();
        }
        List<AccelerationTarget> targets = new ArrayList<>();
        craftingLinkedIds.clear();
        for (IGridNode node : grid.getNodes()) {
            if (!DeviceScanner.isAcceleratableNode(node, this)) {
                continue;
            }
            Object owner = node.getOwner();
            DeviceId id = DeviceScanner.deviceIdOf(owner);
            if (id == null) {
                continue;
            }
            IGridTickable tickable = node.getService(IGridTickable.class);
            BlockEntityTicker<BlockEntity> vanilla = owner instanceof BlockEntity be
                    ? DeviceScanner.vanillaTicker(be)
                    : null;
            // 加速载体：AE2 网格 tick 或原版 tick 至少其一；两者皆无则无真实加工节奏，跳过。
            if (tickable == null && vanilla == null) {
                continue;
            }
            if (targetRegistry.isAccelerated(id)) {
                targets.add(new AccelerationTarget(id, node, tickable, vanilla));
            } else if (shouldSmartLink(node)) {
                targets.add(new AccelerationTarget(id, node, tickable, vanilla));
                craftingLinkedIds.add(id);
            }
        }
        return targets;
    }

    /**
     * 判断网格节点是否应被纳入「智能加速联动」。
     * <p>
     * 是否「此刻参与」（机器忙碌）由加速脉冲内的睡眠判断共同决定，这里只负责把
     * 「候选联动目标」纳入集合。作用域由配置 {@code crafting.smartAccelerateScope} 控制：
     * <ul>
     *   <li>{@link SmartAccelerateScope#ALL_ACCELERATABLE}（默认）：凡经过
     *       {@link DeviceScanner#isAcceleratableNode} 判定可加速（非黑名单基础设施）的设备
     *       一律联动——对任意第三方 AE 工作机器零配置生效，无需它们实现 AE2 接口/能力。
     *       进入 {@code rebuildTargets} 的节点必然已通过该判定，故此处直接放行。</li>
     *   <li>{@link SmartAccelerateScope#CRAFTING_MACHINES}：仅联动「合成相关机器」，
     *       依赖 {@link #isCraftingRelated(IGridNode)} 的 AE2 接口 / 能力 / 类型表识别。</li>
     * </ul>
     */
    private static boolean shouldSmartLink(IGridNode node) {
        if (RuntimeConfig.smartAccelerateScope() == SmartAccelerateScope.ALL_ACCELERATABLE) {
            return true;
        }
        return isCraftingRelated(node);
    }

    /**
     * 判断网格节点是否属于「合成相关机器」。
     * <p>
     * 参与合成的机器分两类：
     * <ul>
     *   <li>pattern provider（接口、样板供应器）：在节点上注册了 {@link ICraftingProvider}
     *       服务，负责接收 CPU 派发的合成任务；</li>
     *   <li>合成执行机器（见 {@link CraftingSupport#isCraftingMachineType}）：凡实现
     *       {@link appeng.api.implementations.blockentities.ICraftingMachine} 的方块
     *       （分子装配室及第三方）一律命中；未实现该接口但参与合成的压印机、充能器
     *       通过类型集合兜底。被邻接的 pattern provider 调用、真正执行合成。</li>
     * </ul>
     * 借助该判定，无需回溯 CPU 的内部任务映射（AE2 未公开「CPU → 具体机器」的查询），
     * 即可命中「正在为合成提供服务」的机器。
     */
    private static boolean isCraftingRelated(IGridNode node) {
        if (node.getService(ICraftingProvider.class) != null) {
            return true;
        }
        Object owner = node.getOwner();
        return owner != null && CraftingSupport.isCraftingMachineType(owner);
    }

    // ========================= 加速目标管理（供菜单调用） =========================

    /**
     * 目标登记表版本号（每变更一次 +1）：供菜单判断「设备列表缓存」是否失效。
     * <p>
     * 玩家勾选 / 调倍数 / 配置卡注入或取出 / 加载存档都会使登记表内容变化，进而使设备列表的
     * 「加速中」标记与倍率过期；版本号变化时菜单才会重建并重新下发列表。
     */
    public int targetRegistryVersion() {
        return targetRegistry.version();
    }

    /**
     * 指定设备当前是否处于被加速状态（任来源：玩家勾选或配置卡注入）。
     */
    public boolean isAccelerating(DeviceId deviceId) {
        return targetRegistry.isAccelerated(deviceId);
    }

    /**
     * 指定设备的当前加速倍数：已登记则返回其登记倍数，否则返回最高倍数
     * （未设置过的设备默认按最高加速，供界面展示与滑块初值）。
     */
    public int getDeviceMultiplier(DeviceId deviceId) {
        return targetRegistry.multiplierFor(deviceId, getAccelMultiplier());
    }

    /**
     * 设置指定设备的加速倍数（界面横向滚动条实时发送）。
     * <p>
     * 倍数大于 1 时登记为「玩家设置」来源；倍数小于等于 1 视为「取消加速」，移除该来源的登记。
     * 由菜单的服务端动作处理器调用。
     *
     * @param deviceId   设备标识（含维度与种类，见 {@link DeviceId}）
     * @param multiplier 新的加速倍数（1 表示不加速）
     */
    public void setDeviceMultiplier(DeviceId deviceId, int multiplier) {
        if (multiplier <= 1) {
            // 取消：移除玩家来源。若设备仍被配置卡注入（CONFIG_CARD 来源），下次卡片同步
            // 仍会按卡片配置恢复——玩家要彻底停止卡管理的设备，应取出配置卡。
            targetRegistry.set(deviceId, 1, AccelSource.PLAYER);
        } else {
            // 上限钳制到当前最高倍数（受速度升级卡影响）。
            int clamped = Math.min(multiplier, getAccelMultiplier());
            targetRegistry.set(deviceId, clamped, AccelSource.PLAYER);
        }
        // 选中集合发生变化：标记缓存待重建，使「点击加速 / 取消加速」立即生效。
        markTargetsDirty();
        saveChanges();
        markForClientUpdate();
    }

    /**
     * 切换指定设备的加速状态（未加速 → 按最高倍数加速；已加速 → 取消加速）。
     * 由菜单的服务端动作处理器调用。
     */
    public void toggleAcceleratedDevice(DeviceId deviceId) {
        if (targetRegistry.isAccelerated(deviceId)) {
            setDeviceMultiplier(deviceId, 1);
        } else {
            setDeviceMultiplier(deviceId, getAccelMultiplier());
        }
    }

    // ========================= 库存回调与生命周期 =========================

    /**
     * 库存内容变化回调（由内部库存触发）：配置卡槽位变化转交绑定组件处理
     * （放入/取出/更换 -> 立即同步卡注入），其余库存变化走超类默认逻辑（保存方块实体）。
     */
    @Override
    public void onChangeInventory(AppEngInternalInventory inv, int slot) {
        super.onChangeInventory(inv, slot);
        configCardBinding.onHostInventoryChanged(inv, slot);
    }

    /**
     * 方块被移除（破坏、爆炸、活塞等任何途径）：先走超类清理，再由配置卡绑定组件清空
     * 槽位内与在线玩家背包中绑定本机的配置卡（避免「即插即用」配置指向已摧毁的加速器）。
     */
    @Override
    public void setRemoved() {
        super.setRemoved();
        configCardBinding.onHostRemoved();
    }

    // ========================= NBT 持久化 =========================

    @Override
    public void saveAdditional(CompoundTag data, HolderLookup.Provider registries) {
        super.saveAdditional(data, registries);
        // 持久化配置卡库存内容（由绑定组件负责），重启后保留玩家放入的配置卡。
        configCardBinding.save(data, registries);
        // 持久化加速目标登记表（含来源标记），重启后保留玩家设置与卡注入，且可精确撤销。
        targetRegistry.save(data, registries);
    }

    @Override
    public void loadTag(CompoundTag data, HolderLookup.Provider registries) {
        super.loadTag(data, registries);
        // 恢复配置卡库存内容（由绑定组件负责）。
        configCardBinding.load(data, registries);
        // 恢复目标登记表（含 PLAYER / CONFIG_CARD 来源标记）。
        targetRegistry.load(data, registries);
        // 目标缓存标脏：首个 tick 会重建；配置卡注入的同步待网格就绪后由节点状态回调触发。
        markTargetsDirty();
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
            DebugLog.info("[诊断][网络] {} 未接入任何 AE 网格（grid=null），视为离线", getBlockPos());
            return;
        }

        IEnergyService energy = grid.getEnergyService();
        IGridNode gridNode = node.getNode();
        if (gridNode == null) {
            DebugLog.info("[诊断][网络] {} 已发现网格，但底层节点仍为空（尚未注册到网格节点列表）", getBlockPos());
            return;
        }
        DebugLog.info(
                "[诊断][网络] {} | 连接面={} | 已Boot={} | 有电={} | 通道满足={} | 激活={} | 在线={} | 通道={}/{} |"
                        + " 网络能量={}/{} | 登记设备={}",
                getBlockPos(), gridNode.getConnectedSides(), gridNode.hasGridBooted(), gridNode.isPowered(),
                gridNode.meetsChannelRequirements(), gridNode.isActive(), gridNode.isOnline(),
                gridNode.getUsedChannels(), gridNode.getMaxChannels(), energy.getStoredPower(),
                energy.getMaxStoredPower(), targetRegistry.size());
    }

    // ========================= 状态同步（GUI / 模型） =========================

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
            // 注意：getMainNode().isPowered() 内部走 getGrid()，节点未入网时同样会抛 ISE，
            // 因此用安全取的网格 + 其能量服务推导，避免状态切换瞬间崩溃（见 grid()）。
            IGrid grid = grid();
            DebugLog.debug("[状态] setOnline -> {} | grid={} isOnline={} isActive={} isPowered={}",
                    online, grid != null, getMainNode().isOnline(), getMainNode().isActive(),
                    grid != null && grid.getEnergyService().isNetworkPowered());
            // 同步给 GUI（writeToStream）显示的连接状态。
            markForClientUpdate();
            // 更新方块状态（online 属性），驱动客户端切换 on 模型，仅当属性变化时才会真正 setBlockAndUpdate。
            markForUpdate();
        }
    }

    private void setWorking(boolean working) {
        if (this.working != working) {
            this.working = working;
            DebugLog.debug("[状态] setWorking -> {}", working);
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
        IGrid grid = grid();
        DebugLog.debug("[状态] onMainNodeStateChanged reason={} | grid={} isOnline={} isActive={} isPowered={}",
                reason, grid != null, getMainNode().isOnline(), getMainNode().isActive(),
                grid != null && grid.getEnergyService().isNetworkPowered());
        // 「是否已接入网络」以 AE2 权威的节点状态变化事件为准，在这里据此重算 online
        // 并同步客户端。避免只在 commonTick 里计算——因为 IManagedGridNode.getGrid() 在
        // commonTick 调用时机的返回值不可靠（客户端侧永远为 null），导致 online 恒为 false、UI 显示未连接。
        setOnline(grid != null && getMainNode().isOnline());
        // 网格接入状态变化时重新同步「由配置卡注入的设备」（如加载后网格从无到有，
        // 把卡上记录的设备纳入加速；或换网后撤销不再属于本网络的卡设备）。
        configCardBinding.onHostInventoryChanged(getConfigCardInventory(), 0);
        // 无论 online 是否变化都刷新方块状态（驱动 on/off 模型切换）。
        markForUpdate();
    }

    @Override
    protected void writeToStream(RegistryFriendlyByteBuf data) {
        super.writeToStream(data);
        data.writeBoolean(online);
        data.writeBoolean(working);
        DebugLog.debug("[同步] writeToStream(server): online={} working={}", online, working);
    }

    @Override
    protected boolean readFromStream(RegistryFriendlyByteBuf data) {
        boolean superResult = super.readFromStream(data);
        this.online = data.readBoolean();
        this.working = data.readBoolean();
        DebugLog.debug("[同步] readFromStream(client): online={} working={}", online, working);
        return superResult;
    }
}
