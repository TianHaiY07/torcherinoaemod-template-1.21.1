package com.tianhai.torcherino_ae.config;

import java.util.List;

/**
 * 全部配置项的默认值集中地（单一事实来源）。
 * <p>
 * 所有「可调数值」的默认值都以本类为唯一来源：NeoForge 配置规格（{@link ModConfig}）
 * 的定义、运行时快照（{@link RuntimeConfig}）的启动兜底、以及纯逻辑公式（倍率/能耗）
 * 的测试引用都从这里取值，代码中不散落重复的数值字面量。
 * <p>
 * 默认值即「模组默认行为」：仅当玩家/服主通过配置文件显式修改时才发生数值变化，
 * 修改本类应保持对默认行为零影响。
 */
public final class ConfigDefaults {

    private ConfigDefaults() {
    }

    // ============================== 加速器 ==============================

    /** 基础加速倍数：未安装任何升级卡时的加速倍率。 */
    public static final int ACCEL_BASE_MULTIPLIER = 4;

    /** I/II/III 三种升级卡的倍增系数（顺序对应升级卡种类，作为各档「第一张」的标称放大倍率）。 */
    public static final List<Integer> ACCEL_CARD_FACTORS = List.of(2, 4, 8);

    /**
     * 同档升级卡重复堆叠时的「边际收益保留比」（0~1）。
     * 同一档的第 1 张按 {@link #ACCEL_CARD_FACTORS} 标称系数全价放大；之后每多插一张该档卡，
     * 其实际放大倍率按 {@code 下一张 = 1 + (上一张 - 1) × 保留比} 向 1 收敛（抑制指数爆炸）。
     * 1.0 表示每张都按同系数放大（退化为旧的指数累乘）；越低同档堆叠收益递减越快。
     * 默认 0.45：单卡与异档混插数值不变，满配 4 张 III 卡约 526 倍（旧公式为 16384 倍）。
     */
    public static final double ACCEL_DIMINISHING_RETENTION = 0.45;

    /** 复合后的最高倍数硬上限：-1 表示不限制（默认不限制）。 */
    public static final int ACCEL_MAX_MULTIPLIER_CAP = -1;

    // ============================== 单 tick 预算 ==============================

    /** 每台加速器每 tick 可执行的额外加速调用预算：-1 表示不限制（默认不限制）。 */
    public static final int BUDGET_TICK_CALLS_PER_SOURCE = -1;

    // ============================== TPS 自适应节流 ==============================

    /**
     * TPS 自适应节流总开关：单 tick 计算耗时逼近 50ms 硬限时自动压紧每源预算，
     * 负载健康时不干预。默认开启——它不会限制「机器够快」时的正常加速，
     * 只在高倍率拖垮 TPS 时削峰，是极端倍率下（默认无上限）的性能保护伞。
     */
    public static final boolean ADAPTIVE_ENABLED = true;

    /** 收紧起点的调用预算（第一档）；仍逼近 50ms 硬限时逐档减半加深（见 AdaptiveThrottle）。 */
    public static final int ADAPTIVE_FLOOR_CALLS = 256;

    /** 单 tick 计算耗时（毫秒）达到该阈值即进入收紧（留出到 50ms 硬限的余量）。 */
    public static final double ADAPTIVE_TIGHTEN_MS = 45.0;

    /** 单 tick 计算耗时（毫秒）回落到该值以下才退出收紧（滞回防抖，须小于收紧阈值）。 */
    public static final double ADAPTIVE_RELAX_MS = 35.0;

    // ============================== 源级加速耗时调控 ==============================

    /**
     * 每个加速源（台加速器 / 火把）每 tick 允许贡献的加速耗时上限（毫秒）。
     * 「按加速器贡献的超额耗时」而非整 tick 计量：只当加速器自己挤占主线程到这个
     * 水位时，才把实际加速倍率往下压；别处负载不会干扰判定。
     */
    public static final double RATE_SOURCE_MS_LIMIT = 15.0;

    /** 本源加速耗时的 EMA 平滑系数（越大响应越快，过小会延迟下压）。默认 0.25 约合 4 tick 时间常数。 */
    public static final double RATE_EMA_ALPHA = 0.25;

    /** 收紧判定：本 tick 加速耗时 EMA ≥ {@code sourceMsLimit × tightenRatio} 时把实际倍率下压。 */
    public static final double RATE_TIGHTEN_RATIO = 1.0;

    /** 放松判定：本 tick 加速耗时 EMA &lt; {@code sourceMsLimit × relaxRatio} 时把实际倍率逐 tick 回升；滞回防抖。 */
    public static final double RATE_RELAX_RATIO = 0.7;

    // ============================== 能耗模型 ==============================

    /** 基础能耗（AE/t）：加速器每 tick 固定抽取。 */
    public static final double POWER_PER_TICK = 1.0;

    /** 每张升级卡额外能耗（AE/t）。 */
    public static final double POWER_PER_UPGRADE_CARD = 0.5;

    /** 每台被加速设备额外能耗（AE/t）。 */
    public static final double POWER_PER_ACCELERATED_DEVICE = 0.5;

    /** 能量缓冲占比：低于该比例时停止加速（停机保缓冲）。 */
    public static final double POWER_BUFFER_FRACTION = 0.9;

    // ============================== 目标缓存 / 菜单 ==============================

    /** 目标缓存重建周期（tick）。 */
    public static final int CACHE_REBUILD_INTERVAL_TICKS = 20;

    /** GUI 设备列表在菜单上的重新采集周期（tick）。 */
    public static final int MENU_DEVICE_LIST_REFRESH_TICKS = 20;

    // ============================== 网格类型表 ==============================

    /**
     * 不可加速基础设施（黑名单）默认清单：全限定类名。
     * <p>
     * 运行期解析为类集合后用于排除判断，可在配置文件中增删；
     * 解析失败的条目仅告警跳过。默认覆盖「存储总线 / P2P 隧道 / 能量元件」
     * 三类没有实际工作可加速的网络基础设施。
     */
    public static final List<String> ACCELERATABLE_BLACKLIST = List.of(
            "appeng.parts.storagebus.StorageBusPart",
            "appeng.parts.p2p.P2PTunnelPart",
            "appeng.blockentity.networking.EnergyCellBlockEntity");

    /**
     * 合成执行机器的兜底类型表默认清单：全限定类名（压印机 / 充能器）。
     * <p>
     * 第一优先「实现 ICraftingMachine」、第二优先「注册 CRAFTING_MACHINE 能力」，
     * 均无法覆盖时才回落到本表兜底（见 CraftingSupport.isCraftingMachineType）。
     */
    public static final List<String> CRAFTING_MACHINE_EXTRA_TYPES = List.of(
            "appeng.blockentity.misc.InscriberBlockEntity",
            "appeng.blockentity.misc.ChargerBlockEntity");

    // ============================== 智能加速 / 诊断 ==============================

    /** 智能加速总开关：关闭后选中 CPU 不再联动加速合成机器。 */
    public static final boolean SMART_ACCELERATE_ENABLED = true;

    /**
     * 智能加速作用域（见 {@link SmartAccelerateScope}）。
     * 默认 {@link SmartAccelerateScope#ALL_ACCELERATABLE}：联动网格内全部可加速设备，
     * 从而对任意第三方 AE 工作机器零配置生效。
     */
    public static final SmartAccelerateScope SMART_ACCELERATE_SCOPE = SmartAccelerateScope.ALL_ACCELERATABLE;

    /** 诊断日志总开关（对应 DebugLog）。 */
    public static final boolean DEBUG_ENABLED = false;

    /** 诊断采样间隔（tick）：每 N tick 输出一次加速脉冲诊断。 */
    public static final int DEBUG_SAMPLE_INTERVAL_TICKS = 20;

    // ============================== 加速火把 ==============================

    /** 火把最大加速倍数。 */
    public static final int TORCHERINO_MAX_SPEED = 4;

    /** 火把 X/Z 轴向最大范围半径。 */
    public static final int TORCHERINO_MAX_XZ_RANGE = 8;

    /** 火把 Y 轴向最大范围半径。 */
    public static final int TORCHERINO_MAX_Y_RANGE = 4;

    /** 火把影响范围「分片扫描」窗口 / 密集发现周期（tick）：一整圈范围扫描分摊到这么多 tick 内完成。 */
    public static final int TORCHERINO_SCAN_INTERVAL_TICKS = 20;

    /** 火把影响范围扫描的「退避上限」（tick）：范围稳定无变化时逐轮把扫描周期翻倍退避到该值。 */
    public static final int TORCHERINO_SCAN_BACKOFF_MAX_TICKS = 200;

    /** 火把影响范围每次扫描在最坏情况下单 tick 会 touch 的单元格上限：范围极大时把扫描窗口（tick 数）
     * 线性拉长以把单 tick 扫描成本钳制在该值内（防止超大范围单 tick 全量遍历造成主线程尖峰）。 */
    public static final int TORCHERINO_SCAN_MAX_CELLS_PER_TICK = 512;

    /** 火把随机 tick 加速的倍率系数：越大随机 tick 触发越频繁（分摊到多 tick，单格单 tick 至多 1 次）。 */
    public static final int TORCHERINO_RANDOM_TICK_RATE = 4;

    // ============================== 客户端 ==============================

    /** 设备列表行过滤缓存开关（行文本稳定时跳过重建，见 DeviceListWidget）。 */
    public static final boolean CLIENT_CACHE_FILTERED_LIST = true;

    /** 配置卡高亮包围盒（方框高亮）渲染总开关。 */
    public static final boolean CLIENT_RENDER_BRACKET_HIGHLIGHT = true;
}
