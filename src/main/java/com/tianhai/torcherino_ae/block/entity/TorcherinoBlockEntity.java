package com.tianhai.torcherino_ae.block.entity;

import com.tianhai.torcherino_ae.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 加速火把方块实体：在其影响范围内，凡是属于 AE2 命名空间（ae2）的方块，都会在每 tick 被额外驱动多次。
 * 采用与 Torcherino 相同的机制：重复调用目标方块的 {@link EntityBlock#getTicker} 所返回的 ticker，
 * 从而加速 AE 的各类机器（ME 接口、驱动器、压印器、充能器、加工器等）。
 */
public class TorcherinoBlockEntity extends BlockEntity {
    /** Applied Energistics 2 的命名空间。凡是注册在此命名空间下的方块都会被加速。 */
    public static final String AE2_NAMESPACE = "ae2";

    /** 右键切换加速倍率时可选的档位。 */
    private static final int[] SPEED_TIERS = {1, 2, 4, 8, 16};
    private static final int MAX_TIER = SPEED_TIERS.length - 1;

    private int xRange;
    private int yRange;
    private int zRange;
    private int speed;
    private boolean active;

    public TorcherinoBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntityTypes.TORCHERINO.get(), pos, state);
        this.xRange = Config.DEFAULT_RANGE_X.get();
        this.yRange = Config.DEFAULT_RANGE_Y.get();
        this.zRange = Config.DEFAULT_RANGE_Z.get();
        this.speed = nearestTier(Config.DEFAULT_SPEED.get());
        this.active = Config.DEFAULT_ACTIVE.get();
    }

    /** 方块实体的 tick 入口，由 {@link com.tianhai.torcherino_ae.block.TorcherinoBlock#getTicker} 驱动。 */
    public static void tick(Level level, BlockPos pos, BlockState state, TorcherinoBlockEntity blockEntity) {
        if (level.isClientSide()) {
            return;
        }
        if (!blockEntity.active || blockEntity.speed <= 0) {
            return;
        }
        int minX = pos.getX() - blockEntity.xRange;
        int minY = pos.getY() - blockEntity.yRange;
        int minZ = pos.getZ() - blockEntity.zRange;
        int maxX = pos.getX() + blockEntity.xRange;
        int maxY = pos.getY() + blockEntity.yRange;
        int maxZ = pos.getZ() + blockEntity.zRange;
        for (BlockPos target : BlockPos.betweenClosed(minX, minY, minZ, maxX, maxY, maxZ)) {
            blockEntity.accelerate((ServerLevel) level, target);
        }
    }

    private void accelerate(ServerLevel level, BlockPos targetPos) {
        if (targetPos.equals(worldPosition)) {
            return;
        }
        BlockState blockState = level.getBlockState(targetPos);
        Block block = blockState.getBlock();
        if (!isAe2Block(block) || !(block instanceof EntityBlock entityBlock)) {
            return;
        }
        BlockEntity blockEntity = level.getBlockEntity(targetPos);
        if (blockEntity == null || blockEntity.isRemoved()) {
            return;
        }
        //noinspection unchecked
        BlockEntityTicker<BlockEntity> ticker =
                (BlockEntityTicker<BlockEntity>) entityBlock.getTicker(level, blockState, blockEntity.getType());
        if (ticker == null) {
            return;
        }
        for (int i = 0; i < speed; i++) {
            if (blockEntity.isRemoved()) {
                return;
            }
            ticker.tick(level, targetPos, blockState, blockEntity);
        }
    }

    private static boolean isAe2Block(Block block) {
        ResourceLocation key = BuiltInRegistries.BLOCK.getKey(block);
        return key != null && AE2_NAMESPACE.equals(key.getNamespace());
    }

    /** 在可选档位中选择最接近给定倍率的一个。 */
    private static int nearestTier(int target) {
        int best = SPEED_TIERS[0];
        for (int tier : SPEED_TIERS) {
            if (Math.abs(tier - target) < Math.abs(best - target)) {
                best = tier;
            }
        }
        return best;
    }

    /** 在可选档位中循环到下一个倍率。 */
    public void cycleSpeed() {
        int index = 0;
        for (int i = 0; i <= MAX_TIER; i++) {
            if (SPEED_TIERS[i] == this.speed) {
                index = (i + 1) % SPEED_TIERS.length;
                break;
            }
        }
        this.speed = SPEED_TIERS[index];
        setChanged();
    }

    public void toggle() {
        this.active = !this.active;
        setChanged();
    }

    public boolean isActive() {
        return active;
    }

    public int getSpeed() {
        return speed;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.saveAdditional(tag, provider);
        tag.putInt("xRange", xRange);
        tag.putInt("yRange", yRange);
        tag.putInt("zRange", zRange);
        tag.putInt("speed", speed);
        tag.putBoolean("active", active);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.loadAdditional(tag, provider);
        xRange = tag.getInt("xRange");
        yRange = tag.getInt("yRange");
        zRange = tag.getInt("zRange");
        speed = tag.getInt("speed");
        if (speed <= 0) {
            speed = nearestTier(Config.DEFAULT_SPEED.get());
        }
        active = tag.getBoolean("active");
    }
}