package com.tianhai.torcherino_ae.block;

import appeng.api.orientation.IOrientationStrategy;
import appeng.api.orientation.OrientationStrategies;
import appeng.block.AEBaseEntityBlock;
import com.tianhai.torcherino_ae.blockentity.AEAcceleratorBlockEntity;
import com.tianhai.torcherino_ae.menu.AEAcceleratorMenu;
import appeng.menu.MenuOpener;
import appeng.menu.locator.MenuLocators;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * AE 加速器方块。
 * 继承 AE2 的 {@link AEBaseEntityBlock}，提供标准的方块实体（机器）基础设施。
 * 玩家右键可打开升级卡界面。
 * <p>
 * 朝向：通过 AE2 的水平朝向策略（{@link OrientationStrategies#horizontalFacing()}）
 * 拥有东南西北朝向属性（facing），并支持扳手旋转。
 * 模型切换：依据方块实体的「已接入网络 / 正在工作」状态，通过 {@link #updateBlockStateFromBlockEntity}
 * 自动切换 on / inactive / 基础模型。
 */
public class AEAcceleratorBlock extends AEBaseEntityBlock<AEAcceleratorBlockEntity> {

    // 是否已接入 AE 网络（用于切换到 on 模型）。
    public static final BooleanProperty ONLINE = BooleanProperty.create("online");
    // 是否正在执行加速（用于切换到 inactive 模型）。
    public static final BooleanProperty WORKING = BooleanProperty.create("working");

    public AEAcceleratorBlock(BlockBehaviour.Properties properties) {
        super(properties);
        // 默认：朝向为北，未接入网络、未工作。
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH)
                .setValue(ONLINE, false)
                .setValue(WORKING, false));
    }

    /**
     * 使用 AE2 的水平朝向策略，使方块拥有东南西北朝向属性，并支持扳手旋转。
     */
    @Override
    public IOrientationStrategy getOrientationStrategy() {
        return OrientationStrategies.horizontalFacing();
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(ONLINE, WORKING);
    }

    /**
     * 根据方块实体的「接入网络 / 正在工作」状态更新方块状态，
     * 从而驱动客户端切换 on / inactive / 基础模型。
     */
    @Override
    protected BlockState updateBlockStateFromBlockEntity(BlockState currentState, AEAcceleratorBlockEntity be) {
        return currentState
                .setValue(ONLINE, be.isOnline())
                .setValue(WORKING, be.isWorking());
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
            BlockHitResult hitResult) {
        if (!level.isClientSide()) {
            openMenu(level, pos, player);
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
            Player player, InteractionHand hand, BlockHitResult hitResult) {
        if (!level.isClientSide()) {
            openMenu(level, pos, player);
        }
        return ItemInteractionResult.sidedSuccess(level.isClientSide());
    }

    /**
     * 覆盖遮挡形状为「空」，使本方块不会遮挡相邻方块的面。
     * <p>
     * 加速器模型四周带镂空框架，但方块仍被当作完整不透明立方体。若不覆盖此方法，
     * 原版会把与加速器相邻的方块的那一面剔除掉；透过加速器的镂空处看到的就是被剔除后
     * 的方块「内部」，造成穿透渲染。返回 {@link Shapes#empty()} 关闭这种遮挡剔除即可修复。
     */
    @Override
    public VoxelShape getOcclusionShape(BlockState state, BlockGetter level, BlockPos pos) {
        return Shapes.empty();
    }

    /**
     * 打开 AE 加速器的升级卡界面。
     */
    private void openMenu(Level level, BlockPos pos, Player player) {
        AEAcceleratorBlockEntity blockEntity = getBlockEntity(level, pos);
        if (blockEntity != null) {
            MenuOpener.open(AEAcceleratorMenu.TYPE, player, MenuLocators.forBlockEntity(blockEntity));
        }
    }
}
