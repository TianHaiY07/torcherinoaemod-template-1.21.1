package com.tianhai.torcherino_ae.common;

import java.util.Set;

import org.jetbrains.annotations.Nullable;

import appeng.api.AECapabilities;
import appeng.api.implementations.blockentities.ICraftingMachine;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IInWorldGridNodeHost;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.ticking.IGridTickable;
import appeng.blockentity.crafting.MolecularAssemblerBlockEntity;
import appeng.blockentity.misc.ChargerBlockEntity;
import appeng.blockentity.misc.InscriberBlockEntity;
import appeng.blockentity.networking.EnergyCellBlockEntity;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import appeng.parts.AEBasePart;
import appeng.parts.p2p.P2PTunnelPart;
import appeng.parts.storagebus.StorageBusPart;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * AE2 网格辅助工具类。
 * <p>
 * 集中管理「加速器」对网格设备的筛选、坐标解析等公共逻辑，
 * 供加速器方块实体（加速脉冲）与菜单（设备列表采集）两处复用，避免重复实现。
 */
public final class AE2GridSupport {

    // 网络基础设施类黑名单：这些设备即使实现了 IGridTickable，也没有实际工作可加速。
    // 未来 AE2 新增基础设施时，只需在此处补充，切勿把黑名单逻辑散落到各处判断。
    private static final Set<Class<?>> NON_ACCELERATABLE = Set.of(
            StorageBusPart.class,
            P2PTunnelPart.class,
            EnergyCellBlockEntity.class);

    // 合成 CPU（Crafting CPU）设备标识前缀。
    // 合成 CPU 是多块结构，不属于 IGridTickable，本身不能被 tickingRequest 加速；
    // 但为了在 UI 中展示、并在选中后对「参与合成的机器」做联动加速，需要给它分配一个
    // 与普通设备不冲突的稳定标识。偏移用「cpu:」前缀，避免与普通坐标标识（纯 long）混淆。
    private static final String CPU_ID_PREFIX = "cpu:";

    // 合成执行机器类型（补充集合）：这些 AE2 机器接收合成任务并实际执行，但它们**未实现**
    // {@link ICraftingMachine} 接口、也未注册 {@code AECapabilities.CRAFTING_MACHINE} 能力
    // （压印机、充能器），因此无法用接口/能力识别，需在此显式登记。
    // 分子装配室及任何实现 ICraftingMachine 的方块（含第三方模组）会被接口判定覆盖，无需登记。
    private static final Set<Class<?>> CRAFTING_MACHINE_TYPES = Set.of(
            InscriberBlockEntity.class,
            ChargerBlockEntity.class);

    private AE2GridSupport() {
    }

    /**
     * 判断网格节点宿主是否为「可加速的机器」。
     * <p>
     * 虽然实现 {@link IGridTickable} 的设备都能被 tick 管理器催促，但存储总线、能量元件、
     * P2P 隧道等网络基础设施没有实际工作可加速，应从可加速设备中排除。
     */
    public static boolean isAcceleratableMachine(@Nullable Object owner) {
        if (owner == null) {
            return false;
        }
        return NON_ACCELERATABLE.stream().noneMatch(type -> type.isInstance(owner));
    }

    /**
     * 解析网格节点宿主的方块坐标（方块实体用自身坐标，部件用其所在线缆/宿主坐标）。
     *
     * @return 能解析出坐标时返回坐标，否则返回 {@code null}
     */
    @Nullable
    public static BlockPos resolveDevicePos(@Nullable Object owner) {
        if (owner instanceof BlockEntity be) {
            return be.getBlockPos();
        }
        if (owner instanceof AEBasePart part) {
            BlockEntity host = part.getBlockEntity();
            return host != null ? host.getBlockPos() : null;
        }
        return null;
    }

    /**
     * 为网格节点宿主生成一个**稳定且可持久化**的设备标识。
     * <p>
     * 方块实体直接用坐标（世界内唯一）；部件用「坐标|朝向」，从而把挂在同一线缆坐标上、
     * 不同朝向的多个可加速部件区分开，避免它们共用同一坐标导致选中一个、另一个也跟着变。
     * <p>
     * 该标识作为加速器选中设备集合（{@code acceleratedDevices}）、每设备独立倍数表
     * 以及 GUI 点击载荷的身份键，贯穿 NBT 持久化。
     *
     * @return 能识别出坐标的宿主返回稳定标识，否则返回 {@code null}
     */
    @Nullable
    public static String deviceIdOf(@Nullable Object owner) {
        if (owner instanceof BlockEntity be) {
            return String.valueOf(be.getBlockPos().asLong());
        }
        if (owner instanceof AEBasePart part) {
            BlockEntity host = part.getBlockEntity();
            long pos = host != null ? host.getBlockPos().asLong() : 0L;
            Direction side = part.getSide();
            return pos + "|" + (side != null ? side.ordinal() : -1);
        }
        return null;
    }

    /**
     * 判断网格节点是否为「可加速的设备」。
     * <p>
     * 条件：注册了网格 tick 服务（{@link IGridTickable}）、宿主非空且非自身、属于可加速机器、
     * 且能解析出坐标。
     * <p>
     * 注意：这里不判断 {@code isActive()}——菜单需要展示非活动设备（弱化色），
     * 仅加速脉冲要求设备处于激活状态，由调用方自行叠加该条件。
     */
    public static boolean isAcceleratableNode(@Nullable IGridNode node, @Nullable Object self) {
        if (node == null) {
            return false;
        }
        Object owner = node.getOwner();
        return node.getService(IGridTickable.class) != null
                && owner != null
                && owner != self
                && isAcceleratableMachine(owner)
                && resolveDevicePos(owner) != null;
    }

    /**
     * 从目标方块实体解析出「可加速设备的网格节点」。
     * <p>
     * 目标方块是 AE 设备（分子装配室、接口、线缆上的部件等）时，以
     * {@code AECapabilities.IN_WORLD_GRID_NODE_HOST} 能力拿到世界内网格节点宿主，
     * 遍历各相邻方向的网格节点，取第一个满足 {@link #isAcceleratableNode} 的节点。
     * 供配置卡手持右键绑定设备时判定目标是否为可加速设备（含黑名单过滤、坐标解析）。
     *
     * @param be   目标方块实体（可能为 null）
     * @param self 需要排除的自身方块实体（如加速器本体；不排除时传 null）
     * @return 可加速设备的网格节点，找不到时返回 {@code null}
     */
    @Nullable
    public static IGridNode findAcceleratableNode(@Nullable BlockEntity be, @Nullable Object self) {
        if (be == null) {
            return null;
        }
        Level level = be.getLevel();
        if (level == null) {
            return null;
        }
        // 世界内网格节点宿主能力在 Level 层查询（AE2 官方约定）。
        IInWorldGridNodeHost host = level.getCapability(AECapabilities.IN_WORLD_GRID_NODE_HOST, be.getBlockPos(), null);
        if (host == null) {
            return null;
        }
        for (Direction dir : Direction.values()) {
            IGridNode node = host.getGridNode(dir);
            if (isAcceleratableNode(node, self)) {
                return node;
            }
        }
        return null;
    }

    /**
     * 从设备标识解析出对应的方块坐标。
     * <p>
     * 设备标识有三种形态：方块实体 = 「坐标 long」；部件 = 「坐标 long|朝向下标」；
     * 合成 CPU = 「cpu:坐标 long」。这里统一剥离前缀与「|朝向」后缀后解析坐标，
     * 供配置卡绑定设备的高亮渲染定位。
     *
     * @return 能解析出坐标时返回坐标，否则返回 {@code null}
     */
    @Nullable
    public static BlockPos resolveDeviceIdPos(@Nullable String deviceId) {
        if (deviceId == null || deviceId.isEmpty()) {
            return null;
        }
        String raw = deviceId;
        if (raw.startsWith(CPU_ID_PREFIX)) {
            raw = raw.substring(CPU_ID_PREFIX.length());
        }
        int pipe = raw.indexOf('|');
        if (pipe >= 0) {
            raw = raw.substring(0, pipe);
        }
        try {
            return BlockPos.of(Long.parseLong(raw));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ========================= 合成 CPU（Crafting CPU）辅助 =========================

    /**
     * 判断指定设备标识是否为「合成 CPU」。
     * <p>
     * 合成 CPU 与普通设备使用不同的标识前缀（{@link #cpuDeviceId} 生成），
     * 两者在加速器选中集合、倍数表中以互不冲突的键共存。
     */
    public static boolean isCpuDeviceId(@Nullable String deviceId) {
        return deviceId != null && deviceId.startsWith(CPU_ID_PREFIX);
    }

    /**
     * 为合成 CPU 生成一个**稳定且可持久化**的设备标识。
     * <p>
     * 合成 CPU 是多块巨型结构，AE2 用 {@link ICraftingCPU}（实际实现为
     * {@link CraftingCPUCluster}）表示，其核心身份是整块结构的包围盒（bounds）。
     * 这里以结构最小角坐标（boundsMin）为身份，前缀 {@code cpu:} 表示这是一台合成 CPU。
     * 该标识与 {@link AE2GridSupport#deviceIdOf(Object)} 生成的普通设备标识互不冲突。
     *
     * @return 能解析出结构的 CPU 返回稳定标识，否则返回 {@code null}
     */
    @Nullable
    public static String cpuDeviceId(@Nullable ICraftingCPU cpu) {
        if (cpu instanceof CraftingCPUCluster cluster) {
            return CPU_ID_PREFIX + cluster.getBoundsMin().asLong();
        }
        return null;
    }

    /**
     * 将 {@link ICraftingCPU} 解析为内部实现 {@link CraftingCPUCluster}，用于读取结构坐标。
     * <p>
     * AE2 公共接口仅暴露名称、忙碌状态等，不暴露坐标；本项目需要坐标做 UI 展示、排序与
     * 搜索，因此在校验类型安全的前提下强转访问内部实现。无法强转时返回 {@code null}（该 CPU 被跳过）。
     */
    @Nullable
    public static CraftingCPUCluster asCpuCluster(@Nullable ICraftingCPU cpu) {
        return cpu instanceof CraftingCPUCluster cluster ? cluster : null;
    }

    /**
     * 判断网格节点宿主是否为「合成执行机器」。
     * <p>
     * 判定采用「接口 + 能力 + 类型兜底」三级策略，最大化覆盖 AE 网络（含 AE 附属/第三方模组）
     * 中所有执行合成的设备：
     * <ul>
     *   <li><b>接口判定</b>：宿主直接实现 {@link ICraftingMachine}（分子装配室，以及任何第三方
     *       直接实现该接口的合成机器）——最快，通用且免维护；</li>
     *   <li><b>能力判定</b>：宿主未直接实现接口，但向所在世界注册了
     *       {@code AECapabilities.CRAFTING_MACHINE} 能力（第三方模组通常这样接入 AE2 合成体系，
     *       如 ExtendedAE 等的合成机器）——通过 {@link appeng.api.implementations.blockentities.ICraftingMachine#of}
     *       查询邻接方向能力命中；</li>
     *   <li><b>类型兜底</b>：既未实现接口也未注册能力、但实际参与合成的原版机器（压印机、充能器），
     *       显式登记在 {@link #CRAFTING_MACHINE_TYPES}。</li>
     * </ul>
     * 智能加速合成 CPU 时，会联动加速这类真正执行合成的机器。
     */
    public static boolean isCraftingMachineType(@Nullable Object owner) {
        if (owner == null) {
            return false;
        }
        if (owner instanceof ICraftingMachine) {
            return true;
        }
        if (owner instanceof BlockEntity be && providesCraftingMachineCapability(be)) {
            return true;
        }
        return CRAFTING_MACHINE_TYPES.stream().anyMatch(type -> type.isInstance(owner));
    }

    /**
     * 判断方块实体是否在任意邻接方向提供了 {@code AECapabilities.CRAFTING_MACHINE} 能力。
     * <p>
     * AE2 的合成能力以 capability 形式暴露（分子装配室、第三方 AE 附属的合成机器均如此注册），
     * 因此以「宿主未直接实现 {@link ICraftingMachine} 接口、但通过能力接入合成体系」的方式判断，
     * 可以让智能加速兼容第三方。查询需要方向参数，故遍历六个方向，任一方向返回能力即命中。
     */
    private static boolean providesCraftingMachineCapability(BlockEntity be) {
        Level level = be.getLevel();
        if (level == null) {
            return false;
        }
        for (Direction direction : Direction.values()) {
            if (ICraftingMachine.of(level, be.getBlockPos(), direction) != null) {
                return true;
            }
        }
        return false;
    }
}
