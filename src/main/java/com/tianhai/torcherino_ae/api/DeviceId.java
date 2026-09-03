package com.tianhai.torcherino_ae.api;

import java.util.Optional;

import org.jetbrains.annotations.Nullable;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

/**
 * 设备身份值类型：全局唯一地标识一台「可被加速的 AE 设备」或一台「合成 CPU」。
 * <p>
 * 由「维度 / 坐标 / 朝向 / 种类」四个语义字段显式构成：
 * <ul>
 *   <li><b>维度</b>：区分不同维度，同名坐标在不同维度间天然隔离；</li>
 *   <li><b>朝向</b>：区分挂在同一线缆坐标上、不同朝向的多个部件；方块实体与 CPU 无朝向，为 {@code null}；</li>
 *   <li><b>种类</b>：区分方块实体 / 部件 / 合成 CPU 三种设备形态。</li>
 * </ul>
 * 提供三种编码形式，各处按需取用：
 * <ul>
 *   <li>{@link #CODEC}：结构化 NBT，用于方块实体存档与配置卡的 Data Component；</li>
 *   <li>{@link #write} / {@link #read}：网络包编解码，用于菜单同步设备列表；</li>
 *   <li>{@link #stableKey} / {@link #parse}：紧凑字符串，用于经 GSON 传输的客户端动作载荷
 *       （GSON 无法可靠反序列化 record，故载荷层仍用字符串，由服务端统一解析与校验）。</li>
 * </ul>
 */
public record DeviceId(
        ResourceKey<Level> dimension,
        BlockPos pos,
        @Nullable Direction side,
        DeviceKind kind) {

    /** NBT 编解码：{@code {dim, pos, side?, kind}}。 */
    public static final Codec<DeviceId> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            ResourceKey.codec(Registries.DIMENSION).fieldOf("dim").forGetter(DeviceId::dimension),
            BlockPos.CODEC.fieldOf("pos").forGetter(DeviceId::pos),
            Direction.CODEC.optionalFieldOf("side").forGetter(id -> Optional.ofNullable(id.side())),
            DeviceKind.CODEC.fieldOf("kind").forGetter(DeviceId::kind))
            .apply(instance, (dim, pos, side, kind) -> new DeviceId(dim, pos, side.orElse(null), kind)));

    /** 方块实体设备：维度 + 自身坐标。 */
    public static DeviceId ofBlock(ResourceKey<Level> dimension, BlockPos pos) {
        return new DeviceId(dimension, pos, null, DeviceKind.BLOCK_ENTITY);
    }

    /** 线缆部件：维度 + 所在线缆坐标 + 朝向。 */
    public static DeviceId ofPart(ResourceKey<Level> dimension, BlockPos cablePos, @Nullable Direction side) {
        return new DeviceId(dimension, cablePos, side, DeviceKind.PART);
    }

    /** 合成 CPU：维度 + 结构最小角坐标。 */
    public static DeviceId ofCpu(ResourceKey<Level> dimension, BlockPos boundsMin) {
        return new DeviceId(dimension, boundsMin, null, DeviceKind.CRAFTING_CPU);
    }

    /** 是否属于「合成 CPU」这一类设备。 */
    public boolean isCpu() {
        return kind == DeviceKind.CRAFTING_CPU;
    }

    /** 写入网络包。 */
    public void write(RegistryFriendlyByteBuf buf) {
        buf.writeResourceKey(dimension);
        buf.writeBlockPos(pos);
        buf.writeVarInt(side == null ? -1 : side.ordinal());
        buf.writeEnum(kind);
    }

    /** 从网络包读取。 */
    public static DeviceId read(RegistryFriendlyByteBuf buf) {
        ResourceKey<Level> dimension = buf.readResourceKey(Registries.DIMENSION);
        BlockPos pos = buf.readBlockPos();
        int sideIndex = buf.readVarInt();
        Direction side = sideIndex < 0 ? null : Direction.values()[sideIndex];
        return new DeviceId(dimension, pos, side, buf.readEnum(DeviceKind.class));
    }

    /**
     * 紧凑字符串形式：{@code 维度|坐标long|朝向下标(-1 表示无)|种类}。
     * <p>
     * 仅用于经 GSON 传输的客户端动作载荷；不要用它的子串去推导语义，
     * 需要结构信息时请使用 {@link #parse} 还原后读取字段。
     */
    public String stableKey() {
        return dimension.location() + "|" + pos.asLong() + "|" + (side == null ? -1 : side.ordinal()) + "|"
                + kind.name();
    }

    /**
     * 解析 {@link #stableKey} 生成的字符串；格式非法时返回 {@code null}（调用方应视为校验失败）。
     */
    @Nullable
    public static DeviceId parse(@Nullable String key) {
        if (key == null) {
            return null;
        }
        String[] parts = key.split("\\|");
        if (parts.length != 4) {
            return null;
        }
        try {
            ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(parts[0]));
            BlockPos pos = BlockPos.of(Long.parseLong(parts[1]));
            int sideIndex = Integer.parseInt(parts[2]);
            Direction side = sideIndex < 0 ? null : Direction.values()[sideIndex];
            return new DeviceId(dimension, pos, side, DeviceKind.valueOf(parts[3]));
        } catch (RuntimeException e) {
            // 客户端可伪造载荷：解析失败一律按非法处理，不抛异常打断服务端。
            return null;
        }
    }
}
