package com.tianhai.torcherino_ae.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * 加速倍率公式的纯逻辑单测。
 * <p>
 * 覆盖默认公式 {@code 4 × 2^I × 4^II × 8^III} 的各档组合、零卡基线、
 * 带参重载（调用方传入实际系数）与 int 溢出钳制。
 * 本类不依赖 Minecraft 运行时，可直接在 JVM 中执行。
 */
class MultiplierCalculatorTest {

    @Test
    void 无卡时返回默认基础倍率() {
        // 未安装任何升级卡：只放大基础倍率（默认 4）。
        assertEquals(MultiplierCalculator.DEFAULT_BASE, MultiplierCalculator.compute(0, 0, 0));
    }

    @Test
    void 单张I型卡按默认系数放大() {
        // 4 × 2^1 = 8。
        assertEquals(MultiplierCalculator.DEFAULT_BASE * 2, MultiplierCalculator.compute(1, 0, 0));
    }

    @Test
    void 单张II型卡按默认系数放大() {
        // 4 × 4^1 = 16。
        assertEquals(MultiplierCalculator.DEFAULT_BASE * 4, MultiplierCalculator.compute(0, 1, 0));
    }

    @Test
    void 单张III型卡按默认系数放大() {
        // 4 × 8^1 = 32。
        assertEquals(MultiplierCalculator.DEFAULT_BASE * 8, MultiplierCalculator.compute(0, 0, 1));
    }

    @Test
    void 多种卡复合累乘() {
        // 各一张：4 × 2 × 4 × 8 = 256。
        assertEquals(256, MultiplierCalculator.compute(1, 1, 1));
    }

    @Test
    void 四张III型卡满配达到最高倍率() {
        // 4 × 8^4 = 16384（4 张 III 卡全插的经典满配值）。
        assertEquals(16384, MultiplierCalculator.compute(0, 0, 4));
    }

    @Test
    void 带参重载按给定系数计算() {
        // base=10，系数 I/II/III = 2/3/5：10 × 3^2 = 90。
        assertEquals(90, MultiplierCalculator.compute(10, 2, 3, 5, 0, 2, 0));
    }

    @Test
    void 超int上限时钳制到IntegerMAX_VALUE() {
        // 1_000_000 × 1_000^2 = 1e12，远超 int 上限，必须钳制而非溢出为负数。
        assertEquals(Integer.MAX_VALUE, MultiplierCalculator.compute(1_000_000, 1_000, 1, 1, 2, 0, 0));
    }

    @Test
    void 巨大基数也不返回负数() {
        // 系数均为 100_000 时幂次增长极快；中间量经 long 累积后即便发生高位截断，
        // 最终钳制也能保证结果不为负（防溢出回归）。
        int result = MultiplierCalculator.compute(100_000, 100_000, 100_000, 100_000, 3, 3, 3);
        assertEquals(Integer.MAX_VALUE, result);
    }
}
