package com.tianhai.torcherino_ae.network;

import org.jetbrains.annotations.Nullable;

import com.tianhai.torcherino_ae.api.DeviceId;

import appeng.parts.AEBasePart;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * 网格宿主的世界坐标与设备标识解析辅助。
 * <p>
 * AE 网格节点的宿主（{@code node.getOwner()}）可能是<b>方块实体</b>（如分子装配室、原子重组仪），
 * 也可能是<b>线缆上的部件</b>（如输入/输出总线、破坏面板，{@link AEBasePart}）。两者取「坐标 /
 * 世界 / 设备标识」的分支逻辑在多处重复（设备扫描、合成机制辅助、加速器菜单采集），
 * 统一收敛到本类，避免各写一份。
 * <p>
 * 约定：
 * <ul>
 *   <li>方块实体用自身坐标 / 自身世界；</li>
 *   <li>部件用其所在的<b>线缆宿主方块实体</b>的坐标 / 世界（部件自身不暴露坐标）；</li>
 *   <li>设备标识 {@link #idOf} 对部件额外带上朝向（{@code part.getSide()}），
 *       从而区分挂在同一线缆坐标上、不同朝向的多个可加速部件。</li>
 * </ul>
 */
public final class GridOwner {

    private GridOwner() {
    }

    /**
     * 解析宿主的「坐标载体」方块实体：方块实体为自身，部件为其所在线缆/宿主方块实体。
     */
    @Nullable
    private static BlockEntity positionEntity(@Nullable Object owner) {
        if (owner instanceof BlockEntity be) {
            return be;
        }
        if (owner instanceof AEBasePart part) {
            return part.getBlockEntity();
        }
        return null;
    }

    /**
     * 解析宿主的方块坐标（方块实体用自身坐标，部件用其所在线缆/宿主坐标）。
     *
     * @return 能解析出坐标时返回坐标，否则返回 {@code null}
     */
    @Nullable
    public static BlockPos posOf(@Nullable Object owner) {
        BlockEntity host = positionEntity(owner);
        return host == null ? null : host.getBlockPos();
    }

    /**
     * 解析宿主所在世界。
     *
     * @return 宿主方块实体所属的世界；无世界（方块实体尚未加载）时返回 {@code null}
     */
    @Nullable
    public static Level levelOf(@Nullable Object owner) {
        BlockEntity host = positionEntity(owner);
        return host == null ? null : host.getLevel();
    }

    /**
     * 为网格节点宿主生成一个<b>稳定且可持久化</b>的设备标识。
     * <p>
     * 方块实体用「维度 + 自身坐标」；部件用「维度 + 所在线缆坐标 + 朝向」，从而把挂在
     * 同一线缆坐标上、不同朝向的多个可加速部件区分开，避免它们共用同一坐标导致
     * 选中一个、另一个也跟着变。标识必须带维度：若仅用 {@code BlockPos.asLong()}，
     * 跨维度同坐标的两个方块会被判定为同一台设备。
     *
     * @return 能识别出坐标与维度的宿主返回稳定标识，否则返回 {@code null}
     */
    @Nullable
    public static DeviceId idOf(@Nullable Object owner) {
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
