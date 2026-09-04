package com.tianhai.torcherino_ae.api;

import org.jetbrains.annotations.Nullable;

import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.ticking.IGridTickable;
import com.tianhai.torcherino_ae.util.AeGrid;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;

/**
 * 已解析的加速目标：在缓存重建期把「身份 / 网格节点 / 加速载体」一次性解析到位，
 * 脉冲期直接使用。每 tick 的加速路径因此不需要再做任何查找、强转或服务查询，
 * 只对已解析好的节点发起调用。
 * <p>
 * 加速载体有两种，二选一（可能两者都非空，但引擎优先走 {@link #tickable()}）：
 * <ul>
 *   <li>{@code tickable}：AE2 网格 tick 服务（{@link IGridTickable}，{@code isGridTicking()==true}），
 *       经 AE2 网格 tick 管理器催促；</li>
 *   <li>{@code vanillaTicker}：宿主方块实体的<b>原版</b> tick 函数（{@link BlockEntityTicker}，
 *       经 {@code EntityBlock.getTicker} 取自方块），用于「接了 AE 网络但加工走原版 tick」的机器，
 *       由引擎直接按倍率反复执行其原版 tick。</li>
 * </ul>
 */
public record AccelerationTarget(DeviceId id, IGridNode node, @Nullable IGridTickable tickable,
                                 @Nullable BlockEntityTicker<BlockEntity> vanillaTicker) {

    /** 是否走 AE2 网格 tick 加速路径（有 {@link IGridTickable} 服务）。 */
    public boolean isGridTicking() {
        return tickable != null;
    }

    /**
     * 节点是否已脱离网格（被移除、区块卸载、换网或已销毁）。
     * 命中即说明缓存已过期，调用方应标记重建并在本次脉冲中跳过。
     * <p>
     * 注意：AE2 节点销毁（方块拆除/部件移除）后访问 {@link IGridNode#getGrid()}
     * 会抛 {@link IllegalStateException}（"A node is being used after it has been destroyed."）
     * 而不是返回 {@code null}——缓存中的目标在方块拆除与缓存重建之间存在存活窗口，
     * 逐 tick 的脉冲路径必须把「已销毁」同样视为脱离，否则会在每 tick 路径上崩溃
     * （见 {@link #gridOf}）。
     */
    public boolean isDetached() {
        return gridOf(node) == null;
    }

    /**
     * 节点是否属于指定网格；传入 {@code null} 时任意网格都算匹配
     * （源未入网时不做网格一致性校验）。节点已销毁时按不属于任何网格处理。
     */
    public boolean belongsTo(IGrid grid) {
        if (grid == null) {
            return true;
        }
        return gridOf(node) == grid;
    }

    /**
     * 安全读取节点当前所属网格。
     * <p>
     * AE2 的 {@code GridNode.getGrid()} 在节点销毁后抛 {@link IllegalStateException}
     * 而非返回 {@code null}；这里统一转译为 {@code null}（=已脱离/已销毁），
     * 使调用方只需判空即可覆盖节点存活的全部情况。仅在缓存过期窗口内异常路径
     * 会构造异常，正常路径与 null 判空开销相同（实现委托 {@code util.AeGrid}，全局唯一）。
     */
    private static IGrid gridOf(IGridNode node) {
        return AeGrid.gridOf(node);
    }
}
