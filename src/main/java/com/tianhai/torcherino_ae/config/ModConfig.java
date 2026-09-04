package com.tianhai.torcherino_ae.config;

import java.util.List;
import java.util.function.Predicate;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * NeoForge 配置规格（ModConfigSpec）。
 * <p>
 * 分为服务端段（{@link Server}，含加速器/能耗/缓存/网格类型表等全部可调数值）与
 * 客户端段（{@link Client}，渲染与列表缓存开关）。默认值一律取自 {@link ConfigDefaults}，
 * 实际生效值经 {@link RuntimeConfig} 的快照缓存被各逻辑层读取（加载/重载事件刷新）。
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
        public final ModConfigSpec.DoubleValue acceleratorCardDiminishing;
        // ---- 预算 ----
        public final ModConfigSpec.IntValue budgetTickCallsPerSource;
        // ---- TPS 自适应节流 ----
        public final ModConfigSpec.BooleanValue adaptiveEnabled;
        public final ModConfigSpec.IntValue adaptiveFloorCalls;
        public final ModConfigSpec.DoubleValue adaptiveTightenMs;
        public final ModConfigSpec.DoubleValue adaptiveRelaxMs;
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
        public final ModConfigSpec.EnumValue<SmartAccelerateScope> craftingSmartAccelerateScope;
        public final ModConfigSpec.BooleanValue debugEnabled;
        public final ModConfigSpec.IntValue debugSampleIntervalTicks;
        // ---- 加速火把 ----
        public final ModConfigSpec.IntValue torcherinoMaxSpeed;
        public final ModConfigSpec.IntValue torcherinoMaxXzRange;
        public final ModConfigSpec.IntValue torcherinoMaxYRange;

        Server(ModConfigSpec.Builder builder) {
            // 加速器
            builder.comment(" 加速器基础数值：默认值即模组默认行为，修改经配置重载后对新生效的网格生效。")
                    .push("accelerator");
            this.acceleratorBaseMultiplier = builder
                    .comment(" 无升级卡时的基础加速倍率（升级卡在此基础上放大）。")
                    .defineInRange("baseMultiplier", ConfigDefaults.ACCEL_BASE_MULTIPLIER, 1, 1_000_000);
            Predicate<Object> positiveInt = o -> o instanceof Integer i && i > 0;
            this.acceleratorCardMultipliers = builder
                    .comment(" I/II/III 三种升级卡的标称倍增系数（作为各档「第一张」的放大倍率），顺序对应，")
                    .comment(" 长度保持 3（不足时按默认补齐）。同档重复堆叠的递减效果见 cardDiminishing。")
                    .defineListAllowEmpty("cardMultipliers", ConfigDefaults.ACCEL_CARD_FACTORS, positiveInt);
            this.acceleratorMaxMultiplierCap = builder
                    .comment(" 复合后的最高倍数硬上限；-1 表示不限制。")
                    .defineInRange("maxMultiplierCap", ConfigDefaults.ACCEL_MAX_MULTIPLIER_CAP, -1, 1_000_000);
            this.acceleratorCardDiminishing = builder
                    .comment(" 同档升级卡重复堆叠时的「边际收益保留比」（0~1）：同一档的第 1 张按")
                    .comment(" cardMultipliers 标称系数全价放大，之后每多插一张该档卡，其实际放大倍率按")
                    .comment(" 「下一张 = 1 + (上一张 - 1) × 保留比」向 1 收敛，抑制同档堆叠的指数爆炸。")
                    .comment(" 1.0 表示每张都按同系数放大，完全还原旧的指数累乘；0.0 表示第二张起不再额外放大。")
                    .comment(" 默认 0.45：单卡与异档混插数值不变，满配 4 张 III 卡约 526 倍（旧公式为 16384 倍）。")
                    .defineInRange("cardDiminishing", ConfigDefaults.ACCEL_DIMINISHING_RETENTION, 0.0, 1.0);
            builder.pop();

            // 单 tick 预算
            builder.comment(" 单 tick 加速预算：控制每台机器每 tick 对网格节点的额外调用次数，防止极端高倍率下过于吃 CPU。")
                    .push("budget");
            this.budgetTickCallsPerSource = builder
                    .comment(" 每台加速器每 tick 可执行的额外加速调用预算；-1 表示不限制。")
                    .comment(" 健康负载下这是硬顶；TPS 告急时经 adaptive 段会自动在它基础上进一步收紧。")
                    .defineInRange("tickCallsPerSource", ConfigDefaults.BUDGET_TICK_CALLS_PER_SOURCE, -1, 1_000_000);
            builder.pop();

            // TPS 自适应节流
            builder.comment(" TPS 自适应节流：服务端单 tick 计算耗时（不含补帧 sleep）达到阈值时，")
                    .comment(" 自动把每源每 tick 的调用预算压下来，防止极端高倍率加速拖垮 TPS。")
                    .comment(" 收紧是【分级递降】的：预算先压到 floorCallsPerSource，若单 tick 仍逼近")
                    .comment(" 50ms（20 TPS）硬限，则每个 tick 再逐档减半（…→64→32→…→1，最深几乎")
                    .comment(" 停加速），直到 TPS 回落到健康区；回落后再逐档放开。")
                    .comment(" 健康负载下完全不干预，预算与 budget 段配置一致（含 -1 不限）。")
                    .push("adaptive");
            this.adaptiveEnabled = builder
                    .comment(" TPS 自适应节流总开关（默认开启）。开启时只在高倍率让单 tick 逼近 50ms")
                    .comment(" （20 TPS）硬限时削峰，不会限制机器负载健康时的正常加速；")
                    .comment(" 设为 false 完全关闭本机制：预算完全按 budget.tickCallsPerSource 执行")
                    .comment(" （-1 即不限，回到旧行为），热重载即时生效。")
                    .define("enabled", ConfigDefaults.ADAPTIVE_ENABLED);
            this.adaptiveFloorCalls = builder
                    .comment(" 收紧起点的调用预算（第一档）。若压到该值后单 tick 仍逼近 50ms 硬限，")
                    .comment(" 会自动逐档减半加深（最终到 1，即几乎停止加速），无需手工调低本项。")
                    .defineInRange("floorCallsPerSource", ConfigDefaults.ADAPTIVE_FLOOR_CALLS, 1, 1_000_000);
            this.adaptiveTightenMs = builder
                    .comment(" 进入收紧的单 tick 计算耗时阈值（毫秒）。50ms=20TPS 硬限，留出余量建议 40~45。")
                    .defineInRange("tightenMs", ConfigDefaults.ADAPTIVE_TIGHTEN_MS, 5.0, 50.0);
            this.adaptiveRelaxMs = builder
                    .comment(" 退出收紧的回落阈值（毫秒），应小于 tightenMs（过大时按 tightenMs 钳制），避免负载在阈值附近抖动。")
                    .defineInRange("relaxMs", ConfigDefaults.ADAPTIVE_RELAX_MS, 1.0, 50.0);
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
            this.craftingSmartAccelerateScope = builder
                    .comment(" 智能加速作用域：CRAFTING_MACHINES 仅联动合成机器（依赖 AE2 接口/能力/类型表识别）；"
                            + " ALL_ACCELERATABLE 联动网格内全部可加速设备（零配置兼容任意第三方 AE 工作机器）。")
                    .defineEnum("smartAccelerateScope", ConfigDefaults.SMART_ACCELERATE_SCOPE);
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
