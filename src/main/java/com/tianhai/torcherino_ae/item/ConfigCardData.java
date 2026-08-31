package com.tianhai.torcherino_ae.item;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

/**
 * 加速器配置卡绑定数据（Data Component 载荷）。
 * <p>
 * 1.21.1 的 ItemStack 使用 Data Component 体系存储物品数据，绑定信息以此组件的形式
 * 随物品移动/复制保留，并以 NBT 形式落盘（服务端权威）。
 * <ul>
 *   <li>{@code acceleratorPos}：绑定的加速器坐标（long，BlockPos.asLong 存储）；</li>
 *   <li>{@code devices}：绑定的设备标识列表，复用 {@code AE2GridSupport.deviceIdOf}
 *       生成的稳定标识。</li>
 * </ul>
 */
public record ConfigCardData(long acceleratorPos, List<String> devices) {

    // 未绑定任何加速器时的空数据（坐标 0 表示无绑定）。
    public static final ConfigCardData EMPTY = new ConfigCardData(0L, List.of());

    // NBT 持久化编解码（随物品存取）。
    public static final Codec<ConfigCardData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.LONG.fieldOf("bound_accelerator").forGetter(ConfigCardData::acceleratorPos),
            Codec.STRING.listOf().fieldOf("bound_devices").forGetter(ConfigCardData::devices)
    ).apply(instance, ConfigCardData::new));

    // 网络同步编解码（物品跨客户端同步时传输）：与 NBT 编解码字段一一对应。
    public static final StreamCodec<RegistryFriendlyByteBuf, ConfigCardData> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public ConfigCardData decode(RegistryFriendlyByteBuf buffer) {
            long acceleratorPos = buffer.readVarLong();
            int count = buffer.readVarInt();
            List<String> devices = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                devices.add(buffer.readUtf());
            }
            return new ConfigCardData(acceleratorPos, List.copyOf(devices));
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, ConfigCardData data) {
            buffer.writeVarLong(data.acceleratorPos());
            buffer.writeVarInt(data.devices().size());
            for (String deviceId : data.devices()) {
                buffer.writeUtf(deviceId);
            }
        }
    };

    /**
     * 以加速器坐标与设备集合构造绑定数据（写入时排序，保证持久化数据稳定不变）。
     */
    public static ConfigCardData of(long acceleratorPos, Set<String> devices) {
        return new ConfigCardData(acceleratorPos, devices.stream().sorted().toList());
    }
}
