package com.tianhai.torcherino_ae.blockentity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;

/**
 * 加速火把影响范围「分片扫描」坐标数学的纯逻辑单测。
 * <p>
 * 覆盖 {@link TorchTargetScanner#bounds} 与 {@link TorchTargetScanner#offsetForIndex}：
 * 外接盒边长/体积、展平下标到相对偏移的映射（四角/中心）、以及「全部下标 ↔ 全部格子」
 * 的双射关系（保证分片扫描恰好无漏扫、无重复）。{@code resolve}（需真实服务端世界）不在此测。
 */
class TorchTargetScannerTest {

    /** 用 bounds.size() 复核给定三轴半径的立方体体积 = (2r+1)^3。 */
    @Test
    void 外接盒体积等于三轴边长的乘积() {
        int[][] cases = {
                {3, 2, 3},
                {8, 4, 8},
                {0, 0, 0},
                {1, 1, 1},
                {5, 0, 5},
        };
        BlockPos self = new BlockPos(100, 64, -100);
        for (int[] c : cases) {
            TorchTargetScanner.Bounds b = TorchTargetScanner.bounds(self, c[0], c[1], c[2]);
            long expected = (2L * c[0] + 1) * (2L * c[1] + 1) * (2L * c[2] + 1);
            assertEquals(expected, b.size(), "范围 (" + c[0] + "," + c[1] + "," + c[2] + ") 的体积");
            assertEquals(self.getX() - c[0], b.minX());
            assertEquals(self.getX() + c[0], b.maxX());
            assertEquals(self.getY() - c[1], b.minY());
            assertEquals(self.getY() + c[1], b.maxY());
            assertEquals(self.getZ() - c[2], b.minZ());
            assertEquals(self.getZ() + c[2], b.maxZ());
        }
    }

    /** 下标 0 映射到最小角，下标 size-1 映射到最大角，中心下标映射到自身（偏移 0,0,0）。 */
    @Test
    void 角与中心下标映射到正确相对偏移() {
        BlockPos self = new BlockPos(0, 0, 0);
        TorchTargetScanner.Bounds b = TorchTargetScanner.bounds(self, 3, 2, 3);
        assertEquals(new BlockPos(-3, -2, -3), TorchTargetScanner.offsetForIndex(0, b));
        assertEquals(new BlockPos(3, 2, 3), TorchTargetScanner.offsetForIndex(b.size() - 1, b));
        assertEquals(BlockPos.ZERO, TorchTargetScanner.offsetForIndex(b.size() / 2, b));
    }

    /** 全部下标对应的相对偏移集合恰好等于整盒格子集合（无漏扫、无重复——双射）。 */
    @Test
    void 下标到偏移是整盒格子的双射() {
        int xr = 4, yr = 2, zr = 4;
        BlockPos self = new BlockPos(10, 10, 10);
        TorchTargetScanner.Bounds b = TorchTargetScanner.bounds(self, xr, yr, zr);
        Set<BlockPos> seen = new HashSet<>((int) b.size());
        Set<BlockPos> expected = new HashSet<>((int) b.size());
        for (int dx = -xr; dx <= xr; dx++) {
            for (int dy = -yr; dy <= yr; dy++) {
                for (int dz = -zr; dz <= zr; dz++) {
                    expected.add(new BlockPos(dx, dy, dz));
                }
            }
        }
        for (int i = 0; i < b.size(); i++) {
            BlockPos off = TorchTargetScanner.offsetForIndex(i, b);
            assertTrue(seen.add(off), "下标 " + i + " 重复映射到 " + off);
            assertTrue(expected.contains(off), "下标 " + i + " 映射到盒外 " + off);
        }
        assertEquals(expected.size(), seen.size());
    }

    /** 三轴范围均为 0：只包含自身一格，下标 0 即相对偏移 (0,0,0)。 */
    @Test
    void 零范围仅含自身() {
        BlockPos self = new BlockPos(5, 5, 5);
        TorchTargetScanner.Bounds b = TorchTargetScanner.bounds(self, 0, 0, 0);
        assertEquals(1, b.size());
        assertEquals(BlockPos.ZERO, TorchTargetScanner.offsetForIndex(0, b));
    }

    /** 分片窗口长度：小范围保持基础窗口；大范围按「单 tick 最多 maxCellsPerTick 格」线性拉长。 */
    @Test
    void 窗口长度按单tick单元格预算拉长() {
        assertEquals(20, TorchTargetScanner.windowFor(20, 245, 512));     // 小范围：保持基础窗口
        assertEquals(20, TorchTargetScanner.windowFor(20, 10_240, 512));  // 恰好在阈值
        assertEquals(21, TorchTargetScanner.windowFor(20, 10_241, 512));  // 刚过阈值 → 拉长 1 tick
        assertEquals(2000, TorchTargetScanner.windowFor(20, 1_023_999, 512)); // 大范围：按预算钳制
        assertEquals(1, TorchTargetScanner.windowFor(1, 0, 512));         // 空/零格子：回退基础窗口
        assertEquals(20, TorchTargetScanner.windowFor(20, 100, 0));       // 非法预算：回退基础窗口
        assertEquals(1, TorchTargetScanner.windowFor(0, 100, 512));       // 非正基础窗口：钳到 1
    }
}
