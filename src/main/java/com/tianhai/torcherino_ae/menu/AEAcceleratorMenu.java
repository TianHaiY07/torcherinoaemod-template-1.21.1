package com.tianhai.torcherino_ae.menu;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.tianhai.torcherino_ae.Torcherinoaemod;
import com.tianhai.torcherino_ae.api.DeviceId;
import com.tianhai.torcherino_ae.blockentity.AEAcceleratorBlockEntity;
import com.tianhai.torcherino_ae.config.RuntimeConfig;
import com.tianhai.torcherino_ae.network.DeviceScanner;
import com.tianhai.torcherino_ae.network.crafting.CraftingSupport;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.core.definitions.AEBlocks;
import appeng.menu.AEBaseMenu;
import appeng.menu.SlotSemantic;
import appeng.menu.SlotSemantics;
import appeng.menu.guisync.GuiSync;
import appeng.menu.implementations.MenuTypeBuilder;
import appeng.menu.slot.AppEngSlot;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import appeng.parts.AEBasePart;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
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

    // 配置卡槽位的自定义插槽语义：用于在界面样式 JSON 的 slots 段中按 id 定位到固定坐标。
    public static final SlotSemantic AE_CONFIG_CARD_SLOT = SlotSemantics.register("ae_accelerator_config_card", false);

    // 客户端「切换设备加速状态」动作名。
    private static final String ACTION_TOGGLE_ACCELERATION = "toggle_acceleration";

    // 客户端「设置设备加速倍数」动作名（配置弹窗横向滚动条实时发送）。
    private static final String ACTION_SET_MULTIPLIER = "set_accel_multiplier";

    // 菜单持有的方块实体引用。
    private final AEAcceleratorBlockEntity host;

    // 网格设备列表快照，经 @GuiSync 同步到客户端。仅服务端会被重新采集，客户端由反序列化填充。
    @GuiSync(1)
    public DeviceList devices = DeviceList.EMPTY;

    // 当前最高加速倍数，经 @GuiSync 同步到客户端。由服务端按升级卡库存实时计算，
    // 供倍数配置弹窗与状态文字展示「插入升级卡后的真实上限」，不依赖客户端方块实体副本。
    @GuiSync(2)
    public int maxMultiplier = AEAcceleratorBlockEntity.BASE_ACCEL_MULTIPLIER;

    // 用于节流设备列表采集的计数器（每配置 menu.deviceListRefreshTicks tick 检查一次，
    // 降低遍历网格的开销；默认 20 tick，见 ConfigDefaults）。
    private int lastUpdate = RuntimeConfig.menuDeviceListRefreshTicks();

    // 设备列表采集缓存（§8.4）：每次到达采集周期先比较「登记表版本 + 网格拓扑签名」，
    // 二者均未变化时直接复用上次构造的 DeviceList——稳态（无人插拔设备、无加速状态变更、
    // 无合成 CPU 结构变化）下不再执行全量采集（new ItemStack + 名称翻译 + 排序）。
    private DeviceList cachedDevices;
    private int lastDevicesRegistryVersion = -1;
    private long lastDevicesTopology = Long.MIN_VALUE;

    public AEAcceleratorMenu(int containerId, Inventory playerInventory, AEAcceleratorBlockEntity host) {
        super(TYPE, containerId, playerInventory, host);
        this.host = host;

        // 复用 AE 的升级卡插槽创建逻辑（RestrictedInputSlot + UPGRADE 语义），自主添加升级卡插槽。
        setupUpgrades(host.getUpgrades());

        // 配置卡槽位：单格，仅允许放入「加速器配置卡」（库存侧已用过滤器限定）。
        // 位置由界面样式 JSON 的 slots.ae_accelerator_config_card 驱动（x:174, y:5）。
        addSlot(new AppEngSlot(host.getConfigCardInventory(), 0), AE_CONFIG_CARD_SLOT);

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
     * 当前最高加速倍数（服务端按升级卡库存实时计算，经 @GuiSync 同步到客户端）。
     */
    public int getMaxMultiplier() {
        return maxMultiplier;
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
        // 客户端载荷经 GSON 传输，deviceId 是 DeviceId.stableKey() 字符串，先解析回类型。
        DeviceId deviceId = DeviceId.parse(target.deviceId);
        // 服务端校验：目标设备必须真实存在于本加速器当前的 AE 网格内。
        // 客户端动作载荷可被伪造，旧实现直接采信，会把任意字符串写入持久化的状态表，
        // 既污染存档又永远不会被加速脉冲命中。
        if (deviceId == null || !isDeviceInGrid(deviceId)) {
            return;
        }
        host.toggleAcceleratedDevice(deviceId);
        // 置为「下一次广播即到期」使设备列表与加速中标记尽快刷新（阈值见 broadcastChanges）。
        lastUpdate = RuntimeConfig.menuDeviceListRefreshTicks() - 1;
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
        // 客户端载荷经 GSON 传输，deviceId 是 DeviceId.stableKey() 字符串，先解析回类型。
        DeviceId deviceId = DeviceId.parse(target.deviceId);
        // 服务端校验：目标设备必须在网格内，且倍数落在 [1, 当前上限] 区间
        // （倍数为 1 表示取消加速，同样要求设备合法，避免写入非法标识）。
        if (deviceId == null || !isDeviceInGrid(deviceId) || target.multiplier < 1
                || target.multiplier > host.getAccelMultiplier()) {
            return;
        }
        host.setDeviceMultiplier(deviceId, target.multiplier);
        lastUpdate = RuntimeConfig.menuDeviceListRefreshTicks() - 1;
    }

    /**
     * 方块实体所在维度（CPU 标识构造需要；菜单存在期间服务端必有已加载的世界）。
     */
    private static ResourceKey<Level> dimensionOf(AEAcceleratorBlockEntity host) {
        Level world = host.getLevel();
        return world != null ? world.dimension() : Level.OVERWORLD;
    }

    /**
     * 校验设备标识是否合法：非空，且确实对应本加速器当前 AE 网格内的一台可加速设备或合成 CPU。
     * <p>
     * 点击是低频操作，这里做一次网格遍历校验的开销可以接受；换来的是「持久化状态里
     * 不会存在永远无法命中的垃圾条目」。筛选谓词与加速脉冲、设备列表采集保持一致。
     * 设备标识自带维度，与网格所在维度不匹配的标识（如来自其它维度的伪造载荷）自然匹配不上。
     */
    private boolean isDeviceInGrid(DeviceId deviceId) {
        if (deviceId == null) {
            return false;
        }
        // 经 host.grid() 安全取值（内部把「节点未入网/销毁时 getGrid() 抛 ISE」转译为 null）。
        IGrid grid = host.grid();
        if (grid == null) {
            return false;
        }
        // 普通设备：必须可加速且非自身。
        for (IGridNode node : grid.getNodes()) {
            if (!DeviceScanner.isAcceleratableNode(node, host)) {
                continue;
            }
            if (deviceId.equals(DeviceScanner.deviceIdOf(node.getOwner()))) {
                return true;
            }
        }
        // 合成 CPU 不属于 IGridTickable，需单独枚举校验（选中它即开启智能加速）。
        for (ICraftingCPU cpu : grid.getCraftingService().getCpus()) {
            if (deviceId.equals(CraftingSupport.cpuDeviceId(dimensionOf(host), cpu))) {
                return true;
            }
        }
        return false;
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
     *       {@link com.tianhai.torcherino_ae.network.DeviceScanner}）。</li>
     * </ul>
     * 保留的设备如压印机、充电器、分子装配室、I/O 端口、输入/输出总线、接口、样板供应器等。
     * 同一宿主导出的多个节点仅保留一个（优先保留活动节点）；同一设备标识（含部件朝向）仅保留一条；剔除加速器自身。
     *
     * @param host 加速器方块实体，用于定位网格与作为自身的排除基准
     * @return 采集到的设备列表（按名称、再按与加速器的距离排序）
     */
    public static DeviceList collectDevices(AEAcceleratorBlockEntity host) {
        IGrid grid = host.grid();
        if (grid == null) {
            return DeviceList.EMPTY;
        }

        // 以宿主对象为键去重，保证同一台机器在列表中只出现一次。
        Map<Object, IGridNode> nodesByOwner = new IdentityHashMap<>();
        for (IGridNode node : grid.getNodes()) {
            // 只保留可加速的设备（注册了网格 tick 服务且属于可加速机器，见 DeviceScanner）。
            if (!DeviceScanner.isAcceleratableNode(node, host)) {
                continue;
            }
            // 同宿主导出的多个节点，优先保留处于活动状态的那个。
            nodesByOwner.merge(node.getOwner(), node, (a, b) -> a.isActive() ? a : b);
        }

        BlockPos origin = host.getBlockPos();
        // 按设备标识去重：设备标识由 DeviceScanner.deviceIdOf 生成（部件含朝向），
        // 因此同一坐标上的多个可加速部件也会作为不同设备保留；同一标识只需一条，优先保留活动设备。
        Map<String, DeviceEntry> devicesById = new LinkedHashMap<>();
        for (Map.Entry<Object, IGridNode> entry : nodesByOwner.entrySet()) {
            DeviceEntry device = toDeviceEntry(entry.getKey(), entry.getValue(), host);
            if (device != null) {
                devicesById.merge(device.id(), device, (a, b) -> a.active() ? a : b);
            }
        }
        List<DeviceEntry> entries = new ArrayList<>(devicesById.values());
        // 追加网络中的合成 CPU（Crafting CPU）条目：CPU 不属于 IGridTickable，需单独采集。
        entries.addAll(collectCpus(host, origin));

        // 排序：先按名称，再按与加速器的欧氏距离平方，保证列表顺序稳定（避免无意义重发）。
        entries.sort(Comparator
                .comparing((DeviceEntry d) -> d.name().getString())
                .thenComparingDouble(d -> d.pos().distSqr(origin)));

        return new DeviceList(entries);
    }

    /**
     * 采集网络中的合成 CPU（Crafting CPU）列表。
     * <p>
     * 合成 CPU 是多块巨型结构，AE2 通过 {@link appeng.api.networking.crafting.ICraftingService#getCpus()}
     * 暴露它们。它们不属于 {@code IGridTickable}，不会被普通设备采集逻辑纳入；但对玩家而言
     * 它们是重要的合成枢纽，需要展示在加速器界面中，并支持「智能加速」：选中 CPU 后，
     * 当该 CPU 处于合成状态（busy）时，加速器会自动联动加速当前参与合成的机器。
     * <p>
     * 每个 CPU 用 {@link DeviceId#ofCpu} 生成设备标识（种类标记为 CRAFTING_CPU），
     * 与普通设备标识由种类字段天然区分。
     */
    private static List<DeviceEntry> collectCpus(AEAcceleratorBlockEntity host, BlockPos origin) {
        IGrid grid = host.grid();
        if (grid == null) {
            return List.of();
        }
        List<DeviceEntry> result = new ArrayList<>();
        for (ICraftingCPU cpu : grid.getCraftingService().getCpus()) {
            DeviceEntry entry = toCpuEntry(cpu, host);
            if (entry != null) {
                result.add(entry);
            }
        }
        return result;
    }

    /**
     * 将一台合成 CPU 转成设备条目；无法解析出结构坐标（强转失败）时返回 {@code null}。
     * <p>
     * 设备标识用 {@link DeviceId#ofCpu} 生成（种类标记为 CRAFTING_CPU，含维度），
     * 供智能加速选中状态与倍数查询使用。
     */
    private static DeviceEntry toCpuEntry(ICraftingCPU cpu, AEAcceleratorBlockEntity host) {
        CraftingCPUCluster cluster = CraftingSupport.asCpuCluster(cpu);
        if (cluster == null) {
            return null;
        }
        DeviceId id = CraftingSupport.cpuDeviceId(dimensionOf(host), cpu);
        if (id == null) {
            return null;
        }
        BlockPos pos = cluster.getBoundsMin();
        // CPU 名称：用户自定义名优先，未命名时用通用占位文案。
        Component name = cpu.getName() != null ? cpu.getName() : Component.translatable(
                "gui." + Torcherinoaemod.MOD_ID + ".ae_accelerator.crafting_cpu");
        boolean active = cluster.isActive();
        return new DeviceEntry(id.stableKey(), name, pos, active, host.isAccelerating(id),
                host.getDeviceMultiplier(id), AEBlocks.CRAFTING_UNIT.stack(), true);
    }

    /**
     * 将网格节点宿主转成设备条目；无法识别（非方块实体也非部件）或无法生成设备标识时返回 {@code null}。
     * 同时根据加速器保存的加速目标表（以设备标识为键）标记该设备是否正在被加速。
     */
    private static DeviceEntry toDeviceEntry(Object owner, IGridNode node, AEAcceleratorBlockEntity host) {
        // 生成稳定设备标识（方块=维度+坐标，部件=维度+线缆坐标+朝向），供选中/倍数身份键使用。
        DeviceId id = DeviceScanner.deviceIdOf(owner);
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

        return new DeviceEntry(id.stableKey(), name, pos, node.isActive(), host.isAccelerating(id),
                host.getDeviceMultiplier(id), icon);
    }

    /**
     * 服务端周期性刷新设备列表（节流以降低遍历网格开销），随后交给父类做 {@code @GuiSync} 同步。
     */
    @Override
    public void broadcastChanges() {
        if (isServerSide()) {
            // 每次广播都同步最高倍数（按升级卡库存与配置实时计算），使插入/取出升级卡后
            // 弹窗上限与状态文字尽快刷新；@GuiSync 仅在数值变化时才真正发包。
            maxMultiplier = host.getAccelMultiplier();
            int refreshTicks = RuntimeConfig.menuDeviceListRefreshTicks();
            if (++lastUpdate >= refreshTicks) {
                lastUpdate = 0;
                devices = getDeviceList();
            }
        }
        super.broadcastChanges();
    }

    /**
     * 返回当前网格的设备列表（带缓存）。
     * <p>
     * 缓存的失效条件是「登记表版本变化（加速中标记 / 倍率过期）」或「网格拓扑签名变化
     * （设备上/下线、激活状态翻转、合成 CPU 结构变化）」；两者都未变化时直接复用上次
     * 采集结果，稳态下几乎零开销（§8.4）。
     */
    private DeviceList getDeviceList() {
        int registryVersion = host.targetRegistryVersion();
        long topology = topologySignature(host);
        if (cachedDevices == null || registryVersion != lastDevicesRegistryVersion
                || topology != lastDevicesTopology) {
            cachedDevices = collectDevices(host);
            lastDevicesRegistryVersion = registryVersion;
            lastDevicesTopology = topology;
        }
        return cachedDevices;
    }

    /**
     * 计算网格的轻量「拓扑签名」，判定设备列表内容是否可能变化。
     * <p>
     * 把「可加速设备的宿主对象 + 活动状态」与「合成 CPU 对象」折叠成整数，
     * 不构造任何对象（与全量采集的 {@code new ItemStack} / 名称翻译 / 排序相比开销可忽略）。
     * 签名仅用于失效判断，精度上等价于：任一设备上下线、激活翻转或 CPU 结构增减都会改变。
     * 登记表（加速中标记与倍率）不在此签名内，由 {@code host.targetRegistryVersion()} 覆盖。
     */
    private static long topologySignature(AEAcceleratorBlockEntity host) {
        IGrid grid = host.grid();
        if (grid == null) {
            return 0;
        }
        long h = 1;
        int deviceCount = 0;
        for (IGridNode node : grid.getNodes()) {
            if (!DeviceScanner.isAcceleratableNode(node, host)) {
                continue;
            }
            Object owner = node.getOwner();
            h = h * 31 + System.identityHashCode(owner);
            h = h * 31 + (node.isActive() ? 1 : 0);
            deviceCount++;
        }
        long cpuHash = 0;
        int cpuCount = 0;
        for (ICraftingCPU cpu : grid.getCraftingService().getCpus()) {
            cpuHash = cpuHash * 31 + System.identityHashCode(cpu);
            cpuCount++;
        }
        return h * 31 + deviceCount + cpuHash * 31 + cpuCount;
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
