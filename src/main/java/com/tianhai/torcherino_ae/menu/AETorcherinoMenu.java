package com.tianhai.torcherino_ae.menu;

import java.util.function.IntConsumer;

import com.tianhai.torcherino_ae.Torcherinoaemod;
import com.tianhai.torcherino_ae.blockentity.AETorcherinoBlockEntity;
import com.tianhai.torcherino_ae.config.RuntimeConfig;
import appeng.menu.AEBaseMenu;
import appeng.menu.guisync.GuiSync;
import appeng.menu.implementations.MenuTypeBuilder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;

/**
 * AE 加速火把的菜单。
 * <p>
 * 火把为独立范围扫描方块，无升级卡插槽。菜单用于把火把的 X/Z/Y 范围与加速倍数（speed）
 * 同步到客户端界面，并接收客户端滑块发来的设置修改。继承 {@link AEBaseMenu} 以复用
 * {@link MenuTypeBuilder}、{@code @GuiSync} 与客户端动作（{@code registerClientAction}）机制。
 * <p>
 * 界面只有四个设置滑块，不显示任何槽位，因此不创建玩家物品栏（见
 * {@link #quickMoveStack}）。
 */
public class AETorcherinoMenu extends AEBaseMenu {

    // 菜单类型常量，由 AE2 的 MenuTypeBuilder 构建（仅创建未注册），再由 ModMenus 放入注册表。
    // 菜单标题取宿主方块实体所属方块的显示名（基础/分级火把名不同），
    // 覆盖界面样式 JSON 里固定的 dialog_title，让三种火把的界面标题各自正确。
    public static final MenuType<AETorcherinoMenu> TYPE = MenuTypeBuilder
            .create(AETorcherinoMenu::new, AETorcherinoBlockEntity.class)
            .withMenuTitle(host -> host.getBlockState().getBlock().getName())
            .buildUnregistered(ResourceLocation.fromNamespaceAndPath(Torcherinoaemod.MOD_ID, "ae_torcherino"));

    // 客户端「设置范围/倍数」动作名。
    private static final String ACTION_SET_X_RANGE = "set_x_range";
    private static final String ACTION_SET_Z_RANGE = "set_z_range";
    private static final String ACTION_SET_Y_RANGE = "set_y_range";
    private static final String ACTION_SET_SPEED = "set_speed";

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

        // 注册客户端「设置范围/倍数」动作：滑块拖动时实时发送新值到服务端。
        registerClientAction(ACTION_SET_X_RANGE, ValueTarget.class, t -> applySetting(host::setXRange, t.value));
        registerClientAction(ACTION_SET_Z_RANGE, ValueTarget.class, t -> applySetting(host::setZRange, t.value));
        registerClientAction(ACTION_SET_Y_RANGE, ValueTarget.class, t -> applySetting(host::setYRange, t.value));
        registerClientAction(ACTION_SET_SPEED, ValueTarget.class, t -> applySetting(host::setSpeed, t.value));
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
     * 客户端「设置范围/倍数」动作的载荷：一个整数值。
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
