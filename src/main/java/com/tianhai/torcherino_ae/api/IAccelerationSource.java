package com.tianhai.torcherino_ae.api;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import appeng.api.networking.IGrid;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * 加速源契约：任何能够驱动 AE2 设备加速的对象（AE 加速器、AE 加速火把）都实现本接口。
 * <p>
 * 旧实现中两个方块实体各自维护一份几乎相同的脉冲方法（催促 + 多次 {@code tickingRequest}
 * + 睡眠判断 + 缓存失效），改一处忘一处就会行为漂移。这里把职责重新划分：
 * <ul>
 *   <li><b>源</b>只回答三个问题：加速谁、每台多少倍、现在能不能工作；</li>
 *   <li><b>引擎</b>（{@code core.AccelerationEngine}）负责执行：催促、多次触发、
 *       返回值早退、预算扣减、失效剔除——全局只有这一份实现。</li>
 * </ul>
 */
public interface IAccelerationSource {

    /** 所在维度：用于构建与校验 {@link DeviceId}，跨维度目标天然隔离。 */
    ResourceKey<Level> dimension();

    /** 源自身坐标：用于设备列表排序、距离计算与展示。 */
    BlockPos origin();

    /** 当前允许的倍率上限：加速器按升级卡计算，火把为固定上限。 */
    int maxMultiplier();

    /** 源是否处于可工作状态：加速器为已联网且节点激活，火把为倍率大于 1 且范围非空。 */
    boolean isActive();

    /**
     * 当前目标集合，由 {@code core.TargetCache} 支撑（内部处理周期重建与失效剔除）。
     * 返回的列表在缓存未重建期间是同一个实例，调用方不应修改它。
     */
    List<AccelerationTarget> targets();

    /**
     * 指定设备本 tick 应使用的加速倍数。返回小于等于 1 时引擎会跳过该设备。
     * <p>
     * 加速器：登记表中查得到就用登记值，查不到则视为「智能加速联动目标」按当前智能倍率；
     * 火把：所有目标统一返回界面设置的倍率。
     */
    int multiplierFor(DeviceId id);

    /** 本源独立的调用预算。 */
    BudgetMeter budget();

    /**
     * 本源所在的 AE 网格；单网格源（加速器）返回具体网格，引擎会校验目标节点
     * 仍属于该网格；多网格源（火把，可同时覆盖多个网络）返回 {@code null}，跳过该校验。
     */
    @Nullable
    IGrid grid();

    /** 目标缓存失效标记：引擎发现失效节点时调用，使下一次取目标时立即重建。 */
    void markTargetsDirty();
}
