package com.tianhai.torcherino_ae.util;

import java.util.function.Supplier;

import com.tianhai.torcherino_ae.Torcherinoaemod;
import com.tianhai.torcherino_ae.config.ConfigDefaults;

/**
 * 诊断日志门面。
 * <p>
 * 集中管理模组的调试日志输出，替代原先直接散落在每 tick 主循环与玩家交互路径上的
 * {@code LOGGER.info("[DBG]...")}。原实现的问题：
 * <ul>
 *   <li>每台加速器每秒固定输出 2 条 INFO 日志，10 台就是每秒 20 条；</li>
 *   <li>日志内容包含全设备明细的字符串拼装（{@code String.format} + {@code getClass().getSimpleName()}），
 *       即便日志框架的级别被调高，拼装开销也已经发生；</li>
 *   <li>玩家每次右键配置卡都会记录一条 INFO 日志。</li>
 * </ul>
 * 本门面提供统一开关：关闭时（默认）所有诊断输出与参数拼装都被完全跳过，
 * 每 tick 路径上只剩一次 {@code volatile} 布尔读取。
 * <p>
 * 使用约定：
 * <ul>
 *   <li>高频（每 tick）路径：先用 {@link #isEnabled()} 守卫整段逻辑，再调用输出方法；</li>
 *   <li>消息构造开销较大时：使用 {@link #info(Supplier)} 懒构造版本。</li>
 * </ul>
 * P3 配置化：开关已接入 NeoForge Config 服务端段 {@code debug.enabled}（由
 * {@code Torcherinoaemod} 在配置加载/重载时调用 {@link #setEnabled} 同步，默认关闭，
 * 与 {@link ConfigDefaults#DEBUG_ENABLED} 一致）；采样间隔默认值亦收口到 ConfigDefaults。
 */
public final class DebugLog {

    // 诊断日志总开关：默认关闭（生产环境零开销）。
    private static volatile boolean enabled = ConfigDefaults.DEBUG_ENABLED;

    /**
     * 默认采样间隔（tick）：诊断日志的节流周期，与原先硬编码的 20 tick 保持一致
     * （配置默认值；高频路径的实际节流间隔由调用方读取 {@code RuntimeConfig.debugSampleIntervalTicks()}）。
     */
    public static final int DEFAULT_SAMPLE_INTERVAL = ConfigDefaults.DEBUG_SAMPLE_INTERVAL_TICKS;

    private DebugLog() {
    }

    /**
     * 诊断日志是否开启。位于每 tick 路径上，仅一次 volatile 读取。
     */
    public static boolean isEnabled() {
        return enabled;
    }

    /**
     * 开启或关闭诊断日志。切换本身使用普通 INFO 输出（属管理员主动操作，不受开关影响）。
     */
    public static void setEnabled(boolean enabled) {
        DebugLog.enabled = enabled;
        Torcherinoaemod.LOGGER.info("[诊断日志] 已{}", enabled ? "开启" : "关闭");
    }

    /**
     * 输出诊断信息（INFO 级）。开关关闭时直接返回，不做任何参数拼装。
     */
    public static void info(String format, Object... args) {
        if (enabled) {
            Torcherinoaemod.LOGGER.info(format, args);
        }
    }

    /**
     * 输出诊断信息（INFO 级）的懒构造版本：消息构造开销较大时使用。
     */
    public static void info(Supplier<String> messageSupplier) {
        if (enabled) {
            Torcherinoaemod.LOGGER.info(messageSupplier.get());
        }
    }

    /**
     * 输出诊断信息（DEBUG 级）。开关关闭时直接返回。
     */
    public static void debug(String format, Object... args) {
        if (enabled) {
            Torcherinoaemod.LOGGER.debug(format, args);
        }
    }

    /**
     * 输出警告（WARN 级）：<b>不受开关影响</b>。
     * <p>
     * 警告用于「存档数据损坏、状态写入失败」等必须让管理员察觉的异常，
     * 这类信息不应因诊断开关关闭而被隐藏。
     */
    public static void warn(String format, Object... args) {
        Torcherinoaemod.LOGGER.warn(format, args);
    }
}
