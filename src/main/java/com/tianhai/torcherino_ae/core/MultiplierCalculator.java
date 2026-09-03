package com.tianhai.torcherino_ae.core;

import com.tianhai.torcherino_ae.config.ConfigDefaults;

/**
 * 加速倍率计算。
 * <p>
 * 公式与重构前完全一致：{@code 基础倍率 × 系数I^I卡数 × 系数II^II卡数 × 系数III^III卡数}，
 * 默认 {@code 4 × 2^I × 4^II × 8^III}。结果钳制到 {@code int} 上限防止溢出。
 * <p>
 * 本类不依赖 Minecraft 运行时，可直接单测；也刻意不依赖升级卡物品本身
 * （保持 {@code core} 层不反向依赖 {@code item}），调用方负责把库存换算成各档卡片数量。
 * <p>
 * P3 配置化后，运行时生效的基数与系数由调用方经 {@code RuntimeConfig} 读取并传入
 * 带参 {@link #compute(int, int, int, int, int, int, int)}；以下 {@code DEFAULT_*} 常量
 * 收口到 {@link ConfigDefaults}（单一事实来源），仅用于默认便捷重载与单测基准。
 */
public final class MultiplierCalculator {

    /** 默认基础倍率：未安装任何升级卡时的最高加速倍数（配置默认值）。 */
    public static final int DEFAULT_BASE = ConfigDefaults.ACCEL_BASE_MULTIPLIER;

    /** 默认 I 型升级卡倍增系数（配置默认值）。 */
    public static final int DEFAULT_FACTOR_I = ConfigDefaults.ACCEL_CARD_FACTORS.get(0);

    /** 默认 II 型升级卡倍增系数（配置默认值）。 */
    public static final int DEFAULT_FACTOR_II = ConfigDefaults.ACCEL_CARD_FACTORS.get(1);

    /** 默认 III 型升级卡倍增系数（配置默认值）。 */
    public static final int DEFAULT_FACTOR_III = ConfigDefaults.ACCEL_CARD_FACTORS.get(2);

    private MultiplierCalculator() {
    }

    /**
     * 按默认基础倍率与默认系数计算复合累乘后的最高加速倍数。
     */
    public static int compute(int cardI, int cardII, int cardIII) {
        return compute(DEFAULT_BASE, DEFAULT_FACTOR_I, DEFAULT_FACTOR_II, DEFAULT_FACTOR_III,
                cardI, cardII, cardIII);
    }

    /**
     * 按给定基础倍率与三种卡片的倍增系数计算最高加速倍数。
     * <p>
     * 每种卡片按其系数作幂次放大（未安装时为 1，即不放大），最后统一钳制到 int 上限。
     * 所有输入均非负，long 中间结果只可能因正溢出变成负值；一旦溢出直接钳到 int 上限，
     * 避免 {@code Math.min(负数, int 上限)} 返回负数的缺陷。
     */
    public static int compute(int base, int factorI, int factorII, int factorIII,
            int cardI, int cardII, int cardIII) {
        long multiplier = base;
        multiplier *= power(factorI, cardI);
        multiplier *= power(factorII, cardII);
        multiplier *= power(factorIII, cardIII);
        if (multiplier < 0) {
            // long 溢出（仅可能由正因子累乘导致）：结果视为「超出可表示范围」。
            return Integer.MAX_VALUE;
        }
        return (int) Math.min(multiplier, Integer.MAX_VALUE);
    }

    /**
     * 计算 {@code base^count}；count 为 0 时返回 1（未安装该卡片）。
     */
    private static long power(int base, int count) {
        long result = 1;
        for (int i = 0; i < count; i++) {
            result *= base;
        }
        return result;
    }
}
