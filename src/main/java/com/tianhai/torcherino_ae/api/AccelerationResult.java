package com.tianhai.torcherino_ae.api;

/**
 * 一次加速脉冲的执行结果。
 * <p>
 * 供调用方判断「本 tick 是否真的加速了设备」（决定是否耗能与切换工作状态），
 * 并可据此输出诊断信息。
 */
public record AccelerationResult(
        /** 真正被加速的设备数。 */
        int hit,
        /** 因设备处于睡眠（空闲）而跳过的数量。 */
        int skippedSleeping,
        /** 因节点未激活而跳过的数量。 */
        int skippedInactive,
        /** 因节点脱离网格而被剔除的数量。 */
        int skippedDetached,
        /** 本 tick 实际执行的 tickingRequest 调用总数。 */
        int tickCalls,
        /** 是否因预算耗尽而提前结束本次脉冲。 */
        boolean budgetExhausted,
        /** 本次脉冲实际执行的耗时（毫秒），供源级加速耗时调控（RateGovernor）取样。 */
        double spentMs) {

    /** 没有任何设备被加速的结果（未激活、无目标等场景）。 */
    public static final AccelerationResult NONE = new AccelerationResult(0, 0, 0, 0, 0, false, 0.0);

    /** 是否至少加速了一台设备。 */
    public boolean didWork() {
        return hit > 0;
    }
}
