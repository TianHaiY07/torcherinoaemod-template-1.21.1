package com.tianhai.torcherino_ae.block;

import com.tianhai.torcherino_ae.block.entity.ModBlockEntityTypes;
import com.tianhai.torcherino_ae.block.entity.TorcherinoBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

public class TorcherinoBlock extends Block implements EntityBlock {
    public TorcherinoBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new TorcherinoBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, net.minecraft.world.level.block.entity.BlockEntityType<T> type) {
        return createTickerHelper(type, ModBlockEntityTypes.TORCHERINO.get(), TorcherinoBlockEntity::tick);
    }

    /** 与原版一致的 ticker 匹配辅助方法，见 {@link net.minecraft.world.level.block.AbstractFurnaceBlock#createTickerHelper}。 */
    @Nullable
    private static <T extends BlockEntity> BlockEntityTicker<T> createTickerHelper(
            BlockEntityType<T> type,
            BlockEntityType<? extends TorcherinoBlockEntity> expectedType,
            BlockEntityTicker<? super TorcherinoBlockEntity> ticker) {
        return type == expectedType ? (BlockEntityTicker<T>) ticker : null;
    }

    @Override
    public InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (!(level.getBlockEntity(pos) instanceof TorcherinoBlockEntity tile)) {
            return InteractionResult.PASS;
        }
        // 左键依然用于破坏方块；右键切换开关，Shift+右键循环加速倍率。
        if (player.isShiftKeyDown()) {
            tile.cycleSpeed();
        } else {
            tile.toggle();
        }
        // 将服务端状态同步到客户端。
        level.sendBlockUpdated(pos, state, state, 3);
        return InteractionResult.SUCCESS;
    }
}