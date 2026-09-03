package com.tianhai.torcherino_ae.core;

import java.util.List;

import com.tianhai.torcherino_ae.api.AccelerationResult;
import com.tianhai.torcherino_ae.api.AccelerationTarget;
import com.tianhai.torcherino_ae.api.BudgetMeter;
import com.tianhai.torcherino_ae.api.IAccelerationSource;

import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.ticking.IGridTickable;
import appeng.api.networking.ticking.ITickManager;
import appeng.api.networking.ticking.TickRateModulation;

/**
 * 加速脉冲执行器：全项目唯一的「让 AE2 设备加速」实现。
 * <p>
 * AE 加速器与 AE 加速火把两类加速源只通过 {@link IAccelerationSource} 暴露
 * 目标与倍率，本类统一完成催促、多次调用、睡眠早退与预算扣减，保证它们
 * 行为一致、实现只有一份。
 * <p>
 * 每台目标的执行顺序：
 * <ol>
 *   <li>节点已脱离网格、或已不属于本源所在网格（换网/被移除）→ 标记缓存重建并跳过；</li>
 *   <li>节点未激活（未通电、未 Boot、通道不足）→ 跳过；</li>
 *   <li>该设备倍率小于等于 1 → 跳过（倍率为 1 即表示不加速）；</li>
 *   <li>设备期望睡眠（空闲中）→ 跳过；</li>
 *   <li>向预算申请调用额度，额度不足 → 标记预算耗尽并结束本次脉冲；</li>
 *   <li>{@code alertDevice} 催促，随后在<b>同一个游戏 tick 内</b>额外执行若干次
 *       {@code tickingRequest}，每次检查返回值，设备转为 {@code SLEEP} 立即停止该设备。</li>
 * </ol>
 * <p>
 * <b>性能约束</b>：本方法位于每 tick 路径上，禁止分配对象、禁止字符串拼接、
 * 禁止日志输出、禁止遍历 {@code grid.getNodes()}。
 */
public final class AccelerationEngine {

    private AccelerationEngine() {
    }

    /**
     * 对指定加速源执行一次加速脉冲。
     */
    public static AccelerationResult pulse(IAccelerationSource source) {
        // 源未处于可工作状态（未联网 / 未激活 / 倍率为 1 / 范围为空）时直接返回。
        if (!source.isActive()) {
            return AccelerationResult.NONE;
        }

        BudgetMeter budget = source.budget();
        budget.resetTick();

        // 单网格源返回具体网格做一致性校验；多网格源（火把）返回 null，跳过该校验。
        IGrid expectedGrid = source.grid();
        List<AccelerationTarget> targets = source.targets();

        int hit = 0;
        int skippedSleeping = 0;
        int skippedInactive = 0;
        int skippedDetached = 0;
        int tickCalls = 0;
        boolean budgetExhausted = false;

        for (int i = 0; i < targets.size(); i++) {
            AccelerationTarget target = targets.get(i);
            IGridNode node = target.node();

            // 节点已脱离网格或已换网：标记缓存待重建，本次跳过（下次重建时自然剔除）。
            if (target.isDetached() || !target.belongsTo(expectedGrid)) {
                skippedDetached++;
                source.markTargetsDirty();
                continue;
            }
            // 只加速处于激活状态的设备。
            if (!node.isActive()) {
                skippedInactive++;
                continue;
            }
            // 倍率小于等于 1 表示不加速（设备被取消，或智能加速当前无 CPU 在合成）。
            int extraCalls = source.multiplierFor(target.id()) - 1;
            if (extraCalls <= 0) {
                continue;
            }
            IGridTickable tickable = target.tickable();
            // 设备期望睡眠（空闲中）：催促唤醒没有意义，跳过。
            if (tickable.getTickingRequest(node).isSleeping()) {
                skippedSleeping++;
                continue;
            }
            // 预算约束：额度耗尽时结束本次脉冲（不限预算时原样放行）。
            if (budget.isExhausted()) {
                budgetExhausted = true;
                break;
            }
            int granted = budget.request(extraCalls);
            if (granted <= 0) {
                budgetExhausted = true;
                break;
            }
            IGrid nodeGrid = node.getGrid();
            ITickManager tickManager = nodeGrid == null ? null : nodeGrid.getTickManager();
            if (tickManager == null) {
                continue;
            }

            // 先把设备提前到「下一个 tick」触发，再在同一 tick 内额外推进工作量。
            tickManager.alertDevice(node);
            for (int c = 0; c < granted; c++) {
                tickCalls++;
                // 设备在处理过程中转入睡眠（工作已完成、原料耗尽或状态变更）时提前结束：
                // 设备已空闲后继续推进其内部工作没有意义，还可能让它越过自身状态机边界。
                // 注意 tickingRequest 返回 TickRateModulation 枚举，与 getTickingRequest
                // 返回的 TickingRequest（才有 isSleeping()）不是一回事，不可混用。
                if (tickable.tickingRequest(node, 1) == TickRateModulation.SLEEP) {
                    break;
                }
            }
            hit++;
        }

        return new AccelerationResult(hit, skippedSleeping, skippedInactive, skippedDetached, tickCalls,
                budgetExhausted);
    }
}
