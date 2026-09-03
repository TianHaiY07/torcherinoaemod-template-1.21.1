package com.tianhai.torcherino_ae.util;

import java.util.function.Supplier;

import com.tianhai.torcherino_ae.Torcherinoaemod;
import com.tianhai.torcherino_ae.config.ConfigDefaults;

/**
 * 诊断日志门面。
 * <p>
 * 集中管理模组的调试日志输出，统一开关默认关闭。关闭时所有诊断输出与消息
 * 参数拼装都被完全跳过，每 tick 路径上只剩一次 {@code volatile} 布尔读取，
 * 生产环境零开销。排查问题时经游戏内配置（NeoForge Config 服务端段
 * {@code debug.enabled}）打开，或在代码中调用 {@link #setEnabled}。
 * <p>
 * 使用约定：
 * <ul>
 *   <li>高频（每 tick）路径：先用 {@link #isEnabled()} 守卫整段逻辑，再调用输出方法；</li>
 *   <li>消息构造开销较大时：使用 {@link #info(Supplier)} 懒构造版本；</li>
 *   <li>存档异常等必须让管理员察觉的信息使用 {@link #warn}，不受开关影响。</li>
 * </ul>
 * 总开关默认值与采样间隔等常量的定义集中在 {@link ConfigDefaults}。
 */
public final class DebugLog {

    // 诊断日志总开关：默认关闭（生产环境零开销）。
    private static volatile boolean enabled = ConfigDefaults.DEBUG_ENABLED;

    /**
     * 默认采样间隔（tick）：诊断日志的节流周期，值取自配置默认
     * （{@link ConfigDefaults#DEBUG_SAMPLE_INTERVAL_TICKS}）；高频路径的实际节流间隔由
     * 调用方读取 {@code RuntimeConfig.debugSampleIntervalTicks()}。
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
