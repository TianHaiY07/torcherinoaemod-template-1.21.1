package com.tianhai.torcherino_ae.item;

import java.util.List;
import java.util.Set;

import com.tianhai.torcherino_ae.api.DeviceId;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Item.TooltipContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

/**
 * 加速器配置卡物品。
 * <p>
 * 配置卡用于「离线配置、即插即用」：手持卡片 Shift+右键本模组的 AE 加速器即可绑定
 * 该加速器，右键网络内可加速设备即可把设备写入卡片（重复右键取消绑定）；
 * 将卡片放入加速器的配置卡槽后，加速器自动按卡片记录对设备启用加速，无需 GUI 勾选。
 * <p>
 * 本类只承载「物品」语义（注册与 tooltip）；绑定数据的 Data Component 读写全部走
 * 数据契约 {@link ConfigCardData}，服务端逻辑与客户端渲染也只依赖该数据契约。
 */
public class AcceleratorConfigCardItem extends Item {

    public AcceleratorConfigCardItem(Properties properties) {
        super(properties);
    }

    /**
     * 工具提示：展示绑定状态（绑定的加速器坐标与绑定设备数量），供玩家离线确认配置。
     */
    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        DeviceId bound = ConfigCardData.getBoundAccelerator(stack);
        if (bound != null) {
            BlockPos pos = bound.pos();
            tooltip.add(Component.translatable("item.torcherino_ae_mod.accelerator_config_card.bound_target",
                    pos.getX(), pos.getY(), pos.getZ()).withStyle(ChatFormatting.AQUA));
        } else {
            tooltip.add(Component.translatable(
                    "item.torcherino_ae_mod.accelerator_config_card.no_target").withStyle(ChatFormatting.DARK_GRAY));
        }
        Set<DeviceId> devices = ConfigCardData.getBoundDevices(stack);
        if (devices.isEmpty()) {
            tooltip.add(Component.translatable(
                    "item.torcherino_ae_mod.accelerator_config_card.no_devices").withStyle(ChatFormatting.DARK_GRAY));
        } else {
            tooltip.add(Component.translatable("item.torcherino_ae_mod.accelerator_config_card.bound_devices",
                    devices.size()).withStyle(ChatFormatting.GREEN));
        }
        tooltip.add(Component.translatable("item.torcherino_ae_mod.accelerator_config_card.tooltip")
                .withStyle(ChatFormatting.GRAY));
    }
}
