package com.tianhai.torcherino_ae.item;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
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
 * 覆盖 {@code CODEC} 的 NBT 编解码往返（含未绑定加速器的空数据）、{@code of(...)}
 * 对设备列表的稳定排序，以及合成 CPU 组外包围盒几何（{@code cpuBounds}）的记录、
 * 归一化裁剪与编解码往返。物品栈交互类方法（绑定/解绑、{@code STREAM_CODEC}）
 * 依赖注册表与物品数据组件基建，由游戏内实测覆盖。
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
        assertTrue(decoded.cpuBounds().isEmpty());
    }

    @Test
    void codec往返保持cpu组外包围盒几何() {
        DeviceId accelerator = DeviceId.ofBlock(DIM, new BlockPos(5, 70, -10));
        DeviceId cpu = DeviceId.ofCpu(DIM, new BlockPos(10, 5, 10));
        BlockPos boundsMax = new BlockPos(20, 9, 18);
        // 合成 CPU 多块组：整组只占一个设备标识，组外包围盒最大角随卡记录。
        ConfigCardData data = new ConfigCardData(accelerator, List.of(cpu),
                List.of(new ConfigCardData.CpuBounds(cpu, boundsMax)));

        var encoded = ConfigCardData.CODEC.encodeStart(NbtOps.INSTANCE, data).getOrThrow();
        ConfigCardData decoded = ConfigCardData.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow();

        assertEquals(data, decoded);
        assertEquals(1, decoded.cpuBounds().size());
        assertEquals(cpu, decoded.cpuBounds().get(0).cpu());
        assertEquals(boundsMax, decoded.cpuBounds().get(0).max());
    }

    @Test
    void 构造器裁剪非cpu或未绑定的几何记录并去重() {
        DeviceId cpu = DeviceId.ofCpu(DIM, new BlockPos(10, 5, 10));
        DeviceId otherCpu = DeviceId.ofCpu(DIM, new BlockPos(30, 5, 10));
        DeviceId machine = DeviceId.ofBlock(DIM, new BlockPos(1, 0, 0));
        // 三条几何记录：引用未绑定 CPU 的 otherCpu、引用普通设备（非 CRAFTING_CPU）的 machine，
        // 以及同一 cpu 的两条重复记录——最后写入者应保留。
        ConfigCardData data = new ConfigCardData(null, List.of(cpu, machine),
                List.of(
                        new ConfigCardData.CpuBounds(otherCpu, new BlockPos(40, 5, 20)),
                        new ConfigCardData.CpuBounds(machine, new BlockPos(2, 0, 0)),
                        new ConfigCardData.CpuBounds(cpu, new BlockPos(20, 9, 18)),
                        new ConfigCardData.CpuBounds(cpu, new BlockPos(21, 9, 18))));

        assertEquals(2, data.devices().size());
        assertTrue(data.devices().contains(cpu));
        assertTrue(data.devices().contains(machine));
        assertEquals(1, data.cpuBounds().size());
        assertEquals(cpu, data.cpuBounds().get(0).cpu());
        assertEquals(new BlockPos(21, 9, 18), data.cpuBounds().get(0).max());
    }

    @Test
    void 按移除坐标清除普通设备而保留合成CPU与其它维度() {
        DeviceId block = DeviceId.ofBlock(DIM, new BlockPos(1, 2, 3));
        DeviceId part = DeviceId.ofPart(DIM, new BlockPos(4, 5, 6), Direction.NORTH);
        // 与 block 同一坐标的合成 CPU：多块结构整组一个标记，单块被拆不应击穿其绑定。
        DeviceId cpu = DeviceId.ofCpu(DIM, new BlockPos(1, 2, 3));
        DeviceId otherDim = DeviceId.ofBlock(ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.parse("minecraft:the_nether")), new BlockPos(1, 2, 3));
        DeviceId intact = DeviceId.ofBlock(DIM, new BlockPos(9, 0, 0));
        ConfigCardData data = new ConfigCardData(null, List.of(block, part, cpu, otherDim, intact), List.of());

        ConfigCardData purged = data.withoutBlockDevicesAt(Map.of(DIM,
                Set.of(new BlockPos(1, 2, 3), new BlockPos(4, 5, 6))));

        assertEquals(Set.of(cpu, otherDim, intact), Set.copyOf(purged.devices()));
    }

    @Test
    void 按移除坐标清除时连带裁剪对应cpu几何记录() {
        DeviceId cpu = DeviceId.ofCpu(DIM, new BlockPos(10, 5, 10));
        DeviceId block = DeviceId.ofBlock(DIM, new BlockPos(1, 2, 3));
        BlockPos boundsMax = new BlockPos(20, 9, 18);
        ConfigCardData data = new ConfigCardData(null, List.of(cpu, block),
                List.of(new ConfigCardData.CpuBounds(cpu, boundsMax)));

        ConfigCardData purged = data.withoutBlockDevicesAt(Map.of(DIM, Set.of(new BlockPos(1, 2, 3))));

        // 仅清普通设备，CPU 绑定与其几何应原样保留。
        assertEquals(List.of(cpu), purged.devices());
        assertEquals(List.of(new ConfigCardData.CpuBounds(cpu, boundsMax)), purged.cpuBounds());
    }

    @Test
    void 无命中时返回原实例且不清除任何绑定() {
        DeviceId block = DeviceId.ofBlock(DIM, new BlockPos(1, 2, 3));
        ConfigCardData data = new ConfigCardData(null, List.of(block), List.of());

        ConfigCardData untouched = data.withoutBlockDevicesAt(
                Map.of(DIM, Set.of(new BlockPos(8, 8, 8))));

        assertSame(data, untouched);
    }

    @Test
    void 整组失效删除cpu条目并连带裁剪几何而保留其它绑定() {
        DeviceId cpu = DeviceId.ofCpu(DIM, new BlockPos(10, 5, 10));
        DeviceId otherCpu = DeviceId.ofCpu(DIM, new BlockPos(30, 5, 10));
        DeviceId machine = DeviceId.ofBlock(DIM, new BlockPos(1, 0, 0));
        BlockPos cpuMax = new BlockPos(20, 9, 18);
        ConfigCardData data = new ConfigCardData(null, List.of(cpu, otherCpu, machine),
                List.of(
                        new ConfigCardData.CpuBounds(cpu, cpuMax),
                        new ConfigCardData.CpuBounds(otherCpu, new BlockPos(40, 5, 20))));

        ConfigCardData removed = data.withoutCpu(cpu);

        assertEquals(Set.of(otherCpu, machine), Set.copyOf(removed.devices()));
        // 被删组的几何应一并裁剪，其它 CPU 几何原样保留。
        assertEquals(1, removed.cpuBounds().size());
        assertEquals(otherCpu, removed.cpuBounds().get(0).cpu());
    }

    @Test
    void 未绑定整组时withoutCpu返回原实例() {
        DeviceId cpu = DeviceId.ofCpu(DIM, new BlockPos(10, 5, 10));
        ConfigCardData data = new ConfigCardData(null, List.of(), List.of());

        assertSame(data, data.withoutCpu(cpu));
        // 非 CPU 参数同样不允许误删。
        assertSame(data, data.withoutCpu(DeviceId.ofBlock(DIM, new BlockPos(10, 5, 10))));
    }

    @Test
    void 部分破坏仍成型时刷新几何保持标识() {
        DeviceId cpu = DeviceId.ofCpu(DIM, new BlockPos(10, 5, 10));
        BlockPos oldMax = new BlockPos(20, 9, 18);
        ConfigCardData data = new ConfigCardData(null, List.of(cpu),
                List.of(new ConfigCardData.CpuBounds(cpu, oldMax)));

        // 再成型后集群仍锚定原最小角：标识不变、最大角收窄到 17,8,17。
        BlockPos newMax = new BlockPos(17, 8, 17);
        ConfigCardData adapted = data.withCpuReplaced(cpu, cpu, newMax);

        assertEquals(List.of(cpu), adapted.devices());
        assertEquals(1, adapted.cpuBounds().size());
        assertEquals(newMax, adapted.cpuBounds().get(0).max());
    }

    @Test
    void 部分破坏仍成型且锚点移动时换绑为新集群标识() {
        DeviceId oldCpu = DeviceId.ofCpu(DIM, new BlockPos(10, 5, 10));
        DeviceId newCpu = DeviceId.ofCpu(DIM, new BlockPos(10, 6, 10));
        DeviceId machine = DeviceId.ofBlock(DIM, new BlockPos(1, 0, 0));
        BlockPos newMax = new BlockPos(17, 8, 17);
        ConfigCardData data = new ConfigCardData(null, List.of(oldCpu, machine),
                List.of(new ConfigCardData.CpuBounds(oldCpu, new BlockPos(20, 9, 18))));

        ConfigCardData rebound = data.withCpuReplaced(oldCpu, newCpu, newMax);

        // 旧标识消失、换绑为新集群标识与几何；普通设备与其它条目不受影响。
        assertEquals(Set.of(newCpu, machine), Set.copyOf(rebound.devices()));
        assertEquals(1, rebound.cpuBounds().size());
        assertEquals(newCpu, rebound.cpuBounds().get(0).cpu());
        assertEquals(newMax, rebound.cpuBounds().get(0).max());
    }

    @Test
    void 换绑参数非法或未绑定时返回原实例() {
        DeviceId cpu = DeviceId.ofCpu(DIM, new BlockPos(10, 5, 10));
        DeviceId otherCpu = DeviceId.ofCpu(DIM, new BlockPos(30, 5, 10));
        ConfigCardData data = new ConfigCardData(null, List.of(otherCpu),
                List.of(new ConfigCardData.CpuBounds(otherCpu, new BlockPos(40, 5, 20))));

        // 未绑定 oldCpu：不改写。
        assertSame(data, data.withCpuReplaced(cpu, cpu, new BlockPos(17, 8, 17)));
        // oldCpu 是普通设备：不是 CPU 组，拒绝改写。
        DeviceId machine = DeviceId.ofBlock(DIM, new BlockPos(10, 5, 10));
        ConfigCardData data2 = new ConfigCardData(null, List.of(machine), List.of());
        assertSame(data2, data2.withCpuReplaced(machine, cpu, new BlockPos(17, 8, 17)));
    }
}
