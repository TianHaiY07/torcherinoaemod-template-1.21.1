package com.tianhai.torcherino_ae.config;

import java.util.List;
import java.util.function.Predicate;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * NeoForge 配置规格（ModConfigSpec）。
 * <p>
 * 分为服务端段（{@link Server}，含加速器/能耗/缓存/网格类型表等全部可调数值）与
 * 客户端段（{@link Client}，渲染与列表缓存开关）。默认值一律取自 {@link ConfigDefaults}，
 * 单一事实来源，保持「默认值 = 现网行为基线」。实际生效值经 {@link RuntimeConfig}
 * 的快照缓存被各逻辑层读取（加载/重载事件刷新）。
 * <p>
 * 配置文件的即时读取顺序：`registerConfig`（构造器，仅排队）→ FML 构造完成后统一加载
 * 配置文件 → `ModConfigEvent.Loading` / `Reloading`（每次加载/重载）把当前值刷入
 * RuntimeConfig 快照，保证启动与热更新都生效。
 */
public final class ModConfig {

    private ModConfig() {
    }

    // ============================== 服务端段 ==============================

    /**
     * 服务端配置段：仅定义规格字段，取值范围在 defineInRange 里约束，
     * 运行期取值一律经 RuntimeConfig 快照（本段字段不直接暴露给逻辑层）。
     */
    public static final class Server {
        // ---- 加速器 ----
        public final ModConfigSpec.IntValue acceleratorBaseMultiplier;
        public final ModConfigSpec.ConfigValue<List<? extends Integer>> acceleratorCardMultipliers;
        public final ModConfigSpec.IntValue acceleratorMaxMultiplierCap;
        // ---- 预算 ----
        public final ModConfigSpec.IntValue budgetTickCallsPerSource;
        // ---- 能耗 ----
        public final ModConfigSpec.DoubleValue powerPerTick;
        public final ModConfigSpec.DoubleValue powerPerUpgradeCard;
        public final ModConfigSpec.DoubleValue powerPerAcceleratedDevice;
        public final ModConfigSpec.DoubleValue powerBufferFraction;
        // ---- 缓存 / 菜单 ----
        public final ModConfigSpec.IntValue cacheRebuildIntervalTicks;
        public final ModConfigSpec.IntValue menuDeviceListRefreshTicks;
        // ---- 网格类型表 ----
        // defineListAllowEmpty 返回 ConfigValue<List<? extends V>>，故用通配符上界承接。
        public final ModConfigSpec.ConfigValue<List<? extends String>> gridAcceleratableBlacklist;
        public final ModConfigSpec.ConfigValue<List<? extends String>> gridCraftingMachineExtraTypes;
        // ---- 智能加速 / 诊断 ----
        public final ModConfigSpec.BooleanValue craftingSmartAccelerateEnabled;
        public final ModConfigSpec.BooleanValue debugEnabled;
        public final ModConfigSpec.IntValue debugSampleIntervalTicks;
        // ---- 加速火把 ----
        public final ModConfigSpec.IntValue torcherinoMaxSpeed;
        public final ModConfigSpec.IntValue torcherinoMaxXzRange;
        public final ModConfigSpec.IntValue torcherinoMaxYRange;

        Server(ModConfigSpec.Builder builder) {
            // 加速器
            builder.comment(" 加速器基础数值：默认值即原版行为基线，修改仅对新建存档/重载后的网格生效。")
                    .push("accelerator");
            this.acceleratorBaseMultiplier = builder
                    .comment(" 无升级卡时的基础加速倍率（升级卡在此基础上累乘）。")
                    .defineInRange("baseMultiplier", ConfigDefaults.ACCEL_BASE_MULTIPLIER, 1, 1_000_000);
            Predicate<Object> positiveInt = o -> o instanceof Integer i && i > 0;
            this.acceleratorCardMultipliers = builder
                    .comment(" I/II/III 三种升级卡的倍增系数，顺序对应，长度保持 3（不足时按默认补齐）。")
                    .defineListAllowEmpty("cardMultipliers", ConfigDefaults.ACCEL_CARD_FACTORS, positiveInt);
            this.acceleratorMaxMultiplierCap = builder
                    .comment(" 复合后的最高倍数硬上限；-1 表示不限制。")
                    .defineInRange("maxMultiplierCap", ConfigDefaults.ACCEL_MAX_MULTIPLIER_CAP, -1, 1_000_000);
            builder.pop();

            // 单 tick 预算
            builder.comment(" 单 tick 加速预算：控制每台机器每 tick 对网格节点的额外调用次数，防止极端高倍率下过于吃 CPU。")
                    .push("budget");
            this.budgetTickCallsPerSource = builder
                    .comment(" 每台加速器每 tick 可执行的额外加速调用预算；-1 表示不限制。")
                    .defineInRange("tickCallsPerSource", ConfigDefaults.BUDGET_TICK_CALLS_PER_SOURCE, -1, 1_000_000);
            builder.pop();

            // 能耗模型
            builder.comment(" AE 能量消耗模型（单位 AE/t）。")
                    .push("power");
            this.powerPerTick = builder
                    .comment(" 基础能耗：加速器每 tick 固定抽取的能量。")
                    .defineInRange("perTick", ConfigDefaults.POWER_PER_TICK, 0.0, 1_000.0);
            this.powerPerUpgradeCard = builder
                    .comment(" 每张升级卡额外能耗。")
                    .defineInRange("perUpgradeCard", ConfigDefaults.POWER_PER_UPGRADE_CARD, 0.0, 1_000.0);
            this.powerPerAcceleratedDevice = builder
                    .comment(" 每台被加速设备额外能耗。")
                    .defineInRange("perAcceleratedDevice", ConfigDefaults.POWER_PER_ACCELERATED_DEVICE, 0.0, 1_000.0);
            this.powerBufferFraction = builder
                    .comment(" 能量缓冲占比（0~1）：低于该比例时停止加速以保住缓冲能量，防止反复停机。")
                    .defineInRange("bufferFraction", ConfigDefaults.POWER_BUFFER_FRACTION, 0.0, 1.0);
            builder.pop();

            // 目标缓存 / 菜单
            builder.comment(" 缓存与列表刷新节奏。")
                    .push("cache");
            this.cacheRebuildIntervalTicks = builder
                    .comment(" 目标缓存重建周期（tick）。")
                    .defineInRange("rebuildIntervalTicks", ConfigDefaults.CACHE_REBUILD_INTERVAL_TICKS, 1, 600);
            builder.pop();
            builder.push("menu");
            this.menuDeviceListRefreshTicks = builder
                    .comment(" GUI 设备列表在菜单上的重新采集周期（tick）。")
                    .defineInRange("deviceListRefreshTicks", ConfigDefaults.MENU_DEVICE_LIST_REFRESH_TICKS, 5, 200);
            builder.pop();

            // 网格类型表
            builder.comment(" 网格设备分类表：条目为「全限定类名」（例如 appeng.parts.storagebus.StorageBusPart），")
                    .comment(" 解析失败的条目仅告警并跳过，不影响其它条目。")
                    .push("grid");
            this.gridAcceleratableBlacklist = builder
                    .comment(" 不可加速基础设施黑名单（清空表示不限制）。")
                    .defineListAllowEmpty("acceleratableBlacklist", ConfigDefaults.ACCELERATABLE_BLACKLIST,
                            o -> o instanceof String);
            this.gridCraftingMachineExtraTypes = builder
                    .comment(" 合成执行机器兜底类型表（用于智能加速，清空表示不启用兜底）。")
                    .defineListAllowEmpty("craftingMachineExtraTypes", ConfigDefaults.CRAFTING_MACHINE_EXTRA_TYPES,
                            o -> o instanceof String);
            builder.pop();

            // 智能加速 / 诊断
            builder.comment(" 智能加速与诊断日志。").push("crafting");
            this.craftingSmartAccelerateEnabled = builder
                    .comment(" 智能加速总开关：关闭后选中合成 CPU 也不会联动加速合成机器。")
                    .define("smartAccelerateEnabled", ConfigDefaults.SMART_ACCELERATE_ENABLED);
            builder.pop();
            builder.comment(" 诊断日志（DebugLog 门面）。").push("debug");
            this.debugEnabled = builder
                    .comment(" 诊断日志总开关。")
                    .define("enabled", ConfigDefaults.DEBUG_ENABLED);
            this.debugSampleIntervalTicks = builder
                    .comment(" 诊断采样间隔（tick）：每 N tick 输出一次加速脉冲诊断。")
                    .defineInRange("sampleIntervalTicks", ConfigDefaults.DEBUG_SAMPLE_INTERVAL_TICKS, 1, 1_200);
            builder.pop();

            // 加速火把
            builder.comment(" AE 加速火把的可调上限（GUI 滑块上限由菜单同步）。")
                    .push("torcherino");
            this.torcherinoMaxSpeed = builder
                    .comment(" 火把最大加速倍数。")
                    .defineInRange("maxSpeed", ConfigDefaults.TORCHERINO_MAX_SPEED, 1, 64);
            this.torcherinoMaxXzRange = builder
                    .comment(" 火把 X/Z 轴向最大范围半径。")
                    .defineInRange("maxXzRange", ConfigDefaults.TORCHERINO_MAX_XZ_RANGE, 0, 64);
            this.torcherinoMaxYRange = builder
                    .comment(" 火把 Y 轴向最大范围半径。")
                    .defineInRange("maxYRange", ConfigDefaults.TORCHERINO_MAX_Y_RANGE, 0, 32);
            builder.pop();
        }
    }

    public static final Server SERVER;
    public static final ModConfigSpec SERVER_SPEC;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        SERVER = new Server(builder);
        SERVER_SPEC = builder.build();
    }

    // ============================== 客户端段 ==============================

    /** 客户端配置段（渲染与列表缓存开关）。 */
    public static final class Client {
        public final ModConfigSpec.BooleanValue cacheFilteredList;
        public final ModConfigSpec.BooleanValue renderBracketHighlight;

        Client(ModConfigSpec.Builder builder) {
            builder.comment(" 客户端渲染与界面开关。").push("client");
            this.cacheFilteredList = builder
                    .comment(" 设备列表行过滤结果缓存：行文本稳定时跳过每帧重建（重启界面生效）。")
                    .define("cacheFilteredList", ConfigDefaults.CLIENT_CACHE_FILTERED_LIST);
            this.renderBracketHighlight = builder
                    .comment(" 配置卡高亮包围盒（方框高亮）渲染总开关。")
                    .define("renderBracketHighlight", ConfigDefaults.CLIENT_RENDER_BRACKET_HIGHLIGHT);
            builder.pop();
        }
    }

    public static final Client CLIENT;
    public static final ModConfigSpec CLIENT_SPEC;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        CLIENT = new Client(builder);
        CLIENT_SPEC = builder.build();
    }
}
