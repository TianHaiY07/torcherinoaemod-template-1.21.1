package com.tianhai.torcherino_ae.api;

/**
 * 每 tick 调用预算：限制单个加速源在一个游戏 tick 内可以触发的
 * {@code IGridTickable.tickingRequest} 总次数。
 * <p>
 * 存在的理由：加速倍率按升级卡复合累乘（4 张 III 卡即 16384 倍），单台设备每 tick
 * 就要被调用上万次，一个源覆盖数十台设备时足以拖垮服务端。预算让管理员能把
 * 单源（乃至全服）的每 tick 调用总量封顶在安全水位。
 * <p>
 * <b>默认不限制</b>（{@link #UNLIMITED}），以完整保留现网数值行为；
 * P3 配置化后预算由加速源按配置项 {@code budget.tickCallsPerSource} 创建（默认 -1 不限），
 * 仅在管理员显式配置后生效。
 * <p>
 * 本类位于 {@code api} 包而非 {@code core}：它无任何外部依赖，且属于
 * {@link IAccelerationSource} 必须声明的契约的一部分，放进 {@code core}
 * 会让 {@code api} 反向依赖 {@code core}，破坏依赖铁律。
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
