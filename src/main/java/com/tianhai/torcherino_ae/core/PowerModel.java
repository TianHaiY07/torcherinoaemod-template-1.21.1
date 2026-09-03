package com.tianhai.torcherino_ae.core;

import com.tianhai.torcherino_ae.config.ConfigDefaults;

/**
 * 加速器能耗模型。
 * <p>
 * 公式与重构前完全一致：{@code 基础值 + 升级卡数 × 每张卡增量 + 被加速设备数 × 每台增量}，
 * 默认为 {@code 1.0 + 卡数 × 0.5 + 设备数 × 0.5}（单位 AE/t）。
 * <p>
 * 注意：能耗与实际加速倍率是<b>脱钩</b>的（加速 16384 倍与加速 2 倍耗电相同），
 * 这是既有设计，本次重构原样保留；若要改为随倍率增长，应在此处统一修改，
 * 而不是散落到调用方。
 * <p>
 * 本类不依赖 Minecraft 运行时，可直接单测。P3 配置化后运行时系数由调用方经
 * {@code RuntimeConfig} 读取并传入带参 {@link #requiredPerTick(double, double, double, int, int)}；
 * 以下 {@code DEFAULT_*} 常量收口到 {@link ConfigDefaults}（单一事实来源）。
 */
public final class PowerModel {

    /** 默认每 tick 基础能耗（配置默认值）。 */
    public static final double DEFAULT_BASE_POWER = ConfigDefaults.POWER_PER_TICK;

    /** 默认每张升级卡额外增加的能耗（配置默认值）。 */
    public static final double DEFAULT_PER_UPGRADE_CARD = ConfigDefaults.POWER_PER_UPGRADE_CARD;

    /** 默认每台被加速设备额外增加的能耗（配置默认值）。 */
    public static final double DEFAULT_PER_DEVICE = ConfigDefaults.POWER_PER_ACCELERATED_DEVICE;

    private PowerModel() {
    }

    /**
     * 按默认系数计算每 tick 所需能量。
     */
    public static double requiredPerTick(int upgradeCards, int acceleratedDevices) {
        return requiredPerTick(DEFAULT_BASE_POWER, DEFAULT_PER_UPGRADE_CARD, DEFAULT_PER_DEVICE,
                upgradeCards, acceleratedDevices);
    }

    /**
     * 按给定系数计算每 tick 所需能量。
     */
    public static double requiredPerTick(double base, double perUpgradeCard, double perDevice,
            int upgradeCards, int acceleratedDevices) {
        return base + upgradeCards * perUpgradeCard + acceleratedDevices * perDevice;
    }
}
