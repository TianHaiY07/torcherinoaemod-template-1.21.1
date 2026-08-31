package com.tianhai.torcherino_ae.item;

import org.jetbrains.annotations.Nullable;

import appeng.api.networking.IGridNode;
import com.tianhai.torcherino_ae.Torcherinoaemod;
import com.tianhai.torcherino_ae.block.ModBlocks;
import com.tianhai.torcherino_ae.common.AE2GridSupport;
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
 *       目标判定复用 {@link AE2GridSupport#findAcceleratableNode}；</li>
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
        if (!AcceleratorConfigCardItem.isConfigCard(card)) {
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
        IGridNode node = AE2GridSupport.findAcceleratableNode(level.getBlockEntity(pos), null);
        if (node == null) {
            // 目标既不是加速器也不是可加速设备：放行（不干扰正常放置/使用）。
            return;
        }
        onDeviceTargeted(event, player, card, node);
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
            boolean bound = AcceleratorConfigCardItem.bindOrUnbindAccelerator(card, pos);
            if (bound) {
                player.displayClientMessage(Component.translatable(
                        "item.torcherino_ae_mod.accelerator_config_card.bind_success",
                        pos.getX(), pos.getY(), pos.getZ()), true);
            } else {
                player.displayClientMessage(Component.translatable(
                        "item.torcherino_ae_mod.accelerator_config_card.unbind_success"), true);
            }
            Torcherinoaemod.LOGGER.info("[配置卡] 玩家 {} 对加速器 {} 执行绑定操作（bound={}）",
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
        if (AcceleratorConfigCardItem.getBoundAccelerator(card) == null) {
            player.displayClientMessage(Component.translatable(
                    "item.torcherino_ae_mod.accelerator_config_card.not_bound_hint"), true);
            event.cancelWithResult(ItemInteractionResult.CONSUME);
            return;
        }
        @Nullable
        String deviceId = AE2GridSupport.deviceIdOf(node.getOwner());
        if (deviceId == null) {
            event.cancelWithResult(ItemInteractionResult.CONSUME);
            return;
        }
        boolean wasBound = AcceleratorConfigCardItem.isDeviceBound(card, deviceId);
        boolean nowBound = AcceleratorConfigCardItem.toggleBoundDevice(card, deviceId);
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
        Torcherinoaemod.LOGGER.info("[配置卡] 玩家 {} 对设备 {} 执行设备绑定操作（nowBound={}）",
                player.getName().getString(), deviceId, nowBound);
        event.cancelWithResult(ItemInteractionResult.CONSUME);
    }
}
