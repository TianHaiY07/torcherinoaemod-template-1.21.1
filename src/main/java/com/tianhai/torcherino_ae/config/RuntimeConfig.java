package com.tianhai.torcherino_ae.config;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 运行时配置快照（配置加载/重载的生效出口）。
 * <p>
 * 逻辑层（core/network/方块实体/GUI）一律从这里读取生效值，而不是直接读取
 * NeoForge 的 {@link ModConfigSpec}：这样每 tick 高频路径只做一次 volatile 原语读，
 * 类型表等「启动期解析」结果也只需在刷新时解析一次。字段初始值取自默认值
 * （即配置尚未刷新时也等于默认行为）。
 * <p>
 * 刷新入口由 {@code Torcherinoaemod} 在 {@code ModConfigEvent.Loading/Reloading}
 * 事件处理器中调用（registerConfig 只是排队，构造器内不可读 ConfigValue；
 * 配置加载前本类字段保持 ConfigDefaults 的默认值）。
 */
public final class RuntimeConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("torcherino-ae-config");

    private RuntimeConfig() {
    }

    // ============================== 加速器 ==============================

    private static volatile int accelBaseMultiplier = ConfigDefaults.ACCEL_BASE_MULTIPLIER;

    /** I/II/III 档倍率（长度恒为 3，配置长度不足时按默认补齐）。 */
    private static volatile int[] accelCardFactors = ConfigDefaults.ACCEL_CARD_FACTORS.stream()
            .mapToInt(Integer::intValue).toArray();

    private static volatile int accelMaxMultiplierCap = ConfigDefaults.ACCEL_MAX_MULTIPLIER_CAP;

    public static int accelBaseMultiplier() {
        return accelBaseMultiplier;
    }

    /** 指定档位（0=I, 1=II, 2=III）的升级卡倍增系数。 */
    public static int accelCardFactor(int index) {
        return accelCardFactors[index];
    }

    /** 最高倍数硬上限；返回 -1 表示不限制。 */
    public static int accelMaxMultiplierCap() {
        return accelMaxMultiplierCap;
    }

    // ============================== 预算 / 能耗 ==============================

    private static volatile int budgetTickCallsPerSource = ConfigDefaults.BUDGET_TICK_CALLS_PER_SOURCE;
    private static volatile double powerPerTick = ConfigDefaults.POWER_PER_TICK;
    private static volatile double powerPerUpgradeCard = ConfigDefaults.POWER_PER_UPGRADE_CARD;
    private static volatile double powerPerAcceleratedDevice = ConfigDefaults.POWER_PER_ACCELERATED_DEVICE;
    private static volatile double powerBufferFraction = ConfigDefaults.POWER_BUFFER_FRACTION;

    /** 每 tick 加速预算；返回 -1 表示不限制。 */
    public static int budgetTickCallsPerSource() {
        return budgetTickCallsPerSource;
    }

    // ============================== TPS 自适应节流 ==============================

    private static volatile boolean adaptiveEnabled = ConfigDefaults.ADAPTIVE_ENABLED;
    private static volatile int adaptiveFloorCalls = ConfigDefaults.ADAPTIVE_FLOOR_CALLS;
    private static volatile double adaptiveTightenMs = ConfigDefaults.ADAPTIVE_TIGHTEN_MS;
    private static volatile double adaptiveRelaxMs = ConfigDefaults.ADAPTIVE_RELAX_MS;

    /** TPS 自适应节流总开关。 */
    public static boolean adaptiveEnabled() {
        return adaptiveEnabled;
    }

    /** 收紧时每源每 tick 的最低调用预算。 */
    public static int adaptiveFloorCalls() {
        return adaptiveFloorCalls;
    }

    /** 进入收紧的单 tick 计算耗时阈值（毫秒）。 */
    public static double adaptiveTightenMs() {
        return adaptiveTightenMs;
    }

    /** 退出收紧的回落阈值（毫秒）。 */
    public static double adaptiveRelaxMs() {
        return adaptiveRelaxMs;
    }

    public static double powerPerTick() {
        return powerPerTick;
    }

    public static double powerPerUpgradeCard() {
        return powerPerUpgradeCard;
    }

    public static double powerPerAcceleratedDevice() {
        return powerPerAcceleratedDevice;
    }

    public static double powerBufferFraction() {
        return powerBufferFraction;
    }

    // ============================== 缓存 / 菜单 ==============================

    private static volatile int cacheRebuildIntervalTicks = ConfigDefaults.CACHE_REBUILD_INTERVAL_TICKS;
    private static volatile int menuDeviceListRefreshTicks = ConfigDefaults.MENU_DEVICE_LIST_REFRESH_TICKS;

    public static int cacheRebuildIntervalTicks() {
        return cacheRebuildIntervalTicks;
    }

    public static int menuDeviceListRefreshTicks() {
        return menuDeviceListRefreshTicks;
    }

    // ============================== 网格类型表（已解析） ==============================

    private static volatile Set<Class<?>> acceleratableBlacklist = Set.of();
    private static volatile Set<Class<?>> craftingMachineExtras = Set.of();

    public static Set<Class<?>> acceleratableBlacklist() {
        return acceleratableBlacklist;
    }

    public static Set<Class<?>> craftingMachineExtras() {
        return craftingMachineExtras;
    }

    // ============================== 智能加速 / 诊断 ==============================

    private static volatile boolean smartAccelerateEnabled = ConfigDefaults.SMART_ACCELERATE_ENABLED;
    private static volatile SmartAccelerateScope smartAccelerateScope = ConfigDefaults.SMART_ACCELERATE_SCOPE;
    private static volatile boolean debugEnabled = ConfigDefaults.DEBUG_ENABLED;
    private static volatile int debugSampleIntervalTicks = ConfigDefaults.DEBUG_SAMPLE_INTERVAL_TICKS;

    public static boolean smartAccelerateEnabled() {
        return smartAccelerateEnabled;
    }

    /** 智能加速作用域（见 {@link SmartAccelerateScope}）。 */
    public static SmartAccelerateScope smartAccelerateScope() {
        return smartAccelerateScope;
    }

    /** 诊断日志总开关（DebugLog 的实际 enable 状态由主类在刷新时同步）。 */
    public static boolean debugEnabled() {
        return debugEnabled;
    }

    public static int debugSampleIntervalTicks() {
        return debugSampleIntervalTicks;
    }

    // ============================== 加速火把 ==============================

    private static volatile int torcherinoMaxSpeed = ConfigDefaults.TORCHERINO_MAX_SPEED;
    private static volatile int torcherinoMaxXzRange = ConfigDefaults.TORCHERINO_MAX_XZ_RANGE;
    private static volatile int torcherinoMaxYRange = ConfigDefaults.TORCHERINO_MAX_Y_RANGE;

    public static int torcherinoMaxSpeed() {
        return torcherinoMaxSpeed;
    }

    public static int torcherinoMaxXzRange() {
        return torcherinoMaxXzRange;
    }

    public static int torcherinoMaxYRange() {
        return torcherinoMaxYRange;
    }

    // ============================== 客户端 ==============================

    private static volatile boolean clientCacheFilteredList = ConfigDefaults.CLIENT_CACHE_FILTERED_LIST;
    private static volatile boolean clientRenderBracketHighlight = ConfigDefaults.CLIENT_RENDER_BRACKET_HIGHLIGHT;

    public static boolean clientCacheFilteredList() {
        return clientCacheFilteredList;
    }

    public static boolean clientRenderBracketHighlight() {
        return clientRenderBracketHighlight;
    }

    // ============================== 刷新入口 ==============================

    /** 服务端段加载/重载后调用：刷新全部服务端快照。 */
    public static void refreshServer(ModConfig.Server server) {
        accelBaseMultiplier = server.acceleratorBaseMultiplier.get();
        accelMaxMultiplierCap = server.acceleratorMaxMultiplierCap.get();
        budgetTickCallsPerSource = server.budgetTickCallsPerSource.get();
        adaptiveEnabled = server.adaptiveEnabled.get();
        adaptiveFloorCalls = Math.max(1, server.adaptiveFloorCalls.get());
        adaptiveTightenMs = server.adaptiveTightenMs.get();
        // 退出阈值钳制到不超过进入阈值，避免「收紧后永不恢复」或阈值倒挂导致逻辑异常。
        adaptiveRelaxMs = Math.min(server.adaptiveRelaxMs.get(), adaptiveTightenMs);
        powerPerTick = server.powerPerTick.get();
        powerPerUpgradeCard = server.powerPerUpgradeCard.get();
        powerPerAcceleratedDevice = server.powerPerAcceleratedDevice.get();
        powerBufferFraction = server.powerBufferFraction.get();
        cacheRebuildIntervalTicks = Math.max(1, server.cacheRebuildIntervalTicks.get());
        menuDeviceListRefreshTicks = Math.max(5, server.menuDeviceListRefreshTicks.get());
        smartAccelerateEnabled = server.craftingSmartAccelerateEnabled.get();
        smartAccelerateScope = server.craftingSmartAccelerateScope.get();
        debugEnabled = server.debugEnabled.get();
        debugSampleIntervalTicks = Math.max(1, server.debugSampleIntervalTicks.get());
        torcherinoMaxSpeed = Math.max(1, server.torcherinoMaxSpeed.get());
        torcherinoMaxXzRange = Math.max(0, server.torcherinoMaxXzRange.get());
        torcherinoMaxYRange = Math.max(0, server.torcherinoMaxYRange.get());
        accelCardFactors = readFactors(server.acceleratorCardMultipliers.get());
        acceleratableBlacklist = resolveTypeSet(server.gridAcceleratableBlacklist.get(), "acceleratableBlacklist");
        craftingMachineExtras = resolveTypeSet(server.gridCraftingMachineExtraTypes.get(), "craftingMachineExtraTypes");
    }

    /** 客户端段加载/重载后调用。 */
    public static void refreshClient(ModConfig.Client client) {
        clientCacheFilteredList = client.cacheFilteredList.get();
        clientRenderBracketHighlight = client.renderBracketHighlight.get();
    }

    /**
     * 解析三档升级卡倍率：长度不足的档位回退默认系数（仅告警，不抛异常），
     * 多余档位忽略。
     */
    private static int[] readFactors(List<? extends Integer> configured) {
        int[] factors = ConfigDefaults.ACCEL_CARD_FACTORS.stream().mapToInt(Integer::intValue).toArray();
        if (configured == null || configured.size() != factors.length) {
            LOGGER.warn("[配置] accelerator.cardMultipliers 期望长度 {}，实际 {}；不足的档位已按默认补全",
                    factors.length, configured == null ? 0 : configured.size());
        }
        for (int i = 0; i < factors.length && configured != null && i < configured.size(); i++) {
            Integer value = configured.get(i);
            if (value != null && value > 0) {
                factors[i] = value;
            }
        }
        return factors;
    }

    /**
     * 把配置里的字符串类型表解析为类集合。
     * <p>
     * 支持「全限定类名」；不含包名或无法加载的条目只告警并跳过（不影响其它条目），
     * 避免个别拼写错误导致整表失效。
     */
    private static Set<Class<?>> resolveTypeSet(List<? extends String> entries, String key) {
        if (entries == null || entries.isEmpty()) {
            return Set.of();
        }
        Set<Class<?>> result = new LinkedHashSet<>();
        for (String raw : entries) {
            String name = raw.trim();
            if (name.isEmpty()) {
                continue;
            }
            if (!name.contains(".")) {
                LOGGER.warn("[配置] grid.{} 的条目 \"{}\" 不是全限定类名，已跳过（示例：appeng.parts.storagebus.StorageBusPart）",
                        key, raw);
                continue;
            }
            try {
                result.add(Class.forName(name));
            } catch (ClassNotFoundException e) {
                LOGGER.warn("[配置] grid.{} 的条目 \"{}\" 未找到对应类，已跳过", key, raw);
            }
        }
        return Set.copyOf(result);
    }
}
