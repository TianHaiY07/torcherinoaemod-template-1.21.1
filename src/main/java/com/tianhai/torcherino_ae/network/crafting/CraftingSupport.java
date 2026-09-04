package com.tianhai.torcherino_ae.network.crafting;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.tianhai.torcherino_ae.api.DeviceId;
import com.tianhai.torcherino_ae.api.DeviceKind;
import com.tianhai.torcherino_ae.config.RuntimeConfig;

import appeng.api.implementations.blockentities.ICraftingMachine;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingService;
import appeng.blockentity.crafting.CraftingBlockEntity;
import appeng.me.cluster.IAECluster;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import appeng.parts.AEBasePart;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * 合成体系辅助：集中管理与「合成 CPU / 合成执行机器」相关的全部逻辑。
 * <p>
 * 大型合成 CPU 结构有<b>两类来源</b>，本文件是唯一解析它们结构坐标的地方，调用方只面向
 * 统一的 {@link CpuGroup} 结构视图，不感知具体实现：
 * <ul>
 *   <li><b>AE2 原版合成 CPU</b>：成员方块为 {@link CraftingBlockEntity}，集群实现为
 *       {@link CraftingCPUCluster}（同时实现 {@link IAECluster} 与 {@link ICraftingCPU}）；</li>
 *   <li><b>AdvancedAE 大型 CPU（可选附属）</b>：成员方块为 AdvancedAE 的
 *       {@code AdvCraftingBlockEntity}，集群为 {@code AdvCraftingCPUCluster}（同样实现 AE2 公共接口
 *       {@link IAECluster}），并经 AE2 网格以 {@code AdvCraftingCPU}（实现 {@link ICraftingCPU}）条目
 *       暴露——AdvancedAE 未安装时本文件保持原行为，安装后其大型 CPU 组在绑定卡 / 高亮几何 /
 *       加速器列表与智能加速 / 拆除清理各环节与 AE2 CPU 完全等价。</li>
 * </ul>
 * AdvancedAE 侧通过<b>软反射</b>访问（类名/成员名固定字符串探测，全部失败即视为未安装），
 * 本模组编译期与运行期都不依赖 AdvancedAE；对 AdvancedAE 结构只做 {@link IAECluster} 层面的
 * 坐标读取，不触碰其任何合成内部逻辑。
 * <ul>
 *   <li>CPU 设备标识生成（{@link #cpuDeviceId}，供登记表与 GUI 载荷使用）；</li>
 *   <li>CPU 条目/成员方块的结构解析（{@link #cpuGroupOf}，供绑定、列表与拆除裁决使用）；</li>
 *   <li>CPU 组成员判定与在线状态（{@link #isCpuGroupMember} / {@link #isCpuActive}）；</li>
 *   <li>合成执行机器三级判定（{@link #isCraftingMachineType}：接口 / 能力 / 类型兜底）。</li>
 * </ul>
 */
public final class CraftingSupport {

    private CraftingSupport() {
    }

    /**
     * 大型合成 CPU 结构的统一视图（AE2 原版 + AdvancedAE 软兼容）。
     *
     * @param id        CPU 组的稳定设备标识（{@link DeviceKind#CRAFTING_CPU}，坐标 = 结构最小角）
     * @param boundsMax 结构最大角坐标（几何，与 {@code id} 一起描述整组包围盒）
     * @param members   成员方块坐标快照；从 {@code getCpus()} 条目侧解析时为只读空表，
     *                  仅方块实体侧按需请求收集（拆除清理的观察集）
     */
    public record CpuGroup(DeviceId id, BlockPos boundsMax, List<BlockPos> members) {
    }

    // ====================================================================
    // AdvancedAE 大型 CPU 可选兼容（软反射探测）
    // ====================================================================

    private static final String ADV_AE_BLOCK_ENTITY = "net.pedroksl.advanced_ae.common.entities.AdvCraftingBlockEntity";
    private static final String ADV_AE_CPU = "net.pedroksl.advanced_ae.common.cluster.AdvCraftingCPU";

    @Nullable
    private static final Class<?> ADV_BE_CLASS = optionalClass(ADV_AE_BLOCK_ENTITY);
    @Nullable
    private static final Class<?> ADV_CPU_CLASS = optionalClass(ADV_AE_CPU);

    /** {@code AdvCraftingBlockEntity#isFormed()}：客户端读方块状态、服务端读集群，双端语义一致。 */
    @Nullable
    private static final Method ADV_BE_IS_FORMED = optionalMethod(ADV_BE_CLASS, "isFormed");
    /** {@code AdvCraftingBlockEntity#getCluster()}：返回 AdvancedAE 集群（实现 {@link IAECluster}）。 */
    @Nullable
    private static final Method ADV_BE_GET_CLUSTER = optionalMethod(ADV_BE_CLASS, "getCluster");
    /** {@code AdvCraftingCPU#isActive()}：网格 CPU 条目的在线状态。 */
    @Nullable
    private static final Method ADV_CPU_IS_ACTIVE = optionalMethod(ADV_CPU_CLASS, "isActive");
    /** {@code AdvCraftingCPU#cluster}（私有字段）：网格 CPU 条目 -> 所属集群对象。 */
    @Nullable
    private static final Field ADV_CPU_CLUSTER_FIELD = optionalField(ADV_CPU_CLASS, "cluster");

    /**
     * 探测可选的 AdvancedAE 类；类不可加载（未安装 / 环境差异 / 类名变化）时返回 {@code null}。
     * 使用不触发类初始化的双参重载，避免在探测阶段推进目标模组自身的静态初始化。
     */
    @Nullable
    private static Class<?> optionalClass(String name) {
        try {
            return Class.forName(name, false, CraftingSupport.class.getClassLoader());
        } catch (Throwable notPresent) {
            return null;
        }
    }

    @Nullable
    private static Method optionalMethod(@Nullable Class<?> type, String name) {
        if (type == null) {
            return null;
        }
        try {
            return type.getMethod(name);
        } catch (Throwable incompatible) {
            return null;
        }
    }

    @Nullable
    private static Field optionalField(@Nullable Class<?> type, String name) {
        if (type == null) {
            return null;
        }
        try {
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (Throwable incompatible) {
            return null;
        }
    }

    /**
     * 判断方块实体是否为「AdvancedAE 大型 CPU 的成型成员」（软反射，仅当探测命中时启用）。
     */
    private static boolean isAdvancedAeCpuMember(@Nullable BlockEntity be) {
        if (be == null || ADV_BE_IS_FORMED == null || !ADV_BE_CLASS.isInstance(be)) {
            return false;
        }
        try {
            return (Boolean) ADV_BE_IS_FORMED.invoke(be);
        } catch (Throwable incompatible) {
            return false;
        }
    }

    /**
     * 服务端：解析 AdvancedAE 成员方块所属集群，转为统一的 {@link IAECluster} 视图。
     */
    @Nullable
    private static IAECluster advancedAeClusterOf(@Nullable BlockEntity be) {
        if (be == null || ADV_BE_GET_CLUSTER == null || !ADV_BE_CLASS.isInstance(be)) {
            return null;
        }
        try {
            Object cluster = ADV_BE_GET_CLUSTER.invoke(be);
            return cluster instanceof IAECluster iaCluster ? iaCluster : null;
        } catch (Throwable incompatible) {
            return null;
        }
    }

    /**
     * 解析 {@code getCpus()} 条目（{@code AdvCraftingCPU}）所属集群，转为统一视图。
     */
    @Nullable
    private static IAECluster advancedAeCpuClusterOf(@Nullable ICraftingCPU cpu) {
        if (cpu == null || ADV_CPU_CLUSTER_FIELD == null || !ADV_CPU_CLASS.isInstance(cpu)) {
            return null;
        }
        try {
            Object cluster = ADV_CPU_CLUSTER_FIELD.get(cpu);
            return cluster instanceof IAECluster iaCluster ? iaCluster : null;
        } catch (Throwable incompatible) {
            return null;
        }
    }

    /**
     * 判断网格 CPU 条目（{@code AdvCraftingCPU}）是否在线（软反射；AE2 条目走
     * {@link CraftingCPUCluster#isActive()}）。
     */
    private static boolean advancedAeCpuActive(@Nullable ICraftingCPU cpu) {
        if (cpu == null || ADV_CPU_IS_ACTIVE == null || !ADV_CPU_CLASS.isInstance(cpu)) {
            return false;
        }
        try {
            return (Boolean) ADV_CPU_IS_ACTIVE.invoke(cpu);
        } catch (Throwable incompatible) {
            return false;
        }
    }

    // ====================================================================
    // CPU 结构解析（AE2 + AdvancedAE 统一入口）
    // ====================================================================

    /**
     * 为合成 CPU 生成一个<b>稳定且可持久化</b>的设备标识。
     * <p>
     * 合成 CPU 是多块巨型结构，AE2 网格经 {@link ICraftingService#getCpus()} 暴露条目
     * （AE2 每条 = 一个 {@link CraftingCPUCluster}；AdvancedAE 每条 {@code AdvCraftingCPU}
     * 是其巨型集群的「单任务 / 剩余容量」视图，同一集群可能对应多条、结构标识相同）。
     * 其核心身份是整块结构的包围盒（bounds），本方法一律归一到结构最小角坐标
     * （{@link DeviceKind#CRAFTING_CPU}），因此 AdvancedAE 同一集群的多条网格条目会解析出
     * 同一个设备标识，天然与登记表 / 绑定卡按「组」合并的语义一致。
     *
     * @param dimension CPU 所在维度（AE2 的 CPU 接口不暴露维度，由调用方提供）
     * @return 能解析出结构的 CPU 返回稳定标识，否则返回 {@code null}
     */
    @Nullable
    public static DeviceId cpuDeviceId(ResourceKey<Level> dimension, @Nullable ICraftingCPU cpu) {
        CpuGroup group = cpuGroupOf(dimension, cpu);
        return group == null ? null : group.id();
    }

    /**
     * 解析网格 CPU 条目（{@code getCpus()}）的结构视图：AE2 条目直接读集群，AdvancedAE
     * 条目经其私有 {@code cluster} 字段读取。无法解析时返回 {@code null}（该 CPU 被跳过）。
     *
     * @param dimension CPU 所在维度
     * @return 结构视图；成员坐标恒为空表（网格侧解析不收集成员，避免高频路径整组遍历）
     */
    @Nullable
    public static CpuGroup cpuGroupOf(ResourceKey<Level> dimension, @Nullable ICraftingCPU cpu) {
        IAECluster cluster = cpu instanceof CraftingCPUCluster aec ? aec : advancedAeCpuClusterOf(cpu);
        if (cluster == null || cluster.isDestroyed()) {
            return null;
        }
        return new CpuGroup(DeviceId.ofCpu(dimension, cluster.getBoundsMin()), cluster.getBoundsMax(), List.of());
    }

    /**
     * 服务端：解析方块实体所属的大型 CPU 组结构视图。
     * <p>
     * 支持 AE2 原版成员方块与 AdvancedAE 成员方块；客户端 / 无世界 / 非成型 CPU 组成员时
     * 返回 {@code null}（集群对象只存在于服务端）。
     *
     * @param be             被点击/拆除的目标方块实体（可能为 {@code null}）
     * @param includeMembers 是否收集整组成员坐标快照；只有拆除裁决（结算后观察剩余成员）需要，
     *                       常规解析传 {@code false} 避免遍历整组
     */
    @Nullable
    public static CpuGroup cpuGroupOf(@Nullable BlockEntity be, boolean includeMembers) {
        if (be == null) {
            return null;
        }
        Level level = be.getLevel();
        if (level == null || level.isClientSide()) {
            return null;
        }
        IAECluster cluster = be instanceof CraftingBlockEntity aec ? aec.getCluster() : advancedAeClusterOf(be);
        if (cluster == null || cluster.isDestroyed()) {
            return null;
        }
        List<BlockPos> members = includeMembers ? collectMemberPositions(cluster) : List.of();
        return new CpuGroup(DeviceId.ofCpu(level.dimension(), cluster.getBoundsMin()), cluster.getBoundsMax(), members);
    }

    /**
     * 把集群的全部成员方块坐标收集成不可变快照（仅拆除清理的观察集需要）。
     */
    private static List<BlockPos> collectMemberPositions(IAECluster cluster) {
        List<BlockPos> positions = new ArrayList<>();
        Iterator<? extends BlockEntity> blockEntities = cluster.getBlockEntities();
        while (blockEntities.hasNext()) {
            positions.add(blockEntities.next().getBlockPos());
        }
        return List.copyOf(positions);
    }

    /**
     * 判断方块实体是否为「已成形的合成 CPU 组」的成员方块。
     * <p>
     * 兼容 AE2 原版（{@link CraftingBlockEntity}）与 AdvancedAE 大型 CPU 成员方块：
     * 两者的 {@code isFormed()} 均在客户端经方块状态 {@code FORMED} 判定、服务端按集群判空，
     * 两端语义一致——因此配置卡右键拦截可用本方法在客户端/服务端得到相同结论
     * （点击 CPU 组任意成员块即命中整组）。AdvancedAE 未安装时对原版行为无任何影响。
     *
     * @param be 被点击的目标方块实体（可能为 {@code null}）
     */
    public static boolean isCpuGroupMember(@Nullable BlockEntity be) {
        if (be instanceof CraftingBlockEntity crafting) {
            return crafting.isFormed();
        }
        return isAdvancedAeCpuMember(be);
    }

    /**
     * 判断网格 CPU 条目的在线状态（供加速器界面展示）：AE2 条目读集群，AdvancedAE
     * 条目经其自身 {@code isActive()}（委托集群）读取。
     */
    public static boolean isCpuActive(@Nullable ICraftingCPU cpu) {
        if (cpu instanceof CraftingCPUCluster cluster) {
            return cluster.isActive();
        }
        return advancedAeCpuActive(cpu);
    }

    // ====================================================================
    // 合成执行机器判定
    // ====================================================================

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
     *       如 ExtendedAE 等的合成机器）——通过 {@link ICraftingMachine#of}
     *       查询邻接方向能力命中；</li>
     *   <li><b>类型兜底</b>：既未实现接口也未注册能力、但实际参与合成的原版机器（压印机、充能器），
     *       显式登记在配置项 {@code grid.craftingMachineExtraTypes}（默认即压印机/充能器）。</li>
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
        LevelAndPos location = levelPosOf(owner);
        if (location != null && providesCraftingMachineCapability(location.level(), location.pos())) {
            return true;
        }
        return RuntimeConfig.craftingMachineExtras().stream().anyMatch(type -> type.isInstance(owner));
    }

    /**
     * 解析宿主用于查询合成能力的世界坐标：方块实体用自身坐标，部件（{@link AEBasePart}）
     * 用其所在线缆宿主坐标。这样第三方以部件形式接入的合成机器也能命中能力检测。
     */
    @Nullable
    private static LevelAndPos levelPosOf(Object owner) {
        if (owner instanceof BlockEntity be) {
            Level level = be.getLevel();
            return level == null ? null : new LevelAndPos(level, be.getBlockPos());
        }
        if (owner instanceof AEBasePart part) {
            BlockEntity host = part.getBlockEntity();
            if (host == null) {
                return null;
            }
            Level level = host.getLevel();
            return level == null ? null : new LevelAndPos(level, host.getBlockPos());
        }
        return null;
    }

    /**
     * 判断宿主所在块是否在任意方向提供了 {@code AECapabilities.CRAFTING_MACHINE} 能力。
     * <p>
     * AE2 的合成能力以 capability 形式暴露（分子装配室、第三方 AE 附属的合成机器均如此注册），
     * 因此以「宿主未直接实现 {@link ICraftingMachine} 接口、但通过能力接入合成体系」的方式判断，
     * 可以让智能加速兼容第三方。查询需要方向参数，故遍历六个方向，任一方向返回能力即命中。
     */
    private static boolean providesCraftingMachineCapability(Level level, BlockPos pos) {
        for (Direction direction : Direction.values()) {
            if (ICraftingMachine.of(level, pos, direction) != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * 宿主的世界坐标快照（Level + BlockPos），用于第三/部件的能力查询。
     */
    private record LevelAndPos(Level level, BlockPos pos) {
    }
}
