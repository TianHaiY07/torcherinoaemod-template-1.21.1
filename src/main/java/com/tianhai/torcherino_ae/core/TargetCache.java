package com.tianhai.torcherino_ae.core;

import java.util.List;
import java.util.function.Supplier;

import com.tianhai.torcherino_ae.api.AccelerationTarget;
import com.tianhai.torcherino_ae.config.ConfigDefaults;

/**
 * 加速目标缓存：把「全网格 / 全区域扫描」的代价从每 tick 摊薄到「周期或变更时」。
 * <p>
 * 旧实现里 AE 加速器与 AE 加速火把各维护一份几乎相同的缓存三件套
 * （目标列表 + dirty 标记 + 周期计时器），这里统一为一份实现。
 * <p>
 * 触发重建的三种时机：
 * <ul>
 *   <li>显式调用 {@link #markDirty()}——选中集合变化、范围变化、配置卡注入等，
 *       使「点击加速」无需等待下一个周期即可生效；</li>
 *   <li>引擎发现失效节点后回调 {@code IAccelerationSource.markTargetsDirty()}；</li>
 *   <li>达到重建周期（默认 20 tick），用于把新增设备纳入、把已移除设备剔除。</li>
 * </ul>
 * P3 配置化：周期默认值收口到 {@link ConfigDefaults}；实际使用方可在构造时传入
 * {@code RuntimeConfig.cacheRebuildIntervalTicks()} 的当前值（方块实体每次创建读取）。
 */
public final class TargetCache {

    /** 默认重建间隔（tick），与重构前硬编码的 20 tick 一致（配置默认值）。 */
    public static final int DEFAULT_REBUILD_INTERVAL = ConfigDefaults.CACHE_REBUILD_INTERVAL_TICKS;

    private final int rebuildIntervalTicks;

    // 当前缓存的目标列表；未初始化时为空列表，避免调用方判空。
    private List<AccelerationTarget> targets = List.of();

    private int timer;

    // 是否待重建：初始为 true，使首个 tick 立即构建。
    private boolean dirty = true;

    public TargetCache() {
        this(DEFAULT_REBUILD_INTERVAL);
    }

    public TargetCache(int rebuildIntervalTicks) {
        this.rebuildIntervalTicks = Math.max(1, rebuildIntervalTicks);
    }

    /**
     * 取当前目标集合，必要时触发重建。
     *
     * @param rebuild 重建逻辑，仅在需要时被调用
     */
    public List<AccelerationTarget> resolve(Supplier<List<AccelerationTarget>> rebuild) {
        if (dirty || ++timer >= rebuildIntervalTicks) {
            timer = 0;
            dirty = false;
            targets = rebuild.get();
        }
        return targets;
    }

    /**
     * 标记待重建：下一次 {@link #resolve} 立即重建。
     */
    public void markDirty() {
        this.dirty = true;
    }

    /** 当前缓存的目标数量（诊断用）。 */
    public int size() {
        return targets.size();
    }
}
