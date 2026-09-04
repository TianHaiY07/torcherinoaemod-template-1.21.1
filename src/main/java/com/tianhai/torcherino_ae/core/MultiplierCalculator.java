package com.tianhai.torcherino_ae.core;

import com.tianhai.torcherino_ae.config.ConfigDefaults;

/**
 * 加速倍率计算：基础倍率按升级卡库存放大，同档重复堆叠采用「边际收益递减」以抑制指数爆炸。
 * <p>
 * 三种升级卡 I/II/III 各带标称倍增系数（配置 {@code accelerator.cardMultipliers}，默认 2 / 4 / 8）。
 * 同一档的<b>第 1 张</b>按标称系数全价放大；之后每多插一张该档卡，其实际放大倍率按
 * {@code 下一张 = 1 + (上一张 - 1) × retention} 向 1 收敛（retention ∈ [0,1] 为「同档边际收益
 * 保留比」，配置 {@code accelerator.cardDiminishing}，默认 0.45）。
 * <p>
 * 由此单卡与异档混插的数值与旧版完全一致（各插一张即 {@code 基础 × I系数 × II系数 × III系数}），
 * 同档堆 4 张的收益被显著压平：默认满配 4 张 III 卡约 526 倍，而旧的纯指数累乘为 16384 倍。
 * retention 设为 1.0 时逐张按同系数放大，恰好退化为旧公式
 * {@code 基础 × I系数^I卡数 × II系数^II卡数 × III系数^III卡数}。结果四舍五入并钳制到
 * {@code int} 上限，防止 double 溢出在取整时被截断成负数。
 * <p>
 * 本类不依赖 Minecraft 运行时，可直接单测；也刻意不依赖升级卡物品本身，
 * 调用方负责把方块实体的升级卡库存换算成各档卡片数量后传入。
 * <p>
 * 运行时生效的基数、系数与保留比由调用方经 {@code RuntimeConfig} 读取并传入带参
 * {@link #compute(int, int, int, int, double, int, int, int)}；以下 {@code DEFAULT_*} 常量
 * 引用 {@link ConfigDefaults}，仅用于默认便捷重载与单测基准。
 */
public final class MultiplierCalculator {

    /** 默认基础倍率：未安装任何升级卡时的最高加速倍数（配置默认值）。 */
    public static final int DEFAULT_BASE = ConfigDefaults.ACCEL_BASE_MULTIPLIER;

    /** 默认 I 型升级卡标称倍增系数（配置默认值）。 */
    public static final int DEFAULT_FACTOR_I = ConfigDefaults.ACCEL_CARD_FACTORS.get(0);

    /** 默认 II 型升级卡标称倍增系数（配置默认值）。 */
    public static final int DEFAULT_FACTOR_II = ConfigDefaults.ACCEL_CARD_FACTORS.get(1);

    /** 默认 III 型升级卡标称倍增系数（配置默认值）。 */
    public static final int DEFAULT_FACTOR_III = ConfigDefaults.ACCEL_CARD_FACTORS.get(2);

    /** 默认同档边际收益保留比（配置默认值）。 */
    public static final double DEFAULT_DIMINISHING = ConfigDefaults.ACCEL_DIMINISHING_RETENTION;

    private MultiplierCalculator() {
    }

    /**
     * 按默认基础倍率、默认系数与默认保留比计算最高加速倍数。
     */
    public static int compute(int cardI, int cardII, int cardIII) {
        return compute(DEFAULT_BASE, DEFAULT_FACTOR_I, DEFAULT_FACTOR_II, DEFAULT_FACTOR_III,
                DEFAULT_DIMINISHING, cardI, cardII, cardIII);
    }

    /**
     * 按给定基础倍率、三种卡片标称系数与默认保留比计算最高加速倍数。
     */
    public static int compute(int base, int factorI, int factorII, int factorIII,
            int cardI, int cardII, int cardIII) {
        return compute(base, factorI, factorII, factorIII, DEFAULT_DIMINISHING, cardI, cardII, cardIII);
    }

    /**
     * 按给定基础倍率、三种卡片标称系数与同档边际收益保留比计算最高加速倍数。
     * <p>
     * 各档分别对卡片数量做「边际收益递减」叠加后相乘（档间互不影响，插槽顺序无关）。
     * retention = 1.0 时退化为逐张按标称系数幂次累乘；结果超 {@code int} 上限时直接钳制，
     * 避免取整时的 double→long 饱和再截断成负数。
     */
    public static int compute(int base, int factorI, int factorII, int factorIII, double retention,
            int cardI, int cardII, int cardIII) {
        double multiplier = base;
        multiplier *= diminishingSeries(factorI, cardI, retention);
        multiplier *= diminishingSeries(factorII, cardII, retention);
        multiplier *= diminishingSeries(factorIII, cardIII, retention);
        if (!(multiplier < Integer.MAX_VALUE)) {
            // 超上限：直接钳到 int 上限（double 不像 long 会溢出为负，取整截断反而危险）。
            return Integer.MAX_VALUE;
        }
        return Math.max(1, (int) Math.round(multiplier));
    }

    /**
     * 同一档的卡片按「边际收益递减」叠加出的复合增益。
     * <p>
     * 第 1 张按标称系数全价；第 k 张的实际倍率 = 1 + (第 k-1 张实际倍率 - 1) × retention，
     * 即逐张向 1 收敛。count 为 0 时返回 1（未安装该卡片）。
     */
    private static double diminishingSeries(int factor, int count, double retention) {
        double total = 1.0;
        double step = factor;
        for (int i = 0; i < count; i++) {
            total *= step;
            step = 1.0 + (step - 1.0) * retention;
        }
        return total;
    }
}
