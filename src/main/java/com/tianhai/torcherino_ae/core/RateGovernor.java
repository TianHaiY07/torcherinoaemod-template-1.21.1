package com.tianhai.torcherino_ae.core;

import com.tianhai.torcherino_ae.config.RuntimeConfig;

/**
 * 源级加速耗时调控器：按「加速器（或加速火把）自身贡献的加速耗时」把<b>实际加速倍率</b>
 * 动态下调，设定倍率只作总阈值、永不超过去。
 * <p>
 * 与 {@link AdaptiveThrottle}（整 tick 计数耗时 → 档位 → 每源调用次数预算）不同，本类按
 * <b>每源独立</b>计量——每台加速器 / 火把各自持有一个实例，各量各的本 tick 加速执行耗时，
 * 互不干扰。它是「耗时 → 实际倍率」的主调控；AdaptiveThrottle 的调用次数预算保留作极端
 * 情况（超大范围 / 极端高倍率）下的兜底，两者作用层级不同、互不冲突。
 * <p>
 * 状态机（每个源一个）：
 * <ul>
 *   <li><b>缩放因子 {@code factor} ∈ (0, 1]</b>：{@code 1} 表示用足设定倍率（不干预），
 *       越接近 0 表示压得越低。实际倍率 = {@code clamp(round(baseCap × factor), 1, baseCap)}。</li>
 *   <li><b>收紧</b>：本 tick 加速耗时 EMA ≥ {@code sourceMsLimit × tightenRatio} 时，把因子<b>即时</b>
 *       压到 {@code min(factor, sourceMsLimit / emaMs)}（超出限值的比例内缩；EMA 越超压得越狠）；</li>
 *   <li><b>放松</b>：耗时 EMA &lt; {@code sourceMsLimit × relaxRatio} 时，因子<b>每 tick 回升一档</b>
 *       （{@code +RELAX_STEP}，见常量），避免临界负载在「全速/下压」间剧烈振荡；</li>
 *   <li><b>滞回带</b>（两阈值之间）：保持当前因子，防抖。</li>
 * </ul>
 * 阈值与 EM 平滑系数见配置 {@code rate.*}（{@link RuntimeConfig}），热重载即时生效。
 * <p>
 * 线程模型：与 {@link AdaptiveThrottle} 一致，全部状态只由服务端逻辑 tick 线程访问。
 */
public final class RateGovernor {

    /** 放松档位步长：因子在滞回带下方每 tick 回升本值（0.1 → 从实测 0.1 到 1 约需 9 tick），避免突变。 */
    private static final double RELAX_STEP = 0.1;

    // 是否有过样本（首个样本直接作为 EMA 初值）。
    private boolean hasSample;
    // 本源加速耗时的 EMA（毫秒）。
    private double emaMs;
    // 当前缩放因子（(0,1]）：1 = 用足设定倍率，越小压得越低。
    private double factor = 1.0;

    /** 喂入一次「本源单 tick 加速执行耗时（毫秒）」样本，平滑并推进因子。 */
    public void sample(double spentMs) {
        emaMs = nextEma(emaMs, hasSample, spentMs, RuntimeConfig.rateEmaAlpha());
        hasSample = true;
        factor = nextFactor(emaMs, factor,
                RuntimeConfig.rateSourceMsLimit(),
                RuntimeConfig.rateTightenRatio(),
                RuntimeConfig.rateRelaxRatio(),
                RELAX_STEP);
    }

    /** 当前本源加速耗时的 EMA（毫秒）；无样本时为 0。 */
    public double emaMs() {
        return emaMs;
    }

    /** 当前缩放因子（(0,1]）。 */
    public double factor() {
        return factor;
    }

    /** 当前是否处于「下压实际倍率」状态。 */
    public boolean isCapped() {
        return factor < 1.0;
    }

    /**
     * 依据当前因子把「设定倍率上限」折算为「实际可用倍率」。
     * <p>
     * 无样本（尚未量过本源耗时）时原样返回设定倍率（不干预）；否则返回
     * {@code clamp(round(baseCap × factor), 1, baseCap)}。
     *
     * @param baseCap 设定倍率上限（加速器 = 整体最高倍率；火把 = 当前 speed）
     */
    public int cap(int baseCap) {
        if (!hasSample) {
            return baseCap;
        }
        return effectiveCap(baseCap, factor);
    }

    /** EMA 更新（纯函数，供单测）：首个样本直接作为初值，之后按 alpha 加权。 */
    static double nextEma(double emaMs, boolean hasSample, double sampleMs, double alpha) {
        return hasSample ? emaMs * (1 - alpha) + sampleMs * alpha : sampleMs;
    }

    /**
     * 因子推进（纯函数，供单测）。滞回 + 分级：
     * <ul>
     *   <li>耗时 EMA ≥ {@code limit × tighten} → 因子<b>即时</b>压到 {@code min(factor, limit/ema)}；</li>
     *   <li>耗时 EMA &lt; {@code limit × relax} → 因子加 {@code relaxStep} 逐 tick 回升（封顶 1）；</li>
     *   <li>其余区间（滞回带）保持当前因子。</li>
     * </ul>
     */
    static double nextFactor(double emaMs, double factor, double limitMs,
            double tightenRatio, double relaxRatio, double relaxStep) {
        double tighten = limitMs * tightenRatio;
        if (emaMs >= tighten) {
            return Math.min(factor, limitMs / emaMs);
        }
        double relax = limitMs * relaxRatio;
        if (emaMs < relax) {
            return Math.min(1.0, factor + relaxStep);
        }
        return factor;
    }

    /** 把设定倍率与缩放因子合成实际可用倍率（纯函数，供单测），钳到 {@code [1, baseCap]}。 */
    static int effectiveCap(int baseCap, double factor) {
        int cap = (int) Math.round(baseCap * factor);
        return Math.max(1, Math.min(baseCap, cap));
    }
}
