package com.tianhai.torcherino_ae.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.tianhai.torcherino_ae.api.AccelSource;
import com.tianhai.torcherino_ae.api.DeviceId;
import com.tianhai.torcherino_ae.util.DebugLog;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;

/**
 * 加速目标登记表：全局唯一的「谁被加速、多少倍、谁设置的」状态表。
 * <p>
 * 旧实现用三个集合分别维护状态，彼此在重启与换卡场景下会失去同步：
 * <ul>
 *   <li>{@code acceleratedDevices}（持久化）：混入配置卡注入结果，无法区分来源；</li>
 *   <li>{@code deviceMultipliers}（持久化，与上面靠数组下标隐式对齐）：每台设备的独立倍数；</li>
 *   <li>{@code configCardDevices}（<b>仅内存</b>）：卡注入来源集合。</li>
 * </ul>
 * 由此产生的真实缺陷：卡注入的结果写进了会持久化的选中集合，而来源集合不持久化，
 * 服务器重启后取出配置卡时无法撤销注入，设备被永久加速。
 * <p>
 * 这里合并为一张带来源标记的表，撤销时按 {@link AccelSource} 精确过滤，
 * 契约在任何时序（含重启后）都成立。
 * <p>
 * 每条 {@link DeviceId} 只保留一份设置；后写入的来源覆盖先写入的（玩家 GUI 显式设置
 * 优先于配置卡注入，取出卡片不会误伤玩家手动勾选的设备）。撤销按来源精确清除，
 * 「玩家勾选与卡注入重叠时，任一方取消即停止」的旧行为在同来源场景下保持不变。
 */
public final class TargetRegistry {

    // NBT 存储键名。
    private static final String TAG_TARGETS = "targets";

    // 变更版本号：每次修改操作（set/remove/clear/clearBySource/load）都会递增。
    // 供服务端菜单判断设备列表缓存是否失效（§8.4），避免稳态下每采集周期全量重建。
    private int version;

    /**
     * 一条登记的加速设置：倍数 + 来源。
     */
    public record TargetSetting(int multiplier, AccelSource source) {
    }

    /**
     * NBT 条目：一个设备标识 + 倍数 + 来源，三者自包含。
     * <p>
     * 取代旧实现「{@code ListTag<String>} + {@code int[]} 靠下标隐式对齐」的脆弱格式。
     */
    private record Entry(DeviceId id, int multiplier, AccelSource source) {

        static final Codec<Entry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                DeviceId.CODEC.fieldOf("id").forGetter(Entry::id),
                Codec.INT.fieldOf("multiplier").forGetter(Entry::multiplier),
                AccelSource.CODEC.fieldOf("source").forGetter(Entry::source))
                .apply(instance, Entry::new));

        static final Codec<List<Entry>> LIST_CODEC = CODEC.listOf();
    }

    private final Map<DeviceId, TargetSetting> settings = new HashMap<>();

    /**
     * 当前登记表版本号：任何修改操作都会使版本递增，供菜单/外部缓存判断内容是否变化。
     */
    public int version() {
        return version;
    }

    /**
     * 设置指定设备的加速倍数与来源；倍数小于等于 1 视为取消该设备的加速。
     */
    public void set(DeviceId id, int multiplier, AccelSource source) {
        if (multiplier <= 1) {
            settings.remove(id);
        } else {
            settings.put(id, new TargetSetting(multiplier, source));
        }
        version++;
    }

    /**
     * 仅撤销指定来源的全部设置：配置卡取出时调用，不会误伤玩家手动勾选的设备。
     */
    public void clearBySource(AccelSource source) {
        settings.values().removeIf(setting -> setting.source() == source);
        version++;
    }

    /** 移除指定设备的登记（不区分来源）。 */
    public void remove(DeviceId id) {
        settings.remove(id);
        version++;
    }

    /** 清空全部登记。 */
    public void clear() {
        settings.clear();
        version++;
    }

    /**
     * 查询指定设备登记的倍数；未登记时返回 {@code fallback}。
     * <p>
     * 用负值作 fallback 可区分「未登记」与「登记了某个倍数」两种状态。
     */
    public int multiplierFor(DeviceId id, int fallback) {
        TargetSetting setting = settings.get(id);
        return setting == null ? fallback : setting.multiplier();
    }

    /** 指定设备是否处于被加速状态。 */
    public boolean isAccelerated(DeviceId id) {
        return settings.containsKey(id);
    }

    /**
     * 已登记设备的标识集合（实时视图，仅供遍历，不应长期持有或修改）。
     */
    public Set<DeviceId> ids() {
        return settings.keySet();
    }

    /** 指定来源已登记的设备标识集合（副本）。 */
    public Set<DeviceId> idsOfSource(AccelSource source) {
        Set<DeviceId> result = new HashSet<>();
        for (Map.Entry<DeviceId, TargetSetting> entry : settings.entrySet()) {
            if (entry.getValue().source() == source) {
                result.add(entry.getKey());
            }
        }
        return result;
    }

    /** 已登记设备数量。 */
    public int size() {
        return settings.size();
    }

    /** 是否没有任何登记。 */
    public boolean isEmpty() {
        return settings.isEmpty();
    }

    /**
     * 写入 NBT：{@code targets: [{id, multiplier, source}, ...]}。
     */
    public void save(CompoundTag tag, HolderLookup.Provider registries) {
        List<Entry> entries = new ArrayList<>(settings.size());
        for (Map.Entry<DeviceId, TargetSetting> entry : settings.entrySet()) {
            entries.add(new Entry(entry.getKey(), entry.getValue().multiplier(), entry.getValue().source()));
        }
        Entry.LIST_CODEC
                .encodeStart(registries.createSerializationContext(NbtOps.INSTANCE), entries)
                .ifSuccess(encoded -> tag.put(TAG_TARGETS, encoded))
                .ifError(error -> DebugLog.warn("[加速状态] 写入 NBT 失败：{}", error.message()));
    }

    /**
     * 读取 NBT。旧存档格式（{@code accelerated_devices} / {@code device_multipliers}）
     * 已断档不再兼容，加载时直接忽略，加速配置清空。
     */
    public void load(CompoundTag tag, HolderLookup.Provider registries) {
        settings.clear();
        version++;
        if (!tag.contains(TAG_TARGETS, Tag.TAG_LIST)) {
            return;
        }
        ListTag raw = tag.getList(TAG_TARGETS, Tag.TAG_COMPOUND);
        Entry.LIST_CODEC
                .parse(registries.createSerializationContext(NbtOps.INSTANCE), raw)
                .resultOrPartial(error -> DebugLog.warn("[加速状态] 跳过损坏的条目：{}", error))
                .ifPresent(entries -> {
                    for (Entry entry : entries) {
                        settings.put(entry.id(), new TargetSetting(entry.multiplier(), entry.source()));
                    }
                });
    }
}
