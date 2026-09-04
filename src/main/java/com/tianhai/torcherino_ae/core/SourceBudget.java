package com.tianhai.torcherino_ae.core;

import com.tianhai.torcherino_ae.api.BudgetMeter;
import com.tianhai.torcherino_ae.config.RuntimeConfig;

/**
 * 单加速源的「每 tick 调用预算」持有器。
 * <p>
 * 集中把静态配置预算（{@code budget.tickCallsPerSource}，-1 不限）经 TPS 自适应节流
 * （{@link AdaptiveThrottle}）合成「当前生效预算」，并在生效值变化时重建计量器；
 * 加速器与加速火把各自持有本类一个实例，从而共享同一套「限额变化才重建、平时零分配」
 * 的语义（见各自 {@code budget()}）。
 * <p>
 * 计量器实例被缓存：仅当生效预算变化（配置改动或收紧/恢复切换）时才重建，平时每 tick 零分配。
 */
public final class SourceBudget {

    private BudgetMeter meter = BudgetMeter.UNLIMITED_METER;
    private int limitTicks = BudgetMeter.UNLIMITED;

    /** 当前生效的预算计量器（上限为静态配置经自适应节流调整后的值）。 */
    public BudgetMeter get() {
        int limit = AdaptiveThrottle.INSTANCE.adjust(RuntimeConfig.budgetTickCallsPerSource());
        if (limit != limitTicks) {
            limitTicks = limit;
            meter = new BudgetMeter(limit);
        }
        return meter;
    }
}
