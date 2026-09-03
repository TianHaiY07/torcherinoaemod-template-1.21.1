package com.tianhai.torcherino_ae.blockentity;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.ticking.IGridTickable;
import com.tianhai.torcherino_ae.api.AccelerationResult;
import com.tianhai.torcherino_ae.api.AccelerationTarget;
import com.tianhai.torcherino_ae.api.BudgetMeter;
import com.tianhai.torcherino_ae.api.DeviceId;
import com.tianhai.torcherino_ae.api.IAccelerationSource;
import com.tianhai.torcherino_ae.config.ConfigDefaults;
import com.tianhai.torcherino_ae.config.RuntimeConfig;
import com.tianhai.torcherino_ae.network.DeviceScanner;
import com.tianhai.torcherino_ae.core.AccelerationEngine;
import com.tianhai.torcherino_ae.core.TargetCache;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * AE 加速火把方块实体。
 * <p>
 * 独立范围扫描架构（Torcherino 式）：自身不接入 AE 网络、不消耗 AE 能量。服务端每个 tick
 * 扫描以本火把为中心的立方体区域（范围由 X/Y/Z 三个滑块独立调节），通过
 * {@link DeviceScanner#findAcceleratableNode} 找到区域内的 AE2 设备（仅限实现
 * {@link IGridTickable} 且属于可加速机器的设备，过滤标准与 AE 加速器一致），
 * 并以配置的倍数（{@code speed}，上限见配置 {@code torcherino.maxSpeed}，默认 4x）加速。
 * 火把可同时加速区域内、即使属于不同 AE 网络的设备。
 * <p>
 * 本类以「加速源」（{@link IAccelerationSource}）接入加速体系：加速执行细节
 * （催促、多次 {@code tickingRequest}、睡眠判断、失效剔除）统一由 {@link AccelerationEngine}
 * 完成，目标缓存由 {@link TargetCache} 支撑。与 AE 加速器的差异仅有两处：
 * 扫描区域内的设备（而非 AE 网格节点），且所有目标使用统一的全局倍率。
 * <p>
 * 速度与范围上限由服务端配置 {@code torcherino.maxSpeed / maxXzRange / maxYRange}
 * 提供（默认 4/8/4），方块实体在 clamp 与存档加载时读取 {@link RuntimeConfig} 当前值；
 * 菜单把这些上限经 {@code @GuiSync} 同步到客户端，作为滑块的可调范围。
 * <p>
 * 性能：目标设备缓存避免每 tick 全区域扫描，仅在范围/倍数变化、设备失效或达到配置的重建周期时重建。
 */
public class AETorcherinoBlockEntity extends BlockEntity implements IAccelerationSource {

    // NBT 存储键名。
    private static final String TAG_X_RANGE = "x_range";
    private static final String TAG_Z_RANGE = "z_range";
    private static final String TAG_Y_RANGE = "y_range";
    private static final String TAG_SPEED = "speed";

    // X/Z/Y 轴向范围半径（上限由配置 torcherino.maxXzRange / maxYRange 提供，默认 8 / 4）。
    private int xRange = 3;
    private int zRange = 3;
    private int yRange = 2;

    // 加速倍数（speed=1 表示不产生额外加速；上限由配置 torcherino.maxSpeed 提供，默认 4）。
    private int speed = ConfigDefaults.TORCHERINO_MAX_SPEED;

    // 是否正在加速（本 tick 确实有设备被加速）。
    private boolean working;

    // 单 tick 调用预算计量器：随配置 budget.tickCallsPerSource 变化而重建（默认 -1 不限）。
    private BudgetMeter budgetMeter = BudgetMeter.UNLIMITED_METER;
    private int budgetLimitTicks = BudgetMeter.UNLIMITED;

    // 目标缓存：周期 / 置脏重建区域内的可加速设备，脉冲只遍历这个小缓存。
    // 重建间隔取配置 cache.rebuildIntervalTicks 的当前值（方块每次创建/重开区块读取，默认 20 tick）。
    private final TargetCache targetCache = new TargetCache(RuntimeConfig.cacheRebuildIntervalTicks());

    public AETorcherinoBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.AE_TORCHERINO.get(), pos, state);
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
        // 整个加速行为由 AccelerationEngine 驱动：源未激活（倍数 <=1 或范围为空）时不加速；
        // 本 tick 确实有设备被加速时才标记工作状态。
        AccelerationResult result = AccelerationEngine.pulse(this);
        setWorking(result.didWork());
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
        return RuntimeConfig.torcherinoMaxSpeed();
    }

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
     * 源是否可工作：倍数大于 1 且三维范围非全零（区域内才有可加速的方块）。
     */
    @Override
    public boolean isActive() {
        return speed > 1 && !isRangeEmpty();
    }

    @Override
    public List<AccelerationTarget> targets() {
        return targetCache.resolve(this::rebuildTargets);
    }

    /**
     * 火把对所有目标使用统一的界面设置倍数；设备个体无独立倍数。
     */
    @Override
    public int multiplierFor(DeviceId id) {
        return speed;
    }

    @Override
    public BudgetMeter budget() {
        // 预算上限由配置 budget.tickCallsPerSource 提供（默认 -1 不限）。
        // 计量器实例被缓存：仅当配置值变化时才重建，避免每个 tick 分配对象。
        int limit = RuntimeConfig.budgetTickCallsPerSource();
        if (limit != budgetLimitTicks) {
            budgetLimitTicks = limit;
            budgetMeter = new BudgetMeter(limit);
        }
        return budgetMeter;
    }

    /**
     * 火把可同时覆盖多个 AE 网络：不校验目标节点与「本源网格」一致，返回 {@code null} 让引擎跳过该校验。
     */
    @Nullable
    @Override
    public IGrid grid() {
        return null;
    }

    @Override
    public void markTargetsDirty() {
        targetCache.markDirty();
    }

    // ========================= 目标缓存重建 =========================

    /**
     * 扫描立方体区域，把区域内所有「可加速的 AE 设备」收集进缓存。
     * <p>
     * 通过 {@link DeviceScanner#findAcceleratableNode} 解析每个方块实体（基于
     * {@code AECapabilities.IN_WORLD_GRID_NODE_HOST} 能力拿到世界内网格节点），
     * 过滤标准与 AE 加速器一致；传入本火把作为 {@code self} 排除自身。
     * 每个目标在重建期解析好设备标识、节点与网格 tick 服务，脉冲期只做调用。
     */
    private List<AccelerationTarget> rebuildTargets() {
        Level world = level;
        if (world == null) {
            return List.of();
        }
        ResourceKey<Level> dim = world.dimension();
        BlockPos center = getBlockPos();
        BlockPos min = center.offset(-xRange, -yRange, -zRange);
        BlockPos max = center.offset(xRange, yRange, zRange);
        List<AccelerationTarget> targets = new ArrayList<>();
        for (BlockPos p : BlockPos.betweenClosed(min, max)) {
            if (p.equals(center)) {
                continue;
            }
            BlockEntity be = world.getBlockEntity(p);
            if (be == null) {
                continue;
            }
            // 只缓存「可加速且非自身」的 AE 设备节点（黑名单/坐标解析复用 DeviceScanner）。
            IGridNode node = DeviceScanner.findAcceleratableNode(be, this);
            if (node == null) {
                continue;
            }
            IGridTickable tickable = node.getService(IGridTickable.class);
            if (tickable == null) {
                continue;
            }
            // 设备标识以宿主导出为准（部件含朝向）；个别无法导出的情况用扫描坐标兜底。
            DeviceId id = DeviceScanner.deviceIdOf(node.getOwner());
            targets.add(new AccelerationTarget(id != null ? id : DeviceId.ofBlock(dim, p), node, tickable));
        }
        return targets;
    }

    // ========================= 访问器与设置 =========================

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
     * 是否正在加速（本 tick 确实有设备被加速）。
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
     * 设置加速倍数（1x~配置上限），范围变化后重建目标缓存。
     */
    public void setSpeed(int value) {
        int clamped = clampRange(value, RuntimeConfig.torcherinoMaxSpeed());
        if (this.speed != clamped) {
            this.speed = clamped;
            onConfigChanged();
        }
    }

    /**
     * 范围 / 倍数变化：目标集合随之变化，标记缓存待重建，并立即通知存档与客户端。
     */
    private void onConfigChanged() {
        targetCache.markDirty();
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
        this.speed = clampRange(tag.getInt(TAG_SPEED), RuntimeConfig.torcherinoMaxSpeed());
        if (this.speed <= 0) {
            this.speed = 1;
        }
        // 加载后配置可能变化，标记缓存待重建。
        targetCache.markDirty();
    }
}
