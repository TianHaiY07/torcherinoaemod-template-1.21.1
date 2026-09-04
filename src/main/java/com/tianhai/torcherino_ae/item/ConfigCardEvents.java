package com.tianhai.torcherino_ae.item;

import org.jetbrains.annotations.Nullable;

import appeng.api.networking.IGridNode;
import com.tianhai.torcherino_ae.Torcherinoaemod;
import com.tianhai.torcherino_ae.api.DeviceId;
import com.tianhai.torcherino_ae.block.ModBlocks;
import com.tianhai.torcherino_ae.network.DeviceScanner;
import com.tianhai.torcherino_ae.network.crafting.CraftingSupport;
import com.tianhai.torcherino_ae.util.DebugLog;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.UseItemOnBlockEvent;

/**
 * 加速器配置卡的交互事件处理器。
 * <p>
 * 使用 {@link UseItemOnBlockEvent} 的 {@code ITEM_BEFORE_BLOCK} 阶段——该阶段在方块
 * {@code useItemOn} 之前派发，可先于「打开设备界面」拦截交互：
 * <ul>
 *   <li>手持配置卡 Shift+右键本模组加速器：绑定 / 切换加速器（已绑定的再次右键 =
 *       取消绑定并清空设备列表）；</li>
 *   <li>手持已绑定加速器的配置卡右键可加速 AE 设备：绑定该设备（重复右键取消），
 *       目标判定复用 {@link DeviceScanner#findAcceleratableNode}；</li>
 *   <li>右键已成形的合成 CPU 组：把<b>整组</b>加入/移出可绑定范围（合成 CPU 由多个方块
 *       连成，一次右键 = 一组一个标记，组外包围盒随卡记录，供高亮 pass 画整组线框；
 *       兼容 AE2 原版 CPU 与 AdvancedAE 大型 CPU 结构的成员块，解析统一见
 *       {@link CraftingSupport#cpuGroupOf}）。</li>
 *   <li>卡片未绑定加速器时右键设备：给出提示并拦截，避免误开设备界面。</li>
 * </ul>
 * 客户端与服务端返回相同结果、仅服务端修改数据，符合 NeoForge 事件约定。
 */
@EventBusSubscriber(modid = Torcherinoaemod.MOD_ID)
public final class ConfigCardEvents {

    private ConfigCardEvents() {
    }

    /**
     * 手持配置卡右键方块时的统一拦截入口。
     */
    @SubscribeEvent
    public static void onUseItemOnBlock(UseItemOnBlockEvent event) {
        if (event.getUsePhase() != UseItemOnBlockEvent.UsePhase.ITEM_BEFORE_BLOCK) {
            return;
        }
        ItemStack card = event.getItemStack();
        if (!ConfigCardData.isConfigCard(card)) {
            return;
        }
        Player player = event.getPlayer();
        if (player == null) {
            return;
        }
        Level level = event.getLevel();
        BlockPos pos = event.getPos();

        // 情形一：目标为本模组加速器。
        if (isAcceleratorBlock(level, pos)) {
            onAcceleratorTargeted(event, player, card, pos);
            return;
        }

        // 情形二：目标为可加速的 AE 设备（复用与加速脉冲一致的判定：含黑名单过滤、坐标解析）。
        IGridNode node = DeviceScanner.findAcceleratableNode(level.getBlockEntity(pos), null, event.getFace());
        if (node != null) {
            onDeviceTargeted(event, player, card, node);
            return;
        }
        // 情形三：目标为已成形的合成 CPU 组（多块连成的结构，整组视为一台可绑定设备；
        // 是 AE 加速器「能够加速」的目标之一——绑定后放入加速器即开启对该 CPU 的智能加速）。
        if (CraftingSupport.isCpuGroupMember(level.getBlockEntity(pos))) {
            onCpuTargeted(event, player, card, pos);
            return;
        }
        // 目标既不是加速器、可加速设备也不是合成 CPU：放行（不干扰正常放置/使用）。
    }

    /**
     * 判断指定坐标是否为本模组的 AE 加速器方块。
     */
    private static boolean isAcceleratorBlock(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.getBlock() == ModBlocks.AE_ACCELERATOR.get();
    }

    /**
     * 目标为加速器：Shift+右键绑定/取消绑定；普通右键放行（打开加速器界面）。
     */
    private static void onAcceleratorTargeted(UseItemOnBlockEvent event, Player player, ItemStack card, BlockPos pos) {
        // 非 Shift 右键：交给方块打开配置界面（不拦截）。
        if (!player.isShiftKeyDown()) {
            return;
        }
        if (!event.getLevel().isClientSide()) {
            boolean bound = ConfigCardData.bindOrUnbindAccelerator(card,
                    DeviceId.ofBlock(event.getLevel().dimension(), pos));
            if (bound) {
                player.displayClientMessage(Component.translatable(
                        "item.torcherino_ae_mod.accelerator_config_card.bind_success",
                        pos.getX(), pos.getY(), pos.getZ()), true);
            } else {
                player.displayClientMessage(Component.translatable(
                        "item.torcherino_ae_mod.accelerator_config_card.unbind_success"), true);
            }
            DebugLog.info("[配置卡] 玩家 {} 对加速器 {} 执行绑定操作（bound={}）",
                    player.getName().getString(), pos, bound);
        }
        // 客户端与服务端一致：拦截本次交互，不打开界面。
        event.cancelWithResult(ItemInteractionResult.CONSUME);
    }

    /**
     * 目标为可加速设备：服务端执行绑定切换，客户端同样拦截（保持一致结果）。
     */
    private static void onDeviceTargeted(UseItemOnBlockEvent event, Player player, ItemStack card, IGridNode node) {
        if (event.getLevel().isClientSide()) {
            event.cancelWithResult(ItemInteractionResult.CONSUME);
            return;
        }
        // 未绑定加速器的卡不能绑定设备：提示并拦截，避免误开目标设备界面。
        if (ConfigCardData.getBoundAccelerator(card) == null) {
            player.displayClientMessage(Component.translatable(
                    "item.torcherino_ae_mod.accelerator_config_card.not_bound_hint"), true);
            event.cancelWithResult(ItemInteractionResult.CONSUME);
            return;
        }
        // 生成稳定设备标识（含维度与种类：方块=BLOCK_ENTITY，部件=PART），写入卡片 Data Component。
        @Nullable
        DeviceId deviceId = DeviceScanner.deviceIdOf(node.getOwner());
        if (deviceId == null) {
            event.cancelWithResult(ItemInteractionResult.CONSUME);
            return;
        }
        boolean wasBound = ConfigCardData.isDeviceBound(card, deviceId);
        boolean nowBound = ConfigCardData.toggleBoundDevice(card, deviceId);
        if (!wasBound && !nowBound) {
            player.displayClientMessage(Component.translatable(
                    "item.torcherino_ae_mod.accelerator_config_card.bind_device_fail"), true);
        } else if (nowBound) {
            player.displayClientMessage(Component.translatable(
                    "item.torcherino_ae_mod.accelerator_config_card.bind_device_success"), true);
        } else {
            player.displayClientMessage(Component.translatable(
                    "item.torcherino_ae_mod.accelerator_config_card.unbind_device_success"), true);
        }
        DebugLog.info("[配置卡] 玩家 {} 对设备 {} 执行设备绑定操作（nowBound={}）",
                player.getName().getString(), deviceId.stableKey(), nowBound);
        event.cancelWithResult(ItemInteractionResult.CONSUME);
    }

    /**
     * 目标为已成形的合成 CPU 组（多块结构）：服务端以<b>整组</b>为粒度绑定/取消绑定。
     * <p>
     * 一个 CPU 组由多个方块连成，组内任意成员块被右键都解析到同一个集群，因而只占卡片上
     * 一条记录（标记合并）；组外包围盒（最小角为标识坐标、最大角另存）一并写入卡片，供
     * {@code ConfigCardHighlightPass} 按 CPU 组外围画线框。客户端同样拦截（保持一致结果）。
     */
    private static void onCpuTargeted(UseItemOnBlockEvent event, Player player, ItemStack card, BlockPos pos) {
        Level level = event.getLevel();
        if (level.isClientSide()) {
            event.cancelWithResult(ItemInteractionResult.CONSUME);
            return;
        }
        // 未绑定加速器的卡不能绑定 CPU：提示并拦截，避免误开目标方块界面。
        if (ConfigCardData.getBoundAccelerator(card) == null) {
            player.displayClientMessage(Component.translatable(
                    "item.torcherino_ae_mod.accelerator_config_card.not_bound_hint"), true);
            event.cancelWithResult(ItemInteractionResult.CONSUME);
            return;
        }
        // 解析被点击块所属的 CPU 组结构（AE2 原版或 AdvancedAE 大型 CPU，统一视图）：
        // 若为 null（理论不可达，isCpuGroupMember 已通过），直接拦截而不产生任何副作用
        // （这类方块本就没有可被打开的界面）。
        CraftingSupport.CpuGroup group = CraftingSupport.cpuGroupOf(level.getBlockEntity(pos), false);
        if (group == null) {
            event.cancelWithResult(ItemInteractionResult.CONSUME);
            return;
        }
        DeviceId cpuId = group.id();
        boolean wasBound = ConfigCardData.isDeviceBound(card, cpuId);
        boolean nowBound = ConfigCardData.toggleBoundCpu(card, cpuId, group.boundsMax());
        if (!wasBound && !nowBound) {
            player.displayClientMessage(Component.translatable(
                    "item.torcherino_ae_mod.accelerator_config_card.bind_device_fail"), true);
        } else if (nowBound) {
            player.displayClientMessage(Component.translatable(
                    "item.torcherino_ae_mod.accelerator_config_card.bind_cpu_success"), true);
        } else {
            player.displayClientMessage(Component.translatable(
                    "item.torcherino_ae_mod.accelerator_config_card.unbind_cpu_success"), true);
        }
        DebugLog.info("[配置卡] 玩家 {} 对合成 CPU 组 {}（max={}）执行设备绑定操作（nowBound={}）",
                player.getName().getString(), cpuId.stableKey(), group.boundsMax(), nowBound);
        event.cancelWithResult(ItemInteractionResult.CONSUME);
    }
}
