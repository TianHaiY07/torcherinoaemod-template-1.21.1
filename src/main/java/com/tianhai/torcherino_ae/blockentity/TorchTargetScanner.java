package com.tianhai.torcherino_ae.blockentity;

import java.util.ArrayList;
import java.util.List;

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
 * 加速火把的「影响范围目标扫描器」。
 * <p>
 * 把「以火把为中心、三维半径由 X/Y/Z 三个滑块决定」的立方体区域内的方块集中扫描并缓存为
 * 不可变快照，供火把每 tick 只对缓存目标做加速调用，避免每 tick 全量遍历整块区域
 * <p>
 * 判定为候选的条件（满足其一）：实现 AE 网格设备接口（{@link IActionHost} 或
 * {@link IGridConnectedBlockEntity}，宽口径，含线缆/总线等全部网格宿主）、带方块实体且其方块
 * 提供 ticker、或方块本身随机 tick。空气与「三者皆无」的纯装饰方块不缓存。
 */
public final class TorchTargetScanner {

    private TorchTargetScanner() {
    }

    /**
     * 一个被缓存的加速目标：在扫描期把「方块实体类型 / ticker / 是否随机 tick / 是否 AE 设备」
     * 一次性解析到位，避免每 tick 重复查表取状态。方块位置必须是不可变快照
     * （{@code betweenClosed} 返回可变 {@link BlockPos}）。
     */
    public record Target(BlockPos pos, boolean isAeMachine,
            @Nullable BlockEntityType<?> beType,
            @Nullable BlockEntityTicker<BlockEntity> ticker, boolean randomlyTicking) {
    }

    /**
     * 重新扫描影响范围立方体，返回范围内「可能被加速」的方块快照列表。
     *
     * @param self   火把自身坐标（自身与其它加速火把不作为目标，防止互相递归加速）
     * @param xRange X 轴半径
     * @param yRange Y 轴半径
     * @param zRange Z 轴半径
     */
    @SuppressWarnings("unchecked")
    public static List<Target> scan(ServerLevel level, BlockPos self, int xRange, int yRange, int zRange) {
        List<Target> targets = new ArrayList<>();
        int minX = self.getX() - xRange;
        int minY = self.getY() - yRange;
        int minZ = self.getZ() - zRange;
        int maxX = self.getX() + xRange;
        int maxY = self.getY() + yRange;
        int maxZ = self.getZ() + zRange;
        for (BlockPos pos : BlockPos.betweenClosed(minX, minY, minZ, maxX, maxY, maxZ)) {
            if (pos.equals(self)) {
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
        return targets;
    }

    /** 判断一个方块实体是否为 AE 网格设备：实现 {@link IActionHost} 或 {@link IGridConnectedBlockEntity}，涵盖 AE2 原版机器与所有附属模组。 */
    private static boolean isAeGridBlockEntity(@Nullable BlockEntity be) {
        return be instanceof IActionHost || be instanceof IGridConnectedBlockEntity;
    }
}
