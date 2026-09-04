package com.tianhai.torcherino_ae.api;

/**
 * 单源每 tick 调用预算：限制一个加速源在一个游戏 tick 内可触发的
 * {@code IGridTickable.tickingRequest} 总次数。
 * <p>
 * 加速倍率随升级卡放大（同档堆叠有边际收益递减，默认满配 4 张 III 卡约 526 倍），
 * 高倍率下单台设备每 tick 就会被调用数百上千次，一个源覆盖数十台设备时总量相当可观。
 * 预算允许把单源的每 tick 调用量封顶在安全水位，防止拖垮服务端。
 * <p>
 * <b>默认不限制</b>（{@link #UNLIMITED}）；每个加速源按服务端配置项
 * {@code budget.tickCallsPerSource}（默认 -1）创建自己的预算实例，仅当管理员
 * 显式配置后才生效。
 */
public final class BudgetMeter {

    /** 表示「不限制」。 */
    public static final int UNLIMITED = -1;

    /** 不限预算的共享实例。 */
    public static final BudgetMeter UNLIMITED_METER = new BudgetMeter(UNLIMITED);

    // 每 tick 的调用上限；负值表示不限。
    private final int limitPerTick;

    // 本 tick 已使用的额度。
    private int used;

    public BudgetMeter(int limitPerTick) {
        this.limitPerTick = limitPerTick;
    }

    /**
     * 申请 n 次调用额度，返回实际获批次数（可能为 0）。不限预算时原样返回。
     */
    public int request(int n) {
        if (limitPerTick < 0) {
            return n;
        }
        int remaining = limitPerTick - used;
        if (remaining <= 0) {
            return 0;
        }
        int granted = Math.min(remaining, n);
        used += granted;
        return granted;
    }

    /** 每 tick 开始时的重置钩子，由加速引擎调用。 */
    public void resetTick() {
        this.used = 0;
    }

    /** 本 tick 额度是否已耗尽。 */
    public boolean isExhausted() {
        return limitPerTick >= 0 && used >= limitPerTick;
    }

    /** 是否为「不限预算」。 */
    public boolean isUnlimited() {
        return limitPerTick < 0;
    }
}
