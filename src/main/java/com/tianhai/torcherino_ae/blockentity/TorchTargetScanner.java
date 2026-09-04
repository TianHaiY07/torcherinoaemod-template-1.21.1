package com.tianhai.torcherino_ae.blockentity;

import org.jetbrains.annotations.Nullable;

import appeng.api.networking.security.IActionHost;
import appeng.me.helpers.IGridConnectedBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 加速火把的「影响范围目标解析器」。
 * <p>
 * 与「扫描调度」（{@link AETorcherinoBlockEntity} 内的分片 + 自适应退避循环）解耦：本类只负责
 * 两件事——把「以火把为中心、X/Y/Z 三轴半径」的立方体外接盒展平为一维索引（支持分片扫描），
 * 以及把单个世界坐标解析成可加速目标的不可变快照（{@link #resolve}）。
 * <p>
 * 判定为候选的条件（满足其一）：实现 AE 网格设备接口（{@link IActionHost} 或
 * {@link IGridConnectedBlockEntity}，宽口径，含线缆/总线等全部网格宿主）、带方块实体且其方块
 * 提供 ticker、或方块本身随机 tick。空气与「三者皆无」的纯装饰方块不缓存。
 * <p>
 * 参照 RTS 输电塔的范围检测：扫描按展平下标分片、单格仅 1 次 {@code getBlockEntity}、
 * 跳过未加载区块（{@code level.isLoaded}），把大范围扫描的主线程成本从「单 tick 一次全量」
 * 摊薄到 {@code scanIntervalTicks} 个 tick 内完成。
 */
public final class TorchTargetScanner {

    private TorchTargetScanner() {
    }

    /**
     * 一个被缓存的加速目标：在扫描期把「方块实体类型 / ticker / 是否随机 tick / 是否 AE 设备」
     * 一次性解析到位，避免每 tick 重复查表取状态。
     */
    public record Target(BlockPos pos, boolean isAeMachine,
            @Nullable BlockEntityType<?> beType,
            @Nullable BlockEntityTicker<BlockEntity> ticker, boolean randomlyTicking) {
    }

    /**
     * 影响范围立方体外接盒：由火把自身坐标与 X/Z/Y 三轴半径计算 min/max 与单元格总数。
     * <p>
     * {@code size}（枚举量）用于把三维区域展平为一维下标，从而支持「分片扫描」——把一个完整扫圈
     * 分摊到多个 tick，避免单个 tick 全量遍历大范围（主线程热点，参照 RTS 输电塔设计）。
     */
    public record Bounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, int size) {
    }

    /**
     * 计算以 {@code self} 为中心、三轴半径为 {@code xRange/yRange/zRange} 的立方体外接盒。
     */
    public static Bounds bounds(BlockPos self, int xRange, int yRange, int zRange) {
        int minX = self.getX() - xRange;
        int minY = self.getY() - yRange;
        int minZ = self.getZ() - zRange;
        int maxX = self.getX() + xRange;
        int maxY = self.getY() + yRange;
        int maxZ = self.getZ() + zRange;
        int size = (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        return new Bounds(minX, minY, minZ, maxX, maxY, maxZ, size);
    }

    /**
     * 把展平的一维下标解码为<b>相对火把坐标</b>的偏移（用于分片扫描时定位下一格）。
     * <p>
     * 下标按「Z 轴最快、其次 Y、X 最慢」排列（与 {@code BlockPos.betweenClosed} 的展平顺序
     * 类似），配合 {@link #bounds} 的 size 使用。返回的是可变的相对偏移，需经
     * {@code worldPosition.offset(off)} 转成世界坐标后再调用 {@link #resolve}。
     */
    public static BlockPos offsetForIndex(int index, Bounds b) {
        int widthZ = b.maxZ() - b.minZ() + 1;
        int heightY = b.maxY() - b.minY() + 1;
        int rangeZ = (b.maxZ() - b.minZ()) / 2;
        int rangeY = (b.maxY() - b.minY()) / 2;
        int rangeX = (b.maxX() - b.minX()) / 2;
        int dz = index % widthZ - rangeZ;
        int dy = (index / widthZ) % heightY - rangeY;
        int dx = index / (widthZ * heightY) - rangeX;
        return new BlockPos(dx, dy, dz);
    }

    /**
     * 把单个世界坐标解析为可加速目标的不可变快照；非候选返回 {@code null}（空气/自身/其它
     * 火把/三者皆无的纯装饰方块）。
     *
     * @param self 火把自身坐标（自身与其它加速火把不作为目标，防止互相递归加速）
     */
    @SuppressWarnings("unchecked")
    @Nullable
    public static Target resolve(ServerLevel level, BlockPos self, BlockPos pos) {
        if (pos.equals(self)) {
            return null;
        }
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            return null;
        }
        Block block = state.getBlock();
        BlockEntity be = level.getBlockEntity(pos);
        // 不能把其它加速火把当作加速目标：火把 A 加速火把 B 的方块实体 ticker 时，
        // B 的 tick 又会反过来去加速 A 的 ticker，两者互相递归直至栈溢出崩溃。
        if (be instanceof AETorcherinoBlockEntity) {
            return null;
        }
        boolean isAeMachine = isAeGridBlockEntity(be);
        boolean randomlyTicking = state.isRandomlyTicking();
        // 只缓存可能被加速（AE 网格设备、有方块实体 tick 或随机 tick）的目标。
        if (!isAeMachine && be == null && !randomlyTicking) {
            return null;
        }
        BlockEntityType<?> beType = be != null ? be.getType() : null;
        BlockEntityTicker<BlockEntity> ticker = null;
        if (beType != null && block instanceof EntityBlock entityBlock) {
            //noinspection unchecked
            ticker = (BlockEntityTicker<BlockEntity>) entityBlock.getTicker(level, state, beType);
        }
        return new Target(pos.immutable(), isAeMachine, beType, ticker, randomlyTicking);
    }

    /**
     * 计算一次完整扫圈的「分片窗口长度」（tick）。
     * <p>
     * 窗口长度取「基础窗口 {@code baseTicks}」与「按单元格总数把单 tick 扫描量钳制到
     * {@code maxCellsPerTick} 所需的最少 tick 数」的较大者：小范围保持 {@code baseTicks} 不变；
     * 范围极大（服务端调高上限）时把窗口线性拉长，使单 tick 最多扫描 {@code maxCellsPerTick} 格，
     * 防止超大范围单 tick 全量遍历造成主线程尖峰。
     */
    public static int windowFor(int baseTicks, int cellCount, int maxCellsPerTick) {
        if (baseTicks < 1) {
            baseTicks = 1;
        }
        if (maxCellsPerTick < 1) {
            return baseTicks;
        }
        if (cellCount <= 0) {
            return baseTicks;
        }
        int stretch = (cellCount + maxCellsPerTick - 1) / maxCellsPerTick;
        return Math.max(baseTicks, stretch);
    }

    /** 判断一个方块实体是否为 AE 网格设备：实现 {@link IActionHost} 或 {@link IGridConnectedBlockEntity}，涵盖 AE2 原版机器与所有附属模组。 */
    private static boolean isAeGridBlockEntity(@Nullable BlockEntity be) {
        return be instanceof IActionHost || be instanceof IGridConnectedBlockEntity;
    }
}
