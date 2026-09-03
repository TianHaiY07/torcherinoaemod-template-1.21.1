package com.tianhai.torcherino_ae.api;

import com.mojang.serialization.Codec;

/**
 * 加速设置的来源。
 * <p>
 * 同一条设备登记可能由玩家手动配置与配置卡自动注入共同驱动，来源用于标记
 * 登记的归属：状态表（{@code core.TargetRegistry}）按来源撤销时，只清除
 * 对应来源的记录——取出配置卡仅撤销它注入的设备，不会误伤玩家在 GUI 中
 * 手动勾选的设备。
 */
public enum AccelSource {

    /** 玩家在 GUI 中手动勾选或调整倍率。 */
    PLAYER,

    /** 由加速器配置卡自动注入。 */
    CONFIG_CARD;

    /** NBT 编解码：以枚举名序列化。 */
    public static final Codec<AccelSource> CODEC = Codec.STRING.xmap(AccelSource::valueOf, AccelSource::name);
}
