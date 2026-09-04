package com.tianhai.torcherino_ae.menu;

import java.util.function.IntConsumer;

import com.tianhai.torcherino_ae.Torcherinoaemod;
import com.tianhai.torcherino_ae.blockentity.AETorcherinoBlockEntity;
import com.tianhai.torcherino_ae.config.RuntimeConfig;
import appeng.menu.AEBaseMenu;
import appeng.menu.guisync.GuiSync;
import appeng.menu.implementations.MenuTypeBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;

/**
 * AE 加速火把的菜单。
 * <p>
 * 火把为独立范围扫描方块，无升级卡插槽。菜单用于把火把的总开关、X/Z/Y 范围与加速倍数
 * （speed）同步到客户端界面，并接收客户端滑块/开关发来的设置修改。继承 {@link AEBaseMenu}
 * 以复用 {@link MenuTypeBuilder}、{@code @GuiSync} 与客户端动作（{@code registerClientAction}）机制。
 * <p>
 * 界面只有总开关与四个设置滑块，不显示任何槽位，因此不创建玩家物品栏（见
 * {@link #quickMoveStack}）。
 */
public class AETorcherinoMenu extends AEBaseMenu {

    // 菜单类型常量，由 AE2 的 MenuTypeBuilder 构建（仅创建未注册），再由 ModMenus 放入注册表。
    // 菜单标题取宿主方块实体所属方块的显示名（基础/分级火把名不同），
    // 覆盖界面样式 JSON 里固定的 dialog_title，让三种火把的界面标题各自正确。
    //
    // 打开即同步：通过 withInitialData 把 host 当前的全部设置（开关、X/Y/Z 范围、倍数与三个
    // 上限）随「打开菜单」数据包一次性带给客户端，并在客户端菜单构造完成、界面渲染首帧之前
    // 写入 @GuiSync 字段。若不这样做，客户端这些字段在首个 @GuiSync 广播到达前保持构造默认值
    // （范围 0、倍数 1、开关开），界面会先按默认渲染、数据到了再跳到真实值——滑块看起来
    // 就像"背景渲染出来后才被调节"。此机制让首帧即为服务端权威值，之后 @GuiSync 广播照常接管。
    public static final MenuType<AETorcherinoMenu> TYPE = MenuTypeBuilder
            .create(AETorcherinoMenu::new, AETorcherinoBlockEntity.class)
            .withMenuTitle(host -> host.getBlockState().getBlock().getName())
            .withInitialData(
                    // 服务端：打开菜单时把 host 上的当前设置写入数据包（服务器端 host 即权威方块实体）。
                    (AETorcherinoBlockEntity host, RegistryFriendlyByteBuf buf) -> {
                        buf.writeVarInt(host.getXRange());
                        buf.writeVarInt(host.getZRange());
                        buf.writeVarInt(host.getYRange());
                        buf.writeVarInt(host.getSpeed());
                        buf.writeBoolean(host.isEnabled());
                        buf.writeVarInt(host.maxSpeed());
                        buf.writeVarInt(host.maxXzRange());
                        buf.writeVarInt(host.maxYRange());
                    },
                    // 客户端：菜单构造完成后、界面首帧渲染前回填 @GuiSync 字段。
                    (AETorcherinoBlockEntity host, AETorcherinoMenu menu, RegistryFriendlyByteBuf buf) -> {
                        menu.xRange = buf.readVarInt();
                        menu.zRange = buf.readVarInt();
                        menu.yRange = buf.readVarInt();
                        menu.speed = buf.readVarInt();
                        menu.enabled = buf.readBoolean() ? 1 : 0;
                        menu.maxSpeed = buf.readVarInt();
                        menu.maxXzRange = buf.readVarInt();
                        menu.maxYRange = buf.readVarInt();
                    })
            .buildUnregistered(ResourceLocation.fromNamespaceAndPath(Torcherinoaemod.MOD_ID, "ae_torcherino"));

    // 客户端「设置范围/倍数/开关」动作名。
    private static final String ACTION_SET_X_RANGE = "set_x_range";
    private static final String ACTION_SET_Z_RANGE = "set_z_range";
    private static final String ACTION_SET_Y_RANGE = "set_y_range";
    private static final String ACTION_SET_SPEED = "set_speed";
    private static final String ACTION_SET_ENABLED = "set_enabled";

    // 菜单持有的方块实体引用。
    private final AETorcherinoBlockEntity host;

    // 网格 X/Z/Y 范围与加速倍数快照，经 @GuiSync 同步到客户端，供滑块定位。
    @GuiSync(1)
    public int xRange;
    @GuiSync(2)
    public int zRange;
    @GuiSync(3)
    public int yRange;
    @GuiSync(4)
    public int speed;
    // 总开关快照（1 开启 / 0 关闭），经 @GuiSync 同步到客户端，供开关控件显示状态。
    @GuiSync(8)
    public int enabled = 1;

    // 服务端配置的最大可调值（torcherino.maxSpeed / maxXzRange / maxYRange），经 @GuiSync 同步
    // 到客户端作为滑块范围上限。字段初值取配置默认（客户端侧无服务端配置时安全兜底），
    // 服务端每广播刷新一次，配置变更后新开的界面即可拿到最新上限。
    @GuiSync(5)
    public int maxSpeed = RuntimeConfig.torcherinoMaxSpeed();
    @GuiSync(6)
    public int maxXzRange = RuntimeConfig.torcherinoMaxXzRange();
    @GuiSync(7)
    public int maxYRange = RuntimeConfig.torcherinoMaxYRange();

    public AETorcherinoMenu(int containerId, Inventory playerInventory, AETorcherinoBlockEntity host) {
        super(TYPE, containerId, playerInventory, host);
        this.host = host;
        // 火把无升级卡插槽，界面也不显示玩家物品栏，因此不创建任何槽位。

        // 注册客户端「设置范围/倍数/开关」动作：滑块拖动/点击开关时实时发送新值到服务端。
        registerClientAction(ACTION_SET_X_RANGE, ValueTarget.class, t -> applySetting(host::setXRange, t.value));
        registerClientAction(ACTION_SET_Z_RANGE, ValueTarget.class, t -> applySetting(host::setZRange, t.value));
        registerClientAction(ACTION_SET_Y_RANGE, ValueTarget.class, t -> applySetting(host::setYRange, t.value));
        registerClientAction(ACTION_SET_SPEED, ValueTarget.class, t -> applySetting(host::setSpeed, t.value));
        registerClientAction(ACTION_SET_ENABLED, ValueTarget.class,
                t -> applySetting(v -> host.setEnabled(v != 0), t.value));
    }

    /**
     * 打开界面时展示的方块实体。
     */
    public AETorcherinoBlockEntity getHost() {
        return host;
    }

    /**
     * 客户端入口：向服务端发送新 X 范围。
     */
    public void sendSetXRange(int value) {
        sendClientAction(ACTION_SET_X_RANGE, new ValueTarget(value));
    }

    /**
     * 客户端入口：向服务端发送新 Z 范围。
     */
    public void sendSetZRange(int value) {
        sendClientAction(ACTION_SET_Z_RANGE, new ValueTarget(value));
    }

    /**
     * 客户端入口：向服务端发送新 Y 范围。
     */
    public void sendSetYRange(int value) {
        sendClientAction(ACTION_SET_Y_RANGE, new ValueTarget(value));
    }

    /**
     * 客户端入口：向服务端发送新加速倍数。
     */
    public void sendSetSpeed(int value) {
        sendClientAction(ACTION_SET_SPEED, new ValueTarget(value));
    }

    /**
     * 客户端入口：向服务端发送新的总开关状态（开启/关闭加速）。
     */
    public void sendSetEnabled(boolean enabled) {
        sendClientAction(ACTION_SET_ENABLED, new ValueTarget(enabled ? 1 : 0));
    }

    /**
     * 总开关当前状态（服务端经 {@code @GuiSync} 同步值，1 表示开启）。
     */
    public boolean isEnabled() {
        return enabled == 1;
    }

    /**
     * 本菜单不含任何槽位，快捷移动没有目标。
     * <p>
     * 父类实现会直接按索引取 {@code slots}，在无槽位时必然越界，因此这里直接返回空物品。
     */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    /**
     * 服务端周期性从方块实体上下拉当前设置，交给父类做 {@code @GuiSync} 同步。
     */
    @Override
    public void broadcastChanges() {
        this.xRange = host.getXRange();
        this.zRange = host.getZRange();
        this.yRange = host.getYRange();
        this.speed = host.getSpeed();
        this.enabled = host.isEnabled() ? 1 : 0;
        this.maxSpeed = host.maxSpeed();
        this.maxXzRange = host.maxXzRange();
        this.maxYRange = host.maxYRange();
        super.broadcastChanges();
    }

    /**
     * 应用客户端设置：方块实体已失效（被破坏、区块卸载）时拒绝写入。
     * <p>
     * 数值钳制由方块实体侧完成（见 {@link AETorcherinoBlockEntity} 各 setter 的 clampRange），
     * 这里补的是「目标是否仍然有效」这一层——菜单在客户端仍持有方块实体引用，
     * 而方块可能已被移除，此时写入会触发对失效方块实体的存档与同步。
     */
    private void applySetting(IntConsumer setter, int value) {
        if (host.isRemoved() || host.getLevel() == null) {
            return;
        }
        setter.accept(value);
    }

    /**
     * 客户端「设置范围/倍数/开关」动作的载荷：一个整数值（开关动作中 1 表示开启、0 表示关闭）。
     * <p>
     * 客户端动作参数经 GSON 序列化传输，这里使用带无参构造器的普通类（而非 record），
     * 保证任何 GSON 版本都能可靠地序列化与反序列化。
     */
    public static class ValueTarget {
        public int value;

        public ValueTarget() {
        }

        public ValueTarget(int value) {
            this.value = value;
        }
    }
}
