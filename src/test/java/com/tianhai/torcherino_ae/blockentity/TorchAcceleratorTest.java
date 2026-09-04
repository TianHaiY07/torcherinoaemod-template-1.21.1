package com.tianhai.torcherino_ae.blockentity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 概率式随机 tick 加速的纯逻辑单测。
 * <p>
 * 覆盖 {@link TorchAccelerator#randomTickDenominator}（分子/分母的钳制与倍率关系）与
 * {@link TorchAccelerator#randomTickHit}（命中/未命中、随机 tick 关闭时不命中）。
 * 实际世界查询（randomTick / gamerule 读取）不在纯 JVM 单测范围。
 */
class TorchAcceleratorTest {

    /** 分母恒在 [1,4096]；倍率越高分母越小（触发越频繁）。 */
    @Test
    void 分母被钳制在合理区间且随倍率单调递减() {
        assertEquals(1024, TorchAccelerator.randomTickDenominator(1, 4));     // 4096/(1×4)
        assertEquals(256, TorchAccelerator.randomTickDenominator(4, 4));      // 4096/(4×4)
        assertEquals(1, TorchAccelerator.randomTickDenominator(4096, 4096));  // 极值：钳到下界 1
        assertEquals(4096, TorchAccelerator.randomTickDenominator(0, 0));     // 非正输入：钳到 1×1 → 分母 4096
        assertEquals(4096, TorchAccelerator.randomTickDenominator(1, 1));
    }

    /** 掷出值 < vanillaRandomTicks 才命中；随机 tick 关闭（=0）时永不命中。 */
    @Test
    void 命中判定遵循概率区间() {
        // 分母 1024、vanilla=3：掷出 0/1/2 → 命中，3..1023 → 未命中。
        assertTrue(TorchAccelerator.randomTickHit(1024, 3, 0));
        assertTrue(TorchAccelerator.randomTickHit(1024, 3, 2));
        assertFalse(TorchAccelerator.randomTickHit(1024, 3, 3));
        assertFalse(TorchAccelerator.randomTickHit(1024, 3, 1023));
        // 分母 1 且 vanilla>=1：掷出恒 0 → 必命中（倍率极高时的上界——单格单 tick 至多 1 次）。
        assertTrue(TorchAccelerator.randomTickHit(1, 1, 0));
        // 随机 tick 关闭（vanilla=0）→ 永不命中。
        assertFalse(TorchAccelerator.randomTickHit(1, 0, 0));
    }
}
