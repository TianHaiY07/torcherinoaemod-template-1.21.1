package com.tianhai.torcherino_ae.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.mojang.serialization.Codec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

/**
 * 设备身份值类型 {@link DeviceId} 的纯逻辑单测。
 * <p>
 * 覆盖：跨维度同坐标不相等、部件按朝向区分、{@code stableKey}/{@code parse}
 * 字符串往返与非法输入兜底、CODEC 在 NBT 上的编解码往返（不依赖注册表运行环境）。
 * 网络包往返（{@code write}/{@code read}）依赖 {@code RegistryFriendlyByteBuf}，
 * 需完整 Minecraft 运行环境，不在纯 JVM 单测范围（由游戏内实测覆盖）。
 */
class DeviceIdTest {

    /** 测试用维度 key：仅作为不等性区分的标签，不需真实注册。 */
    private static ResourceKey<Level> dim(String location) {
        return ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(location));
    }

    @Test
    void 跨维度同坐标的设备不相等() {
        BlockPos pos = new BlockPos(100, 64, -200);
        DeviceId overworld = DeviceId.ofBlock(dim("minecraft:overworld"), pos);
        DeviceId nether = DeviceId.ofBlock(dim("minecraft:the_nether"), pos);
        // 维度参与 equals/stableKey：跨维度同坐标的两台设备标识必须不同，不能互相误判。
        assertNotEquals(overworld, nether);
        assertNotEquals(overworld.stableKey(), nether.stableKey());
    }

    @Test
    void 同维度同坐标的部件按朝向区分() {
        BlockPos cablePos = new BlockPos(0, 100, 0);
        DeviceId north = DeviceId.ofPart(dim("minecraft:overworld"), cablePos, Direction.NORTH);
        DeviceId south = DeviceId.ofPart(dim("minecraft:overworld"), cablePos, Direction.SOUTH);
        DeviceId noSide = DeviceId.ofPart(dim("minecraft:overworld"), cablePos, null);
        assertNotEquals(north, south);
        assertNotEquals(north, noSide);
        assertEquals(north, DeviceId.ofPart(dim("minecraft:overworld"), cablePos, Direction.NORTH));
    }

    @Test
    void 方块实体与CPU种类不同则即使坐标相同也不相等() {
        BlockPos pos = new BlockPos(5, 5, 5);
        ResourceKey<Level> dim = dim("minecraft:overworld");
        assertNotEquals(DeviceId.ofBlock(dim, pos), DeviceId.ofCpu(dim, pos));
    }

    @Test
    void stableKey与parse往返一致() {
        ResourceKey<Level> dim = dim("minecraft:overworld");
        DeviceId block = DeviceId.ofBlock(dim, new BlockPos(1, 2, 3));
        DeviceId part = DeviceId.ofPart(dim, new BlockPos(-4, 0, 7), Direction.UP);
        DeviceId cpu = DeviceId.ofCpu(dim, new BlockPos(10, -20, 30));

        assertEquals(block, DeviceId.parse(block.stableKey()));
        assertEquals(part, DeviceId.parse(part.stableKey()));
        assertEquals(cpu, DeviceId.parse(cpu.stableKey()));
    }

    @Test
    void stableKey保持稳定() {
        ResourceKey<Level> dim = dim("minecraft:overworld");
        DeviceId id = DeviceId.ofPart(dim, new BlockPos(9, 9, 9), Direction.EAST);
        // 同一设备多次生成字符串应完全一致（持久化/GUI 载荷以它作身份）。
        assertEquals(id.stableKey(), DeviceId.ofPart(dim, new BlockPos(9, 9, 9), Direction.EAST).stableKey());
    }

    @Test
    void cpu种类标记正确() {
        DeviceId cpu = DeviceId.ofCpu(dim("minecraft:overworld"), new BlockPos(0, 0, 0));
        assertTrue(cpu.isCpu());
        assertFalse(DeviceId.ofBlock(dim("minecraft:overworld"), new BlockPos(0, 0, 0)).isCpu());
        assertFalse(DeviceId.ofPart(dim("minecraft:overworld"), new BlockPos(0, 0, 0), Direction.NORTH).isCpu());
    }

    @Test
    void 非法输入parse返回null不抛异常() {
        // 客户端可伪造载荷：任何非法格式都必须按「校验失败」处理，而不是抛异常打断服务端。
        assertNull(DeviceId.parse(null));
        assertNull(DeviceId.parse(""));
        assertNull(DeviceId.parse("a|b")); // 段数不足
        assertNull(DeviceId.parse("a|b|c|d|e")); // 段数过多
        assertNull(DeviceId.parse("minecraft:overworld|not-a-long|1|BLOCK_ENTITY")); // 坐标非法
        assertNull(DeviceId.parse("minecraft:overworld|123|1|NOT_A_KIND")); // 种类非法
        assertNull(DeviceId.parse("not a valid location##|123|-1|BLOCK_ENTITY")); // 维度非法
    }

    @Test
    void codec在NBT上编解码往返() {
        ResourceKey<Level> dim = dim("minecraft:overworld");
        assertCodecRoundTrip(DeviceId.ofBlock(dim, new BlockPos(1, 2, 3)));
        assertCodecRoundTrip(DeviceId.ofPart(dim, new BlockPos(-5, 8, 0), Direction.WEST));
        assertCodecRoundTrip(DeviceId.ofPart(dim, new BlockPos(-5, 8, 0), null));
        assertCodecRoundTrip(DeviceId.ofCpu(dim, new BlockPos(30, 40, 50)));
    }

    /** 用 NbtOps 直接对单个 CODEC 做编码/解码往返断言。 */
    private static void assertCodecRoundTrip(DeviceId id) {
        Codec<DeviceId> codec = DeviceId.CODEC;
        var encoded = codec.encodeStart(NbtOps.INSTANCE, id).getOrThrow();
        assertEquals(id, codec.parse(NbtOps.INSTANCE, encoded).getOrThrow());
    }
}
