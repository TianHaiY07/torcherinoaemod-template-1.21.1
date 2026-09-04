package com.tianhai.torcherino_ae.network;

import org.jetbrains.annotations.Nullable;

import com.tianhai.torcherino_ae.api.DeviceId;
import com.tianhai.torcherino_ae.config.RuntimeConfig;

import appeng.api.AECapabilities;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IInWorldGridNodeHost;
import appeng.api.networking.ticking.IGridTickable;
import appeng.api.parts.IPart;
import appeng.api.parts.IPartHost;
import appeng.parts.AEBasePart;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 网格设备扫描器：集中管理「哪些网格宿主是可加速设备」的判定与设备身份解析，
 * 供多处消费者复用，避免把筛选谓词散落到各处：
 * <ul>
 *   <li>AE 加速器的目标缓存重建（{@code rebuildTargets}）；</li>
 *   <li>配置卡注入的网格内设备判定（{@code ConfigCardBinding}）；</li>
 *   <li>加速器菜单的设备列表采集与手持配置卡右键的设备判定。</li>
 * </ul>
 */
public final class DeviceScanner {

    private DeviceScanner() {
    }

    /**
     * 判断网格节点宿主是否为「可加速的机器」。
     * <p>
     * 虽然实现 {@link IGridTickable} 的设备都能被 tick 管理器催促，但存储总线、能量元件、
     * P2P 隧道等网络基础设施没有实际工作可加速，应从可加速设备中排除。
     * <p>
     * 黑名单由服务端配置项 {@code grid.acceleratableBlacklist} 提供（全限定类名），
     * 解析结果经 {@link RuntimeConfig#acceleratableBlacklist()} 读取；今后要排除新的
     * 基础设施类型只需在配置文件中增补条目，无需改代码。
     */
    public static boolean isAcceleratableMachine(@Nullable Object owner) {
        if (owner == null) {
            return false;
        }
        return RuntimeConfig.acceleratableBlacklist().stream().noneMatch(type -> type.isInstance(owner));
    }

    /**
     * 判断网格节点是否为「可加速的设备」。
     * <p>
     * 条件：宿主非空且非自身、属于可加速机器、能解析出坐标，且满足加速载体之一：
     * <ul>
     *   <li>注册了网格 tick 服务（{@link IGridTickable}）——经 AE2 网格 tick 管理器加速；</li>
     *   <li>宿主方块实体具有<b>原版</b> tick 函数（{@link EntityBlock#getTicker} 非空）——
     *       接了 AE 网络、但加工走原版 tick 的机器，由引擎按倍率反复执行其原版 tick。</li>
     * </ul>
     * <p>
     * 注意：这里不判断 {@code isActive()}——菜单需要展示非活动设备（弱化色），
     * 仅加速脉冲要求设备处于激活状态，由调用方自行叠加该条件。
     */
    public static boolean isAcceleratableNode(@Nullable IGridNode node, @Nullable Object self) {
        if (node == null) {
            return false;
        }
        Object owner = node.getOwner();
        boolean gridTickable = node.getService(IGridTickable.class) != null;
        boolean vanillaTicking = owner instanceof BlockEntity be && isVanillaTicking(be);
        return (gridTickable || vanillaTicking)
                && owner != null
                && owner != self
                && isAcceleratableMachine(owner)
                && resolveDevicePos(owner) != null;
    }

    /**
     * 宿主方块实体是否具有服务端<b>原版</b> tick 函数（{@link EntityBlock#getTicker} 非空）。
     * <p>
     * 用于识别「接了 AE 网络、但加工走原版 {@code BlockEntity} tick」的机器——它们不注册
     * {@link IGridTickable}，却仍有真实的加工节奏可被加速。客户端无 tick，一律视为否。
     */
    public static boolean isVanillaTicking(@Nullable BlockEntity be) {
        return vanillaTicker(be) != null;
    }

    /**
     * 解析宿主方块实体的服务端原版 tick 函数；无（非 {@link EntityBlock} / 客户端 / 方块不 tick）时返回 {@code null}。
     */
    @Nullable
    public static BlockEntityTicker<BlockEntity> vanillaTicker(@Nullable BlockEntity be) {
        if (be == null) {
            return null;
        }
        Level level = be.getLevel();
        if (level == null || level.isClientSide()) {
            return null;
        }
        BlockState state = be.getBlockState();
        Block block = state.getBlock();
        if (!(block instanceof EntityBlock entityBlock)) {
            return null;
        }
        @SuppressWarnings({"unchecked", "rawtypes"})
        BlockEntityTicker<BlockEntity> ticker = (BlockEntityTicker) entityBlock.getTicker(level, state, be.getType());
        return ticker;
    }

    /**
     * 解析网格节点宿主的方块坐标（方块实体用自身坐标，部件用其所在线缆/宿主坐标）。
     *
     * @return 能解析出坐标时返回坐标，否则返回 {@code null}
     */
    @Nullable
    private static BlockPos resolveDevicePos(@Nullable Object owner) {
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
     * 从目标方块实体解析出「可加速设备的网格节点」。
     * <p>
     * 目标方块是 AE 设备（分子装配室、接口、线缆上的部件等）时，以
     * {@code AECapabilities.IN_WORLD_GRID_NODE_HOST} 能力拿到世界内网格节点宿主，
     * 遍历各相邻方向的网格节点，取第一个满足 {@link #isAcceleratableNode} 的节点。
     * <p>
     * <b>线缆多部件宿主</b>：输入/输出总线、破坏面板等「可加速设备」大多是挂在
     * 线缆上的<b>部件</b>。AE2 的线缆宿主按方向查询只返回线缆自身节点（或部件的对外
     * 节点），不会把部件的本体节点暴露出来——因此必须先按 {@link IPartHost} 枚举各侧
     * 面的部件、再以部件自身的 {@link IGridNode} 判定，否则点击总线永远“找不到可加速
     * 设备”。同一线缆挂了多个可加速部件时，优先命中玩家点击的面。
     * 供配置卡手持右键绑定设备时判定目标是否为可加速设备（含黑名单过滤、坐标解析）。
     *
     * @param be         目标方块实体（可能为 null）
     * @param self       需要排除的自身方块实体（如加速器本体；不排除时传 null）
     * @param preferSide 玩家点击到的面；线缆上有多个可加速部件时优先该面，可为 null
     * @return 可加速设备的网格节点，找不到时返回 {@code null}
     */
    @Nullable
    public static IGridNode findAcceleratableNode(@Nullable BlockEntity be, @Nullable Object self,
            @Nullable Direction preferSide) {
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
        if (host instanceof IPartHost partHost) {
            IGridNode firstHit = null;
            for (Direction dir : Direction.values()) {
                IPart part = partHost.getPart(dir);
                if (part == null) {
                    continue;
                }
                IGridNode node = part.getGridNode();
                if (!isAcceleratableNode(node, self)) {
                    continue;
                }
                if (dir == preferSide) {
                    return node;
                }
                if (firstHit == null) {
                    firstHit = node;
                }
            }
            return firstHit;
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
     * 为网格节点宿主生成一个**稳定且可持久化**的设备标识。
     * <p>
     * 方块实体用「维度 + 自身坐标」；部件用「维度 + 所在线缆坐标 + 朝向」，从而把挂在
     * 同一线缆坐标上、不同朝向的多个可加速部件区分开，避免它们共用同一坐标导致
     * 选中一个、另一个也跟着变。
     * <p>
     * 该标识作为加速器目标登记表（{@code core.TargetRegistry}）、每设备独立倍数
     * 以及 GUI 点击载荷的身份键，贯穿 NBT 持久化。
     * <p>
     * 标识必须带维度：若仅用 {@code BlockPos.asLong()}，跨维度同坐标的两个方块会被
     * 判定为同一台设备，配置卡也会因此在维度之间误绑定。
     *
     * @return 能识别出坐标与维度的宿主返回稳定标识，否则返回 {@code null}
     */
    @Nullable
    public static DeviceId deviceIdOf(@Nullable Object owner) {
        if (owner instanceof BlockEntity be) {
            Level level = be.getLevel();
            return level == null ? null : DeviceId.ofBlock(level.dimension(), be.getBlockPos());
        }
        if (owner instanceof AEBasePart part) {
            BlockEntity host = part.getBlockEntity();
            if (host == null) {
                return null;
            }
            Level level = host.getLevel();
            return level == null ? null : DeviceId.ofPart(level.dimension(), host.getBlockPos(), part.getSide());
        }
        return null;
    }
}
