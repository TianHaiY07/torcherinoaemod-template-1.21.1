package com.tianhai.torcherino_ae.blockentity;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;

/**
 * 「同网络加速器独占（先放置者优先）」先后裁决的纯逻辑单测。
 * <p>
 * 覆盖 {@link AEAcceleratorBlockEntity#isNotLaterThan}：放置时刻比较、
 * 任一未知（新放置未首 tick / 旧档无记录）时的坐标字典序平局裁决。
 */
class AcceleratorOrderTest {

    // 与 AEAcceleratorBlockEntity.createdAtTick 的初始值（未记录）保持一致。
    private static final long UNKNOWN = Long.MIN_VALUE;

    @Test
    void 均已知时按放置时刻比较() {
        BlockPos a = new BlockPos(1, 64, 1);
        BlockPos b = new BlockPos(2, 64, 2);
        // 先放置者不晚于后放置者。
        assertTrue(AEAcceleratorBlockEntity.isNotLaterThan(100, a, 200, b));
        // 后放置者晚于先放置者。
        assertFalse(AEAcceleratorBlockEntity.isNotLaterThan(200, b, 100, a));
    }

    @Test
    void 时刻相同视为不晚于() {
        BlockPos a = new BlockPos(1, 64, 1);
        BlockPos b = new BlockPos(2, 64, 2);
        // 同时放置（同世界 tick）时两个方向都「不晚于」——由调用方两侧同时停用兜底。
        assertTrue(AEAcceleratorBlockEntity.isNotLaterThan(500, a, 500, b));
        assertTrue(AEAcceleratorBlockEntity.isNotLaterThan(500, b, 500, a));
    }

    @Test
    void 任一未知时按坐标字典序平局裁决() {
        // 坐标比较沿用 Vec3i.compareTo 语义；用例坐标在 Y 轴拉开距离以不依赖其内部轴序。
        BlockPos small = new BlockPos(1, 32, 1);
        BlockPos big = new BlockPos(1, 64, 1);
        // 位置较小者视为先放置：不晚于位置较大者。
        assertTrue(AEAcceleratorBlockEntity.isNotLaterThan(UNKNOWN, small, 300, big));
        assertTrue(AEAcceleratorBlockEntity.isNotLaterThan(100, small, UNKNOWN, big));
        // 位置较大者晚于位置较小者。
        assertFalse(AEAcceleratorBlockEntity.isNotLaterThan(UNKNOWN, big, 300, small));
        assertFalse(AEAcceleratorBlockEntity.isNotLaterThan(100, big, UNKNOWN, small));
    }

    @Test
    void 两者未知时按坐标字典序裁决() {
        BlockPos a = new BlockPos(1, 64, 1);
        BlockPos b = new BlockPos(0, 32, 0);
        // 旧档两台都无记录：位置较小者（b）视为先放置。
        assertFalse(AEAcceleratorBlockEntity.isNotLaterThan(UNKNOWN, a, UNKNOWN, b));
        assertTrue(AEAcceleratorBlockEntity.isNotLaterThan(UNKNOWN, b, UNKNOWN, a));
    }
}
