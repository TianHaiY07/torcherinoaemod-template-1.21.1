package com.tianhai.torcherino_ae.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * 加速倍率公式的纯逻辑单测。
 * <p>
 * 覆盖「同档边际收益递减」模型的各档组合：零卡基线、单卡全价、异档混插与旧版一致
 * （各插一张 = 4 × 2 × 4 × 8 = 256）、同档堆叠被压平（默认满配 4 张 III 约 526 倍而非 16384）、
 * 保留比 1.0 精确还原旧指数累乘，以及带参重载与 int 溢出钳制。
 * 本类不依赖 Minecraft 运行时，可直接在 JVM 中执行。
 */
class MultiplierCalculatorTest {

    @Test
    void 无卡时返回默认基础倍率() {
        // 未安装任何升级卡：只放大基础倍率（默认 4）。
        assertEquals(MultiplierCalculator.DEFAULT_BASE, MultiplierCalculator.compute(0, 0, 0));
    }

    @Test
    void 单张I型卡按默认标称系数全价放大() {
        // 各档第一张按标称系数全价生效：4 × 2 = 8。
        assertEquals(MultiplierCalculator.DEFAULT_BASE * 2, MultiplierCalculator.compute(1, 0, 0));
    }

    @Test
    void 单张II型卡按默认标称系数全价放大() {
        // 4 × 4 = 16。
        assertEquals(MultiplierCalculator.DEFAULT_BASE * 4, MultiplierCalculator.compute(0, 1, 0));
    }

    @Test
    void 单张III型卡按默认标称系数全价放大() {
        // 4 × 8 = 32。
        assertEquals(MultiplierCalculator.DEFAULT_BASE * 8, MultiplierCalculator.compute(0, 0, 1));
    }

    @Test
    void 异档各一张与旧版一致() {
        // 档间互不影响、各档第一张全价：4 × 2 × 4 × 8 = 256，与旧的纯指数累乘一致。
        assertEquals(256, MultiplierCalculator.compute(1, 1, 1));
    }

    @Test
    void 默认保留比下满配四张III型卡被压平() {
        // 默认保留比 0.45：4 × 8 × 4.15 × 2.4175 × 1.637875 ≈ 525.83 → 526。
        // 旧指数累乘为 16384，递减模型把它压到约 2^9 量级。
        assertEquals(526, MultiplierCalculator.compute(0, 0, 4));
    }

    @Test
    void 同档边际增益逐张收敛() {
        // 边际相对增益逐张下降：×8 → ×4.15 → ×2.42 → ×1.64，故第 3 张起几乎不再膨胀。
        int one = MultiplierCalculator.compute(0, 0, 1); // 32
        int two = MultiplierCalculator.compute(0, 0, 2); // ≈133
        int three = MultiplierCalculator.compute(0, 0, 3); // ≈321
        assertEquals(32, one);
        assertEquals(133, two);
        assertEquals(321, three);
    }

    @Test
    void 保留比1时精确还原旧指数累乘() {
        // retention = 1.0：每张按同系数放大，等价于 4 × 8^4 = 16384（旧行为回归基准）。
        int legacy = MultiplierCalculator.compute(4, 2, 4, 8, 1.0, 0, 0, 4);
        assertEquals(16384, legacy);
    }

    @Test
    void 带参重载按给定系数与保留比计算() {
        // base=10，系数 I/II/III = 2/3/5，保留比 1.0：10 × 3^2 = 90（纯指数语义）。
        assertEquals(90, MultiplierCalculator.compute(10, 2, 3, 5, 1.0, 0, 2, 0));
    }

    @Test
    void 默认保留比下带参重载收益低于纯指数() {
        // base=10，II 系数 3 插 2 张，默认保留比 0.45：10 × 3 × 1.9 = 57 < 90（纯指数）。
        assertEquals(57, MultiplierCalculator.compute(10, 2, 3, 5, 0, 2, 0));
    }

    @Test
    void 超int上限时钳制到IntegerMAX_VALUE() {
        // 1_000_000 × 1_000^2 = 1e12，远超 int 上限，必须钳制而非溢出为负数。
        assertEquals(Integer.MAX_VALUE,
                MultiplierCalculator.compute(1_000_000, 1_000, 1, 1, 1.0, 2, 0, 0));
    }

    @Test
    void 巨大基数也不返回负数() {
        // 系数均为 100_000、保留比 1.0 时幂次增长极快，double 乘积远超 int 上限，
        // 取整截断前必须先钳制，保证结果不为负（防溢出回归）。
        int result = MultiplierCalculator.compute(100_000, 100_000, 100_000, 100_000, 1.0, 3, 3, 3);
        assertEquals(Integer.MAX_VALUE, result);
    }
}
