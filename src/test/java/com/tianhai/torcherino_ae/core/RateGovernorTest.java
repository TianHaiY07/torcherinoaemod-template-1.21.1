package com.tianhai.torcherino_ae.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 源级加速耗时调控 {@link RateGovernor} 的纯逻辑单测。
 * <p>
 * 覆盖：EMA 平滑收敛、因子推进（无超限保持 / 超限即时下压 / 滞回带保持 / 回落逐 tick 回升并封顶）、
 * 实际倍率合成（无样本不干预 / 比例下压 / 上下界钳制）、以及整机 sample → cap 流程。
 * 不依赖 Minecraft 运行时；运行时阈值经 RuntimeConfig 读取，单测按配置默认值
 * （sourceMsLimit=15，emaAlpha=0.25，tightenRatio=1.0，relaxRatio=0.7）驱动。
 */
class RateGovernorTest {

    // ============================== EMA 平滑 ==============================

    @Test
    void 首个样本直接作为初值() {
        assertEquals(20.0, RateGovernor.nextEma(0, false, 20, 0.25), 1e-9);
    }

    @Test
    void EMA随时间常数向样本值收敛() {
        double ema = 0;
        boolean has = false;
        for (int i = 0; i < 100; i++) {
            ema = RateGovernor.nextEma(ema, has, 10, 0.25);
            has = true;
        }
        assertEquals(10, ema, 1e-6);
    }

    // ============================== 因子推进 ==============================

    @Test
    void 耗时在阈值内时保持不干预() {
        // 未到下压阈值（5×1.0），也不低于放松线（5×0.7=3.5）之下的仅样本；因子保持 1。
        assertEquals(1.0, RateGovernor.nextFactor(4.0, 1.0, 5.0, 1.0, 0.7, 0.1), 1e-9);
        // 低于放松线：回升（此处已是 1，封顶）。
        assertEquals(1.0, RateGovernor.nextFactor(3.0, 1.0, 5.0, 1.0, 0.7, 0.1), 1e-9);
    }

    @Test
    void 超限时按比例即时下压() {
        // 耗时 EMA 恰为 5ms（= 阈值），比例 = 5/5 = 1，压到 min(factor, 1) → 仍为 1（临界）。
        assertEquals(1.0, RateGovernor.nextFactor(5.0, 1.0, 5.0, 1.0, 0.7, 0.1), 1e-9);
        // 耗时 10ms（2× 阈值）：比例 = 5/10 = 0.5，因子压到 0.5。
        assertEquals(0.5, RateGovernor.nextFactor(10.0, 1.0, 5.0, 1.0, 0.7, 0.1), 1e-9);
        // 本机已压到 0.2，更超限（20ms，比例 0.25）再压到 min(0.2, 0.25) = 0.2（不回升，只更紧）。
        assertEquals(0.2, RateGovernor.nextFactor(20.0, 0.2, 5.0, 1.0, 0.7, 0.1), 1e-9);
    }

    @Test
    void 耗时在滞回带时保持当前因子() {
        // 已压到 0.5，耗时 4ms（在放松线 3.5 与下压线 5 之间）→ 保持 0.5，防抖。
        assertEquals(0.5, RateGovernor.nextFactor(4.0, 0.5, 5.0, 1.0, 0.7, 0.1), 1e-9);
    }

    @Test
    void 回落到放松线以下时逐tick回升() {
        // 耗时 3ms（< 3.5）且已压到 0.5：每次 sample 回升 0.1。
        assertEquals(0.6, RateGovernor.nextFactor(3.0, 0.5, 5.0, 1.0, 0.7, 0.1), 1e-9);
        assertEquals(0.7, RateGovernor.nextFactor(3.0, 0.6, 5.0, 1.0, 0.7, 0.1), 1e-9);
        // 封顶：回升不超过 1。
        assertEquals(1.0, RateGovernor.nextFactor(3.0, 0.95, 5.0, 1.0, 0.7, 0.1), 1e-9);
    }

    // ============================== 实际倍率合成 ==============================

    @Test
    void 无样本时不干预返回设定倍率() {
        RateGovernor governor = new RateGovernor();
        assertEquals(8, governor.cap(8));
        assertEquals(64, governor.cap(64));
    }

    @Test
    void 按下压因子合成实际倍率并钳制上下界() {
        // base=526, factor=0.5 → 263。
        assertEquals(263, RateGovernor.effectiveCap(526, 0.5));
        // factor=0.2 → 545×… 取整。
        assertEquals(105, RateGovernor.effectiveCap(526, 0.2));
        // 下界钳到 1：factor 很小也不得 < 1（倍增率至少为 1，即不额外加速）。
        assertEquals(1, RateGovernor.effectiveCap(526, 0.001));
        // 上界钳到 base：factor=1 用足设定倍率。
        assertEquals(526, RateGovernor.effectiveCap(526, 1.0));
        // base 本身为 1（无可压）：恒为 1。
        assertEquals(1, RateGovernor.effectiveCap(1, 0.5));
    }

    // ============================== 整机流程（按配置默认阈值） ==============================

    @Test
    void 高耗时下压实际倍率负载回落后逐tick回升() {
        RateGovernor governor = new RateGovernor();
        // 无样本：不干预，用足设定倍率。
        assertFalse(governor.isCapped());
        assertEquals(526, governor.cap(526));

        // 本机持续 30ms（2× 阈值 15ms）：EMA 收敛后因子压到 0.5，实际倍率降到设定的一半。
        for (int i = 0; i < 30; i++) {
            governor.sample(30);
        }
        assertTrue(governor.isCapped());
        assertEquals(263, governor.cap(526));

        // 回落到 5ms（低于放松线 10.5ms）并持续足够长：EMA 先降过放松线，因子再逐 tick 回升回 1。
        for (int i = 0; i < 60; i++) {
            governor.sample(5);
        }
        assertFalse(governor.isCapped());
        assertEquals(1.0, governor.factor(), 1e-9);
        assertEquals(526, governor.cap(526));
    }

    @Test
    void 持续极重耗时把实际倍率压到很低() {
        RateGovernor governor = new RateGovernor();
        // 持续 60ms（4× 阈值）：因子按 15/60 = 0.25 下压，实际倍率约 132（设定倍率的 ~1/4，
        // 把本源耗时钳在配置上限内，而非一刀切到 1）。
        for (int i = 0; i < 40; i++) {
            governor.sample(60);
        }
        assertTrue(governor.isCapped());
        assertEquals(132, governor.cap(526));
    }
}
