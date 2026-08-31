package com.tianhai.torcherino_ae.item;

import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.TreeSet;

import org.jetbrains.annotations.Nullable;

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
 * 绑定数据（绑定的加速器坐标 + 设备标识列表）以 {@link ConfigCardData} Data Component
 * 存储于 ItemStack（服务端权威，随物品移动/复制以 NBT 形式保留）；本类集中管理读写，
 * 交互提示文案走 lang 文件。
 */
public class AcceleratorConfigCardItem extends Item {

    // 单张卡片最多绑定的设备数量上限（防止复制粘贴/失控写入造成组件数据膨胀）。
    public static final int MAX_BOUND_DEVICES = 64;

    public AcceleratorConfigCardItem(Properties properties) {
        super(properties);
    }

    /**
     * 判断物品栈是否为加速器配置卡。
     */
    public static boolean isConfigCard(ItemStack stack) {
        return !stack.isEmpty() && stack.is(ModItems.ACCELERATOR_CONFIG_CARD.get());
    }

    /**
     * 读取卡上绑定的加速器坐标。
     *
     * @return 未绑定时返回 {@code null}
     */
    @Nullable
    public static BlockPos getBoundAccelerator(ItemStack stack) {
        ConfigCardData data = stack.get(ModDataComponents.CONFIG_CARD_DATA);
        if (data == null || data.acceleratorPos() == 0L) {
            return null;
        }
        return BlockPos.of(data.acceleratorPos());
    }

    /**
     * 卡片是否绑定到指定加速器坐标。
     */
    public static boolean isBoundTo(ItemStack stack, BlockPos pos) {
        BlockPos bound = getBoundAccelerator(stack);
        return bound != null && bound.equals(pos);
    }

    /**
     * 读取卡上绑定的设备标识集合（只读视图）。
     */
    public static Set<String> getBoundDevices(ItemStack stack) {
        ConfigCardData data = stack.get(ModDataComponents.CONFIG_CARD_DATA);
        return data == null ? Set.of() : Set.copyOf(data.devices());
    }

    /**
     * 指定设备是否已在卡上绑定。
     */
    public static boolean isDeviceBound(ItemStack stack, String deviceId) {
        return getBoundDevices(stack).contains(deviceId);
    }

    /**
     * 绑定加速器：写入加速器坐标并清空旧的设备列表。
     * <p>
     * 改绑到另一台加速器时，旧设备来自原加速器的网络，对新的绑定无意义，
     * 因此一律清空，避免「卡走则停」的残留误加速。
     */
    public static void bindAccelerator(ItemStack stack, BlockPos pos) {
        stack.set(ModDataComponents.CONFIG_CARD_DATA, ConfigCardData.of(pos.asLong(), Set.of()));
    }

    /**
     * 切换加速器绑定（Shift+右键加速器的断言语义）：
     * 卡片已绑定该加速器 -> 取消绑定并同时清空全部设备绑定；
     * 否则绑定该加速器（原绑定与设备列表一并被替换）。
     *
     * @return {@code true}=绑定成功，{@code false}=已取消绑定
     */
    public static boolean bindOrUnbindAccelerator(ItemStack stack, BlockPos pos) {
        if (isBoundTo(stack, pos)) {
            unbindAccelerator(stack);
            return false;
        }
        bindAccelerator(stack, pos);
        return true;
    }

    /**
     * 取消加速器绑定，并同时清空卡上全部设备绑定。
     */
    public static void unbindAccelerator(ItemStack stack) {
        stack.remove(ModDataComponents.CONFIG_CARD_DATA);
    }

    /**
     * 切换设备绑定：已绑定 -> 取消；未绑定 -> 写入（达到上限时不再写入）。
     *
     * @return 切换完成后该设备是否处于已绑定状态
     */
    public static boolean toggleBoundDevice(ItemStack stack, String deviceId) {
        Set<String> devices = new TreeSet<>(getBoundDevices(stack));
        if (devices.contains(deviceId)) {
            devices.remove(deviceId);
        } else if (devices.size() < MAX_BOUND_DEVICES) {
            devices.add(deviceId);
        }
        writeBoundDevices(stack, devices);
        return devices.contains(deviceId);
    }

    /**
     * 把设备标识集合写回卡片组件（保持稳定排序，便于 NBT 持久化不抖动）。
     */
    public static void writeBoundDevices(ItemStack stack, Set<String> devices) {
        ConfigCardData data = stack.get(ModDataComponents.CONFIG_CARD_DATA);
        long accelerator = data == null ? 0L : data.acceleratorPos();
        stack.set(ModDataComponents.CONFIG_CARD_DATA, ConfigCardData.of(accelerator, devices));
    }

    /**
     * 工具提示：展示绑定状态（绑定的加速器坐标与绑定设备数量），供玩家离线确认配置。
     */
    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        BlockPos bound = getBoundAccelerator(stack);
        if (bound != null) {
            tooltip.add(Component.translatable("item.torcherino_ae_mod.accelerator_config_card.bound_target",
                    bound.getX(), bound.getY(), bound.getZ()).withStyle(ChatFormatting.AQUA));
        } else {
            tooltip.add(Component.translatable(
                    "item.torcherino_ae_mod.accelerator_config_card.no_target").withStyle(ChatFormatting.DARK_GRAY));
        }
        Set<String> devices = getBoundDevices(stack);
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
