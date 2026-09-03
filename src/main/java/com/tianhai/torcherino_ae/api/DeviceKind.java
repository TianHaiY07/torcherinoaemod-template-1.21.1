package com.tianhai.torcherino_ae.api;

import com.mojang.serialization.Codec;

/**
 * 设备种类：决定 {@link DeviceId} 的标识构成方式与后续解析路径，
 * 使「这台设备属于哪一类」成为显式字段，而不是靠字符串前缀之类的约定。
 */
public enum DeviceKind {

    /** AE 机器方块实体：压印机、分子装配室、接口等。标识 = 维度 + 自身坐标。 */
    BLOCK_ENTITY,

    /** 线缆上的部件：接口部件、样板供应器等。标识 = 维度 + 所在线缆坐标 + 朝向。 */
    PART,

    /**
     * 合成 CPU 多块结构：不属于 {@code IGridTickable}，本身不能被直接加速，
     * 玩家选中它即开启「智能加速」。标识 = 维度 + 结构最小角坐标。
     */
    CRAFTING_CPU;

    /** NBT 编解码：以枚举名序列化。 */
    public static final Codec<DeviceKind> CODEC = Codec.STRING.xmap(DeviceKind::valueOf, DeviceKind::name);
}
