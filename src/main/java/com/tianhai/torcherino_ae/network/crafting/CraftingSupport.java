package com.tianhai.torcherino_ae.network.crafting;

import org.jetbrains.annotations.Nullable;

import com.tianhai.torcherino_ae.api.DeviceId;
import com.tianhai.torcherino_ae.api.DeviceKind;
import com.tianhai.torcherino_ae.config.ConfigDefaults;
import com.tianhai.torcherino_ae.config.RuntimeConfig;

import appeng.api.implementations.blockentities.ICraftingMachine;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * 合成体系辅助：集中管理与「合成 CPU / 合成执行机器」相关的全部逻辑，
 * 是全项目唯一接触 AE2 内部实现类 {@link CraftingCPUCluster}（CPU 结构坐标读取）的地方：
 * <ul>
 *   <li>CPU 设备标识生成（{@link #cpuDeviceId}，供登记表与 GUI 载荷使用）；</li>
 *   <li>CPU 条目坐标读取（{@link #asCpuCluster}，强转访问隔离在本文件内）；</li>
 *   <li>合成执行机器三级判定（{@link #isCraftingMachineType}：接口 / 能力 / 类型兜底）。</li>
 * </ul>
 */
public final class CraftingSupport {

    private CraftingSupport() {
    }

    /**
     * 为合成 CPU 生成一个**稳定且可持久化**的设备标识。
     * <p>
     * 合成 CPU 是多块巨型结构，AE2 用 {@link ICraftingCPU}（实际实现为
     * {@link CraftingCPUCluster}）表示，其核心身份是整块结构的包围盒（bounds）。
     * 这里以结构最小角坐标（boundsMin）为坐标，种类标记为 {@link DeviceKind#CRAFTING_CPU}；
     * CPU 与普通设备共用同一键空间，靠种类字段区分，不依赖字符串前缀约定。
     *
     * @param dimension CPU 所在维度（AE2 的 CPU 接口不暴露维度，由调用方提供）
     * @return 能解析出结构的 CPU 返回稳定标识，否则返回 {@code null}
     */
    @Nullable
    public static DeviceId cpuDeviceId(ResourceKey<Level> dimension, @Nullable ICraftingCPU cpu) {
        CraftingCPUCluster cluster = asCpuCluster(cpu);
        return cluster == null ? null : DeviceId.ofCpu(dimension, cluster.getBoundsMin());
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
     *       显式登记在配置项 {@code grid.craftingMachineExtraTypes}（默认即压印机/充能器，
     *       见 {@link ConfigDefaults}），经 {@link RuntimeConfig#craftingMachineExtras()} 读取。</li>
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
        return RuntimeConfig.craftingMachineExtras().stream().anyMatch(type -> type.isInstance(owner));
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
