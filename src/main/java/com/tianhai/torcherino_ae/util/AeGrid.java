package com.tianhai.torcherino_ae.util;

import org.jetbrains.annotations.Nullable;

import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;

/**
 * AE 网格安全取值辅助。
 * <p>
 * AE2 的 {@code GridNode.getGrid()} 在节点<b>未入网 / 已销毁</b>时会抛 {@link IllegalStateException}
 * （"A node is being used after it has been destroyed."）而非返回 {@code null}。本类统一把该异常
 * 转译为 {@code null}（= 未接入任何网格 / 已脱离），使调用方只需判空即可覆盖节点存活的全部情况。
 * <p>
 * 全项目所有读网格处都必须经此安全入口（或等价地自行包 try/catch），<b>不要</b>改为直接调用
 * {@code getGrid()} 再判空——否则在节点存活但未入网 / 已销毁的窗口内会在每 tick 路径上崩溃。
 */
public final class AeGrid {

    private AeGrid() {
    }

    /**
     * 安全读取节点当前所属网格。
     *
     * @return 节点所属网格；节点未入网 / 已销毁时返回 {@code null}
     */
    @Nullable
    public static IGrid gridOf(@Nullable IGridNode node) {
        if (node == null) {
            return null;
        }
        try {
            return node.getGrid();
        } catch (IllegalStateException destroyed) {
            return null;
        }
    }
}
