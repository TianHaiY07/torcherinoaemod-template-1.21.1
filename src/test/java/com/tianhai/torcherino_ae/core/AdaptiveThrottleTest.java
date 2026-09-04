package com.tianhai.torcherino_ae.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * TPS 自适应节流 {@link AdaptiveThrottle} 的纯逻辑单测。
 * <p>
 * 覆盖：EMA 平滑收敛、分级档位推进（进入/加深/滞回保持/逐档恢复/最深封顶）、
 * 生效预算合成（不限/静态顶/各档位预算）、以及整机 sample → level → adjust 流程。
 * 不依赖 Minecraft 运行时；运行时阈值经 RuntimeConfig 读取，单测按配置默认值
 * （enabled=true，floor=256，tighten=45ms，relax=35ms）驱动。
 */
class AdaptiveThrottleTest {

    // ============================== EMA 平滑 ==============================

    @Test
    void 首个样本直接作为初值() {
        assertEquals(20.0, AdaptiveThrottle.nextEma(0, false, 20, 0.25), 1e-9);
    }

    @Test
    void EMA随时间常数向样本值收敛() {
        double ema = 0;
        boolean has = false;
        for (int i = 0; i < 100; i++) {
            ema = AdaptiveThrottle.nextEma(ema, has, 10, 0.25);
            has = true;
        }
        // 100 次加权后应已非常接近恒定样本 10（0.75^100 已可忽略）。
        assertEquals(10, ema, 1e-6);
    }

    // ============================== 档位状态推进 ==============================

    @Test
    void 未收紧时达到阈值才进入第一档() {
        assertEquals(0, AdaptiveThrottle.advanceLevel(35, 0, 45, 35));
        assertEquals(0, AdaptiveThrottle.advanceLevel(44, 0, 45, 35));
        assertEquals(1, AdaptiveThrottle.advanceLevel(45, 0, 45, 35));
        assertEquals(1, AdaptiveThrottle.advanceLevel(60, 0, 45, 35));
    }

    @Test
    void 超时持续则逐档加深() {
        // 已收紧且耗时仍在进入阈值之上：每 tick 加深一档。
        assertEquals(2, AdaptiveThrottle.advanceLevel(46, 1, 45, 35));
        assertEquals(3, AdaptiveThrottle.advanceLevel(46, 2, 45, 35));
        // 最深封顶：不越过 MAX_LEVEL，避免档位无限增长。
        assertEquals(10, AdaptiveThrottle.advanceLevel(46, 10, 45, 35));
    }

    @Test
    void 耗时在滞回带时保持当前档位() {
        // 已收紧但耗时仍高于恢复阈值：保持档位，避免抖动。
        assertEquals(2, AdaptiveThrottle.advanceLevel(40, 2, 45, 35));
        assertEquals(3, AdaptiveThrottle.advanceLevel(36, 3, 45, 35));
    }

    @Test
    void 回落到恢复阈值以下时逐档放松() {
        // 每 tick 最多放松一档，避免临界负载在全速/停转间振荡。
        assertEquals(1, AdaptiveThrottle.advanceLevel(34, 2, 45, 35));
        assertEquals(0, AdaptiveThrottle.advanceLevel(34, 1, 45, 35));
        // 耗时远低于恢复线同样只放松一档：从第 3 档回到 0 需要连续多个 tick。
        assertEquals(2, AdaptiveThrottle.advanceLevel(10, 3, 45, 35));
    }

    // ============================== 生效预算合成 ==============================

    @Test
    void 未启用或未收紧时原样返回配置预算() {
        assertEquals(-1, AdaptiveThrottle.effectiveLimit(-1, false, 3, 256));
        assertEquals(5000, AdaptiveThrottle.effectiveLimit(5000, false, 3, 256));
        assertEquals(-1, AdaptiveThrottle.effectiveLimit(-1, true, 0, 256));
        assertEquals(5000, AdaptiveThrottle.effectiveLimit(5000, true, 0, 256));
    }

    @Test
    void 收紧时不限预算被压到对应档位() {
        // 第一档 = 地板值本身；之后每深一档减半，最终收敛到 1。
        assertEquals(256, AdaptiveThrottle.effectiveLimit(-1, true, 1, 256));
        assertEquals(128, AdaptiveThrottle.effectiveLimit(-1, true, 2, 256));
        assertEquals(32, AdaptiveThrottle.effectiveLimit(-1, true, 4, 256));
        assertEquals(1, AdaptiveThrottle.effectiveLimit(-1, true, 10, 256));
        // 地板为 1 时任何档位都是 1（最紧即完全停止额外加速）。
        assertEquals(1, AdaptiveThrottle.effectiveLimit(-1, true, 1, 1));
        assertEquals(1, AdaptiveThrottle.effectiveLimit(-1, true, 5, 1));
    }

    @Test
    void 收紧时取静态预算与档位预算的较小值() {
        assertEquals(256, AdaptiveThrottle.effectiveLimit(1000, true, 1, 256));
        assertEquals(128, AdaptiveThrottle.effectiveLimit(1000, true, 2, 256));
        // 静态预算本身低于当前档位：保持静态（玩家显式设得更严，不因节流放宽）。
        assertEquals(100, AdaptiveThrottle.effectiveLimit(100, true, 1, 256));
    }

    // ============================== 整机流程（按配置默认阈值） ==============================

    @Test
    void 高负载逐档收紧负载回落后逐档放开() {
        AdaptiveThrottle throttle = new AdaptiveThrottle();
        // 无样本：未收紧，不限预算原样返回。
        assertFalse(throttle.isThrottled());
        assertEquals(0, throttle.throttleLevel());
        assertEquals(-1, throttle.adjust(-1));

        // 单 tick 持续 60ms（远超 20 TPS 硬限）→ EMA 越过 45ms 收紧阈值并逐 tick 加深档位。
        for (int i = 0; i < 5; i++) {
            throttle.sample(60);
        }
        assertTrue(throttle.isThrottled());
        // 第 5 档预算 = 256 >> 4 = 16，远低于默认地板 256——说明削峰是逐级加深的。
        assertEquals(5, throttle.throttleLevel());
        assertEquals(16, throttle.adjust(-1));

        // 负载回落到 20ms 并持续足够长：EMA 先降过 35ms 恢复线，档位再逐 tick 放松回 0。
        for (int i = 0; i < 40; i++) {
            throttle.sample(20);
        }
        assertFalse(throttle.isThrottled());
        assertEquals(0, throttle.throttleLevel());
        assertEquals(-1, throttle.adjust(-1));
    }

    @Test
    void 极端持续超时预算被压到最低1次() {
        AdaptiveThrottle throttle = new AdaptiveThrottle();
        // 持续极重负载：档位逐 tick 加深直至封顶，预算收敛到 1 次/tick（近乎停加速保 TPS）。
        for (int i = 0; i < 20; i++) {
            throttle.sample(80);
        }
        assertTrue(throttle.isThrottled());
        assertEquals(1, throttle.adjust(-1));
        // 深度收紧期间静态配置的 -1 与固定预算都被压到同一最低水位。
        assertEquals(1, throttle.adjust(5000));
    }
}
