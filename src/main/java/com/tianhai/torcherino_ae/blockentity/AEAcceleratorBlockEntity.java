package com.tianhai.torcherino_ae.blockentity;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import com.tianhai.torcherino_ae.network.GridOwner;
import com.tianhai.torcherino_ae.network.PatternProviderSupport;
import com.tianhai.torcherino_ae.network.crafting.CraftingSupport;
import com.tianhai.torcherino_ae.core.AccelerationEngine;
import com.tianhai.torcherino_ae.core.AdaptiveThrottle;
import com.tianhai.torcherino_ae.core.MultiplierCalculator;
import com.tianhai.torcherino_ae.core.PowerModel;
import com.tianhai.torcherino_ae.core.RateGovernor;
import com.tianhai.torcherino_ae.core.SourceBudget;
import com.tianhai.torcherino_ae.core.TargetCache;
import com.tianhai.torcherino_ae.core.TargetRegistry;
import com.tianhai.torcherino_ae.item.ModItems;
import com.tianhai.torcherino_ae.util.AeGrid;
import com.tianhai.torcherino_ae.util.DebugLog;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
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

    // 样板供应器下游联动映射：下游设备标识 → 联动它的母样板供应器标识集。
    // 在 rebuildTargets 期间重建：凡「被加速（登记或智能联动）的样板供应器」，
    // 其投放方向（PatternProviderSupport.pushDirections）上的相邻可加速设备（且与本源同网格）
    // 都会记入；multiplierFor 对这些下游设备按「母源生效倍率」放行（供应链联动，
    // 见 collectDownstreamTargets 与 multiplierFor），使投料链路上下游获得同等加速。
    private final Map<DeviceId, Set<DeviceId>> downstreamSources = new HashMap<>();

    // 智能加速倍率缓存：每个游戏 tick 至多计算一次（扫描被选中且正在合成的 CPU）。
    private long smartCheckTick = -1;
    private int smartCpuMultiplier;

    // 单 tick 调用预算：预算值由配置 budget.tickCallsPerSource 经 TPS 自适应节流决定，变化时重建（见 SourceBudget）。
    private final SourceBudget budget = new SourceBudget();

    // 源级加速耗时调控：按「本加速器自身贡献的加速耗时」把实际倍率动态下调（设定倍率只作总阈值）。
    // 每台加速器独立计量，别处负载不干扰；AdaptiveThrottle 的调用次数预算保留作极端兜底（见 RateGovernor）。
    private final RateGovernor rateGovernor = new RateGovernor();

    // 网络诊断计数器：每累计 20 tick（即 1 秒）输出一次完整连接状态，
    // 便于实时判断加速器是否真正连接上 AE 网络（排查「UI 显示未连接」问题）。
    private int diagnosticTimer;

    // 配置卡 CPU 绑定自愈周期：每累计该 tick 数核对一次卡槽内卡片绑定的合成 CPU 组是否仍
    // 以记录的结构真实成型（失效即删/换绑，见 ConfigCardBinding.reconcileCpuBindings）。
    // 计数在 commonTick 首部推进，即使加速器未接线/离网也持续自愈。
    private static final int CPU_BINDING_RECONCILE_INTERVAL = 40; // 2 秒
    private int cpuBindingReconcileTicks;

    // NBT 存储键名：本机「放置时刻」（同网络仅一台加速器工作的先后判定基准）。
    private static final String TAG_CREATED_TICK = "created_tick";

    // 本机的「放置时刻」（世界游戏时间，随 NBT 持久化）：首次服务端 tick 记录。
    // 同网络存在多台加速器时以此裁决「先放置者优先」：后放置者停止工作（不叠加加速）。
    // Long.MIN_VALUE 表示尚未记录（新放置未首 tick / 旧档无记录），此时按方块坐标字典序
    // 作确定性平局裁决（见 isNotLaterThan），保证任何时序下只留一台工作。
    private long createdAtTick = Long.MIN_VALUE;

    // 网络中是否存在「不晚于本机放置」的其它加速器（服务端维护，见 refreshNetworkHasOtherAccelerator）。
    // 存在时本机停止工作（不叠加加速），GUI 隐藏设备列表并显示「该网络已存在AE加速器！」提示。
    private boolean networkHasOtherAccelerator;

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
        // 已登记设备（玩家勾选 / 配置卡注入）用其独立倍数：显式设置优先，不叠加、不被覆盖。
        int registered = targetRegistry.multiplierFor(id, -1);
        if (registered > 1) {
            return Math.min(registered, runCap());
        }
        // 未登记：在「样板供应器下游联动（继承母源）倍率」与「自身智能候选倍率」间取最大——
        // 供应链联动的语义是「投料方在加速、接收方不能掉队」（母源倍率更高时下游同等跟进，
        // 正是「供应链全部关联设备同等加速」）；自身智能候选更高时（CPU 合成正在用这台机器）
        // 也不能被供应链低倍率拖累。两条语义独立生效、取强者，与登记优先不冲突。
        int linked = downstreamLinkedMultiplier(id);
        int smart = 1;
        if (RuntimeConfig.smartAccelerateEnabled() && craftingLinkedIds.contains(id)) {
            smart = currentSmartCpuMultiplier();
        }
        int best = Math.max(linked, smart);
        // 实际倍率 = min(设定倍率, 被测耗时压到的 runCap)：设定倍率只是总阈值，只往下调不往上超。
        return best > 1 ? Math.min(best, runCap()) : 1;
    }

    /**
     * 指定设备的自身生效倍率（不含 {@link #runCap()} 压顶与<b>下游联动递归</b>）：
     * 已登记（PLAYER / CONFIG_CARD）返回登记值；未登记但在智能联动集内返回
     * 当前智能加速倍率；两者皆未中返回 1。仅供「下游联动倍率合成」
     * （{@link #downstreamLinkedMultiplier}）查询母源时使用——母源倍率
     * 只认登记值/智能倍率，不递归查询母源自己的下游，防止供应链倍率循环放大。
     */
    private int sourceMultiplierFor(DeviceId id) {
        int registered = targetRegistry.multiplierFor(id, -1);
        if (registered > 1) {
            return registered;
        }
        // 未登记但属于智能联动目标：按当前智能加速倍率放行（受配置
        // crafting.smartAccelerateEnabled 控制；关闭时返回 1，引擎因 extraCalls<=0
        // 自动跳过，不产生联动加速）。
        if (RuntimeConfig.smartAccelerateEnabled() && craftingLinkedIds.contains(id)) {
            return currentSmartCpuMultiplier();
        }
        return 1;
    }

    /**
     * 合成指定设备作为「样板供应器下游」时应继承的联动倍率。
     * <p>
     * 经 {@link #downstreamSources} 反向查找联动它的全部母样板供应器，
     * 取各母源自身生效倍率（{@link #sourceMultiplierFor}，本身也受智能联动规则）的最大值；
     * 无母源或母源全部 ≤1 时返回 1。母源倍率查询不递归下游（供应链只向下游方向
     * 单层继承，不会出现倍率循环放大）。
     */
    private int downstreamLinkedMultiplier(DeviceId id) {
        return PatternProviderSupport.linkedMultiplier(downstreamSources.get(id), this::sourceMultiplierFor);
    }

    /**
     * 当前「实际可用」的加速倍率上限（总阈值 = 整体最高倍率 {@link #getAccelMultiplier()}）。
     * <p>
     * 由 {@link #rateGovernor} 依据本加速器「本 tick 贡献的加速耗时」动态下调：负载健康时返回
     * 整体最高倍率（不干预），本加速器把自己主线程时间挤到配置上限以上时按比例压到
     * {@code [1, max]}。GUI 显示的仍是设定倍率（{@link #getDeviceMultiplier}），实际倍率仅在
     * 脉冲执行时经 {@link #multiplierFor} 生效。
     */
    public int runCap() {
        return rateGovernor.cap(getAccelMultiplier());
    }

    @Override
    public BudgetMeter budget() {
        // 预算上限 = 静态配置（budget.tickCallsPerSource，默认 -1 不限）经 TPS 自适应节流调整后
        // 的生效值（AdaptiveThrottle）：健康负载时原样返回配置值，单 tick 逼近 50ms 硬限时
        // 自动收紧到下限，回落自动恢复。计量器实例被缓存：仅当生效预算变化（配置改动或
        // 收紧/恢复切换）时才重建，平时每个 tick 零分配（缓存在 SourceBudget 持有器内）。
        return budget.get();
    }

    /**
     * 安全读取节点当前所属网格（{@link IAccelerationSource} 契约）。
     * <p>
     * AE2 的 {@code GridNode.getGrid()} 在底层节点存在但未入网时抛
     * {@link IllegalStateException} 而非返回 {@code null}（与
     * {@link AccelerationTarget#gridOf} 转译语义一致，实现委托 {@code util.AeGrid}）——本方块实体
     * 所有内部逻辑与菜单统一经此方法取网格，把「无网格」一律转译为 {@code null}，调用方只需判空。
     */
    @Nullable
    @Override
    public IGrid grid() {
        return AeGrid.gridOf(getMainNode().getNode());
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

        // 本机放置时刻：首次服务端 tick 记录（世界游戏时间，随 NBT 持久化）。
        // 同网络多台加速器时作为「先放置者优先」的先后判定基准（见 isNotLaterThan）。
        if (createdAtTick == Long.MIN_VALUE) {
            createdAtTick = level.getGameTime();
            setChanged();
        }

        // 配置卡绑定的合成 CPU 组自愈（周期核对，见 ConfigCardBinding.reconcileCpuBindings）：
        // 破坏清理只由「玩家挖掘/爆炸」事件驱动，CPU 经其它途径失效（改成 L 形等无效形状、
        // 拆除事件漏网、同网络加速器定位失败）时卡上记录会残留；本判定与网络/电力无关，
        // 方块实体只要在服务端加载就持续推进，保证失效记录被及时删除或换绑。
        if (++cpuBindingReconcileTicks >= CPU_BINDING_RECONCILE_INTERVAL) {
            cpuBindingReconcileTicks = 0;
            configCardBinding.reconcileCpuBindings();
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

        // 同网络已有「先放置的」其它加速器：本机停止工作（不叠加加速）、不耗电、不标记工作，
        // GUI 隐藏设备列表并显示「该网络已存在AE加速器！」（见 AEAcceleratorMenu / AEAcceleratorScreen）。
        // 标记由网络节点状态事件即时刷新 + 目标缓存重建周期兜底（见 refreshNetworkHasOtherAccelerator）。
        if (networkHasOtherAccelerator) {
            setWorking(false);
            return;
        }

        // 节能措施：先执行加速脉冲，统计本 tick「真正被加速」（非睡眠、正处工作状态）的设备数。
        // 只有确实有设备在工作时才消耗能量并标记 working；否则（所有选中设备都已空闲）
        // 不提取能量、不标记工作，方块停留在 ae_accelerator_on（接电但空闲）模型，实现节能。
        double needed = getRequiredPowerPerTick();
        AccelerationResult result = AccelerationEngine.pulse(this);
        // 把本次脉冲实际耗时喂入本源耗时调控：据此动态下调实际倍率（设定倍率只作总阈值）。
        // 无论是否 didWork 都采样——全部设备空转时耗时很小，EMA 回落，实际倍率自动回升。
        rateGovernor.sample(result.spentMs());

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
                            + " 睡眠跳过={} | 未激活跳过={} | 脱离剔除={} | 预算耗尽={} | 最高倍数={} | 实际上限={} |"
                            + " 登记设备={} | 自适应节流={} | tick耗时EMA={}ms | 本源加速EMA={}ms | 倍率因子={}",
                    getBlockPos(), needed, available, isWorking, result.hit(), result.tickCalls(),
                    result.skippedSleeping(), result.skippedInactive(), result.skippedDetached(),
                    result.budgetExhausted(), getAccelMultiplier(), runCap(), targetRegistry.size(),
                    AdaptiveThrottle.INSTANCE.isThrottled()
                            ? "收紧(档" + AdaptiveThrottle.INSTANCE.throttleLevel() + ")"
                            : "正常",
                    (double) Math.round(AdaptiveThrottle.INSTANCE.emaTickMs() * 10) / 10,
                    (double) Math.round(rateGovernor.emaMs() * 10) / 10,
                    (double) Math.round(rateGovernor.factor() * 100) / 100);
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
     * 只遍历网格一次，产出两类目标（引擎按每台设备的倍率放行）：
     * <ul>
     *   <li>已登记的设备（玩家勾选 / 配置卡注入）→ 常规加速目标；</li>
     *   <li>未被登记、但被纳入智能联动的设备（见 {@link #shouldSmartLink}，作用域由配置
     *       {@code crafting.smartAccelerateScope} 控制：默认联动网内全部可加速设备以兼容任意
     *       第三方 AE 工作机器；保守模式仅联动 ICraftingProvider 与合成执行机器）→
     *       标识记入 {@link #craftingLinkedIds}，仅当有正在合成的被选中 CPU 时才被放行；</li>
     *   <li><b>样板供应器下游联动</b>：凡「被加速（登记或智能联动）的样板供应器」，其投放方向
     *       （见 {@code PatternProviderSupport}）上的相邻可加速设备若未收录，补进目标列表，
     *       并记入 {@link #downstreamSources} 使它们继承母源生效倍率（投料供应链联动，
     *       见 {@link #collectDownstreamTargets}）。</li>
     * </ul>
     * 设备可能同时属多个类别（登记设备也可能在智能联动候选内）：统一经标识去重表
     * 合并后返回（任一类别命中即收录一次，倍率裁决见 {@link #multiplierFor}）。
     * 筛选谓词与菜单设备列表采集保持一致（见 {@link DeviceScanner#isAcceleratableNode}）。
     */
    private List<AccelerationTarget> rebuildTargets() {
        IGrid grid = grid();
        if (grid == null) {
            craftingLinkedIds.clear();
            downstreamSources.clear();
            return List.of();
        }
        // 顺带刷新「同网络先放置加速器」标记（周期兜底；即时路径见 onMainNodeStateChanged）。
        refreshNetworkHasOtherAccelerator();
        List<AccelerationTarget> targets = new ArrayList<>();
        // 去重表用 LinkedHashMap：保持「网格遍历顺序 + 下游增补顺序」稳定输出，
        // 引擎按目标列表顺序逐台推进（预算耗尽时先到先得），顺序变化会让
        // 不同设备的预算分配每轮重建时漂移，行为不可预期。
        Map<DeviceId, AccelerationTarget> byId = new LinkedHashMap<>();
        craftingLinkedIds.clear();
        downstreamSources.clear();
        for (IGridNode node : grid.getNodes()) {
            Object owner = node.getOwner();
            // 其它加速器不是可加速目标（同网络仅先放置者工作，防止互加速与叠加）。
            if (isAcceleratorOwner(owner)) {
                continue;
            }
            if (!DeviceScanner.isAcceleratableNode(node, this)) {
                continue;
            }
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
            boolean registered = targetRegistry.isAccelerated(id);
            boolean smartLinked = shouldSmartLink(node);
            if (!registered && !smartLinked) {
                continue;
            }
            BlockEntity ownerBe = owner instanceof BlockEntity be ? be : null;
            byId.put(id, new AccelerationTarget(id, node, tickable, vanilla, ownerBe));
            // 登记设备即使同时是智能联动候选，倍率也以登记值为准（registered 分支优先），
            // 标识仍纳入联动集以保持智能倍率统计口径与语义一致。
            if (smartLinked) {
                craftingLinkedIds.add(id);
            }
            // 被加速的样板供应器：解析其投放方向上的下游设备并补录（供应链联动）。
            if (PatternProviderSupport.isPatternProvider(owner)) {
                collectDownstreamTargets(grid, owner, id, byId);
            }
        }
        targets.addAll(byId.values());
        return targets;
    }

    /**
     * 收集「样板供应器 → 下游接收设备」的供应链联动目标。
     * <p>
     * 样板供应器把材料经投放方向（{@link PatternProviderSupport#pushDirections}）注入
     * 相邻方块。下游接收者判定分两条路（与投料方式对应）：
     * <ul>
     *   <li><b>网格设备</b>：经 {@link DeviceScanner#findAcceleratableNode} 找到可加速节点
     *       （含黑名单过滤与载体判定），且与本源同一网格——直接贴着的合成机器
     *       （分子装配室等）、接口、第三方<b>接网</b>制造机均命中；跨网设备不收录
     *       （引擎的 {@link AccelerationTarget#belongsTo} 校验同样会剔除）；</li>
     *   <li><b>无节点原版 tick 设备</b>：取不到网格节点时，若相邻方块本身是「原版 tick
     *       加工设备」（{@link DeviceScanner#vanillaTicker} 非空）且不在可加速黑名单——
     *       即<b>完全未接 AE 网络</b>的第三方科技 mod 机器（如 Mekanism 设备，供应器经
     *       原版物品容器能力直接投料）——仍作为下游补录，按<b>纯原版 tick</b>路径加速
     *       （不经网格：无激活/断电/睡眠概念，见 {@link AccelerationTarget} 无节点目标）。</li>
     * </ul>
     * 每台下游设备：
     * <ul>
     *   <li>记入 {@link #downstreamSources}（标识 → 母样板供应器标识，多供一时取
     *       最高母源倍率，见 {@link #downstreamLinkedMultiplier}）；</li>
     *   <li>若未被主循环收录（未登记且被智能联动作用域排除），补进目标列表——
     *       例如保守联动模式下的接口、智能加速关闭时的普通机器，以及不接网的
     *       原版 tick 机器（后者不可能被主循环收录——主循环只遍历网格节点）。</li>
     * </ul>
     *
     * @param grid          当前网格（用于同网格校验）
     * @param providerOwner 样板供应器网格宿主（方块实体或线缆部件）
     * @param providerId    该样板供应器的设备标识（作为下游的「母源」记录）
     * @param targets       目标去重表（按设备标识，重建期间共享）
     */
    private void collectDownstreamTargets(IGrid grid, Object providerOwner, DeviceId providerId,
            Map<DeviceId, AccelerationTarget> targets) {
        Level world = GridOwner.levelOf(providerOwner);
        EnumSet<Direction> dirs = PatternProviderSupport.pushDirections(providerOwner);
        if (world == null || dirs == null || dirs.isEmpty()) {
            return;
        }
        BlockPos origin = GridOwner.posOf(providerOwner);
        if (origin == null) {
            return;
        }
        for (BlockPos adjPos : PatternProviderSupport.downstreamPositions(origin, dirs)) {
            if (!world.isLoaded(adjPos)) {
                continue;
            }
            BlockEntity adjBe = world.getBlockEntity(adjPos);
            DeviceId downId;
            if (adjBe == null) {
                continue;
            }
            // 复用统一谓词解析相邻方块的可加速节点（方块实体或线缆部件均可，
            // 见 DeviceScanner.findAcceleratableNode 的部件枚举逻辑）。
            IGridNode downNode = DeviceScanner.findAcceleratableNode(adjBe, this, null);
            if (downNode == null) {
                // 无节点兜底：相邻方块是未接 AE 网络的纯原版 tick 加工设备（第三方
                // 科技 mod 机器等，供应器经原版物品容器能力直接投料）——取不到网格
                // 节点，仍作为下游联动，按原版 tick 路径加速（无节点目标，见
                // AccelerationTarget）。黑名单与载体判定复用 DeviceScanner 谓词。
                BlockEntityTicker<BlockEntity> downVanilla = DeviceScanner.vanillaTicker(adjBe);
                if (downVanilla == null || !DeviceScanner.isAcceleratableMachine(adjBe)) {
                    continue;
                }
                downId = DeviceId.ofBlock(world.dimension(), adjPos);
                downstreamSources.computeIfAbsent(downId, k -> new HashSet<>()).add(providerId);
                targets.put(downId, new AccelerationTarget(downId, null, null, downVanilla, adjBe));
                continue;
            }
            // 只联动同一网格内的设备：跨网设备即使相邻也不加速。
            if (AeGrid.gridOf(downNode) != grid) {
                continue;
            }
            Object downOwner = downNode.getOwner();
            downId = DeviceScanner.deviceIdOf(downOwner);
            if (downId == null) {
                continue;
            }
            // 记录「下游 → 母源」映射：multiplierFor 据此让下游继承母源生效倍率。
            downstreamSources.computeIfAbsent(downId, k -> new HashSet<>()).add(providerId);
            // 下游设备未被主循环收录时补进目标列表（含加速载体解析）。
            if (!targets.containsKey(downId)) {
                IGridTickable downTickable = downNode.getService(IGridTickable.class);
                BlockEntity downOwnerBe = downOwner instanceof BlockEntity be ? be : null;
                BlockEntityTicker<BlockEntity> downVanilla = downOwnerBe != null
                        ? DeviceScanner.vanillaTicker(downOwnerBe)
                        : null;
                targets.put(downId,
                        new AccelerationTarget(downId, downNode, downTickable, downVanilla, downOwnerBe));
            }
        }
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

    // ========================= 同网络加速器独占（先放置者优先） =========================

    /**
     * 判断宿主是否为「本模组 AE 加速器」方块实体。
     * <p>
     * 供菜单设备列表、网格设备校验与配置卡注入统一过滤：一个网络中只允许一台加速器工作
     * （先放置者优先），其它加速器既不是可加速目标、也不应出现在设备列表（防止互加速与叠加）。
     */
    public static boolean isAcceleratorOwner(Object owner) {
        return owner instanceof AEAcceleratorBlockEntity;
    }

    /**
     * 先者优先裁决：a 是否「不晚于」b（b 是后放置者则停）。
     * <p>
     * 两者均已知放置时刻时比较时刻；任一未知（新放置未首 tick、旧档无记录）时按方块坐标
     * 字典序作为确定性平局裁决（位置较小者视为先放置）。纯静态、可单测。
     */
    public static boolean isNotLaterThan(long aTick, BlockPos aPos, long bTick, BlockPos bPos) {
        if (aTick != Long.MIN_VALUE && bTick != Long.MIN_VALUE) {
            return aTick <= bTick;
        }
        return aPos.compareTo(bPos) <= 0;
    }

    /**
     * 网络中是否存在「先放置的」其它加速器（供 GUI 与菜单判断本机是否被独占规则停用）。
     * <p>
     * 存在时本机停止工作（不叠加加速），GUI 隐藏设备列表并显示「该网络已存在AE加速器！」。
     */
    public boolean networkHasOtherAccelerator() {
        return networkHasOtherAccelerator;
    }

    /**
     * 重新扫描当前网格，刷新「网络中是否存在先放置的其它加速器」标记。
     * <p>
     * 触发点：网络节点状态事件（拓扑变化时即时，见 {@link #onMainNodeStateChanged}）与
     * 目标缓存重建（周期兜底，见 {@link #rebuildTargets}）。客户端节点不入网格，grid() 为
     * null 时安全保持 false。
     */
    private void refreshNetworkHasOtherAccelerator() {
        IGrid grid = grid();
        boolean found = false;
        if (grid != null) {
            for (IGridNode node : grid.getNodes()) {
                Object owner = node.getOwner();
                if (owner instanceof AEAcceleratorBlockEntity other && other != this
                        && isNotLaterThan(other.createdAtTick, other.getBlockPos(),
                                createdAtTick, getBlockPos())) {
                    found = true;
                    break;
                }
            }
        }
        networkHasOtherAccelerator = found;
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
        // 持久化本机放置时刻：重启后两台加速器的先后判定依然成立（先放置者继续工作）。
        data.putLong(TAG_CREATED_TICK, createdAtTick);
    }

    @Override
    public void loadTag(CompoundTag data, HolderLookup.Provider registries) {
        super.loadTag(data, registries);
        // 恢复配置卡库存内容（由绑定组件负责）。
        configCardBinding.load(data, registries);
        // 恢复目标登记表（含 PLAYER / CONFIG_CARD 来源标记）。
        targetRegistry.load(data, registries);
        // 恢复放置时刻；旧档无此标签时为 Long.MIN_VALUE，首个 tick 会按当前世界时间重设。
        createdAtTick = data.contains(TAG_CREATED_TICK, Tag.TAG_LONG)
                ? data.getLong(TAG_CREATED_TICK)
                : Long.MIN_VALUE;
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
        // 网络拓扑变化（含其它加速器接入/移除）时即时刷新「先放置者优先」判定，
        // 使后放置的加速器在放置瞬间即停止工作（无需等待目标缓存重建周期）。
        refreshNetworkHasOtherAccelerator();
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
