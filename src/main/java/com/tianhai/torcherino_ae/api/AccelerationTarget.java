package com.tianhai.torcherino_ae.api;

import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.ticking.IGridTickable;

/**
 * 已解析的加速目标：在缓存重建期一次性解析好身份、网格节点与网格 tick 服务，
 * 使每 tick 的脉冲路径无需再做任何查找、强转或服务查询。
 * <p>
 * 这是「每 tick 路径不遍历网格」这条性能铁律的载体——重建期把重活干完，
 * 脉冲期只做纯粹的调用。
 */
public record AccelerationTarget(DeviceId id, IGridNode node, IGridTickable tickable) {

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
     * （火把可同时覆盖多个 AE 网络，不做网格一致性校验）。
     * 节点已销毁时按不属于任何网格处理。
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
     * 会构造异常，正常路径与 null 判空开销相同。
     */
    private static IGrid gridOf(IGridNode node) {
        try {
            return node.getGrid();
        } catch (IllegalStateException destroyed) {
            return null;
        }
    }
}
