package com.tianhai.torcherino_ae.item;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.tianhai.torcherino_ae.api.DeviceId;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * 加速器配置卡绑定数据（Data Component 载荷 + 数据契约）。
 * <p>
 * 1.21.1 的 ItemStack 使用 Data Component 体系存储物品数据，绑定信息以此组件的形式
 * 随物品移动/复制保留，并以 NBT 形式落盘（服务端权威）。
 * <ul>
 *   <li>{@code accelerator}：绑定的加速器标识（{@link DeviceId}，含维度与坐标）；</li>
 *   <li>{@code devices}：绑定的设备标识列表，复用 {@code DeviceScanner.deviceIdOf} / CPU
 *       组标识（{@code CraftingSupport.cpuDeviceId}）生成的稳定标识——合成 CPU 是多块组，
 *       整组只占一个标识（标记合并），重复绑定同组的其他块不会新增条目；</li>
 *   <li>{@code cpuBounds}：已绑定合成 CPU 组的<b>外包围盒几何</b>（仅记录最大角，
 *       最小角由该 CPU 标识的坐标承载），供手持卡片高亮时按 CPU 组外围画线框。
 *       与 {@code devices} 保持一致性：任何写操作都会同步裁剪掉失效条目。</li>
 * </ul>
 * 绑定的加速器与设备都使用<b>含维度</b>的 {@link DeviceId} 存储，跨维度同坐标的方块
 * 不会被误认成同一台，配置卡不会跨维度误绑定。
 * <p>
 * 本类是配置卡数据的<b>纯数据契约</b>：Data Component 的全部读写静态方法集中于此。
 * 客户端渲染层（如配置卡高亮 pass）与服务端逻辑层都只依赖本数据类，不触碰物品类
 * {@link AcceleratorConfigCardItem}（后者仅剩「物品」语义：注册、tooltip），
 * 避免渲染/逻辑层被物品类上的交互方法反向牵制。
 */
public record ConfigCardData(DeviceId accelerator, List<DeviceId> devices, List<CpuBounds> cpuBounds) {

    // 单张卡片最多绑定的设备数量上限（防止复制粘贴/失控写入造成组件数据膨胀）。
    public static final int MAX_BOUND_DEVICES = 64;

    /**
     * 设备列表排序器：按稳定字符串排序，保证持久化数据不因集合迭代顺序而抖动。
     * <p>
     * 注意：必须声明在 {@link #EMPTY} 之前——空数据实例的紧凑构造器在类静态初始化阶段
     * 就会使用本排序器，若声明在后则该字段仍为 null，构造器内 {@code sorted(...)} 会 NPE。
     */
    private static final Comparator<DeviceId> DEVICE_ORDER = Comparator.comparing(DeviceId::stableKey);

    // 未绑定任何加速器时的空数据。
    public static final ConfigCardData EMPTY = new ConfigCardData(null, List.of(), List.of());

    /**
     * 紧凑构造器：统一做「去重 + 稳定排序 + 设备/几何一致性」归一化，保证任何入口
     * （CODEC 解码、网络解码、各种写操作）产出的实例都满足不变量，持久化数据不抖动。
     */
    public ConfigCardData {
        List<DeviceId> sortedDevices = devices == null
                ? List.of()
                : devices.stream().sorted(DEVICE_ORDER).distinct().toList();
        Set<DeviceId> bound = new HashSet<>(sortedDevices);
        // 几何列表只保留「已绑定且确为合成 CPU」的条目，同一 CPU 去重（后者覆盖前者）。
        Map<DeviceId, CpuBounds> boxByCpu = new LinkedHashMap<>();
        if (cpuBounds != null) {
            for (CpuBounds bounds : cpuBounds) {
                if (bounds != null && bounds.cpu() != null && bounds.cpu().isCpu() && bound.contains(bounds.cpu())) {
                    boxByCpu.put(bounds.cpu(), bounds);
                }
            }
        }
        devices = sortedDevices;
        cpuBounds = boxByCpu.values().stream()
                .sorted(Comparator.comparing(bounds -> bounds.cpu().stableKey()))
                .toList();
    }

    /**
     * 合成 CPU 绑定组的外包围盒几何（随卡持久化，供整组高亮渲染）。
     * <p>
     * 合成 CPU 是多块结构，一组可能由多个方块连成；{@link DeviceId} 以组最小角坐标为标识，
     * 这里补充记录最大角坐标，两者合起来即整组的 AABB（角坐标均含该方块）。
     *
     * @param cpu 该 CPU 组的设备标识（{@code kind} 必须为 {@code CRAFTING_CPU}）
     * @param max CPU 组外包围盒最大角方块坐标
     */
    public record CpuBounds(DeviceId cpu, BlockPos max) {

        // NBT 持久化编解码：{cpu, max}。
        public static final Codec<CpuBounds> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                DeviceId.CODEC.fieldOf("cpu").forGetter(CpuBounds::cpu),
                BlockPos.CODEC.fieldOf("max").forGetter(CpuBounds::max))
                .apply(instance, CpuBounds::new));
    }

    // NBT 持久化编解码（随物品存取）。加速器可缺省（未绑定状态）；cpu_bounds 兼容旧卡数据缺省为空。
    public static final Codec<ConfigCardData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            DeviceId.CODEC.optionalFieldOf("bound_accelerator")
                    .forGetter(data -> Optional.ofNullable(data.accelerator())),
            DeviceId.CODEC.listOf().fieldOf("bound_devices").forGetter(ConfigCardData::devices),
            Codec.list(CpuBounds.CODEC).optionalFieldOf("cpu_bounds", List.of())
                    .forGetter(ConfigCardData::cpuBounds))
            .apply(instance, (accelerator, devices, bounds) -> new ConfigCardData(accelerator.orElse(null), devices,
                    bounds)));

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
            int cpuCount = buffer.readVarInt();
            List<CpuBounds> cpuBounds = new ArrayList<>(cpuCount);
            for (int i = 0; i < cpuCount; i++) {
                cpuBounds.add(new CpuBounds(DeviceId.read(buffer), buffer.readBlockPos()));
            }
            return new ConfigCardData(accelerator, devices, cpuBounds);
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
            buffer.writeVarInt(data.cpuBounds().size());
            for (CpuBounds bounds : data.cpuBounds()) {
                bounds.cpu().write(buffer);
                buffer.writeBlockPos(bounds.max());
            }
        }
    };

    /**
     * 以加速器标识与设备集合构造绑定数据（写入时排序，保证持久化数据稳定不变）。
     */
    public static ConfigCardData of(DeviceId accelerator, Set<DeviceId> devices) {
        return new ConfigCardData(accelerator, List.copyOf(devices), List.of());
    }

    /** 设备集合排序后构造新实例（保持列表稳定）。 */
    public static ConfigCardData of(DeviceId accelerator, List<DeviceId> devices) {
        return new ConfigCardData(accelerator, devices, List.of());
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
     * 读取卡上绑定的设备标识集合（只读视图；合成 CPU 组整组只占一个标识）。
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
     * 读取卡上已绑定的合成 CPU 组的外包围盒<b>最大角</b>坐标。
     *
     * @return 未绑定该 CPU 或缺少几何记录（旧数据）时返回 {@code null}
     */
    @Nullable
    public static BlockPos cpuBoundsMaxOf(ItemStack stack, DeviceId cpuId) {
        ConfigCardData data = stack.get(ModDataComponents.CONFIG_CARD_DATA);
        if (data == null || cpuId == null) {
            return null;
        }
        for (CpuBounds bounds : data.cpuBounds()) {
            if (bounds.cpu().equals(cpuId)) {
                return bounds.max();
            }
        }
        return null;
    }

    /**
     * 绑定加速器：写入加速器标识并清空旧的设备列表与 CPU 几何。
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
     * <p>
     * 仅用于普通设备（方块实体 / 线缆部件）；合成 CPU 请走
     * {@link #toggleBoundCpu(ItemStack, DeviceId, BlockPos)}，以便同时维护组外包围盒几何。
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
     * 切换合成 CPU 组绑定：已绑定 -> 取消（连同几何）；未绑定 -> 写入（达到上限时不再写入）。
     * <p>
     * CPU 组由多个方块连成：这里以<b>整组</b>为粒度绑定/取消，配合最大角几何记录，
     * 使「重复右键同组任意方块」都指向同一条目（标记合并）。
     *
     * @param cpuId    该 CPU 组的设备标识（{@code kind} 必须为 {@code CRAFTING_CPU}）
     * @param boundsMax CPU 组外包围盒最大角方块坐标（可空：极端情况下仅记录标识、不画整组框）
     * @return 切换完成后该 CPU 组是否处于已绑定状态
     */
    public static boolean toggleBoundCpu(ItemStack stack, DeviceId cpuId, @Nullable BlockPos boundsMax) {
        if (cpuId == null || !cpuId.isCpu()) {
            return isDeviceBound(stack, cpuId);
        }
        ConfigCardData data = stack.get(ModDataComponents.CONFIG_CARD_DATA);
        DeviceId accelerator = data == null ? null : data.accelerator();
        Set<DeviceId> devices = new HashSet<>(data == null ? Set.of() : data.devices());
        List<CpuBounds> cpuBounds = new ArrayList<>(data == null ? List.of() : data.cpuBounds());
        if (devices.contains(cpuId)) {
            devices.remove(cpuId);
            cpuBounds.removeIf(bounds -> bounds.cpu().equals(cpuId));
        } else if (devices.size() < MAX_BOUND_DEVICES) {
            devices.add(cpuId);
            if (boundsMax != null) {
                cpuBounds.removeIf(bounds -> bounds.cpu().equals(cpuId));
                cpuBounds.add(new CpuBounds(cpuId, boundsMax));
            }
        }
        // 构造器会再做一次归一化（裁剪设备已移除但几何残留的条目）。
        stack.set(ModDataComponents.CONFIG_CARD_DATA, new ConfigCardData(accelerator, List.copyOf(devices), cpuBounds));
        return devices.contains(cpuId);
    }

    /**
     * 把设备标识集合写回卡片组件（保持稳定排序，便于 NBT 持久化不抖动；
     * 同时裁剪已不在集合中的合成 CPU 几何记录）。
     */
    public static void writeBoundDevices(ItemStack stack, Set<DeviceId> devices) {
        ConfigCardData data = stack.get(ModDataComponents.CONFIG_CARD_DATA);
        DeviceId accelerator = data == null ? null : data.accelerator();
        List<CpuBounds> cpuBounds = data == null ? List.of() : data.cpuBounds();
        stack.set(ModDataComponents.CONFIG_CARD_DATA, new ConfigCardData(accelerator, List.copyOf(devices), cpuBounds));
    }

    // ========================= 合成 CPU 组生命期联动（失效删除 / 再成型换绑） =========================

    /**
     * 移除卡上绑定的合成 CPU 组条目（设备标识与对应外包围盒几何一并删除）。
     * <p>
     * AE2 的 CPU 组在任一成员块被拆除时会整体解散、由剩余块重新计算能否再成型：
     * 若不再成型（整组被拆光，或拆剩的形状已无效），由 {@code ConfigCardCleanup} 在拆除
     * 事件结算后调用本方法，把失效组的绑定从卡上删除，避免卡片永久残留指向已消失组的
     * 条目、以及在同坐标重建新组时被旧数据误注入。
     *
     * @return 是否真的有该 CPU 组条目被移除
     */
    public static boolean removeCpuBinding(ItemStack stack, DeviceId cpuId) {
        if (cpuId == null || !cpuId.isCpu()) {
            return false;
        }
        ConfigCardData data = stack.get(ModDataComponents.CONFIG_CARD_DATA);
        if (data == null) {
            return false;
        }
        ConfigCardData out = data.withoutCpu(cpuId);
        if (out.equals(data)) {
            return false;
        }
        stack.set(ModDataComponents.CONFIG_CARD_DATA, out);
        return true;
    }

    /**
     * 把卡上绑定的合成 CPU 组条目改写为指向「再成型后」的集群。
     * <p>
     * 集群对象在任一成员块被拆除时销毁、由剩余成员重新成型为新集群：若剩余成员仍能
     * 成型（部分破坏但组依旧有效），调用本方法把绑定标识（组最小角，几何移动时可能
     * 随之改变）与最大角几何整体替换——标识未变时等价于仅刷新几何。加速器注入、手持
     * 卡片高亮（pass 渲染按本几何画整组框）因此自动跟随组的真实几何。
     *
     * @param oldCpuId 当前绑定（拆除前）的 CPU 组标识
     * @param newCpuId 再成型后集群的标识（{@code kind} 必须为 {@code CRAFTING_CPU}）
     * @param newMax   再成型后集群的最大角方块坐标
     * @return 是否真的发生了改写
     */
    public static boolean replaceCpuBinding(ItemStack stack, DeviceId oldCpuId, DeviceId newCpuId, BlockPos newMax) {
        if (oldCpuId == null || !oldCpuId.isCpu() || newCpuId == null || !newCpuId.isCpu()) {
            return false;
        }
        ConfigCardData data = stack.get(ModDataComponents.CONFIG_CARD_DATA);
        if (data == null) {
            return false;
        }
        ConfigCardData out = data.withCpuReplaced(oldCpuId, newCpuId, newMax);
        if (out.equals(data)) {
            return false;
        }
        stack.set(ModDataComponents.CONFIG_CARD_DATA, out);
        return true;
    }

    /**
     * 移除卡上绑定于「方块已被移除」坐标的全部普通设备记录（BLOCK_ENTITY / PART）。
     * <p>
     * 设备方块被破坏 / 爆炸摧毁后由 {@code ConfigCardCleanup} 调用：同一坐标线缆上的
     * 部件绑定会随整条线缆被拆而一并失效，因此按「维度 + 方块坐标」整点清除、不区分朝向；
     * 合成 CPU 是整组一个标识的多块结构，拆除行为会先解散整组再由剩余块判定是否再成型，
     * 其失效语义与本方法不同——见 {@link #removeCpuBinding} / {@link #replaceCpuBinding}
     * （由同一定时器按组裁决后调用），本方法不触碰任何 CPU 条目。
     *
     * @return 是否有绑定被清除
     */
    public static boolean purgeRemovedBlockDevices(ItemStack stack,
            Map<ResourceKey<Level>, Set<BlockPos>> removedByDimension) {
        ConfigCardData data = stack.get(ModDataComponents.CONFIG_CARD_DATA);
        if (data == null || data.devices().isEmpty()) {
            return false;
        }
        ConfigCardData purged = data.withoutBlockDevicesAt(removedByDimension);
        if (purged.equals(data)) {
            return false;
        }
        stack.set(ModDataComponents.CONFIG_CARD_DATA, purged);
        return true;
    }

    /**
     * 返回移除了「给定维度坐标上已消失方块」的普通设备绑定后的新数据；没有任何命中时返回
     * {@code this}（不产生新实例）。仅普通设备（BLOCK_ENTITY / PART）按方块坐标匹配，
     * 合成 CPU 条目不受影响——CPU 组整组失效 / 再成型由
     * {@link #withoutCpu(DeviceId)} / {@link #withCpuReplaced(DeviceId, DeviceId, BlockPos)} 处理。
     * 纯数据方法，可脱离运行时直接测试。
     *
     * @param removedByDimension 各维度内已被移除（破坏）的方块坐标集合
     */
    public ConfigCardData withoutBlockDevicesAt(Map<ResourceKey<Level>, Set<BlockPos>> removedByDimension) {
        List<DeviceId> remaining = new ArrayList<>(devices.size());
        boolean changed = false;
        for (DeviceId device : devices) {
            // 只按方块坐标匹配普通设备：合成 CPU 的多块组不会被单块拆除击穿。
            if (!device.isCpu()) {
                Set<BlockPos> removedPositions = removedByDimension.get(device.dimension());
                if (removedPositions != null && removedPositions.contains(device.pos())) {
                    changed = true;
                    continue;
                }
            }
            remaining.add(device);
        }
        // 构造器会再次归一化（去重/排序，并裁剪已移除 CPU 的几何记录——此处 CPU 未被移除）。
        return changed ? new ConfigCardData(accelerator, remaining, cpuBounds) : this;
    }

    /**
     * 返回删除了某合成 CPU 组条目（设备标识与其外包围盒几何一并删除）后的新数据；
     * 未绑定该组时返回 {@code this}。纯数据方法，可脱离运行时直接测试。
     */
    public ConfigCardData withoutCpu(DeviceId cpuId) {
        if (cpuId == null || !cpuId.isCpu() || !devices.contains(cpuId)) {
            return this;
        }
        List<DeviceId> remaining = new ArrayList<>(devices.size());
        for (DeviceId device : devices) {
            if (!device.equals(cpuId)) {
                remaining.add(device);
            }
        }
        // 构造器会裁剪掉已移出设备列表的该 CPU 几何记录。
        return new ConfigCardData(accelerator, remaining, cpuBounds);
    }

    /**
     * 返回把某合成 CPU 组条目改写为「再成型后」集群的新数据：设备标识由 {@code oldCpuId}
     * 换成 {@code newCpuId}（两者可相同，即仅刷新几何），对应外包围盒最大角改为
     * {@code newMax}；卡上未绑定 {@code oldCpuId} 或参数非法时返回 {@code this}。
     * 其余普通设备与其它 CPU 条目不受影响。纯数据方法，可脱离运行时直接测试。
     *
     * @param oldCpuId 当前绑定（拆除前）的 CPU 组标识
     * @param newCpuId 再成型后集群的标识（可等于 {@code oldCpuId}）
     * @param newMax   再成型后集群的最大角方块坐标
     */
    public ConfigCardData withCpuReplaced(DeviceId oldCpuId, DeviceId newCpuId, @Nullable BlockPos newMax) {
        if (oldCpuId == null || !oldCpuId.isCpu() || newCpuId == null || !newCpuId.isCpu()
                || !devices.contains(oldCpuId)) {
            return this;
        }
        List<DeviceId> replaced = new ArrayList<>(devices.size());
        for (DeviceId device : devices) {
            replaced.add(device.equals(oldCpuId) ? newCpuId : device);
        }
        List<CpuBounds> bounds = new ArrayList<>(cpuBounds.size());
        for (CpuBounds entry : cpuBounds) {
            if (entry.cpu().equals(oldCpuId)) {
                if (newMax != null) {
                    bounds.add(new CpuBounds(newCpuId, newMax));
                }
            } else {
                bounds.add(entry);
            }
        }
        // 构造器统一归一化（去重/排序/裁剪），保证持久化数据不抖动。
        return new ConfigCardData(accelerator, replaced, bounds);
    }
}
