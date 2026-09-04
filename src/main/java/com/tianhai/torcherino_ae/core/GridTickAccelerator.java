package com.tianhai.torcherino_ae.core;

import org.jetbrains.annotations.Nullable;

import appeng.api.networking.IGridNode;
import appeng.api.networking.ticking.IGridTickable;
import appeng.api.networking.ticking.TickRateModulation;

/**
 * 网格 tick 黑盒加速的执行原语。
 * <p>
 * 全项目有两处「在同一个游戏 tick 内对 AE 设备重复推进处理进度」的循环——
 * {@link AccelerationEngine#pulse} 的火把加速器网格 tick 段，以及加速火把的黑盒网格 tick 段。
 * 其中真正<b>相同且可复用的核心</b>是「把设备向前推若干步，遇到 {@code SLEEP} 立即停止」。
 * <p>
 * 注意：这两处调用方在<b>预算粒度 / sleepDevice / 异常兜底 / 返回语义</b>上刻意不同，
 * 本类<b>不做</b>预算、不抛/吞异常、不调用 {@code sleepDevice}，只保留最内层「推进循环」。
 * 因此调用方仍需自行处理：预算申请、{@code alertDevice}/{@code sleepDevice}、每 tick 防御性
 * {@code try/catch}（火把面向任意方块，需要一个稳健的异常围栏，见 AETorcherinoBlockEntity）。
 */
public final class GridTickAccelerator {

    private GridTickAccelerator() {
    }

    /**
     * 在同一个游戏 tick 内对设备发起至多 {@code maxCalls} 次 {@code tickingRequest(node, 1)}。
     * <p>
     * 每次调用检查返回值：设备转入 {@link TickRateModulation#SLEEP}（工作已完成、原料耗尽或
     * 状态变更）时立即结束——设备已空闲后继续推进其内部工作没有意义，还可能让它越过自身状态机边界。
     * <p>
     * <b>计数口径</b>：返回的「调用次数」<b>包含</b>返回 {@code SLEEP} 的那一次（与预算、诊断计数
     * 一致）；返回 0 表示设备尚未被调用或一进入即睡眠。
     *
     * @param maxCalls 最多发起的调用次数（由调用方按倍率/预算预先确定）
     * @return 实际发起的调用次数（含触发 {@code SLEEP} 的那一次）
     */
    public static int tick(@Nullable IGridNode node, @Nullable IGridTickable tickable, int maxCalls) {
        if (node == null || tickable == null) {
            return 0;
        }
        int calls = 0;
        for (int c = 0; c < maxCalls; c++) {
            calls++;
            if (tickable.tickingRequest(node, 1) == TickRateModulation.SLEEP) {
                break;
            }
        }
        return calls;
    }
}
