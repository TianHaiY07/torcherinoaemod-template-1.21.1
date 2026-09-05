package com.tianhai.torcherino_ae.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import com.tianhai.torcherino_ae.api.DeviceId;

/**
 * 样板供应器下游联动辅助 {@link PatternProviderSupport} 的纯逻辑单测。
 * <p>
 * 覆盖两部分（均只需 MC 值类型，无需真实世界/网格）：
 * <ul>
 *   <li>{@link #downstreamPositions}：投放方向集 → 下游接收方块坐标（单方向 / 六向全投 /
 *       无重复 / 与原点相对关系正确）；</li>
 *   <li>{@link #linkedMultiplier}：下游设备「继承母源倍率」的合成（空集 / 多母源取最大 /
 *       无效母源忽略、不递归）。</li>
 * </ul>
 * 宿主识别（{@code instanceof PatternProviderLogicHost}）与投放方向读取（{@code getTargets()}）
 * 依赖 AE2 对象，不在纯 JVM 单测范围（由游戏内实测覆盖）。
 */
class PatternProviderSupportTest {

    private static DeviceId id(int x) {
        ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION,
                net.minecraft.resources.ResourceLocation.parse("minecraft:overworld"));
        return DeviceId.ofBlock(dim, new BlockPos(x, 64, 0));
    }

    /** 单方向投放：下游恰好为原点在该方向上的相邻格。 */
    @Test
    void 单方向投放解析出对应相邻坐标() {
        BlockPos origin = new BlockPos(10, 64, -10);
        List<BlockPos> pos = PatternProviderSupport.downstreamPositions(origin,
                EnumSet.of(Direction.NORTH));
        assertEquals(1, pos.size());
        assertEquals(new BlockPos(10, 64, -11), pos.get(0));
        assertFalse(pos.contains(origin), "下游坐标不得包含投放起点自身");
    }

    /** ALL 模式（六向全投）：下游为原点全部六个邻居，坐标无重复。 */
    @Test
    void 六向全投解析出六个互异邻居() {
        BlockPos origin = new BlockPos(0, 64, 0);
        List<BlockPos> pos = PatternProviderSupport.downstreamPositions(origin,
                EnumSet.allOf(Direction.class));
        assertEquals(6, pos.size());
        assertTrue(pos.contains(origin.north()));
        assertTrue(pos.contains(origin.south()));
        assertTrue(pos.contains(origin.east()));
        assertTrue(pos.contains(origin.west()));
        assertTrue(pos.contains(origin.above()));
        assertTrue(pos.contains(origin.below()));
        Set<BlockPos> distinct = Set.copyOf(pos);
        assertEquals(6, distinct.size(), "六向投放不得产生重复下游坐标");
        assertFalse(pos.contains(origin), "下游坐标不得包含投放起点自身");
    }

    /** 无母源或空母源集：一律返回 1（不加速）。 */
    @Test
    void 无母源时联动倍率为一() {
        assertEquals(1, PatternProviderSupport.linkedMultiplier(null, this::dummy));
        assertEquals(1, PatternProviderSupport.linkedMultiplier(Set.of(), this::dummy));
    }

    /** 多母源取最大；单母源直接继承其倍率。 */
    @Test
    void 多母源取最大倍率() {
        Set<DeviceId> sources = Set.of(id(1), id(2), id(3));
        assertEquals(8, PatternProviderSupport.linkedMultiplier(sources,
                did -> did.pos().getX() == 2 ? 8 : 4));
        assertEquals(4, PatternProviderSupport.linkedMultiplier(Set.of(id(5)), did -> 4));
    }

    /** 全部母源均无效（倍率 ≤1）时返回 1；无效母源不拖低有效母源。 */
    @Test
    void 无效母源忽略() {
        Set<DeviceId> sources = Set.of(id(1), id(2));
        assertEquals(1, PatternProviderSupport.linkedMultiplier(sources, did -> 1));
        assertEquals(4, PatternProviderSupport.linkedMultiplier(sources,
                did -> did.pos().getX() == 1 ? 1 : 4));
    }

    // 供「无母源」用例使用的空实现（本应不可达，返回 1 即可）。
    private int dummy(DeviceId did) {
        return 1;
    }

    /** 防御性确认：合成入口（Map 下游 → 母源集）与合成函数签名兼容，结果随映射变化。 */
    @Test
    void 映射驱动合成结果随母源变化() {
        Map<DeviceId, Set<DeviceId>> mapping = Map.of(id(9), Set.of(id(1), id(2)));
        int linked = PatternProviderSupport.linkedMultiplier(mapping.get(id(9)),
                did -> did.pos().getX() == 1 ? 2 : 3);
        assertEquals(3, linked);
        // 移除全部母源后不再联动（映射与合成函数解耦，此处模拟映射为空）。
        assertEquals(1, PatternProviderSupport.linkedMultiplier(null, did -> 3));
    }

    /** 方向集与下游坐标列表一一对应（顺序一致、长度一致），供调用方按序遍历。 */
    @Test
    void 方向与下游坐标一一对应() {
        BlockPos origin = new BlockPos(5, 70, 5);
        List<Direction> dirs = new ArrayList<>(EnumSet.of(Direction.UP, Direction.WEST, Direction.SOUTH));
        // 构建与方向顺序一致的期望坐标。
        List<BlockPos> expected = new ArrayList<>();
        for (Direction d : dirs) {
            expected.add(origin.relative(d));
        }
        assertEquals(expected, PatternProviderSupport.downstreamPositions(origin, EnumSet.copyOf(dirs)));
    }
}
