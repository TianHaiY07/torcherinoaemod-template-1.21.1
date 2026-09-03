package com.tianhai.torcherino_ae.item;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.tianhai.torcherino_ae.api.DeviceId;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;

/**
 * 加速器配置卡绑定数据（Data Component 载荷 + 数据契约）。
 * <p>
 * 1.21.1 的 ItemStack 使用 Data Component 体系存储物品数据，绑定信息以此组件的形式
 * 随物品移动/复制保留，并以 NBT 形式落盘（服务端权威）。
 * <ul>
 *   <li>{@code accelerator}：绑定的加速器标识（{@link DeviceId}，含维度与坐标）；</li>
 *   <li>{@code devices}：绑定的设备标识列表，复用 {@code DeviceScanner.deviceIdOf}
 *       生成的稳定标识。</li>
 * </ul>
 * 绑定的加速器与设备都使用<b>含维度</b>的 {@link DeviceId} 存储，跨维度同坐标的方块
 * 不会被误认成同一台，配置卡不会跨维度误绑定。
 * <p>
 * 本类是配置卡数据的<b>纯数据契约</b>：Data Component 的全部读写静态方法集中于此。
 * 客户端渲染层（如配置卡高亮 pass）与服务端逻辑层都只依赖本数据类，不触碰物品类
 * {@link AcceleratorConfigCardItem}（后者仅剩「物品」语义：注册、tooltip），
 * 避免渲染/逻辑层被物品类上的交互方法反向牵制。
 */
public record ConfigCardData(DeviceId accelerator, List<DeviceId> devices) {

    // 单张卡片最多绑定的设备数量上限（防止复制粘贴/失控写入造成组件数据膨胀）。
    public static final int MAX_BOUND_DEVICES = 64;

    // 未绑定任何加速器时的空数据。
    public static final ConfigCardData EMPTY = new ConfigCardData(null, List.of());

    /** 设备列表排序器：按稳定字符串排序，保证持久化数据不因集合迭代顺序而抖动。 */
    private static final Comparator<DeviceId> DEVICE_ORDER = Comparator.comparing(DeviceId::stableKey);

    // NBT 持久化编解码（随物品存取）。加速器可缺省（未绑定状态）。
    public static final Codec<ConfigCardData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            DeviceId.CODEC.optionalFieldOf("bound_accelerator")
                    .forGetter(data -> Optional.ofNullable(data.accelerator())),
            DeviceId.CODEC.listOf().fieldOf("bound_devices").forGetter(ConfigCardData::devices))
            .apply(instance, (accelerator, devices) -> new ConfigCardData(accelerator.orElse(null), devices)));

    // 网络同步编解码（物品跨客户端同步时传输）。
    public static final StreamCodec<RegistryFriendlyByteBuf, ConfigCardData> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public ConfigCardData decode(RegistryFriendlyByteBuf buffer) {
            DeviceId accelerator = buffer.readBoolean() ? DeviceId.read(buffer) : null;
            int count = buffer.readVarInt();
            List<DeviceId> devices = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                devices.add(DeviceId.read(buffer));
            }
            return new ConfigCardData(accelerator, List.copyOf(devices));
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, ConfigCardData data) {
            buffer.writeBoolean(data.accelerator() != null);
            if (data.accelerator() != null) {
                data.accelerator().write(buffer);
            }
            buffer.writeVarInt(data.devices().size());
            for (DeviceId device : data.devices()) {
                device.write(buffer);
            }
        }
    };

    /**
     * 以加速器标识与设备集合构造绑定数据（写入时排序，保证持久化数据稳定不变）。
     */
    public static ConfigCardData of(DeviceId accelerator, Set<DeviceId> devices) {
        return new ConfigCardData(accelerator, devices.stream().sorted(DEVICE_ORDER).toList());
    }

    /** 设备集合排序后构造新实例（保持列表稳定）。 */
    public static ConfigCardData of(DeviceId accelerator, List<DeviceId> devices) {
        return new ConfigCardData(accelerator, devices.stream().sorted(DEVICE_ORDER).toList());
    }

    // ========================= 绑定数据读写（数据契约入口） =========================

    /**
     * 判断物品栈是否为加速器配置卡。
     */
    public static boolean isConfigCard(ItemStack stack) {
        return !stack.isEmpty() && stack.is(ModItems.ACCELERATOR_CONFIG_CARD.get());
    }

    /**
     * 读取卡上绑定的加速器标识。
     *
     * @return 未绑定时返回 {@code null}
     */
    @Nullable
    public static DeviceId getBoundAccelerator(ItemStack stack) {
        ConfigCardData data = stack.get(ModDataComponents.CONFIG_CARD_DATA);
        return data == null ? null : data.accelerator();
    }

    /**
     * 卡片是否绑定到指定加速器（标识含维度，跨维度同坐标不会被误判为同一台）。
     */
    public static boolean isBoundTo(ItemStack stack, DeviceId acceleratorId) {
        DeviceId bound = getBoundAccelerator(stack);
        return bound != null && bound.equals(acceleratorId);
    }

    /**
     * 读取卡上绑定的设备标识集合（只读视图）。
     */
    public static Set<DeviceId> getBoundDevices(ItemStack stack) {
        ConfigCardData data = stack.get(ModDataComponents.CONFIG_CARD_DATA);
        return data == null ? Set.of() : Set.copyOf(data.devices());
    }

    /**
     * 指定设备是否已在卡上绑定。
     */
    public static boolean isDeviceBound(ItemStack stack, DeviceId deviceId) {
        return getBoundDevices(stack).contains(deviceId);
    }

    /**
     * 绑定加速器：写入加速器标识并清空旧的设备列表。
     * <p>
     * 改绑到另一台加速器时，旧设备来自原加速器的网络，对新的绑定无意义，
     * 因此一律清空，避免「卡走则停」的残留误加速。
     */
    public static void bindAccelerator(ItemStack stack, DeviceId acceleratorId) {
        stack.set(ModDataComponents.CONFIG_CARD_DATA, ConfigCardData.of(acceleratorId, Set.of()));
    }

    /**
     * 切换加速器绑定（Shift+右键加速器的断言语义）：
     * 卡片已绑定该加速器 -> 取消绑定并同时清空全部设备绑定；
     * 否则绑定该加速器（原绑定与设备列表一并被替换）。
     *
     * @return {@code true}=绑定成功，{@code false}=已取消绑定
     */
    public static boolean bindOrUnbindAccelerator(ItemStack stack, DeviceId acceleratorId) {
        if (isBoundTo(stack, acceleratorId)) {
            unbindAccelerator(stack);
            return false;
        }
        bindAccelerator(stack, acceleratorId);
        return true;
    }

    /**
     * 取消加速器绑定，并同时清空卡上全部设备绑定。
     */
    public static void unbindAccelerator(ItemStack stack) {
        stack.remove(ModDataComponents.CONFIG_CARD_DATA);
    }

    /**
     * 切换设备绑定：已绑定 -> 取消；未绑定 -> 写入（达到上限时不再写入）。
     *
     * @return 切换完成后该设备是否处于已绑定状态
     */
    public static boolean toggleBoundDevice(ItemStack stack, DeviceId deviceId) {
        Set<DeviceId> devices = new HashSet<>(getBoundDevices(stack));
        if (devices.contains(deviceId)) {
            devices.remove(deviceId);
        } else if (devices.size() < MAX_BOUND_DEVICES) {
            devices.add(deviceId);
        }
        writeBoundDevices(stack, devices);
        return devices.contains(deviceId);
    }

    /**
     * 把设备标识集合写回卡片组件（保持稳定排序，便于 NBT 持久化不抖动）。
     */
    public static void writeBoundDevices(ItemStack stack, Set<DeviceId> devices) {
        ConfigCardData data = stack.get(ModDataComponents.CONFIG_CARD_DATA);
        DeviceId accelerator = data == null ? null : data.accelerator();
        stack.set(ModDataComponents.CONFIG_CARD_DATA, ConfigCardData.of(accelerator, devices));
    }
}
