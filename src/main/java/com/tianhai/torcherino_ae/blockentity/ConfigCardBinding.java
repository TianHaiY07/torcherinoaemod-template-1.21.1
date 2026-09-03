package com.tianhai.torcherino_ae.blockentity;

import java.util.HashSet;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import appeng.api.inventories.InternalInventory;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.util.inv.AppEngInternalInventory;
import appeng.util.inv.filter.IAEItemFilter;
import com.tianhai.torcherino_ae.api.AccelSource;
import com.tianhai.torcherino_ae.api.DeviceId;
import com.tianhai.torcherino_ae.network.DeviceScanner;
import com.tianhai.torcherino_ae.item.ConfigCardData;
import com.tianhai.torcherino_ae.item.ModItems;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * 加速器配置卡绑定协调组件（P2 拆分自 {@link AEAcceleratorBlockEntity}）。
 * <p>
 * 封装配置卡在方块实体上的<b>全部</b>生命周期职责：
 * <ul>
 *   <li>单格配置卡库存（含「仅允许放入绑定本机的卡片」过滤器）；</li>
 *   <li>库存变化（放入/取出/更换）时把卡上设备增量注入/撤销到登记表；</li>
 *   <li>方块被移除时清空槽位内与在线玩家背包中绑定本机的卡片绑定；</li>
 *   <li>库存的 NBT 持久化。</li>
 * </ul>
 * 卡来源（{@link AccelSource#CONFIG_CARD}）的注入结果随方块实体的 {@link TargetRegistry}
 * 持久化，重启后取出配置卡仍可精确撤销（P1 修复的旧缺陷）。
 * <p>
 * 依赖关系只回指方块实体（同包协作）：网格经 {@link AEAcceleratorBlockEntity#grid()}、
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
            // 卡片放入/取出/更换 -> 立即同步卡注入的设备（无需等待下一个重建周期）。
            syncConfigCardDevices();
        }
    }

    /**
     * 按配置卡上的绑定信息同步「卡来源（CONFIG_CARD）的加速注入」。
     * <p>
     * 卡片放入本机配置卡槽后：把卡上记录、且位于本网络内可加速（非自身）的设备登记为
     * 卡来源，默认按最高倍数——「卡在则加速」。卡片取出、更换、绑定数据变化或网格接入
     * 变化时重新同步，把不再有效的卡来源设备撤销——「卡走则停」。
     * <p>
     * P1 起卡来源的注入<b>随方块实体持久化</b>（登记表带来源标记存 NBT），彻底修复了
     * 旧实现「注入结果写进持久化集合而来源信息只存内存，重启后取出配置卡无法撤销」的缺陷。
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
            if (!DeviceScanner.isAcceleratableNode(node, host)) {
                continue;
            }
            DeviceId id = DeviceScanner.deviceIdOf(node.getOwner());
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
        for (Player player : levelNow.players()) {
            Inventory inventory = player.getInventory();
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                ItemStack stack = inventory.getItem(i);
                if (ConfigCardData.isConfigCard(stack)
                        && ConfigCardData.isBoundTo(stack, selfId)) {
                    ConfigCardData.unbindAccelerator(stack);
                }
            }
        }
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
