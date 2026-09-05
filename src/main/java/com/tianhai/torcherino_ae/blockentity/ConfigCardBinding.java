package com.tianhai.torcherino_ae.blockentity;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import com.tianhai.torcherino_ae.core.TargetRegistry;
import org.jetbrains.annotations.Nullable;

import appeng.api.inventories.InternalInventory;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.util.inv.AppEngInternalInventory;
import appeng.util.inv.filter.IAEItemFilter;
import com.tianhai.torcherino_ae.api.AccelSource;
import com.tianhai.torcherino_ae.api.DeviceId;
import com.tianhai.torcherino_ae.item.ConfigCardData;
import com.tianhai.torcherino_ae.item.ModItems;
import com.tianhai.torcherino_ae.network.DeviceScanner;
import com.tianhai.torcherino_ae.network.crafting.CraftingSupport;
import com.tianhai.torcherino_ae.util.ConfigCardScanner;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * 加速器配置卡在方块实体上的绑定协调组件。
 * <p>
 * 封装配置卡在方块实体上的<b>全部</b>生命周期职责：
 * <ul>
 *   <li>单格配置卡库存（含「仅允许放入绑定本机的卡片」过滤器）；</li>
 *   <li>库存变化（放入/取出/更换）时把卡上设备增量注入/撤销到登记表；</li>
 *   <li>方块被移除时清空槽位内与在线玩家背包中绑定本机的卡片绑定；</li>
 *   <li>库存的 NBT 持久化。</li>
 * </ul>
 * 卡来源（{@link AccelSource#CONFIG_CARD}）的注入结果随方块实体的 {@link TargetRegistry}
 * 持久化（来源标记随档保存），重启后取出配置卡仍能精确撤销注入。
 * <p>
 * 依赖关系只回指所属方块实体（同包协作）：网格经 {@link AEAcceleratorBlockEntity#grid()}、
 * 登记表经同包字段 {@code targetRegistry}、保存与缓存标脏经方块实体委托方法。
 */
public final class ConfigCardBinding {

    // NBT 存储键名：配置卡放入的槽位库存。
    private static final String TAG_CONFIG_CARD = "config_card_inventory";

    // 所属方块实体（提供网格、登记表、保存与缓存标脏）。
    private final AEAcceleratorBlockEntity host;

    // 配置卡库存：单格，仅允许放入「绑定本机的加速器配置卡」。
    private final AppEngInternalInventory configCardInventory;

    ConfigCardBinding(AEAcceleratorBlockEntity host) {
        this.host = host;
        // 创建单格配置卡库存：仅接受绑定本机的「加速器配置卡」（绑定比较含维度与坐标，
        // 防止异地维度的卡片混入；未绑定的卡片同样被拒绝）。
        this.configCardInventory = new AppEngInternalInventory(host, 1, 1, new IAEItemFilter() {
            @Override
            public boolean allowInsert(InternalInventory inv, int slot, ItemStack stack) {
                return stack.is(ModItems.ACCELERATOR_CONFIG_CARD.get()) && isBoundToSelf(stack);
            }
        });
    }

    /**
     * 本机的设备标识（维度 + 坐标 + BLOCK_ENTITY 种类）。
     */
    @Nullable
    private DeviceId selfDeviceId() {
        Level world = host.getLevel();
        return world == null ? null : DeviceId.ofBlock(world.dimension(), host.getBlockPos());
    }

    /**
     * 卡片绑定的是否为本机。
     */
    private boolean isBoundToSelf(ItemStack stack) {
        DeviceId self = selfDeviceId();
        return self != null && ConfigCardData.isBoundTo(stack, self);
    }

    /**
     * 配置卡库存：单格，仅允许存放「绑定本机的加速器配置卡」。
     */
    public AppEngInternalInventory getInventory() {
        return configCardInventory;
    }

    /**
     * 库存内容变化回调（由方块实体的 {@code onChangeInventory} 转发，仅关心配置卡槽位变化）。
     */
    void onHostInventoryChanged(AppEngInternalInventory inv, int slot) {
        if (inv == configCardInventory) {
            // 卡片放入/取出/更换：先自愈卡上绑定的合成 CPU 组（记录的结构失效即删/换绑，
            // 见 reconcileCpuBindings），再同步卡注入的设备（无需等待下一个重建周期）。
            reconcileCpuBindings();
            syncConfigCardDevices();
        }
    }

    /**
     * 自愈校验配置卡上绑定的合成 CPU 组（服务端）：逐条核对卡上记录的「最小角/最大角结构」
     * 是否仍是当前世界<b>真实成型</b>的集群（CPU 组有效性完全由世界几何决定，见
     * {@link CraftingSupport#cpuGroupAt}）。
     * <p>
     * 触发点：本机定期 tick（方块实体保持加载即持续自愈，即使加速器未接线/离网）与配置卡槽
     * 内容变化。它是 {@code ConfigCardCleanup} 破坏事件清理的<b>兜底</b>——后者只由「玩家挖掘 /
     * 爆炸」两类事件派发，且定位同网络加速器依赖拆除位置的能力解析，任一环节脱钩（非成员块
     * 拆除引发的解散、拆除事件未派发、网格解析失败、卡片暂存他处等）都会让卡上记录残留；
     * 本方法不依赖事件与网格，直接以世界几何判定并修复。
     * <p>
     * 裁决语义与拆除清理一致（见 {@code ConfigCardCleanup#resolveCpuFate}）：记录仍与真实结构
     * 一致则不动；不一致时以记录包围盒为观察区——恰一个成型组残留则改写为新组标识/几何
     * （换绑，如拆掉一角/一层后剩余部分仍成型），无残留或拆成多个组则删除该组绑定。观察区
     * 含未加载区块时放弃（不基于残缺信息裁决）。
     * <p>
     * 有改动时经 {@code setItemDirect} 改写槽位卡片并落盘；库存变化回调会再次进入本方法
     * （彼时已无改动，安全返回）并同步卡来源注入（撤销失效组登记 / 按新组标识重注入）。
     */
    void reconcileCpuBindings() {
        Level level = host.getLevel();
        if (level == null || level.isClientSide()) {
            return;
        }
        ItemStack card = configCardInventory.getStackInSlot(0);
        if (!ConfigCardData.isConfigCard(card)) {
            return;
        }
        boolean changed = reconcileCpuBindingsOnCard(card,
                dim -> dim.equals(level.dimension()) ? level : null);
        if (changed) {
            // 改写槽位内卡片并落盘；setItemDirect 触发 onHostInventoryChanged -> syncConfigCardDevices，
            // 把已不在卡上/网络内的 CONFIG_CARD 登记立即撤销（换绑则撤销旧标识、按新标识重注入）。
            configCardInventory.setItemDirect(0, card);
            host.saveChanges();
        }
    }

    /**
     * 按世界几何校验卡片上绑定的全部合成 CPU 组（就地改写卡片数据组件），返回是否有改动。
     * <p>
     * 供两处复用：加速器卡槽内的卡片（{@link #reconcileCpuBindings()} 为其包装）与
     * {@code ConfigCardCleanup} 对在线玩家背包卡片的周期性兜底。判定不依赖任何破坏事件与
     * 网格，直接以 CPU 组记录的「最小角/最大角」结构对照当前世界是否仍真实成型
     * （见 {@link CraftingSupport#cpuGroupAt}）；记录失效时按「删 / 换绑」裁决
     * （见 {@link CraftingSupport#cpuGroupsWithin}）。
     *
     * @param levelOf 按 CPU 所在维度解析世界（同一张卡可能绑定多个维度的 CPU，各自取对应世界）；
     *                解析不到（维度未加载 / 非本机世界）时该 CPU 保持现状
     * @return 是否有卡上记录被删除或改写
     */
    static boolean reconcileCpuBindingsOnCard(ItemStack card, Function<ResourceKey<Level>, Level> levelOf) {
        List<DeviceId> boundCpus = ConfigCardData.getBoundDevices(card).stream()
                .filter(DeviceId::isCpu)
                .toList();
        if (boundCpus.isEmpty()) {
            return false;
        }
        boolean changed = false;
        for (DeviceId cpuId : boundCpus) {
            Level level = levelOf.apply(cpuId.dimension());
            if (level == null || level.isClientSide()) {
                continue; // 该维度世界不可用：无法校验，保持现状
            }
            BlockPos boundsMax = ConfigCardData.cpuBoundsMaxOf(card, cpuId);
            if (boundsMax == null) {
                continue; // 旧档/手工数据缺几何记录：无结构可核对，保持现状
            }
            // 1) O(1) 快检：记录结构仍是世界中的真实成型集群 -> 绑定有效，无需任何改动。
            if (CraftingSupport.cpuGroupAt(level, cpuId, boundsMax) != null) {
                continue;
            }
            // 2) 记录已与真实结构不一致：以记录包围盒为观察区，按「删 / 换绑」语义裁决去向。
            List<CraftingSupport.CpuGroup> survivors = CraftingSupport.cpuGroupsWithin(level,
                    cpuId.pos(), boundsMax);
            if (survivors == null) {
                continue; // 观察区含未加载区块：本次放弃，待下次周期再判
            }
            if (survivors.isEmpty() || survivors.size() > 1) {
                changed |= ConfigCardData.removeCpuBinding(card, cpuId);
            } else {
                CraftingSupport.CpuGroup survivor = survivors.get(0);
                changed |= ConfigCardData.replaceCpuBinding(card, cpuId, survivor.id(), survivor.boundsMax());
            }
        }
        return changed;
    }

    /**
     * 按配置卡上的绑定信息同步「卡来源（CONFIG_CARD）的加速注入」。
     * <p>
     * 卡片放入本机配置卡槽后：把卡上记录、且位于本网络内可加速（非自身）的设备登记为
     * 卡来源，默认按最高倍数——「卡在则加速」。卡片取出、更换、绑定数据变化或网格接入
     * 变化时重新同步，把不再有效的卡来源设备撤销——「卡走则停」。
     * <p>
     * 卡来源的注入随登记表<b>持久化</b>（带来源标记存 NBT），因此重启后取出配置卡
     * 依然能按来源精确撤销，不会留下无法撤销的永久加速。
     * <p>
     * 玩家通过 GUI 显式设置（PLAYER 来源）的设备不受卡同步覆盖，玩家设置优先。
     *
     * @implNote 本方法由「配置卡槽变化 / 主节点状态变化」事件触发，而非每个 tick 运行。
     *           只在变化时执行，注入与撤销均为增量式（比较登记表的当前状态），无全量重写。
     */
    private void syncConfigCardDevices() {
        // 客户端没有权威网格，同步只在服务端进行。
        if (host.getLevel() != null && host.getLevel().isClientSide()) {
            return;
        }
        // 网格尚不可用（加载中 / 未接线）：无法判定卡上设备归属哪个网络，跳过本次同步；
        // 已注入的卡来源设备保持现状，待网格就绪后由 onMainNodeStateChanged 再次同步。
        IGrid grid = host.grid();
        if (grid == null) {
            return;
        }
        Set<DeviceId> bound = new HashSet<>();
        ItemStack card = configCardInventory.getStackInSlot(0);
        if (ConfigCardData.isConfigCard(card)) {
            bound.addAll(ConfigCardData.getBoundDevices(card));
        }
        // 只注入「本网络内可加速且非自身」的设备。
        // 性能要点：这里单次遍历网格收集全部可加速设备标识，再与卡上绑定集合求交集，
        // 避免对每个绑定设备各遍历一次全网格（满绑定 64 条时即 64 次全网格遍历）。
        Set<DeviceId> inNetwork = new HashSet<>();
        for (IGridNode node : grid.getNodes()) {
            // 其它加速器不是可加速设备（同网络仅先放置者工作），不能作为卡来源设备绑定。
            if (AEAcceleratorBlockEntity.isAcceleratorOwner(node.getOwner())) {
                continue;
            }
            if (!DeviceScanner.isAcceleratableNode(node, host)) {
                continue;
            }
            DeviceId id = DeviceScanner.deviceIdOf(node.getOwner());
            if (id != null && bound.contains(id)) {
                inNetwork.add(id);
            }
        }
        // 追加本网络内的合成 CPU 组：CPU 由多个方块连成，网格以「集群（组）」为单位暴露
        // （getCpus() 每条 = 一组）；卡上绑定的是组标识（最小角），此处按组匹配。
        // 注入后等同「选中该 CPU 的智能加速」：该 CPU 合成期间联动加速参与合成的机器。
        ResourceKey<Level> dimension = host.dimension();
        for (ICraftingCPU cpu : grid.getCraftingService().getCpus()) {
            DeviceId id = CraftingSupport.cpuDeviceId(dimension, cpu);
            if (id != null && bound.contains(id)) {
                inNetwork.add(id);
            }
        }
        boolean changed = false;
        // 注入：卡上且网络内、当前未被任何来源加速的设备，按当前最高倍数登记为卡来源。
        for (DeviceId id : inNetwork) {
            if (host.targetRegistry.multiplierFor(id, -1) < 0) {
                host.targetRegistry.set(id, host.getAccelMultiplier(), AccelSource.CONFIG_CARD);
                changed = true;
            }
        }
        // 撤销：已是卡来源、但已不在「卡上且网络内」的设备（取出卡 / 更换卡 / 设备离开网络 / 换网）。
        for (DeviceId id : host.targetRegistry.idsOfSource(AccelSource.CONFIG_CARD)) {
            if (!inNetwork.contains(id)) {
                host.targetRegistry.set(id, 1, AccelSource.CONFIG_CARD);
                changed = true;
            }
        }
        if (changed) {
            host.markTargetsDirty();
            host.saveChanges();
        }
    }

    /**
     * 方块被移除（破坏、爆炸、活塞等任何途径）时清理绑定本机的配置卡（由方块实体
     * {@code setRemoved} 调用，仅服务端执行；客户端区块卸载同样触发，由 isClientSide 保护）：
     * <ul>
     *   <li>本机槽位内的卡片：清空其绑定（避免「即插即用」配置指向已摧毁的加速器）；</li>
     *   <li>在线玩家背包中绑定本机的卡片：清空其绑定。</li>
     * </ul>
     */
    void onHostRemoved() {
        Level levelNow = host.getLevel();
        if (levelNow == null || levelNow.isClientSide()) {
            return;
        }
        // 槽位内的卡：直接改写其 NBT（库存物品引用在服务端是权威对象）。
        ItemStack card = configCardInventory.getStackInSlot(0);
        if (ConfigCardData.isConfigCard(card)
                && ConfigCardData.getBoundAccelerator(card) != null) {
            ConfigCardData.unbindAccelerator(card);
            configCardInventory.setItemDirect(0, card);
        }
        // 在线玩家背包中的卡：扫描全部物品槽（主物品栏/装备/副手），清理绑定本机的卡片。
        DeviceId selfId = DeviceId.ofBlock(levelNow.dimension(), host.getBlockPos());
        ConfigCardScanner.forEachConfigCardInInventories(levelNow.players(),
                stack -> {
                    if (ConfigCardData.isBoundTo(stack, selfId)) {
                        ConfigCardData.unbindAccelerator(stack);
                    }
                });
    }

    /**
     * 持久化配置卡库存内容，重启后保留玩家放入的配置卡。
     */
    void save(CompoundTag data, HolderLookup.Provider registries) {
        configCardInventory.writeToNBT(data, TAG_CONFIG_CARD, registries);
    }

    /**
     * 恢复配置卡库存内容。
     */
    void load(CompoundTag data, HolderLookup.Provider registries) {
        configCardInventory.readFromNBT(data, TAG_CONFIG_CARD, registries);
    }
}
