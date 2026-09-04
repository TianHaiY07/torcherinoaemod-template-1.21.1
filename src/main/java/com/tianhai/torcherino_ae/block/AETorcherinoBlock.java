package com.tianhai.torcherino_ae.block;

import java.util.Map;
import java.util.function.Supplier;

import org.jetbrains.annotations.Nullable;

import appeng.menu.MenuOpener;
import appeng.menu.locator.MenuLocators;
import com.tianhai.torcherino_ae.blockentity.AETorcherinoBlockEntity;
import com.tianhai.torcherino_ae.menu.AETorcherinoMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * AE 加速火把。
 * <p>
 * 独立范围扫描的加速方块（Torcherino 式架构）：自身不接入 AE 网络、不消耗 AE 能量，
 * 服务端每个 tick 扫描周围立方体区域，把其中的方块以配置的倍数加速，加速对象不限于 AE 设备：
 * AE 网格设备重复驱动其网格 tick（{@code IGridTickable}），带方块实体 ticker 的方块重复调用
 * 其 ticker（原版熔炉、第三方机器等），可随机 tick 的方块重复调用随机 tick——三条路径对同一目标
 * 并行生效，详见 {@link com.tianhai.torcherino_ae.blockentity.AETorcherinoBlockEntity}。
 * <p>
 * 模型使用 {@code ae_torcherino.json}（朝上的火把模型）；方块拥有 6 个朝向（上/下/东南西北），
 * 通过 {@link #FACING} 属性控制，选择箱随朝向旋转。无碰撞体积（可穿行），不产生火把火焰粒子。
 */
public class AETorcherinoBlock extends Block implements EntityBlock {

    // 6 个朝向属性（上/下/东南西北）。
    public static final DirectionProperty FACING = BlockStateProperties.FACING;

    // 本方块对应的方块实体类型（用于 getTicker 类型校验）与工厂（用于 newBlockEntity）。
    // 以 Supplier 形式保存，避免在 ModBlocks 静态初始化阶段解析 ModBlockEntities 的
    // DeferredHolder（两个 DeferredRegister 的注册事件存在执行顺序，提前 get() 可能拿不到值）。
    private final Supplier<BlockEntityType<? extends AETorcherinoBlockEntity>> blockEntityType;
    private final BlockEntityType.BlockEntitySupplier<? extends AETorcherinoBlockEntity> blockEntityFactory;

    // ===== 选择箱（随朝向旋转，无碰撞体积） =====
    // 基础「朝上」火把：细长柱体，宽 4px、高 9px，位于方块下部。
    private static final VoxelShape SHAPE_UP = pixelBox(6, 0, 6, 10, 9, 10);
    // 朝下：火把挂在天花板，底部（宽大底座）朝上、尖端朝下。
    private static final VoxelShape SHAPE_DOWN = pixelBox(6, 7, 6, 10, 16, 10);
    // 朝北：尖端指向 -Z。
    private static final VoxelShape SHAPE_NORTH = pixelBox(6, 3, 0, 10, 9, 6);
    // 朝南：尖端指向 +Z。
    private static final VoxelShape SHAPE_SOUTH = pixelBox(6, 3, 10, 10, 9, 16);
    // 朝西：尖端指向 -X。
    private static final VoxelShape SHAPE_WEST = pixelBox(0, 3, 6, 6, 9, 10);
    // 朝东：尖端指向 +X。
    private static final VoxelShape SHAPE_EAST = pixelBox(10, 3, 6, 16, 9, 10);

    private static final Map<Direction, VoxelShape> SHAPES = Map.of(
            Direction.UP, SHAPE_UP,
            Direction.DOWN, SHAPE_DOWN,
            Direction.NORTH, SHAPE_NORTH,
            Direction.SOUTH, SHAPE_SOUTH,
            Direction.WEST, SHAPE_WEST,
            Direction.EAST, SHAPE_EAST);

    public AETorcherinoBlock(Properties properties,
            Supplier<BlockEntityType<? extends AETorcherinoBlockEntity>> blockEntityType,
            BlockEntityType.BlockEntitySupplier<? extends AETorcherinoBlockEntity> blockEntityFactory) {
        super(properties);
        this.blockEntityType = blockEntityType;
        this.blockEntityFactory = blockEntityFactory;
        this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.UP));
    }

    /**
     * 以「被点击的面」确定火把朝向：点击方块顶面则火把朝上放置，点击侧面则火把朝该面法线方向伸出。
     */
    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return this.defaultBlockState().setValue(FACING, context.getClickedFace());
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    /**
     * 无碰撞体积：玩家可直接穿过火把。
     */
    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos,
            CollisionContext context) {
        return Shapes.empty();
    }

    /**
     * 选择箱（用于高亮/破坏判定）随朝向旋转，返回对应朝向下预先算好的旋转形状。
     */
    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES.get(state.getValue(FACING));
    }

    /**
     * 遮挡形状为空：火把是空心细柱，若按整块不透明处理，相邻方块与火把相邻的面会被剔除，
     * 透过火把的镂空看到相邻方块的内部。返回空即关闭该遮挡剔除。
     */
    @Override
    public VoxelShape getOcclusionShape(BlockState state, BlockGetter level, BlockPos pos) {
        return Shapes.empty();
    }

    /**
     * 火把需要依附于某个实心面：该面位于朝向的反方向。朝向为上→依附下方地面；朝向为北→依附南侧方块。
     */
    @Override
    public boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        Direction facing = state.getValue(FACING);
        return canSupportCenter(level, pos.relative(facing.getOpposite()), facing);
    }

    @Override
    public void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighborBlock,
            BlockPos neighborPos, boolean isMoving) {
        if (!level.isClientSide() && !state.canSurvive(level, pos)) {
            level.destroyBlock(pos, true);
        }
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return blockEntityFactory.create(pos, state);
    }

    /**
     * 服务端每 tick 执行加速扫描；客户端不执行（模型无动画，无需客户端 tick）。
     * <p>
     * 类型校验通过与构造时绑定的方块实体类型比较完成（基础火把为 AE_TORCHERINO，
     * 分级火把 I/II 分别为各自类型），三类方块的服务器 tick 均复用
     * {@link AETorcherinoBlockEntity#serverTick}（分级火把为其子类），无需各自实现。
     */
    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
            BlockEntityType<T> type) {
        // 客户端不提供 ticker；类型不匹配时同样返回 null，避免下方的强制类型转换
        // 在异常数据流下抛出 ClassCastException。
        if (level.isClientSide() || !type.equals(this.blockEntityType.get())) {
            return null;
        }
        return (lvl, pos, st, be) -> AETorcherinoBlockEntity.serverTick(lvl, pos, st, (AETorcherinoBlockEntity) be);
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

    private void openMenu(Level level, BlockPos pos, Player player) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof AETorcherinoBlockEntity torch) {
            MenuOpener.open(AETorcherinoMenu.TYPE, player, MenuLocators.forBlockEntity(torch));
        }
    }

    /**
     * 以像素坐标创建选择箱（内部自动换算为 0..1 的分数坐标）。
     */
    private static VoxelShape pixelBox(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        return Shapes.box(minX / 16.0, minY / 16.0, minZ / 16.0, maxX / 16.0, maxY / 16.0, maxZ / 16.0);
    }
}
