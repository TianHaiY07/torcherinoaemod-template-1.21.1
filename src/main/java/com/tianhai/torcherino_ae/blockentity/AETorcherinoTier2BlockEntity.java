package com.tianhai.torcherino_ae.blockentity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * AE 加速火把 II。
 * <p>
 * 与基础 {@link AETorcherinoBlockEntity} 行为完全一致（可调 X/Z/Y 范围与加速倍数），
 * 唯一区别是加速倍率的上限被固定为 {@link #MAX_SPEED}（324x），由控制台配置驱动的
 * {@code torcherino.maxSpeed}（默认 4）不再约束本分级火把。放置时默认倍率即为其上限。
 */
public class AETorcherinoTier2BlockEntity extends AETorcherinoBlockEntity {

    /** 本分级火把可调到的最大加速倍数。 */
    public static final int MAX_SPEED = 324;

    public AETorcherinoTier2BlockEntity(BlockPos pos, BlockState state) {
        super(pos, state, ModBlockEntities.AE_TORCHERINO_TIER_II.get(), MAX_SPEED);
    }

    /**
     * 供 {@link net.minecraft.world.level.block.entity.BlockEntityType} 使用的工厂方法。
     */
    public static AETorcherinoTier2BlockEntity create(BlockPos pos, BlockState state) {
        return new AETorcherinoTier2BlockEntity(pos, state);
    }

    /**
     * 本分级火把的倍率上限固定为 {@link #MAX_SPEED}，不受服务端配置 {@code torcherino.maxSpeed} 影响。
     */
    @Override
    public int maxSpeed() {
        return MAX_SPEED;
    }
}
