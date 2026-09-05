package com.tianhai.torcherino_ae.blockentity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import appeng.api.AECapabilities;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IInWorldGridNodeHost;
import appeng.util.inv.AppEngInternalInventory;
import com.tianhai.torcherino_ae.Torcherinoaemod;
import com.tianhai.torcherino_ae.api.DeviceId;
import com.tianhai.torcherino_ae.item.ConfigCardData;
import com.tianhai.torcherino_ae.network.crafting.CraftingSupport;
import com.tianhai.torcherino_ae.util.AeGrid;
import com.tianhai.torcherino_ae.util.ConfigCardScanner;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * 「绑定设备/合成 CPU 组被移除 -> 配置卡与加速器数据联动清理」的服务端协调器。
 * <p>
 * 配置卡上绑定的目标被挖掘、爆炸等途径移除后，卡片上仍保留其绑定记录（Data Component），
 * 若卡正在某台加速器卡槽中，对应登记表里 {@code CONFIG_CARD} 来源的注入记录也不会立即
 * 撤销——存档里会残留指向已销毁目标的条目，卡片再放入加速器、或同一坐标出现新目标时
 * 会再次注入。
 * <p>
 * 本类订阅两类破坏事件作为触发源，把本 tick 内被移除的方块批量收集：
 * <ul>
 *   <li>{@link BlockEvent.BreakEvent}：玩家挖掘破坏（最常见途径，创造模式同样派发）；</li>
 *   <li>{@link ExplosionEvent.Detonate}：爆炸受影响方块列表结算阶段派发；方块实体若尚在
 *       可顺带捕获其所在网格以定位同网络加速器，缺失时退化为「仅清背包卡片」路径。</li>
 * </ul>
 * 两类目标的生命周期语义不同，处理分道：
 * <ol>
 *   <li><b>普通设备</b>（方块实体 / 线缆部件，{@code DeviceKind.BLOCK_ENTITY / PART}）：
 *       延迟一帧核对方块确被移除后，按「维度 + 坐标」整点清除卡上对应绑定——扫描
 *       <b>全部在线玩家</b>背包中的配置卡，以及<b>与被移除设备同网络（grid）</b>的
 *       加速器卡槽卡片；</li>
 *   <li><b>合成 CPU 组</b>：AE2 原版合成 CPU 与 AdvancedAE 大型 CPU（可选附属，软兼容）的
 *       集群对象在任一成员块被拆除时<b>整体解散</b>、由剩余成员重新计算是否再成型——因此以
 *       「拆除时捕获的整组成员」为观察集，在解散与再成型结算完成（延迟数 tick）后读取
 *       剩余成员的真实成型状态：
 *       <ul>
 *         <li>仍有<b>一个</b>成型集群残留（部分破坏但组依旧有效）：把卡上该组条目
 *             <b>改写为新集群</b>（标识 = 新最小角，几何 = 新最大角）——组被拆除一角或
 *             一层后，手持卡片高亮与加速器注入自动跟随真实几何（换绑语义）；</li>
 *         <li>无残留成型集群（整组被拆光 / 拆剩的形状无效）或残留被拆成<b>多个</b>
 *             独立集群：原绑定指向的「这一组」已不存在，从卡上<b>删除</b>该组条目。</li>
 *       </ul></li>
 * </ol>
 * 改写加速器卡槽内的卡片会触发其库存变化回调，进而重新同步卡来源注入：删除组时把已不在
 * 卡上 / 网络内的 {@code CONFIG_CARD} 登记立即撤销并落盘；换绑时撤销旧集群标识、按新
 * 标识重新注入（加速不因组几何变化而中断）。
 * <p>
 * 覆盖边界（刻意保守，避免误伤）：存放在箱子 / ME 网络存储等不可枚举容器里的卡片，以及
 * 离线玩家背包中的卡片，本次不会被即时清理（待其下次被取用/放入加速器时自愈）；被移除
 * 组所在区块未加载时放弃裁决（不基于残缺信息做删除/换绑）。
 */
@EventBusSubscriber(modid = Torcherinoaemod.MOD_ID)
public final class ConfigCardCleanup {

    private ConfigCardCleanup() {
    }

    /**
     * 拆除 CPU 组块到 AE2 解散 / 再成型完全结算需要等待的 tick 数。
     */
    private static final int CPU_SETTLE_TICKS = 4;

    /**
     * 合成 CPU 组裁决「观察区含未加载区块」时允许多重试的轮数；超过则放弃本次候选，
     * 改由周期兜底（{@link #reconcileInventoryCards}）在区块加载后再自愈。
     */
    private static final int CPU_SETTLE_MAX_RETRIES = 50;

    /**
     * 在线玩家背包配置卡「CPU 绑定自愈」周期（tick）。核对卡上记录的合成 CPU 组是否仍
     * 以真实结构成型，是 {@code ConfigCardCleanup} 破坏事件清理的兜底（见
     * {@link ConfigCardBinding#reconcileCpuBindingsOnCard}）。
     */
    private static final int INVENTORY_RECONCILE_INTERVAL = 40; // 2 秒

    // 在线玩家背包卡自愈计数器（仅服务端逻辑 tick 线程访问）。
    private static int inventoryReconcileTicks;

    /**
     * 一个「方块被移除」候选：破坏前的方块状态（用于延迟一帧核对是否真的被移除）与
     * 破坏前所在 AE 网格（仅当被破坏方块是网格节点宿主时存在，用于定位同网络加速器）。
     */
    private record Removal(ServerLevel level, BlockPos pos, BlockState capturedState, @Nullable IGrid grid) {
    }

    /**
     * 一个「合成 CPU 组成员块被移除」候选：拆除前捕获的整组成员坐标（用于解散结算后
     * 观察剩余成员的真实成型状态）、组标识（最小角）与组所在网格（定位同网络加速器）。
     */
    private record CpuRemoval(ServerLevel level, BlockPos removedPos, BlockState capturedState, DeviceId cpuId,
            List<BlockPos> memberPositions, @Nullable IGrid grid, int queuedTick, int attempts) {
    }

    // 本 tick 内待结算的普通设备移除候选（仅服务端逻辑 tick 线程访问，无需同步）。
    private static final List<Removal> PENDING = new ArrayList<>();

    // 等待「解散 + 再成型」结算的 CPU 组移除候选（跨多个服务端 tick 存活）。
    private static final List<CpuRemoval> CPU_PENDING = new ArrayList<>();

    /**
     * 玩家挖掘方块：记录为「被移除候选」。
     */
    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (event.getLevel() instanceof ServerLevel level) {
            recordRemoval(level, event.getPos());
        }
    }

    /**
     * 爆炸摧毁方块：受影响列表中逐个记录候选。方块实体是否仍在视派发时机而定，
     * 缺失网格仅影响「同网络加速器定位」，玩家背包卡片清理不受影响。
     */
    @SubscribeEvent
    public static void onExplosionDetonate(ExplosionEvent.Detonate event) {
        if (event.getLevel() instanceof ServerLevel level) {
            for (BlockPos pos : event.getAffectedBlocks()) {
                recordRemoval(level, pos);
            }
        }
    }

    /**
     * 记录一个被移除候选。方块没有方块实体时直接忽略——能被绑定为设备/CPU 组的方块必然
     * 是方块实体（AE 机器 / 线缆部件 / CPU 组块），这是每次挖掘都可能命中的廉价过滤。
     * 成型的 CPU 组成员块走专门的组裁决流程，不进入普通设备移除通道。
     */
    private static void recordRemoval(ServerLevel level, BlockPos pos) {
        if (level.isClientSide()) {
            return;
        }
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity == null) {
            return;
        }
        // 成员块为 AE2 原版合成 CPU 或 AdvancedAE 大型 CPU（软兼容）时走整组裁决通道，
        // 快照整组成员坐标作为解散结算后的观察集（见 queueCpuRemoval）。
        CraftingSupport.CpuGroup group = CraftingSupport.cpuGroupOf(blockEntity, true);
        if (group != null) {
            queueCpuRemoval(level, pos, blockEntity.getBlockState(), group);
            return;
        }
        PENDING.add(new Removal(level, pos, blockEntity.getBlockState(), gridOf(level, pos)));
    }

    /**
     * 记录「成型 CPU 组成员块被移除」候选：拆除事件发生时组尚未被拆散，整组成员完整，
     * 快照全部成员坐标作为解散结算后的观察集。
     * <p>
     * 兼容 AE2 原版合成 CPU 与 AdvancedAE 大型 CPU：两者的集群都在任一成员块被拆时整体
     * 解散、由剩余成员重算是否再成型，裁决流程完全一致，只依赖统一的 {@link CpuGroup}
     * 结构视图（见 {@link CraftingSupport#cpuGroupOf}）。
     * <p>
     * 同组同 tick 只保留一条候选：整组被爆炸一次性炸掉多块时，解散结算观察的是「剩余
     * 成员的真实成型状态」，与具体哪一块触发的记录无关，因此同组同 tick 重复事件直接
     * 忽略（首个事件快照的成员集包含本 tick 内所有将被移除的块）。
     */
    private static void queueCpuRemoval(ServerLevel level, BlockPos pos, BlockState state,
            CraftingSupport.CpuGroup group) {
        DeviceId cpuId = group.id();
        int now = level.getServer().getTickCount();
        for (CpuRemoval pending : CPU_PENDING) {
            if (pending.level() != level || !pending.cpuId().equals(cpuId)) {
                continue;
            }
            if (pending.removedPos().equals(pos) || pending.queuedTick() == now) {
                return;
            }
        }
        // 组所在网格经拆除位置的能力解析（AE2 / AdvancedAE CPU 成员方块同为网格节点宿主，
        // 与集群对象取到的网格一致）；解析失败仅退化为「清背包卡片」，不影响玩家手持卡清理。
        CPU_PENDING.add(new CpuRemoval(level, pos, state, cpuId, new ArrayList<>(group.members()),
                gridOf(level, pos), now, 0));
    }

    /**
     * 解析被移除方块所在 AE 网格（用于定位「同网格内、卡槽里可能引用了该设备」的加速器）。
     * 方块不是网格节点宿主（如普通方块实体）或节点未入网时返回 {@code null}。
     */
    @Nullable
    private static IGrid gridOf(ServerLevel level, BlockPos pos) {
        IInWorldGridNodeHost host = level.getCapability(AECapabilities.IN_WORLD_GRID_NODE_HOST, pos, null);
        if (host == null) {
            return null;
        }
        for (Direction direction : Direction.values()) {
            // AeGrid.gridOf 把「节点未入网/销毁时 getGrid() 抛 ISE」统一转译为 null，任一方向命中即返回。
            IGrid grid = AeGrid.gridOf(host.getGridNode(direction));
            if (grid != null) {
                return grid;
            }
        }
        return null;
    }

    /**
     * 服务端 tick 结束：结算本 tick 收集到的普通设备移除候选（合并扫描），并推进 CPU 组
     * 候选的解散结算等待。
     */
    @SubscribeEvent
    public static void onServerTickEnd(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        // 周期兜底：核对在线玩家背包中配置卡绑定的合成 CPU 组是否仍真实成型（不依赖破坏事件，
        // CPU 经任何途径失效——L 形等无效形状、事件漏网、定位失败——都能被及时清掉/换绑）。
        if (++inventoryReconcileTicks > INVENTORY_RECONCILE_INTERVAL) {
            inventoryReconcileTicks = 0;
            reconcileInventoryCards(server);
        }
        if (PENDING.isEmpty() && CPU_PENDING.isEmpty()) {
            return;
        }
        if (!PENDING.isEmpty()) {
            List<Removal> batch = new ArrayList<>(PENDING);
            PENDING.clear();
            processRemovals(batch);
        }
        if (!CPU_PENDING.isEmpty()) {
            processCpuRemovals(server);
        }
    }

    /**
     * 周期兜底：逐张扫描在线玩家背包中的配置卡，按世界几何校验其绑定的合成 CPU 组
     * （记录的结构失效即删除/换绑）。它补充了破坏事件清理覆盖不到的缺口——卡片暂存背包 /
     * 手持、CPU 经改动形状等非事件途径失效、拆除事件漏网时，卡上记录不再永久残留。
     * <p>
     * 不枚举箱子/ME 存储等不可遍历容器与离线玩家（无法枚举），这类卡片在下次被取用/
     * 放入加速器时经对应自愈路径处理。
     */
    private static void reconcileInventoryCards(MinecraftServer server) {
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        if (players.isEmpty()) {
            return;
        }
        for (ServerPlayer player : players) {
            var inventory = player.getInventory();
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                ItemStack stack = inventory.getItem(i);
                if (!ConfigCardData.isConfigCard(stack)) {
                    continue;
                }
                if (ConfigCardBinding.reconcileCpuBindingsOnCard(stack, dim -> server.getLevel(dim))) {
                    // 改写玩家背包内的卡片（数据组件已就地更新，回写以触发客户端同步）。
                    inventory.setItem(i, stack);
                }
            }
        }
    }

    private static void processRemovals(List<Removal> batch) {
        MinecraftServer server = null;
        // 各维度内「确实已被移除」的方块坐标（延迟一帧核对：原方块仍在则跳过）。
        Map<ResourceKey<Level>, Set<BlockPos>> removedByDimension = new HashMap<>();
        List<IGrid> affectedGrids = new ArrayList<>();
        for (Removal removal : batch) {
            BlockState current = removal.level().getBlockState(removal.pos());
            if (current.equals(removal.capturedState())) {
                // 方块仍原样存在：破坏事件被取消（如领地保护），不要误清绑定。
                continue;
            }
            if (server == null) {
                server = removal.level().getServer();
            }
            removedByDimension.computeIfAbsent(removal.level().dimension(), key -> new HashSet<>())
                    .add(removal.pos());
            if (removal.grid() != null && !affectedGrids.contains(removal.grid())) {
                affectedGrids.add(removal.grid());
            }
        }
        if (server == null || removedByDimension.isEmpty()) {
            return;
        }
        // 1) 在线玩家背包中的配置卡：清除绑定到已移除坐标的普通设备。
        ConfigCardScanner.forEachConfigCardInInventories(server.getPlayerList().getPlayers(),
                stack -> ConfigCardData.purgeRemovedBlockDevices(stack, removedByDimension));
        // 2) 与被移除设备同网络的加速器：改写其卡槽卡片（触发库存变化 -> 同步撤销已注入的
        //    CONFIG_CARD 登记，并随方块实体落盘）。
        for (IGrid grid : affectedGrids) {
            try {
                for (IGridNode node : grid.getNodes()) {
                    if (node.getOwner() instanceof AEAcceleratorBlockEntity accelerator
                            && !accelerator.isRemoved() && accelerator.getLevel() != null) {
                        purgeAcceleratorSlotCard(accelerator, removedByDimension);
                    }
                }
            } catch (IllegalStateException destroyed) {
                // 设备移除导致该网格分裂/销毁，节点集合已不可用：跳过，待加速器下次同步自愈。
            }
        }
    }

    /**
     * 推进 CPU 组移除候选：等待拆除事件确实生效 + AE2 解散/再成型结算完成，然后逐组
     * 裁决其去向（见类注释）。被取消的拆除（方块仍在）直接放弃本次候选。
     */
    private static void processCpuRemovals(MinecraftServer server) {
        List<CpuRemoval> snapshot = new ArrayList<>(CPU_PENDING);
        CPU_PENDING.clear();
        int now = server.getTickCount();
        for (CpuRemoval removal : snapshot) {
            BlockState current = removal.level().getBlockState(removal.removedPos());
            if (current.equals(removal.capturedState())) {
                // 拆除事件被取消（如领地保护）：组未受影响，不做任何改动。
                continue;
            }
            if (now - removal.queuedTick() < CPU_SETTLE_TICKS) {
                // 给 AE2 留出「整组解散 -> 按剩余块重新成型」的结算时间，继续等待。
                CPU_PENDING.add(removal);
                continue;
            }
            resolveCpuFate(removal);
        }
    }

    /**
     * 裁决单个 CPU 组的拆除去向：以拆除前快照的整组成员为观察集，读取它们拆除结算后的
     * 真实成型状态（见类注释）。观察区含未加载区块时放弃裁决，避免基于残缺信息误删/误绑。
     */
    private static void resolveCpuFate(CpuRemoval removal) {
        ServerLevel level = removal.level();
        for (BlockPos memberPos : removal.memberPositions()) {
            if (!level.isLoaded(memberPos)) {
                // 观察区尚有区块未加载：本次无法可靠裁决。重排该候选等待区块加载（有重试上限
                // 防止永久不加载的区域无限排队）；超限后放弃——届时由周期兜底
                // （reconcileCpuBindings 的几何自愈）在区块加载后再清理卡片记录。
                if (removal.attempts() >= CPU_SETTLE_MAX_RETRIES) {
                    return;
                }
                CPU_PENDING.add(new CpuRemoval(removal.level(), removal.removedPos(), removal.capturedState(),
                        removal.cpuId(), removal.memberPositions(), removal.grid(), removal.queuedTick(),
                        removal.attempts() + 1));
                return;
            }
        }
        // 收集剩余仍成型成员所属的 CPU 组（AE2 + AdvancedAE 统一结构视图）：无残留 = 整组
        // 失效；一个 = 部分破坏但组仍成型；多个 = 原组被拆成多个独立成型组。
        // 集群的最小角坐标唯一标识整组，因此用结构标识（而非对象句柄）去重即可。
        List<CraftingSupport.CpuGroup> survivors = new ArrayList<>();
        for (BlockPos memberPos : removal.memberPositions()) {
            CraftingSupport.CpuGroup group = CraftingSupport.cpuGroupOf(level.getBlockEntity(memberPos), false);
            if (group != null && survivors.stream().noneMatch(other -> other.id().equals(group.id()))) {
                survivors.add(group);
            }
        }
        if (survivors.isEmpty() || survivors.size() > 1) {
            // 整组失效 / 拆成多个组：原绑定指向的「这一组」已不存在 -> 从卡上删除。
            applyCpuFate(removal, null, null);
        } else {
            CraftingSupport.CpuGroup survivor = survivors.get(0);
            // 部分破坏仍成型：改写为新集群的标识（新最小角）与几何（新最大角），
            // 卡片高亮与加速器注入自动跟随真实成型状态。
            applyCpuFate(removal, survivor.id(), survivor.boundsMax());
        }
    }

    /**
     * 把「拆除后去向」落到能触达的全部卡片上：
     * <ul>
     *   <li>在线玩家背包中的配置卡（手持查看/改绑场景的主入口）；</li>
     *   <li>组拆除前所在网格中加速器的卡槽卡片（注入撤销/换绑需触发同步才能落盘）。</li>
     * </ul>
     *
     * @param replacement 再成型后集群的标识；为 {@code null} 表示删除该组绑定
     * @param newMax      再成型后集群的最大角方块坐标（{@code replacement} 非空时必填）
     */
    private static void applyCpuFate(CpuRemoval removal, @Nullable DeviceId replacement, @Nullable BlockPos newMax) {
        DeviceId oldCpu = removal.cpuId();
        MinecraftServer server = removal.level().getServer();
        ConfigCardScanner.forEachConfigCardInInventories(server.getPlayerList().getPlayers(), stack -> {
            if (ConfigCardData.isDeviceBound(stack, oldCpu)) {
                applyCpuFateToStack(stack, oldCpu, replacement, newMax);
            }
        });
        IGrid grid = removal.grid();
        if (grid == null) {
            return;
        }
        try {
            for (IGridNode node : grid.getNodes()) {
                if (node.getOwner() instanceof AEAcceleratorBlockEntity accelerator
                        && !accelerator.isRemoved() && accelerator.getLevel() != null) {
                    AppEngInternalInventory inventory = accelerator.getConfigCardInventory();
                    ItemStack card = inventory.getStackInSlot(0);
                    if (ConfigCardData.isConfigCard(card) && ConfigCardData.isDeviceBound(card, oldCpu)
                            && applyCpuFateToStack(card, oldCpu, replacement, newMax)) {
                        // setItemDirect 触发 onHostInventoryChanged -> syncConfigCardDevices：
                        // 删除组时撤销 CONFIG_CARD 注入；换绑时撤销旧标识、按新集群标识重注入。
                        inventory.setItemDirect(0, card);
                        accelerator.saveChanges();
                    }
                }
            }
        } catch (IllegalStateException destroyed) {
            // 网格已分裂/销毁（拆到控制器等）：跳过，加速器下次卡来源同步自愈。
        }
    }

    private static boolean applyCpuFateToStack(ItemStack stack, DeviceId oldCpu,
            @Nullable DeviceId replacement, @Nullable BlockPos newMax) {
        return replacement == null
                ? ConfigCardData.removeCpuBinding(stack, oldCpu)
                : ConfigCardData.replaceCpuBinding(stack, oldCpu, replacement, newMax);
    }

    /**
     * 清除加速器卡槽内配置卡上指向「已移除方块」的设备绑定；有变化时经
     * {@code setItemDirect} 改写槽位物品，触发库存变化回调同步撤销对应卡来源注入。
     */
    private static void purgeAcceleratorSlotCard(AEAcceleratorBlockEntity accelerator,
            Map<ResourceKey<Level>, Set<BlockPos>> removedByDimension) {
        AppEngInternalInventory inventory = accelerator.getConfigCardInventory();
        ItemStack card = inventory.getStackInSlot(0);
        if (!ConfigCardData.isConfigCard(card)
                || !ConfigCardData.purgeRemovedBlockDevices(card, removedByDimension)) {
            return;
        }
        // setItemDirect 会触发 onHostInventoryChanged -> syncConfigCardDevices，
        // 把已不在卡上 / 网络内的 CONFIG_CARD 登记立即撤销；随后强制方块实体落盘。
        inventory.setItemDirect(0, card);
        accelerator.saveChanges();
    }
}
