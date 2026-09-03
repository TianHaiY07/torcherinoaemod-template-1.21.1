package com.tianhai.torcherino_ae.api;

import com.mojang.serialization.Codec;

/**
 * 加速设置的来源。
 * <p>
 * 旧实现用三个集合分别维护「玩家勾选」「每台倍数」「配置卡注入」，三者会失去同步，
 * 导致服务器重启后取出配置卡不会撤销注入。新模型把来源内建进状态
 * （见 {@code core.TargetRegistry}），撤销时按来源精确过滤。
 */
public enum AccelSource {

    /** 玩家在 GUI 中手动勾选或调整倍率。 */
    PLAYER,

    /** 由加速器配置卡自动注入。 */
    CONFIG_CARD;

    /** NBT 编解码：以枚举名序列化。 */
    public static final Codec<AccelSource> CODEC = Codec.STRING.xmap(AccelSource::valueOf, AccelSource::name);
}
