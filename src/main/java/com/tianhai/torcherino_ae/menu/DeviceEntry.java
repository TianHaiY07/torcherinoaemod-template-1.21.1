package com.tianhai.torcherino_ae.menu;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.world.item.ItemStack;

import appeng.menu.guisync.PacketWritable;

/**
 * 网络中的一台可加速设备（一个网格节点）。
 * <p>
 * 用于在加速器界面中展示设备名称、坐标、活动状态、是否正在被加速、当前加速倍数与图标。
 * 实现 {@link PacketWritable} 以便随菜单通过 {@code @GuiSync} 同步到客户端。
 * <p>
 * 加速倍数语义：1 表示「未加速」（该设备不在加速列表），大于 1 表示正在被加速。
 * 每台设备拥有独立的倍数，由服务端 {@link AEAcceleratorBlockEntity#getDeviceMultiplier} 提供。
 * <p>
 * 设备身份：{@code id} 是服务端生成的稳定标识（见
 * {@link com.tianhai.torcherino_ae.common.AE2GridSupport#deviceIdOf}），
 * 用于作为点击/倍数的身份键；{@code pos} 仅用于界面展示坐标、排序与搜索。
 */
public record DeviceEntry(String id, Component name, BlockPos pos, boolean active, boolean accelerated, int multiplier,
        ItemStack icon) implements PacketWritable {

    /**
     * 从网络包读取端的设备条目。
     */
    public DeviceEntry(RegistryFriendlyByteBuf data) {
        this(
                data.readUtf(),
                ComponentSerialization.TRUSTED_STREAM_CODEC.decode(data),
                data.readBlockPos(),
                data.readBoolean(),
                data.readBoolean(),
                data.readVarInt(),
                ItemStack.OPTIONAL_STREAM_CODEC.decode(data));
    }

    @Override
    public void writeToPacket(RegistryFriendlyByteBuf data) {
        data.writeUtf(id);
        ComponentSerialization.TRUSTED_STREAM_CODEC.encode(data, name);
        data.writeBlockPos(pos);
        data.writeBoolean(active);
        data.writeBoolean(accelerated);
        data.writeVarInt(multiplier);
        ItemStack.OPTIONAL_STREAM_CODEC.encode(data, icon);
    }
}
