package com.tianhai.torcherino_ae.item;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.tianhai.torcherino_ae.api.DeviceId;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

/**
 * 配置卡数据契约 {@link ConfigCardData} 中可脱离运行时直测的部分。
 * <p>
 * 覆盖 {@code CODEC} 的 NBT 编解码往返（含未绑定加速器的空数据）与
 * {@code of(...)} 对设备列表的稳定排序。物品栈交互类方法（绑定/解绑、
 * {@code STREAM_CODEC}）依赖注册表与物品数据组件基建，由游戏内实测覆盖。
 */
class ConfigCardDataTest {

    private static final ResourceKey<Level> DIM = ResourceKey.create(Registries.DIMENSION,
            ResourceLocation.parse("minecraft:overworld"));

    @Test
    void of方法对设备集合按稳定键排序() {
        DeviceId a = DeviceId.ofBlock(DIM, new BlockPos(30, 0, 0));
        DeviceId b = DeviceId.ofPart(DIM, new BlockPos(1, 0, 0), Direction.NORTH);
        DeviceId c = DeviceId.ofCpu(DIM, new BlockPos(10, 0, 0));
        // 乱序集合输入：输出必须稳定排序（不因集合迭代顺序而抖动持久化数据）。
        ConfigCardData data = ConfigCardData.of(
                DeviceId.ofBlock(DIM, new BlockPos(0, 0, 0)), Set.of(c, a, b));

        List<DeviceId> sorted = List.of(a, b, c).stream()
                .sorted(java.util.Comparator.comparing(DeviceId::stableKey)).toList();
        assertEquals(sorted, data.devices());
    }

    @Test
    void codec往返保持加速器与设备列表() {
        DeviceId accelerator = DeviceId.ofBlock(DIM, new BlockPos(5, 70, -10));
        DeviceId device1 = DeviceId.ofBlock(DIM, new BlockPos(1, 0, 0));
        DeviceId device2 = DeviceId.ofPart(DIM, new BlockPos(2, 0, 0), Direction.DOWN);
        ConfigCardData data = ConfigCardData.of(accelerator, Set.of(device1, device2));

        var encoded = ConfigCardData.CODEC.encodeStart(NbtOps.INSTANCE, data).getOrThrow();
        ConfigCardData decoded = ConfigCardData.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow();

        assertEquals(data, decoded);
        assertEquals(accelerator, decoded.accelerator());
        assertEquals(Set.of(device1, device2), Set.copyOf(decoded.devices()));
    }

    @Test
    void 空数据的codec往返() {
        // 未绑定加速器：accelerator 为 null，optional 字段应被省略并可正常往返。
        var encoded = ConfigCardData.CODEC.encodeStart(NbtOps.INSTANCE, ConfigCardData.EMPTY).getOrThrow();
        ConfigCardData decoded = ConfigCardData.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow();
        assertEquals(ConfigCardData.EMPTY, decoded);
        assertEquals(null, decoded.accelerator());
        assertTrue(decoded.devices().isEmpty());
    }
}
