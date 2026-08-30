package com.tianhai.torcherino_ae.common;

import java.util.Set;

import org.jetbrains.annotations.Nullable;

import appeng.api.networking.IGridNode;
import appeng.api.networking.ticking.IGridTickable;
import appeng.blockentity.networking.EnergyCellBlockEntity;
import appeng.parts.AEBasePart;
import appeng.parts.p2p.P2PTunnelPart;
import appeng.parts.storagebus.StorageBusPart;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
}
