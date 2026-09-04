package com.tianhai.torcherino_ae.blockentity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import appeng.api.networking.security.IActionHost;
import appeng.api.networking.ticking.IGridTickable;
import appeng.me.helpers.IGridConnectedBlockEntity;
import com.tianhai.torcherino_ae.api.BudgetMeter;
import com.tianhai.torcherino_ae.block.AETorcherinoBlock;
import com.tianhai.torcherino_ae.config.ConfigDefaults;
import com.tianhai.torcherino_ae.config.RuntimeConfig;
import com.tianhai.torcherino_ae.core.AdaptiveThrottle;
import com.tianhai.torcherino_ae.core.RateGovernor;
import com.tianhai.torcherino_ae.core.SourceBudget;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * AE 加速火把方块实体。
 * <p>
 * 独立范围扫描架构（原始 Torcherino 式）：自身不接入 AE 网络、不消耗 AE 能量。服务端每个 tick
 * 把以本火把为中心的立方体区域（范围由 X/Y/Z 三个滑块独立调节）内的方块按配置的倍数
 * （{@code speed}，上限见配置 {@code torcherino.maxSpeed}，默认 4x）加速，加速对象不限于 AE 设备：
 * <ol>
 *   <li><b>AE 网格 tick</b>：方块实现 {@link IActionHost} 或 {@link IGridConnectedBlockEntity}
 *       （覆盖 AE2 原版机器与所有附属模组的网格设备）时，拿到网格节点上的
 *       {@link IGridTickable} 服务后重复驱动其处理进度；</li>
 *   <li><b>方块实体 tick</b>：重复调用目标方块 {@link EntityBlock#getTicker} 返回的 ticker
 *       （覆盖原版熔炉、第三方机器等一切带方块实体 tick 的方块）；</li>
 *   <li><b>随机 tick</b>：对可随机 tick 的方块（作物、原木等）重复调用 {@link BlockState#randomTick}。</li>
 * </ol>
 * 三条路径对同一目标并行生效（是 AE 设备又带方块实体 tick/随机 tick 时各自叠加），与
 * 原始版 Torcherino 的加速模式一致；火把可同时覆盖多个 AE 网络。
 * <p>
 * 性能：影响范围内的方块位置按「分片增量扫描 + 自适应退避」缓存（委托 {@link TorchTargetScanner}
 * 解析单个坐标），一整圈扫描分摊到 {@code scanIntervalTicks}（默认 20）tick 内完成，每 tick 只对
 * 缓存到的目标做调用；范围稳定无变化时逐轮把扫描周期退避到 {@code scanBackoffMaxTicks}（默认 200），
 * 显著降低稳态扫描成本（参照 RTS 输电塔的供电范围扫描）。范围内有方块放置/破坏时经
 * {@link TorcherinoWakeup} 事件唤醒立即恢复密集扫描，保证新设备被及时识别。目标缓存仅为方块
 * 位置的快照，方块实体 tick/随机 tick 路径在执行前都会重新校验状态，避免误加速已被替换的方块。
 * <p>
 * 速度与范围上限由服务端配置 {@code torcherino.maxSpeed / maxXzRange / maxYRange}
 * 提供（默认 4/8/4），方块实体在 clamp 与存档加载时读取 {@link RuntimeConfig} 当前值；
 * 菜单把这些上限经 {@code @GuiSync} 同步到客户端，作为滑块的可调范围。
 */
public class AETorcherinoBlockEntity extends BlockEntity {

    // NBT 存储键名。
    private static final String TAG_X_RANGE = "x_range";
    private static final String TAG_Z_RANGE = "z_range";
    private static final String TAG_Y_RANGE = "y_range";
    private static final String TAG_SPEED = "speed";
    private static final String TAG_ENABLED = "enabled";

    // X/Z/Y 轴向范围半径（上限由配置 torcherino.maxXzRange / maxYRange 提供，默认 8 / 4）。
    private int xRange = 3;
    private int zRange = 3;
    private int yRange = 2;

    // 加速倍数（speed=1 表示不产生额外加速；上限由 maxSpeed() 提供：基础火把取配置
    // torcherino.maxSpeed（默认 4），分级火把取各自固定的 64 / 324）。
    private int speed;

    // 总开关：false 时火把暂停一切加速（倍率与范围设置保留，可随时恢复）。放置默认开启。
    private boolean enabled = true;

    // 是否正在加速（本 tick 确实对某些目标发起了加速调用）。
    private boolean working;

    // 是否已在本方块实体加载后把「总开关」写入过方块状态（一次性，见 tick 开头）。
    private boolean enabledStateSynced;

    // 缓存的「已提交」影响范围内目标（由分片扫描在每圈完成后一次性替换），避免每 tick 全量遍历整块区域。
    private List<TorchTargetScanner.Target> targets = new ArrayList<>();
    // 当前这一圈扫描窗口内暂时累积到的目标（圈末经过对比后整体提交给 targets）。
    private final List<TorchTargetScanner.Target> pendingTargets = new ArrayList<>();
    // 当前这圈的外接盒（自 offsetForIndex 定位下一格），窗口开始时按当前范围重建。
    private TorchTargetScanner.Bounds scanBounds;
    // 当前这圈的分片窗口长度（tick）：小范围 = scanIntervalTicks；大范围按 scanMaxCellsPerTick 拉长，
    // 把单 tick 实际查询的格数钳制在预算内（见 TorchTargetScanner.windowFor / maybeScan）。
    private int scanWindow;
    // 分片扫描游标（展平后的一维下标，0..scanBounds.size()-1）。
    private int scanCursor;
    // 扫描周期（tick）：密集发现周期与退避上限之间自适应（见 maybeScan）。
    private int scanPeriod = ConfigDefaults.TORCHERINO_SCAN_INTERVAL_TICKS;
    // 进入下一扫描窗口的最早游戏时刻（退避时把窗口拉开，有变动/事件唤醒时置为当前时刻立即重扫）。
    private long scanGateTick = Long.MIN_VALUE;
    // 是否处于「扫描窗口」内：窗口为连续 scanIntervalTicks tick 分摊扫完一整圈。
    private boolean scanWindowActive;
    // 本扫描窗口结束时目标集是否相对上一圈发生了变化（决定本轮收窄为密集周期还是退避）。
    private boolean scanRoundChanged;

    // 每 tick 加速调用预算：预算值 = 配置 budget.tickCallsPerSource（默认 -1 不限）经 TPS
    // 自适应节流调整后的生效值；TPS 逼近硬限时自动收紧并逐档递减（见 AdaptiveThrottle），
    // 负载健康时 -1 原样放行，行为与 AE 加速器端完全一致。缓存与重建逻辑在 SourceBudget 内。
    private final SourceBudget torchBudget = new SourceBudget();

    // 源级加速耗时调控：按「本火把自身贡献的加速耗时」把实际倍率动态下调（设定倍率只作总阈值）。
    // 每座火把独立计量，与 AE 加速器一致；AdaptiveThrottle 的调用次数预算保留作极端兜底（见 RateGovernor）。
    private final RateGovernor torchRateGovernor = new RateGovernor();

    public AETorcherinoBlockEntity(BlockPos pos, BlockState state) {
        this(pos, state, ModBlockEntities.AE_TORCHERINO.get(), ConfigDefaults.TORCHERINO_MAX_SPEED);
    }

    /**
     * 供子类使用的受保护构造器：允许指定自身的 {@link BlockEntityType} 与默认加速倍数。
     * <p>
     * 分级加速火把（I/II）通过它注入各自固定的倍率上限，且必须传入各自的方块实体类型，
     * 否则 {@code getType()} 会指向基础火把类型，破坏 ticker/菜单/存档等多处依赖。
     *
     * @param type        本方块实例实际所属的方块实体类型
     * @param defaultSpeed 放置时的初始加速倍数（分级火把为各自上限，基础火把为配置默认值）
     */
    protected AETorcherinoBlockEntity(BlockPos pos, BlockState state,
            BlockEntityType<? extends AETorcherinoBlockEntity> type, int defaultSpeed) {
        super(type, pos, state);
        this.speed = defaultSpeed;
    }

    /**
     * 供 {@link net.minecraft.world.level.block.entity.BlockEntityType} 使用的工厂方法。
     */
    public static AETorcherinoBlockEntity create(BlockPos pos, BlockState state) {
        return new AETorcherinoBlockEntity(pos, state);
    }

    /**
     * 服务端每 tick 调用；客户端不执行（模型无动画）。
     */
    public static void serverTick(Level level, BlockPos pos, BlockState state, AETorcherinoBlockEntity be) {
        be.tick(level);
    }

    private void tick(Level level) {
        // 客户端不执行扫描与加速逻辑（也没有粒子/动画需求）。
        if (level.isClientSide()) {
            return;
        }
        // 首次 tick 把总开关同步进方块状态（见 syncEnabledBlockStateOnce 注释）。
        syncEnabledBlockStateOnce();
        // 源未激活（倍数 <= 1 或范围为空）时不加速。
        if (!isActive()) {
            setWorking(false);
            return;
        }
        // 定期重扫影响范围以抓取新放置/移除的方块，然后加速所有缓存目标。
        // 扫描采用分片增量 + 自适应退避（见 maybeScan）：不为零成本地周期化全量遍历，也不让单 tick
        // 全量扫描大范围。（forceRescan）事件唤醒可把退避窗口拉回密集发现。
        maybeScan((ServerLevel) level, level.getGameTime());
        // 预算每 tick 清零：本 tick 内所有目标的加速调用共享这份额度，耗尽即停止剩余目标，
        // 防止极端高倍率 + 大范围把单 tick 拖过 50ms 硬限（TPS 自适应节流负责按负载收紧）。
        BudgetMeter budget = budget();
        budget.resetTick();
        boolean didWork = false;
        // 计量「本火把自身贡献的加速耗时」（只计加速执行循环，不计范围扫描），喂入本源耗时调控，
        // 据此把实际倍率动态下调（设定倍率只作总阈值，见 effectiveSpeed）。
        long pulseStartNanos = System.nanoTime();
        int effSpeed = effectiveSpeed();
        for (TorchTargetScanner.Target target : targets) {
            didWork |= TorchAccelerator.accelerate(level, target, effSpeed, budget);
        }
        torchRateGovernor.sample((System.nanoTime() - pulseStartNanos) / 1_000_000.0);
        setWorking(didWork);
    }

    /**
     * 当前「实际可用」的加速倍数（总阈值 = 当前设定倍数 {@link #getSpeed()}，永不超过去）。
     * <p>
     * 由 {@link #torchRateGovernor} 依据本火把「本 tick 贡献的加速耗时」动态下调：负载健康时返回设定
     * 倍数（不干预），本火把把自己主线程时间挤到配置上限以上时按比例压到 {@code [1, speed]}。
     * GUI 显示与存档仍用设定倍数（{@link #getSpeed()}），实际倍率仅在加速执行时经本方法生效。
     */
    private int effectiveSpeed() {
        return torchRateGovernor.cap(speed);
    }

    /**
     * 每 tick 调用预算计量器：上限 = 配置 {@code budget.tickCallsPerSource}（默认 -1 不限）
     * 经 TPS 自适应节流（{@link AdaptiveThrottle}）调整后的生效值；计量器实例被缓存，
     * 仅当生效预算变化（配置改动或收紧档位切换）时重建，平时每个 tick 零分配。
     */
    private BudgetMeter budget() {
        return torchBudget.get();
    }

    // ========================= 目标缓存（分片增量 + 自适应退避扫描） =========================

    /**
     * <b>自适应退避</b>驱动的范围扫描（参照 RTS 输电塔的供电范围扫描）。每个扫描周期
     * （{@link #scanPeriod}）内用 {@code scanIntervalTicks}（默认 20）tick 分摊扫完一整圈，
     * 其余 tick 完全跳过（零扫描成本）。
     * <ul>
     *   <li>本圈目标集有变化 → 周期收窄回密集发现周期（默认 20）；</li>
     *   <li>本圈无变化 → 周期翻倍退避至 {@code scanBackoffMaxTicks}（默认 200，稳定范围低频兜底）。</li>
     * </ul>
     * 收益：稳态扫描成本降到原来的 {@code scanIntervalTicks / scanPeriod}（默认 ≤1/10）。
     * 新设备的发现延迟被 {@link TorcherinoWakeup} 的方块放置/破坏事件唤醒兜底（立即收窄回扫一圈），
     * 因此退避只影响「无变化时的稳态扫描成本」，不影响新设备的及时响应。
     */
    private void maybeScan(ServerLevel level, long now) {
        int base = RuntimeConfig.torcherinoScanIntervalTicks();
        int periodMax = Math.max(base, RuntimeConfig.torcherinoScanBackoffMaxTicks());
        if (!scanWindowActive) {
            if (now < scanGateTick) {
                return;                          // 冷却期：完全跳过，零扫描成本。
            }
            scanWindowActive = true;
            scanRoundChanged = false;
            pendingTargets.clear();
            scanBounds = TorchTargetScanner.bounds(worldPosition, xRange, yRange, zRange);
            // 窗口长度自适应：范围极大时按 scanMaxCellsPerTick 拉长，把单 tick 查询格数钳在预算内。
            scanWindow = TorchTargetScanner.windowFor(base, scanBounds.size(),
                    RuntimeConfig.torcherinoScanMaxCellsPerTick());
            scanCursor = 0;
        }
        if (scanRange(level, scanWindow)) {      // 已完成一整圈
            scanWindowActive = false;
            // 退避下限不得小于当前窗口长度（否则窗口会重叠），上限取 scanBackoffMaxTicks 与该下限较大者。
            int floor = Math.max(periodMax, scanWindow);
            scanPeriod = scanRoundChanged ? scanWindow
                    : Math.min(scanPeriod * 2, floor);
            scanGateTick = now + scanPeriod;
        }
    }

    /**
     * 扫描影响范围中的一小段（本 tick 分摊的一帧），把发现的目标写入 {@link #pendingTargets}。
     * <ul>
     *   <li><b>分片</b>：每 tick 只扫 {@code ceil(size / window)} 格，一整圈在 {@code window} tick 内扫完，</li>
     *   <li><b>跳未加载区块</b>：先 {@code level.isLoaded} 跳过未加载格（避免无谓查询/触碰未加载区块），</li>
     *   <li>单格仅 1 次 {@code getBlockEntity}（经 {@link TorchTargetScanner#resolve}）。</li>
     * </ul>
     *
     * @return 本次调用是否<b>完成了一整圈扫描</b>（游标回绕），供退避调度评估。
     */
    private boolean scanRange(ServerLevel level, int window) {
        if (scanBounds == null || scanBounds.size() <= 0) {
            return true;
        }
        int perTick = Math.max(1, (int) Math.ceil(scanBounds.size() / (double) window));
        for (int i = 0; i < perTick && scanCursor < scanBounds.size(); i++, scanCursor++) {
            BlockPos off = TorchTargetScanner.offsetForIndex(scanCursor, scanBounds);
            BlockPos pos = worldPosition.offset(off);
            if (!level.isLoaded(pos)) {
                continue;                        // 未加载区块：跳过（加速不了未加载方块）。
            }
            TorchTargetScanner.Target hit = TorchTargetScanner.resolve(level, worldPosition, pos);
            if (hit != null) {
                pendingTargets.add(hit);
            }
        }
        if (scanCursor >= scanBounds.size()) {
            scanCursor = 0;
            commitTargets();
            return true;
        }
        return false;
    }

    /**
     * 一圈扫描完成后把它提交为正式目标集：对比「上一圈已提交目标集」与「本圈新扫结果」，
     * 据此决定本次是否算「有变化」（决定退避还是保持密集扫描）。
     */
    private void commitTargets() {
        scanRoundChanged = !sameTargets(targets, pendingTargets);
        targets = new ArrayList<>(pendingTargets);
    }

    /** 两个目标集是否等价（按位置集合比较，顺序无关）。用于判断一圈扫描期间范围内设备是否变化。 */
    private static boolean sameTargets(List<TorchTargetScanner.Target> a, List<TorchTargetScanner.Target> b) {
        if (a.size() != b.size()) {
            return false;
        }
        Set<BlockPos> set = new HashSet<>();
        for (TorchTargetScanner.Target t : a) {
            set.add(t.pos());
        }
        for (TorchTargetScanner.Target t : b) {
            if (!set.contains(t.pos())) {
                return false;
            }
        }
        return true;
    }

    /**
     * 立即唤醒重扫：把扫描周期收窄回密集发现周期，并置扫描门为当前时刻，使下个 tick 即开始扫一圈。
     * <p>
     * 由 {@link TorcherinoWakeup}（范围内方块放置/破坏事件）与 {@link #onConfigChanged()}
     * （范围/倍数变化）调用，补充「退避稳态下新增设备响应慢」的短板——事件驱动只唤醒被影响到的
     * 火把，退避稳态仍省主线程成本，两者兼得。
     */
    public void forceRescan() {
        if (level == null || level.isClientSide()) {
            return;
        }
        scanWindowActive = false;
        scanPeriod = RuntimeConfig.torcherinoScanIntervalTicks();
        scanGateTick = level.getGameTime();
    }

    // ========================= 访问器与设置 =========================

    /** 当前配置的最大加速倍数（服务端权威，菜单同步到客户端 UI 作滑块上限）。 */
    public int maxSpeed() {
        return RuntimeConfig.torcherinoMaxSpeed();
    }

    /** 当前配置的 X/Z 轴最大范围半径（服务端权威，菜单同步到客户端 UI）。 */
    public int maxXzRange() {
        return RuntimeConfig.torcherinoMaxXzRange();
    }

    /** 当前配置的 Y 轴最大范围半径（服务端权威，菜单同步到客户端 UI）。 */
    public int maxYRange() {
        return RuntimeConfig.torcherinoMaxYRange();
    }

    /**
     * 火把是否可工作：总开关开启、倍数大于 1 且三维范围非全零（区域内才有可加速的方块）。
     * 关闭开关后为 {@code false}，tick 直接短路，火把不再扫描也不再加速任何方块。
     */
    public boolean isActive() {
        return enabled && speed > 1 && !isRangeEmpty();
    }

    /**
     * X/Z/Y 轴范围是否全部为 0（因此区域内无任何方块）。
     */
    public boolean isRangeEmpty() {
        return xRange == 0 && zRange == 0 && yRange == 0;
    }

    /**
     * 某世界坐标是否位于本火把的立方体影响范围内（X/Y/Z 三轴各自独立判定）。
     * <p>
     * 供 {@link TorcherinoWakeup} 在方块放置/破坏事件唤醒时做 AABB 精确校验。
     */
    public boolean isInRange(BlockPos pos) {
        return Math.abs(pos.getX() - worldPosition.getX()) <= xRange
                && Math.abs(pos.getY() - worldPosition.getY()) <= yRange
                && Math.abs(pos.getZ() - worldPosition.getZ()) <= zRange;
    }

    public int getXRange() {
        return xRange;
    }

    public int getZRange() {
        return zRange;
    }

    public int getYRange() {
        return yRange;
    }

    /**
     * 当前加速倍数（1x～配置上限）。1 表示不产生额外加速。
     */
    public int getSpeed() {
        return speed;
    }

    /**
     * 总开关当前状态：{@code true} 允许火把加速，{@code false} 暂停全部加速
     * （倍率与范围设置保留，待重新开启后按原设置恢复）。
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 设置总开关。关闭后火把经 {@link #isActive()} 短路立即停止加速（tick 不再扫描与调用
     * 目标，工作状态清零），并把方块状态切到熄灭（off）模型；开启后从下一次 tick 起按
     * 原倍率与范围恢复加速，模型切回点亮模型。
     */
    public void setEnabled(boolean enabled) {
        if (this.enabled != enabled) {
            this.enabled = enabled;
            syncEnabledBlockState();
            onConfigChanged();
        }
    }

    /**
     * 首次 tick 调用一次（每次方块实体加载后）：把方块状态里的「总开关」位与存档值对齐，
     * 驱动客户端显示对应模型。正常情况下开关经 {@link #setEnabled} 已即时写入方块状态、
     * 存档值与状态一致，此处为空操作；升级前的旧档方块状态不含 {@code enabled} 属性
     * （属性本次新增）时，经此修复可避免旧火把在升级后因缺失变体而显示错误。
     */
    private void syncEnabledBlockStateOnce() {
        if (enabledStateSynced) {
            return;
        }
        enabledStateSynced = true;
        syncEnabledBlockState();
    }

    /**
     * 把当前总开关值写入方块状态（仅服务端）。与状态现值相同时直接短路，
     * 不会产生多余的网络包或相邻方块更新。
     */
    private void syncEnabledBlockState() {
        if (level == null || level.isClientSide()) {
            return;
        }
        BlockState state = level.getBlockState(worldPosition);
        if (state.getBlock() instanceof AETorcherinoBlock) {
            boolean current = state.hasProperty(AETorcherinoBlock.ENABLED)
                    && state.getValue(AETorcherinoBlock.ENABLED);
            if (current != enabled) {
                level.setBlock(worldPosition, AETorcherinoBlock.applyEnabledState(state, enabled),
                        Block.UPDATE_CLIENTS);
            }
        }
    }

    /**
     * 是否正在加速（本 tick 确实对某些目标发起了加速调用）。
     */
    public boolean isWorking() {
        return working;
    }

    private void setWorking(boolean working) {
        this.working = working;
    }

    public void setXRange(int value) {
        int clamped = clampRange(value, RuntimeConfig.torcherinoMaxXzRange());
        if (this.xRange != clamped) {
            this.xRange = clamped;
            onConfigChanged();
        }
    }

    public void setZRange(int value) {
        int clamped = clampRange(value, RuntimeConfig.torcherinoMaxXzRange());
        if (this.zRange != clamped) {
            this.zRange = clamped;
            onConfigChanged();
        }
    }

    public void setYRange(int value) {
        int clamped = clampRange(value, RuntimeConfig.torcherinoMaxYRange());
        if (this.yRange != clamped) {
            this.yRange = clamped;
            onConfigChanged();
        }
    }

    /**
     * 设置加速倍数（1x~配置上限），范围变化后触发下一次 tick 立即重扫目标。
     */
    public void setSpeed(int value) {
        int clamped = clampRange(value, maxSpeed());
        if (this.speed != clamped) {
            this.speed = clamped;
            onConfigChanged();
        }
    }

    /**
     * 范围 / 倍数变化：目标集合可能随之变化，迫使下一个 tick 立即重扫目标缓存，
     * 并立即通知存档与客户端。范围变化同时会改变 {@link TorcherinoWakeup} 空间哈希覆盖的
     * 单元，因此需要重新注册（注销旧覆盖 + 登记新覆盖）。
     */
    private void onConfigChanged() {
        forceRescan();
        if (level instanceof ServerLevel serverLevel) {
            TorcherinoWakeup.unregister(serverLevel, this);
            TorcherinoWakeup.register(serverLevel, this);
        }
        setChanged();
        if (level != null && !level.isClientSide()) {
            level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), Block.UPDATE_ALL);
        }
    }

    /**
     * 方块实体加载（chunk 装载）时注册到 {@link TorcherinoWakeup}，使范围内方块放置/破坏
     * 事件能唤醒本火把密集重扫（仅服务端）。
     */
    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel serverLevel) {
            TorcherinoWakeup.register(serverLevel, this);
        }
    }

    /**
     * 方块实体移除时从 {@link TorcherinoWakeup} 注销，避免残留桶引用（仅服务端）。
     */
    @Override
    public void setRemoved() {
        if (level instanceof ServerLevel serverLevel) {
            TorcherinoWakeup.unregister(serverLevel, this);
        }
        super.setRemoved();
    }

    /**
     * 把数值钳制到 [0, max] 区间。
     */
    private int clampRange(int value, int max) {
        return Math.max(0, Math.min(value, max));
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt(TAG_X_RANGE, xRange);
        tag.putInt(TAG_Z_RANGE, zRange);
        tag.putInt(TAG_Y_RANGE, yRange);
        tag.putInt(TAG_SPEED, speed);
        tag.putBoolean(TAG_ENABLED, enabled);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        this.xRange = clampRange(tag.getInt(TAG_X_RANGE), RuntimeConfig.torcherinoMaxXzRange());
        this.zRange = clampRange(tag.getInt(TAG_Z_RANGE), RuntimeConfig.torcherinoMaxXzRange());
        this.yRange = clampRange(tag.getInt(TAG_Y_RANGE), RuntimeConfig.torcherinoMaxYRange());
        this.speed = clampRange(tag.getInt(TAG_SPEED), maxSpeed());
        if (this.speed <= 0) {
            this.speed = 1;
        }
        // 旧档没有开关标签时默认开启，避免升级后已放置的火把突然全部停止加速。
        this.enabled = !tag.contains(TAG_ENABLED) || tag.getBoolean(TAG_ENABLED);
        // 加载后范围/配置可能变化，迫使下一个 tick 立即重扫目标缓存。
        forceRescan();
    }
}
