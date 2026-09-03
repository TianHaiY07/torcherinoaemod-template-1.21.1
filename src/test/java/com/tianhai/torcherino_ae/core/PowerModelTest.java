package com.tianhai.torcherino_ae.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * 加速器能耗模型的纯逻辑单测。
 * <p>
 * 覆盖默认线性公式 {@code 1.0 + 卡数 × 0.5 + 设备数 × 0.5}、零卡零设备边界
 * 与带参重载（调用方传入实际系数）。不依赖 Minecraft 运行时。
 */
class PowerModelTest {

    /** 浮点比较容差。 */
    private static final double DELTA = 1e-9;

    @Test
    void 零卡零设备时只付基础能耗() {
        assertEquals(PowerModel.DEFAULT_BASE_POWER, PowerModel.requiredPerTick(0, 0), DELTA);
    }

    @Test
    void 默认系数线性叠加() {
        // 2 张升级卡 + 3 台设备：1.0 + 2×0.5 + 3×0.5 = 3.5。
        assertEquals(3.5, PowerModel.requiredPerTick(2, 3), DELTA);
    }

    @Test
    void 带参重载按给定系数计算() {
        // base=0.5，每卡 +0.25，每设备 +2.0：0.5 + 2×0.25 + 1×2.0 = 3.0。
        assertEquals(3.0, PowerModel.requiredPerTick(0.5, 0.25, 2.0, 2, 1), DELTA);
    }

    @Test
    void 能耗与实际倍率脱钩() {
        // 设计要点回归：能耗只与「台数」挂钩，与倍率高低无关。
        // 两台被加速设备无论倍率高低都只按台数计费：1.0 + 0×0.5 + 2×0.5 = 2.0。
        assertEquals(2.0, PowerModel.requiredPerTick(0, 2), DELTA);
    }
}
