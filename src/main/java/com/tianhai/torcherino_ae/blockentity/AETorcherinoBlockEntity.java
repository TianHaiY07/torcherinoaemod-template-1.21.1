package com.tianhai.torcherino_ae.blockentity;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.security.IActionHost;
import appeng.api.networking.ticking.IGridTickable;
import appeng.api.networking.ticking.ITickManager;
import appeng.api.networking.ticking.TickRateModulation;
import appeng.me.helpers.IGridConnectedBlockEntity;
import com.tianhai.torcherino_ae.Torcherinoaemod;
import com.tianhai.torcherino_ae.api.BudgetMeter;
import com.tianhai.torcherino_ae.config.ConfigDefaults;
import com.tianhai.torcherino_ae.config.RuntimeConfig;
import com.tianhai.torcherino_ae.core.AdaptiveThrottle;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
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
 * 性能：影响范围内的方块位置在每 {@link #SCAN_INTERVAL} tick 集中扫描并缓存，每 tick 只对
 * 缓存到的目标做调用，避免每 tick 全量遍历整块区域。目标缓存仅为方块位置的快照，方块实体
 * tick/随机 tick 路径在执行前都会重新校验状态，避免误加速已被替换的方块。
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

    /** 影响范围内目标的重扫间隔（tick）。重新扫描以捕获新放置/移除的方块，降低每 tick 的全量遍历开销。 */
    private static final int SCAN_INTERVAL = 20;

    // X/Z/Y 轴向范围半径（上限由配置 torcherino.maxXzRange / maxYRange 提供，默认 8 / 4）。
    private int xRange = 3;
    private int zRange = 3;
    private int yRange = 2;

    // 加速倍数（speed=1 表示不产生额外加速；上限由 maxSpeed() 提供：基础火把取配置
    // torcherino.maxSpeed（默认 4），分级火把取各自固定的 64 / 324）。
    private int speed;

    // 是否正在加速（本 tick 确实对某些目标发起了加速调用）。
    private boolean working;

    // 影响范围内一个被缓存的加速目标：缓存方块实体类型与 ticker、是否随机 tick、是否 AE 设备，
    // 避免每 tick 重复查表取状态。方块位置必须是不可变快照（betweenClosed 返回可变 BlockPos）。
    private record Target(BlockPos pos, boolean isAeMachine,
                          @Nullable BlockEntityType<?> beType,
                          @Nullable BlockEntityTicker<BlockEntity> ticker, boolean randomlyTicking) {
    }

    // 缓存的影响范围内目标，避免每 tick 全量遍历整块区域。
    private final List<Target> targets = new ArrayList<>();
    // 距下一次重扫的剩余 tick。
    private int scanCooldown;

    // 每 tick 加速调用预算：预算值 = 配置 budget.tickCallsPerSource（默认 -1 不限）经 TPS
    // 自适应节流调整后的生效值；TPS 逼近硬限时自动收紧并逐档递减（见 AdaptiveThrottle），
    // 负载健康时 -1 原样放行，行为与 AE 加速器端完全一致。实例仅在生效预算变化时重建。
    private BudgetMeter torchBudget = BudgetMeter.UNLIMITED_METER;
    private int torchBudgetLimitTicks = BudgetMeter.UNLIMITED;

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
        // 源未激活（倍数 <= 1 或范围为空）时不加速。
        if (!isActive()) {
            setWorking(false);
            return;
        }
        // 定期重扫影响范围以抓取新放置/移除的方块，然后加速所有缓存目标。
        if (--scanCooldown <= 0) {
            refreshTargets((ServerLevel) level);
        }
        // 预算每 tick 清零：本 tick 内所有目标的加速调用共享这份额度，耗尽即停止剩余目标，
        // 防止极端高倍率 + 大范围把单 tick 拖过 50ms 硬限（TPS 自适应节流负责按负载收紧）。
        BudgetMeter budget = budget();
        budget.resetTick();
        boolean didWork = false;
        for (Target target : targets) {
            didWork |= accelerate(level, target, budget);
        }
        setWorking(didWork);
    }

    /**
     * 每 tick 调用预算计量器：上限 = 配置 {@code budget.tickCallsPerSource}（默认 -1 不限）
     * 经 TPS 自适应节流（{@link AdaptiveThrottle}）调整后的生效值；计量器实例被缓存，
     * 仅当生效预算变化（配置改动或收紧档位切换）时重建，平时每个 tick 零分配。
     */
    private BudgetMeter budget() {
        int limit = AdaptiveThrottle.INSTANCE.adjust(RuntimeConfig.budgetTickCallsPerSource());
        if (limit != torchBudgetLimitTicks) {
            torchBudgetLimitTicks = limit;
            torchBudget = new BudgetMeter(limit);
        }
        return torchBudget;
    }

    // ========================= 目标缓存（区域扫描） =========================

    /**
     * 重新扫描影响范围立方体，把范围内「可能被加速」的方块缓存进列表。
     * <p>
     * 判定为候选的条件（满足其一）：实现 AE 网格设备接口（{@link IActionHost} 或
     * {@link IGridConnectedBlockEntity}，宽口径，含线缆/总线等全部网格宿主）、带方块实体
     * 且其方块提供 ticker、或方块本身随机 tick。空气与「三者皆无」的纯装饰方块不缓存。
     */
    private void refreshTargets(ServerLevel level) {
        scanCooldown = SCAN_INTERVAL;
        targets.clear();
        int minX = worldPosition.getX() - xRange;
        int minY = worldPosition.getY() - yRange;
        int minZ = worldPosition.getZ() - zRange;
        int maxX = worldPosition.getX() + xRange;
        int maxY = worldPosition.getY() + yRange;
        int maxZ = worldPosition.getZ() + zRange;
        for (BlockPos pos : BlockPos.betweenClosed(minX, minY, minZ, maxX, maxY, maxZ)) {
            if (pos.equals(worldPosition)) {
                continue;
            }
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) {
                continue;
            }
            Block block = state.getBlock();
            BlockEntity be = level.getBlockEntity(pos);
            // 不能把其它加速火把当作加速目标：火把 A 加速火把 B 的方块实体 ticker 时，
            // B 的 tick 又会反过来去加速 A 的 ticker，两者互相递归直至栈溢出崩溃。
            if (be instanceof AETorcherinoBlockEntity) {
                continue;
            }
            boolean isAeMachine = isAeGridBlockEntity(be);
            boolean randomlyTicking = state.isRandomlyTicking();
            // 只缓存可能被加速（AE 网格设备、有方块实体 tick 或随机 tick）的目标。
            if (!isAeMachine && be == null && !randomlyTicking) {
                continue;
            }
            BlockEntityType<?> beType = be != null ? be.getType() : null;
            BlockEntityTicker<BlockEntity> ticker = null;
            if (beType != null && block instanceof EntityBlock entityBlock) {
                //noinspection unchecked
                ticker = (BlockEntityTicker<BlockEntity>) entityBlock.getTicker(level, state, beType);
            }
            // betweenClosed 迭代返回可变 BlockPos，需拷贝成不可变快照才能存入缓存。
            targets.add(new Target(pos.immutable(), isAeMachine, beType, ticker, randomlyTicking));
        }
    }

    // ========================= 三条加速路径（原始 Torcherino 式） =========================

    /**
     * 按缓存目标自身的类型执行命中路径：
     * <ul>
     *   <li>AE 网格设备 → 重复驱动网格 tick（{@link #accelerateGridTicks}）；</li>
     *   <li>方块实体 ticker → 重复调用方块 ticker（{@link #accelerateBlockEntityTicks}）；</li>
     *   <li>随机 tick → 重复调用方块随机 tick（{@link #accelerateRandomTicks}）。</li>
     * </ul>
     * 三条路径对同一目标并行生效；所有调用共享本 tick 的 {@code budget}（逐次按需申请，
     * 预算耗尽即停止后续调用，保证极端负载下不会越过每 tick 的调用上限）。
     *
     * @return 本 tick 是否确实发起过任何加速调用
     */
    private boolean accelerate(Level level, Target target, BudgetMeter budget) {
        boolean didWork = false;
        BlockPos pos = target.pos;
        BlockEntity be = level.getBlockEntity(pos);
        // 仅当方块实体类型未变化时复用缓存，避免误加速已被替换的方块；否则该目标本次跳过，
        // 下一次重扫会重新缓存，自会纠正。
        if (be != null && !be.isRemoved() && target.beType != null && be.getType() == target.beType) {
            if (target.isAeMachine) {
                didWork |= accelerateGridTicks(be, budget);
            }
            if (target.ticker != null) {
                didWork |= accelerateBlockEntityTicks(be, target.ticker, budget);
            }
        }
        if (target.randomlyTicking) {
            didWork |= accelerateRandomTicks((ServerLevel) level, pos, level.getBlockState(pos), budget);
        }
        return didWork;
    }

    /**
     * 核心加速路径之一：AE 机器的实际处理逻辑大多注册为 {@link IGridTickable}（网格 tick）。
     * 通过 {@link IActionHost} 或 {@link IGridConnectedBlockEntity} 拿到网格节点；这两种接口
     * 能覆盖 AE2 原版机器与所有附属模组（如 DataEnergistics）的网络设备。
     * <p>
     * AE2 机器的处理进度由网格 tick 驱动。多数机器在 {@code tickingRequest} 中只推进一个
     * 离散步骤并忽略第二个参数（ticksSinceLastCall），因此必须循环调用多次才能真正加速；
     * 仅少数机器会把该参数当作倍率做乘法。这里统一按 1 tick 循环调用，保证对所有机器都有效。
     *
     * @return 是否确实发起过网格 tick 调用
     */
    private boolean accelerateGridTicks(BlockEntity blockEntity, BudgetMeter budget) {
        IGridNode node = getGridNode(blockEntity);
        if (node == null) {
            return false;
        }
        IGrid grid = safeGrid(node);
        if (grid == null) {
            return false;
        }
        IGridTickable tickable = node.getService(IGridTickable.class);
        if (tickable == null) {
            return false;
        }
        // 空闲（睡眠）中的设备无需驱动，直接跳过，避免高倍率下对空闲设备做大量无意义调用。
        try {
            if (tickable.getTickingRequest(node).isSleeping()) {
                return false;
            }
        } catch (RuntimeException e) {
            return false;
        }
        boolean didWork = false;
        for (int i = 0; i < speed - 1; i++) {
            // 每 tick 预算按次申请：额度耗尽立即停止本目标的剩余调用，后续目标同样受限。
            if (budget.request(1) <= 0) {
                return didWork;
            }
            try {
                TickRateModulation modulation = tickable.tickingRequest(node, 1);
                didWork = true;
                // 设备在工作结束后会返回 SLEEP，通知 AE2 tick 管理器停止调度，避免无效唤醒。
                if (modulation == TickRateModulation.SLEEP) {
                    ITickManager tickManager = grid.getTickManager();
                    if (tickManager != null) {
                        tickManager.sleepDevice(node);
                    }
                    break;
                }
            } catch (Exception e) {
                Torcherinoaemod.LOGGER.error("Failed while accelerating AE grid tick for {} at {}",
                        blockEntity.getType(), blockEntity.getBlockPos(), e);
                return didWork;
            }
        }
        return didWork;
    }

    /**
     * 加速路径之二：重复调用目标方块的 {@link EntityBlock#getTicker} 返回的方块实体 ticker
     * （原版熔炉、第三方机器的处理进度多由方块实体 tick 驱动）。
     */
    private boolean accelerateBlockEntityTicks(BlockEntity blockEntity, BlockEntityTicker<BlockEntity> ticker,
            BudgetMeter budget) {
        Level level = blockEntity.getLevel();
        BlockPos pos = blockEntity.getBlockPos();
        BlockState state = blockEntity.getBlockState();
        if (level == null) {
            return false;
        }
        boolean didWork = false;
        for (int i = 0; i < speed - 1; i++) {
            if (blockEntity.isRemoved()) {
                return didWork;
            }
            // 每 tick 预算按次申请：额度耗尽立即停止本目标的剩余调用，后续目标同样受限。
            if (budget.request(1) <= 0) {
                return didWork;
            }
            try {
                ticker.tick(level, pos, state, blockEntity);
                didWork = true;
            } catch (Exception e) {
                Torcherinoaemod.LOGGER.error("Failed while accelerating block entity {} at {}",
                        blockEntity.getType(), pos, e);
                return didWork;
            }
        }
        return didWork;
    }

    /**
     * 加速路径之三：对可随机 tick 的方块（作物、树苗、原木等）重复调用随机 tick。
     */
    private boolean accelerateRandomTicks(ServerLevel level, BlockPos targetPos, BlockState blockState,
            BudgetMeter budget) {
        if (!blockState.isRandomlyTicking()) {
            return false;
        }
        boolean didWork = false;
        for (int i = 0; i < speed - 1; i++) {
            BlockState current = level.getBlockState(targetPos);
            if (!current.isRandomlyTicking()) {
                return didWork;
            }
            // 每 tick 预算按次申请：额度耗尽立即停止本目标的剩余调用，后续目标同样受限。
            if (budget.request(1) <= 0) {
                return didWork;
            }
            try {
                current.randomTick(level, targetPos, level.getRandom());
                didWork = true;
            } catch (Exception e) {
                Torcherinoaemod.LOGGER.error("Failed while accelerating random tick block at {}", targetPos, e);
                return didWork;
            }
        }
        return didWork;
    }

    // ========================= AE 设备判定（宽口径） =========================

    /** 判断一个方块实体是否为 AE 网格设备：实现 {@link IActionHost} 或 {@link IGridConnectedBlockEntity}，涵盖 AE2 原版机器与所有附属模组。 */
    private static boolean isAeGridBlockEntity(@Nullable BlockEntity be) {
        return be instanceof IActionHost || be instanceof IGridConnectedBlockEntity;
    }

    /**
     * 从方块实体解析网格节点：优先取 {@link IActionHost} 的可行动节点，其次取
     * {@link IGridConnectedBlockEntity} 的主节点。
     */
    @Nullable
    private IGridNode getGridNode(BlockEntity blockEntity) {
        if (blockEntity instanceof IActionHost actionHost) {
            IGridNode node = actionHost.getActionableNode();
            if (node != null) {
                return node;
            }
        }
        if (blockEntity instanceof IGridConnectedBlockEntity gridConnected) {
            return gridConnected.getMainNode().getNode();
        }
        return null;
    }

    /**
     * 安全读取节点当前所属网格：AE2 的 {@code GridNode.getGrid()} 在节点未入网/销毁时会抛
     * {@link IllegalStateException} 而非返回 {@code null}，这里统一捕获并转译为 {@code null}。
     */
    @Nullable
    private static IGrid safeGrid(IGridNode node) {
        try {
            return node.getGrid();
        } catch (IllegalStateException e) {
            return null;
        }
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
     * 火把是否可工作：倍数大于 1 且三维范围非全零（区域内才有可加速的方块）。
     */
    public boolean isActive() {
        return speed > 1 && !isRangeEmpty();
    }

    /**
     * X/Z/Y 轴范围是否全部为 0（因此区域内无任何方块）。
     */
    public boolean isRangeEmpty() {
        return xRange == 0 && zRange == 0 && yRange == 0;
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
     * 并立即通知存档与客户端。
     */
    private void onConfigChanged() {
        scanCooldown = 0;
        setChanged();
        if (level != null && !level.isClientSide()) {
            level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), Block.UPDATE_ALL);
        }
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
        // 加载后范围/配置可能变化，迫使下一个 tick 立即重扫目标缓存。
        scanCooldown = 0;
    }
}
