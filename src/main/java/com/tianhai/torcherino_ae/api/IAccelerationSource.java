package com.tianhai.torcherino_ae.api;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import appeng.api.networking.IGrid;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * 加速源契约：AE 加速器经本接口接入统一的加速脉冲引擎。
 * <p>
 * 接口只声明源自身的特征——加速谁、每台多少倍、当前能否工作；实际执行由
 * {@link com.tianhai.torcherino_ae.core.AccelerationEngine} 统一驱动（催促、多次触发、
 * 睡眠早退、预算扣减、失效剔除），保证行为一致、实现只维护一份。
 */
public interface IAccelerationSource {

    /** 所在维度：用于构建与校验 {@link DeviceId}，跨维度目标天然隔离。 */
    ResourceKey<Level> dimension();

    /** 源自身坐标：用于设备列表排序、距离计算与展示。 */
    BlockPos origin();

    /** 当前允许的倍率上限（AE 加速器按升级卡复合计算）。 */
    int maxMultiplier();

    /** 源是否处于可工作状态（AE 加速器为已联网且节点激活）。 */
    boolean isActive();

    /**
     * 当前目标集合，由 {@code core.TargetCache} 支撑（内部处理周期重建与失效剔除）。
     * 返回的列表在缓存未重建期间是同一个实例，调用方不应修改它。
     */
    List<AccelerationTarget> targets();

    /**
     * 指定设备本 tick 应使用的加速倍数。返回小于等于 1 时引擎会跳过该设备。
     * <p>
     * 加速器：登记表中查得到就用登记值，查不到则视为「智能加速联动目标」按当前智能倍率。
     */
    int multiplierFor(DeviceId id);

    /** 本源独立的调用预算。 */
    BudgetMeter budget();

    /**
     * 本源所在的 AE 网格；引擎会校验目标节点仍属于该网格，
     * 源未入网（返回 {@code null}）时跳过该校验。
     */
    @Nullable
    IGrid grid();

    /** 目标缓存失效标记：引擎发现失效节点时调用，使下一次取目标时立即重建。 */
    void markTargetsDirty();
}
