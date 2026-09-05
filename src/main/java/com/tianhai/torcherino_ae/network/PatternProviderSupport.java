package com.tianhai.torcherino_ae.network;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.ToIntFunction;

import org.jetbrains.annotations.Nullable;

import com.tianhai.torcherino_ae.api.DeviceId;

import appeng.helpers.patternprovider.PatternProviderLogicHost;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * 样板供应器（Pattern Provider）辅助：投放方向与下游接收设备的解析，及下游联动倍率合成。
 * <p>
 * 作用：当加速源（AE 加速器）对「样板供应器」类设备生效时，材料会经其投放方向
 * （{@link PatternProviderLogicHost#getTargets()}）被注入相邻的接收方块——下游设备。
 * 为保证整条供应链获得同等加速，加速源把这些下游设备一并纳入加速目标，
 * 并让它们继承母样板供应器的生效倍率（见 {@link #linkedMultiplier}）。
 * <p>
 * 覆盖两类样板供应器宿主（AE2 1.21.1 统一实现 {@link PatternProviderLogicHost}）：
 * <ul>
 *   <li><b>方块版</b>（{@code PatternProviderBlockEntity}）：投放方向取自方块状态的
 *       {@code push_direction} 属性——单方向时只投该向，{@code ALL} 时六向全投；</li>
 *   <li><b>部件版</b>（{@code PatternProviderPart}）：永远只朝附着面投放。</li>
 * </ul>
 * 下游坐标 = 投放起点（方块实体用自身坐标，部件用其所在线缆宿主坐标）沿投放方向
 * 偏移一格；相邻方块的可加速性判定与节点解析不在此类（复用 {@link DeviceScanner}）。
 * <p>
 * 本类只做纯逻辑（方向展开 / 倍率合成），不触碰 AE2 内部类与网格对象，可单元测试。
 */
public final class PatternProviderSupport {

    private PatternProviderSupport() {
    }

    /**
     * 判定网格宿主是否为「样板供应器」类设备（方块版 / 部件版统一经 AE2 公共接口识别，
     * 不区分具体类，第三方实现该接口的供料设备同样命中）。
     */
    public static boolean isPatternProvider(@Nullable Object owner) {
        return owner instanceof PatternProviderLogicHost;
    }

    /**
     * 取样板供应器的投放方向集合。
     *
     * @return 非样板供应器宿主时返回 {@code null}；宿主为方块版时返回
     *         {@code push_direction} 推导的方向集（{@code ALL} 模式为六向），
     *         部件版返回附着面单方向
     */
    @Nullable
    public static EnumSet<Direction> pushDirections(@Nullable Object owner) {
        if (owner instanceof PatternProviderLogicHost host) {
            return host.getTargets();
        }
        return null;
    }

    /**
     * 把「投放起点坐标 + 投放方向集」展开为下游接收方块的坐标列表。
     * <p>
     * 每个方向仅偏移一格；方向集天然无重复，结果列表亦无重复坐标。
     * 纯几何计算，供目标收集方（{@code AEAcceleratorBlockEntity}）解析相邻方块时调用。
     *
     * @param origin 投放起点坐标（方块实体为自身坐标，部件为其线缆宿主坐标）
     * @param dirs   投放方向集（见 {@link #pushDirections}）
     */
    public static List<BlockPos> downstreamPositions(BlockPos origin, EnumSet<Direction> dirs) {
        List<BlockPos> result = new ArrayList<>(dirs.size());
        for (Direction dir : dirs) {
            result.add(origin.relative(dir));
        }
        return result;
    }

    /**
     * 合成「下游设备应继承的联动倍率」：遍历联动它的全部母源（样板供应器），
     * 取各母源生效倍率的最大值；无母源或母源全部无效时返回 1（不加速）。
     * <p>
     * 语义：同一台下游设备可能被多台样板供应器供料（多供一），只要任一母源
     * 在加速，下游设备就随其加速；最终倍率由调用方再统一钳制到本源上限
     * （{@code RateGovernor} 的 runCap），与普通设备一致「只往下调、不往上超」。
     *
     * @param sources      下游设备 → 母样板供应器标识集（可能为 {@code null} / 空）
     * @param multiplierOf 任一母源标识 → 其当前生效倍率（≤1 视为无效，不计入）
     */
    public static int linkedMultiplier(@Nullable Set<DeviceId> sources,
            ToIntFunction<DeviceId> multiplierOf) {
        if (sources == null || sources.isEmpty()) {
            return 1;
        }
        int linked = 1;
        for (DeviceId source : sources) {
            int m = multiplierOf.applyAsInt(source);
            if (m > linked) {
                linked = m;
            }
        }
        return linked;
    }
}
