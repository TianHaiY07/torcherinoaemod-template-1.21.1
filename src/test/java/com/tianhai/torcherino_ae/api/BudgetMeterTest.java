package com.tianhai.torcherino_ae.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 每 tick 调用预算 {@link BudgetMeter} 的纯逻辑单测（§10.1）。
 * <p>
 * 覆盖：预算封顶后按余量发放与早退、跨 tick 重置、{@code -1} 表示不限
 * （默认即不限，保证现网行为零变更）、零额度边界。不依赖 Minecraft 运行时。
 */
class BudgetMeterTest {

    @Test
    void 限额内按申请全额发放() {
        BudgetMeter meter = new BudgetMeter(10);
        assertEquals(10, meter.request(10));
        assertTrue(meter.isExhausted());
        assertEquals(0, meter.request(1));
    }

    @Test
    void 超额申请只发放余量且封顶后早退() {
        BudgetMeter meter = new BudgetMeter(5);
        assertEquals(3, meter.request(3));
        assertFalse(meter.isExhausted());
        // 余量 2：超额申请只批 2。
        assertEquals(2, meter.request(3));
        assertTrue(meter.isExhausted());
        // 已耗尽：后续申请一律 0（引擎据此早退）。
        assertEquals(0, meter.request(3));
    }

    @Test
    void 跨tick重置后额度恢复() {
        BudgetMeter meter = new BudgetMeter(2);
        assertEquals(2, meter.request(2));
        assertTrue(meter.isExhausted());

        meter.resetTick();
        assertFalse(meter.isExhausted());
        assertEquals(1, meter.request(1));
        assertFalse(meter.isExhausted());
        assertEquals(1, meter.request(1));
        assertTrue(meter.isExhausted());
    }

    @Test
    void 负数表示不限() {
        BudgetMeter meter = new BudgetMeter(BudgetMeter.UNLIMITED);
        assertTrue(meter.isUnlimited());
        assertFalse(meter.isExhausted());
        // 不限预算时按申请原样发放，永不封顶。
        assertEquals(1000, meter.request(1000));
        assertEquals(1000, meter.request(1000));
        assertFalse(meter.isExhausted());
    }

    @Test
    void 不限共享实例可直接复用() {
        assertSame(BudgetMeter.UNLIMITED_METER, BudgetMeter.UNLIMITED_METER);
        assertTrue(BudgetMeter.UNLIMITED_METER.isUnlimited());
        assertEquals(Integer.MAX_VALUE, BudgetMeter.UNLIMITED_METER.request(Integer.MAX_VALUE));
    }

    @Test
    void 零额度立即耗尽() {
        BudgetMeter meter = new BudgetMeter(0);
        assertTrue(meter.isExhausted());
        assertEquals(0, meter.request(5));
        // 每 tick 重置后仍为 0（永无额度，等价于「该源本 tick 不加速」）。
        meter.resetTick();
        assertTrue(meter.isExhausted());
    }

    @Test
    void 不限制实例的resetTick无副作用() {
        BudgetMeter meter = new BudgetMeter(BudgetMeter.UNLIMITED);
        meter.request(5);
        meter.resetTick();
        assertEquals(7, meter.request(7));
        assertFalse(meter.isExhausted());
    }
}
