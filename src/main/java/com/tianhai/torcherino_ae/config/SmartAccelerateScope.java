package com.tianhai.torcherino_ae.config;

/**
 * 智能加速作用域：决定「选中合成 CPU 且其正在合成」时，加速器所在网格内哪些设备被纳入联动加速。
 * <p>
 * {@code ALL_ACCELERATABLE} 作用于「经过 {@code DeviceScanner.isAcceleratableNode} 判定可加速、
 * 且不在 {@code grid.acceleratableBlacklist} 内」的全部设备——对任意第三方 AE 工作机器零配置生效，
 * 无需它们实现 AE2 的 {@code ICraftingMachine}/{@code CRAFTING_MACHINE} 或注册 {@code ICraftingProvider}；
 * 真正不需要被加速的基础设施仍由黑名单排除。
 */
public enum SmartAccelerateScope {

    /** 仅联动「真正的合成机器 / 合成提供者」（依赖 AE2 接口 / 能力 / {@code grid.craftingMachineExtraTypes} 类型表识别）。 */
    CRAFTING_MACHINES,

    /** 联动网格内全部可加速设备（非黑名单基础设施），零配置兼容任意第三方 AE 工作机器。 */
    ALL_ACCELERATABLE
}
