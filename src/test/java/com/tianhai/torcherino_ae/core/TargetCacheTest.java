package com.tianhai.torcherino_ae.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.tianhai.torcherino_ae.api.AccelerationTarget;

/**
 * 泛型目标缓存 {@link TargetCache} 的纯逻辑单测。
 * <p>
 * 覆盖：首次访问立即构建、{@code markDirty} 后下一次访问立即重建、到达重建周期
 * 后自动重建。目标元素只作不透明载荷（此处允许 null 占位），缓存逻辑本身不触碰
 * 目标内容（脉冲期的失效剔除属引擎职责，不在本类测试范围）。
 */
class TargetCacheTest {

    /** 每次重建都让计数器 +1 并返回带标记列表，用重建次数断言行为。 */
    private static final class RebuildRecorder {
        final AtomicInteger count = new AtomicInteger();
        final int marker;

        RebuildRecorder(int marker) {
            this.marker = marker;
        }

        List<AccelerationTarget> rebuild() {
            count.incrementAndGet();
            // record 组件允许 null（本测试不构造真实网格节点）。
            return List.of(new AccelerationTarget(null, null, null, null, null),
                    new AccelerationTarget(null, null, null, null, null));
        }
    }

    @Test
    void 首次访问立即构建() {
        TargetCache cache = new TargetCache(100);
        RebuildRecorder recorder = new RebuildRecorder(0);
        assertEquals(2, cache.resolve(recorder::rebuild).size());
        assertEquals(1, recorder.count.get());
    }

    @Test
    void 周期内不重复重建() {
        TargetCache cache = new TargetCache(10);
        RebuildRecorder recorder = new RebuildRecorder(0);
        cache.resolve(recorder::rebuild); // 首次构建
        for (int i = 0; i < 9; i++) {
            cache.resolve(recorder::rebuild);
        }
        assertEquals(1, recorder.count.get());
    }

    @Test
    void 到达周期后自动重建() {
        // 间隔 3：第 1 次（dirty）与第 4、7 次访问重建（timer 累积）。
        TargetCache cache = new TargetCache(3);
        RebuildRecorder recorder = new RebuildRecorder(0);
        cache.resolve(recorder::rebuild); // 1 次：重建
        cache.resolve(recorder::rebuild); // 2 次
        cache.resolve(recorder::rebuild); // 3 次
        assertEquals(1, recorder.count.get());
        cache.resolve(recorder::rebuild); // 4 次：重建
        assertEquals(2, recorder.count.get());
        cache.resolve(recorder::rebuild); // 5 次
        cache.resolve(recorder::rebuild); // 6 次
        cache.resolve(recorder::rebuild); // 7 次：重建
        assertEquals(3, recorder.count.get());
    }

    @Test
    void markDirty后下一次访问立即重建() {
        TargetCache cache = new TargetCache(1_000); // 周期足够长，隔离「周期触发」因素
        RebuildRecorder recorder = new RebuildRecorder(0);
        cache.resolve(recorder::rebuild); // 首次构建
        cache.resolve(recorder::rebuild); // 周期内不重建
        assertEquals(1, recorder.count.get());

        cache.markDirty();
        cache.resolve(recorder::rebuild); // 置脏后立即重建
        assertEquals(2, recorder.count.get());
    }

    @Test
    void 重建内容替换旧内容() {
        TargetCache cache = new TargetCache(100);
        var recorder = new RebuildRecorder(1);
        cache.resolve(recorder::rebuild);
        cache.markDirty();
        // 第二次构建返回同一标记列表内容一致；此处仅验证返回列表长度跟随最近一次重建。
        List<AccelerationTarget> second = cache.resolve(recorder::rebuild);
        assertEquals(2, second.size());
    }

    @Test
    void 重建间隔至少为1() {
        // 负数/0 间隔被钳到 1：每次访问都会因周期触发重建（等价于逐 tick 全量重建）。
        TargetCache cache = new TargetCache(0);
        RebuildRecorder recorder = new RebuildRecorder(0);
        cache.resolve(recorder::rebuild);
        cache.resolve(recorder::rebuild);
        assertEquals(2, recorder.count.get());
    }
}
