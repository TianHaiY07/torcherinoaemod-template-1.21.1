package com.tianhai.torcherino_ae.menu;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.tianhai.torcherino_ae.Torcherinoaemod;
import com.tianhai.torcherino_ae.blockentity.AEAcceleratorBlockEntity;
import com.tianhai.torcherino_ae.common.AE2GridSupport;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.menu.AEBaseMenu;
import appeng.menu.guisync.GuiSync;
import appeng.menu.implementations.MenuTypeBuilder;
import appeng.parts.AEBasePart;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * AE 加速器的菜单。
 * <p>
 * 基类直接继承 {@link AEBaseMenu}（而非 AE2 的通用 UpgradeableMenu），以此自主控制插槽创建，
 * 同时复用 AE 的 {@link appeng.menu.slot.RestrictedInputSlot} 与 {@link appeng.menu.SlotSemantics#UPGRADE}
 * 语义来创建升级卡插槽。
 * <p>
 * 服务端会从加速器所在的 AE 网格采集「可加速设备」列表（仅包含注册了网格 tick 服务、
 * 且属于可加速机器的设备，即实现 {@link appeng.api.networking.ticking.IGridTickable} 的机器——存储总线、能量元件、
 * P2P 隧道等网络基础设施会被排除），并通过 {@code @GuiSync} 同步到客户端界面。
 * 玩家点击设备条目时，客户端通过 {@code toggle_acceleration} 客户端动作把目标坐标发给服务端，
 * 由服务端切换该设备的加速状态。
 */
public class AEAcceleratorMenu extends AEBaseMenu {

    // 菜单类型常量，由 AE2 的 MenuTypeBuilder 构建（仅创建未注册），再由 ModMenus 放入注册表。
    public static final MenuType<AEAcceleratorMenu> TYPE = MenuTypeBuilder
            .create(AEAcceleratorMenu::new, AEAcceleratorBlockEntity.class)
            .buildUnregistered(ResourceLocation.fromNamespaceAndPath(Torcherinoaemod.MOD_ID, "ae_accelerator"));

    // 客户端「切换设备加速状态」动作名。
    private static final String ACTION_TOGGLE_ACCELERATION = "toggle_acceleration";

    // 客户端「设置设备加速倍数」动作名（配置弹窗横向滚动条实时发送）。
    private static final String ACTION_SET_MULTIPLIER = "set_accel_multiplier";

    // 菜单持有的方块实体引用。
    private final AEAcceleratorBlockEntity host;

    // 网格设备列表快照，经 @GuiSync 同步到客户端。仅服务端会被重新采集，客户端由反序列化填充。
    @GuiSync(1)
    public DeviceList devices = DeviceList.EMPTY;

    // 用于节流设备列表采集的计数器（每 20 tick 采集一次，降低遍历网格的开销）。
    private int lastUpdate = 20;

    public AEAcceleratorMenu(int containerId, Inventory playerInventory, AEAcceleratorBlockEntity host) {
        super(TYPE, containerId, playerInventory, host);
        this.host = host;

        // 复用 AE 的升级卡插槽创建逻辑（RestrictedInputSlot + UPGRADE 语义），自主添加升级卡插槽。
        setupUpgrades(host.getUpgrades());

        // 创建玩家物品栏（主物品栏 + 快捷栏）槽位，便于玩家将升级卡拖入/取出。
        createPlayerInventorySlots(playerInventory);

        // 注册客户端「点击设备条目切换加速状态」动作。
        registerClientAction(ACTION_TOGGLE_ACCELERATION, DeviceTarget.class, this::toggleAcceleration);
        // 注册客户端「设置设备加速倍数」动作（配置弹窗横向滚动条拖动时实时发送）。
        registerClientAction(ACTION_SET_MULTIPLIER, MultiplierTarget.class, this::setMultiplier);
    }

    /**
     * 打开界面时展示的方块实体。
     */
    public AEAcceleratorBlockEntity getHost() {
        return host;
    }

    /**
     * 客户端入口：向服务端发送「切换指定设备加速状态」动作。
     */
    public void sendToggleAcceleration(String deviceId) {
        sendClientAction(ACTION_TOGGLE_ACCELERATION, DeviceTarget.fromDeviceId(deviceId));
    }

    /**
     * 服务端处理器：切换目标设备的加速状态，并强制下一次广播立即重采集设备列表，
     * 让界面上的「加速中」标记尽快刷新。
     */
    private void toggleAcceleration(DeviceTarget target) {
        host.toggleAcceleratedDevice(target.deviceId);
        // 置为 20 使 broadcastChanges 下一次调用即重采集（见 broadcastChanges 的 ++lastUpdate >= 20）。
        lastUpdate = 20;
    }

    /**
     * 客户端入口：向服务端发送「设置指定设备加速倍数」动作（配置弹窗横向滚动条拖动时调用）。
     */
    public void sendSetAccelMultiplier(String deviceId, int multiplier) {
        sendClientAction(ACTION_SET_MULTIPLIER, MultiplierTarget.fromDeviceId(deviceId, multiplier));
    }

    /**
     * 服务端处理器：设置目标设备的加速倍数（大于 1 视为加速，等于 1 视为取消加速），
     * 并强制下一次广播立即重采集设备列表，让界面上的倍数与「加速中」标记尽快刷新。
     */
    private void setMultiplier(MultiplierTarget target) {
        host.setDeviceMultiplier(target.deviceId, target.multiplier);
        lastUpdate = 20;
    }

    /**
     * 采集加速器所在网格中的「可加速设备」列表。
     * <p>
     * 遍历网格全部节点，仅保留真正「可加速的机器」：
     * <ul>
     *   <li>必须注册了网格 tick 服务（实现 {@link appeng.api.networking.ticking.IGridTickable}）——线缆、存储组件等不参与
     *       网格 tick 的网络设备不会出现；</li>
     *   <li>必须是可加速的机器——存储总线、能量元件、P2P 隧道等网络基础设施即使实现了
     *       {@link appeng.api.networking.ticking.IGridTickable} 也没有实际工作可加速，会被排除（见
     *       {@link com.tianhai.torcherino_ae.common.AE2GridSupport}）。</li>
     * </ul>
     * 保留的设备如压印机、充电器、分子装配室、I/O 端口、输入/输出总线、接口、样板供应器等。
     * 同一宿主导出的多个节点仅保留一个（优先保留活动节点）；同一设备标识（含部件朝向）仅保留一条；剔除加速器自身。
     *
     * @param host 加速器方块实体，用于定位网格与作为自身的排除基准
     * @return 采集到的设备列表（按名称、再按与加速器的距离排序）
     */
    public static DeviceList collectDevices(AEAcceleratorBlockEntity host) {
        IGrid grid = host.getMainNode().getGrid();
        if (grid == null) {
            return DeviceList.EMPTY;
        }

        // 以宿主对象为键去重，保证同一台机器在列表中只出现一次。
        Map<Object, IGridNode> nodesByOwner = new IdentityHashMap<>();
        for (IGridNode node : grid.getNodes()) {
            // 只保留可加速的设备（注册了网格 tick 服务且属于可加速机器，见 AE2GridSupport）。
            if (!AE2GridSupport.isAcceleratableNode(node, host)) {
                continue;
            }
            // 同宿主导出的多个节点，优先保留处于活动状态的那个。
            nodesByOwner.merge(node.getOwner(), node, (a, b) -> a.isActive() ? a : b);
        }

        BlockPos origin = host.getBlockPos();
        // 按设备标识去重：设备标识由 AE2GridSupport.deviceIdOf 生成（部件含朝向），
        // 因此同一坐标上的多个可加速部件也会作为不同设备保留；同一标识只需一条，优先保留活动设备。
        Map<String, DeviceEntry> devicesById = new LinkedHashMap<>();
        for (Map.Entry<Object, IGridNode> entry : nodesByOwner.entrySet()) {
            DeviceEntry device = toDeviceEntry(entry.getKey(), entry.getValue(), host);
            if (device != null) {
                devicesById.merge(device.id(), device, (a, b) -> a.active() ? a : b);
            }
        }
        List<DeviceEntry> entries = new ArrayList<>(devicesById.values());

        // 排序：先按名称，再按与加速器的欧氏距离平方，保证列表顺序稳定（避免无意义重发）。
        entries.sort(Comparator
                .comparing((DeviceEntry d) -> d.name().getString())
                .thenComparingDouble(d -> d.pos().distSqr(origin)));

        return new DeviceList(entries);
    }

    /**
     * 将网格节点宿主转成设备条目；无法识别（非方块实体也非部件）或无法生成设备标识时返回 {@code null}。
     * 同时根据加速器保存的加速目标集合（以设备标识为键）标记该设备是否正在被加速。
     */
    private static DeviceEntry toDeviceEntry(Object owner, IGridNode node, AEAcceleratorBlockEntity host) {
        // 生成稳定设备标识（方块用坐标，部件用「坐标|朝向」），供选中/倍数身份键使用。
        String id = AE2GridSupport.deviceIdOf(owner);
        if (id == null) {
            return null;
        }
        ItemStack icon;
        Component name;
        BlockPos pos;

        if (owner instanceof BlockEntity be) {
            var block = be.getBlockState().getBlock();
            icon = new ItemStack(block);
            name = block.getName();
            pos = be.getBlockPos();
        } else if (owner instanceof AEBasePart part) {
            icon = new ItemStack(part.getPartItem().asItem());
            name = icon.getHoverName();
            // 部件本身不暴露坐标，取其所在线缆/宿主的方块坐标，用于界面展示、排序与搜索。
            BlockEntity hostBe = part.getBlockEntity();
            pos = hostBe != null ? hostBe.getBlockPos() : BlockPos.ZERO;
        } else {
            return null;
        }

        return new DeviceEntry(id, name, pos, node.isActive(), host.isAccelerating(id), host.getDeviceMultiplier(id),
                icon);
    }

    /**
     * 服务端周期性刷新设备列表（节流以降低遍历网格开销），随后交给父类做 {@code @GuiSync} 同步。
     */
    @Override
    public void broadcastChanges() {
        if (isServerSide() && ++lastUpdate >= 20) {
            lastUpdate = 0;
            devices = collectDevices(host);
        }
        super.broadcastChanges();
    }

    /**
     * 客户端「切换加速状态」动作的载荷：目标设备的稳定标识。
     * <p>
     * 客户端动作参数经 GSON 序列化传输，这里使用带无参构造器的普通类（而非 record），
     * 保证任何 GSON 版本都能可靠地序列化与反序列化。
     */
    public static class DeviceTarget {
        public String deviceId;

        public DeviceTarget() {
        }

        public DeviceTarget(String deviceId) {
            this.deviceId = deviceId;
        }

        public static DeviceTarget fromDeviceId(String deviceId) {
            return new DeviceTarget(deviceId);
        }
    }

    /**
     * 客户端「设置设备加速倍数」动作的载荷：目标设备标识 + 新的加速倍数。
     */
    public static class MultiplierTarget {
        public String deviceId;
        public int multiplier;

        public MultiplierTarget() {
        }

        public MultiplierTarget(String deviceId, int multiplier) {
            this.deviceId = deviceId;
            this.multiplier = multiplier;
        }

        public static MultiplierTarget fromDeviceId(String deviceId, int multiplier) {
            return new MultiplierTarget(deviceId, multiplier);
        }
    }
}
