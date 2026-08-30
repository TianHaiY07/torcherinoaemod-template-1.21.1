package com.tianhai.torcherino_ae.menu;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.network.RegistryFriendlyByteBuf;

import appeng.menu.guisync.PacketWritable;

/**
 * 加速器可加速设备的列表快照。
 * <p>
 * 纯数据载体：作为菜单 {@code @GuiSync} 同步字段通过网络包在服务端与客户端之间传输。
 * 列表内容由服务端采集网格节点生成（见 {@link AEAcceleratorMenu#collectDevices}）。
 */
public record DeviceList(List<DeviceEntry> devices) implements PacketWritable {

    /**
     * 空列表常量，用于未接入网络或网络内无设备时。
     */
    public static final DeviceList EMPTY = new DeviceList(List.of());

    /**
     * 从网络包读取端的设备列表。
     */
    public DeviceList(RegistryFriendlyByteBuf data) {
        this(readList(data));
    }

    private static List<DeviceEntry> readList(RegistryFriendlyByteBuf data) {
        int count = data.readVarInt();
        List<DeviceEntry> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            result.add(new DeviceEntry(data));
        }
        return result;
    }

    @Override
    public void writeToPacket(RegistryFriendlyByteBuf data) {
        data.writeVarInt(devices.size());
        for (DeviceEntry entry : devices) {
            entry.writeToPacket(data);
        }
    }
}
