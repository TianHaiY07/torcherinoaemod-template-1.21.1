package com.tianhai.torcherino_ae.core;

import com.tianhai.torcherino_ae.config.RuntimeConfig;

/**
 * TPS 自适应节流：当服务端「单个游戏 tick 的纯计算耗时」逼近 50ms 硬限（20 TPS）时，
 * 自动把每个加速源每 tick 的额外调用预算压到安全水位，削平极端高倍率造成的 CPU 尖峰；
 * 负载回落后自动恢复。负载健康时完全不干预，预算语义与配置完全一致（含 {@code -1} 不限），
 * 因此默认开启也不会限制「机器够快」时的加速体验。
 * <p>
 * <b>分级递降削峰</b>：极端高倍率下单源预算即使压到 {@code floorCallsPerSource}
 * （默认 256）仍可能让单 tick 超过 50ms 硬限。因此本类在收紧后持续观察 EMA——
 * 只要仍超过进入阈值，就把预算<b>逐档减半</b>（每 tick 最多下一档，
 * 256 → 128 → 64 → … → 1，即从「收紧」一直加深到「几乎停加速」），直到 TPS 回落到健康区；
 * 回落后再<b>逐档放开</b>（每 tick 回升一档，避免负载临界时预算在全速/停转间剧烈振荡）。
 * <p>
 * 采样：主类监听 {@code ServerTickEvent.Pre/Post}，把「单 tick 计算耗时（不含补帧 sleep）」
 * 经 {@link #sample} 喂入，内部以指数移动平均（EMA，时间常数约 4 tick）平滑后与阈值比较。
 * 收紧/恢复使用<b>滞回</b>双阈值（进入 {@code tightenMs}，退出 {@code relaxMs}）。
 * <p>
 * 阈值与预算起点见配置 {@code adaptive.*}（{@link RuntimeConfig}），热重载即时生效。
 * 总开关 {@code adaptive.enabled}（默认开启）：关闭时本类<b>完全旁路</b>——
 * 事件计时直接返回（零开销），并清零已累积的采样/收紧状态，方块实体读到的预算
 * 原样等于 {@code budget.tickCallsPerSource}（含 {@code -1} 不限），即回到旧行为。
 * <p>
 * 线程模型：本类全部状态只由服务端逻辑 tick 线程访问（事件与方块实体同线程），无需同步。
 */
public final class AdaptiveThrottle {

    /** 进程级实例：主类事件接线喂样本，方块实体读取生效预算，共用同一份状态。 */
    public static final AdaptiveThrottle INSTANCE = new AdaptiveThrottle();

    /** EMA 平滑系数：越大响应越快，过小会延迟收紧。取 0.25 约合 4 tick 时间常数。 */
    private static final double EMA_ALPHA = 0.25;

    /**
     * 最深收紧档位：第 level 档的预算为 {@code floorCalls >> (level - 1)}，
     * 该深度足以把任意默认地板（≤ 1_000_000）在十几 tick 内递减到 1 次/tick。
     */
    private static final int MAX_LEVEL = 10;

    // 是否有过样本（首个样本直接作为 EMA 初值）。
    private boolean hasSample;
    // 单 tick 计算耗时的 EMA（毫秒）。
    private double emaMs;
    // 当前收紧档位：0 = 未收紧（预算 = 静态配置原样）；>0 = 收紧中，档位越高预算越紧。
    private int level;

    // 上一个 ServerTickEvent.Pre 的时刻，用于 Pre/Post 配对计时。
    private long tickStartNanos = -1;

    // 包内可见：仅供同包单元测试直接构造实例驱动；运行期统一使用 INSTANCE。
    AdaptiveThrottle() {
    }

    /** ServerTickEvent.Pre：记录本 tick 起始时刻；自适应关闭时直接旁路（零开销）。 */
    public void onTickStart() {
        if (!RuntimeConfig.adaptiveEnabled()) {
            return;
        }
        tickStartNanos = System.nanoTime();
    }

    /**
     * ServerTickEvent.Post：本 tick 计算完成，结算耗时并喂样本。
     * 自适应关闭时清零累积状态，保证「关闭再开启」不会带着关闭前的旧收紧/旧 EMA 续跑。
     */
    public void onTickEnd() {
        if (!RuntimeConfig.adaptiveEnabled()) {
            clearState();
            return;
        }
        if (tickStartNanos >= 0) {
            sample((System.nanoTime() - tickStartNanos) / 1_000_000.0);
            tickStartNanos = -1;
        }
    }

    /** 清零全部采样与收紧状态（自适应关闭期间每个 tick 调用，幂等）。 */
    private void clearState() {
        hasSample = false;
        emaMs = 0;
        level = 0;
        tickStartNanos = -1;
    }

    /**
     * 喂入一次「单 tick 纯计算耗时（毫秒，不含补帧 sleep）」样本。
     * <p>
     * 公开供单元测试直接驱动；运行期由 {@link #onTickEnd} 调用。
     */
    public void sample(double tickMs) {
        emaMs = nextEma(emaMs, hasSample, tickMs, EMA_ALPHA);
        hasSample = true;
        level = advanceLevel(emaMs, level,
                RuntimeConfig.adaptiveTightenMs(), RuntimeConfig.adaptiveRelaxMs());
    }

    /** 当前单 tick 计算耗时 EMA（毫秒）；无样本时为 0。 */
    public double emaTickMs() {
        return emaMs;
    }

    /** 当前是否处于收紧（限制每源预算）状态。 */
    public boolean isThrottled() {
        return level > 0;
    }

    /** 当前收紧档位（0 = 未收紧；越大预算越紧，见 {@link #effectiveLimit}）。 */
    public int throttleLevel() {
        return level;
    }

    /**
     * 把静态配置预算（{@code budget.tickCallsPerSource}，-1 不限）与自适应节流合成
     * 「当前生效的每源每 tick 预算」。关闭自适应或负载健康时原样返回配置值。
     * <p>
     * 方块实体每 tick 调用本方法并对比上次值，仅在收紧/恢复切换时重建预算计量器，
     * 平时零分配。
     */
    public int adjust(int configuredLimit) {
        return effectiveLimit(configuredLimit, RuntimeConfig.adaptiveEnabled(),
                level, RuntimeConfig.adaptiveFloorCalls());
    }

    /** EMA 更新（纯函数，供单测）：首个样本直接作为初值，之后按 alpha 加权。 */
    static double nextEma(double emaMs, boolean hasSample, double sampleMs, double alpha) {
        return hasSample ? emaMs * (1 - alpha) + sampleMs * alpha : sampleMs;
    }

    /**
     * 收紧档位推进（纯函数，供单测）。滞回 + 分级：
     * <ul>
     *   <li>未收紧：耗时达到 {@code tightenMs} 进入第 1 档，否则保持 0；</li>
     *   <li>已收紧：耗时仍 ≥ {@code tightenMs} → 加深一档（每 tick 最多 1 档，
     *       档位越深预算越紧，见 {@link #effectiveLimit}）；</li>
     *   <li>已收紧：耗时回落到 {@code relaxMs} 以下 → 放松一档（同样每 tick 最多 1 档，
     *       避免临界负载在全速/停转间振荡）；</li>
     *   <li>其余区间（中间滞回带）保持当前档位。</li>
     * </ul>
     */
    static int advanceLevel(double emaMs, int level, double tightenMs, double relaxMs) {
        if (level <= 0) {
            return emaMs >= tightenMs ? 1 : 0;
        }
        if (emaMs >= tightenMs) {
            return Math.min(level + 1, MAX_LEVEL);
        }
        if (emaMs < relaxMs) {
            return level - 1;
        }
        return level;
    }

    /**
     * 生效预算合成（纯函数，供单测）：收紧时取「配置静态预算」与「当前档位预算」的较小值；
     * 档位预算 = {@code floorCalls >> (level - 1)}（每加深一档减半，最终收敛到 1）。
     * 配置为 {@code -1}（不限）时直接返回当前档位预算。
     */
    static int effectiveLimit(int configuredLimit, boolean enabled, int level, int floorCalls) {
        if (!enabled || level <= 0) {
            return configuredLimit;
        }
        int floor = Math.max(1, floorCalls >> (level - 1));
        if (configuredLimit < 0) {
            return floor;
        }
        return Math.min(configuredLimit, floor);
    }
}
