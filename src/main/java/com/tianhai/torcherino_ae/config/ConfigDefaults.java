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

    /** I/II/III 三种升级卡的倍增系数（顺序对应升级卡种类）。 */
    public static final List<Integer> ACCEL_CARD_FACTORS = List.of(2, 4, 8);

    /** 复合后的最高倍数硬上限：-1 表示不限制（默认不限制）。 */
    public static final int ACCEL_MAX_MULTIPLIER_CAP = -1;

    // ============================== 单 tick 预算 ==============================

    /** 每台加速器每 tick 可执行的额外加速调用预算：-1 表示不限制（默认不限制）。 */
    public static final int BUDGET_TICK_CALLS_PER_SOURCE = -1;

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

    // ============================== 客户端 ==============================

    /** 设备列表行过滤缓存开关（行文本稳定时跳过重建，见 DeviceListWidget）。 */
    public static final boolean CLIENT_CACHE_FILTERED_LIST = true;

    /** 配置卡高亮包围盒（方框高亮）渲染总开关。 */
    public static final boolean CLIENT_RENDER_BRACKET_HIGHLIGHT = true;
}
