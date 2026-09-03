package com.tianhai.torcherino_ae.client.screen;
import com.tianhai.torcherino_ae.client.widget.DeviceConfigPopup;
import com.tianhai.torcherino_ae.client.widget.DeviceListWidget;
import com.tianhai.torcherino_ae.menu.AEAcceleratorMenu;
import com.tianhai.torcherino_ae.menu.DeviceEntry;

import appeng.client.Point;
import appeng.client.gui.AEBaseScreen;
import appeng.client.gui.style.PaletteColor;
import appeng.client.gui.style.ScreenStyle;
import appeng.client.gui.widgets.AETextField;
import appeng.client.gui.widgets.Scrollbar;
import appeng.client.gui.widgets.UpgradesPanel;
import appeng.menu.SlotSemantics;
import com.tianhai.torcherino_ae.Torcherinoaemod;
import com.tianhai.torcherino_ae.blockentity.AEAcceleratorBlockEntity;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/**
 * AE 加速器的客户端界面。
 * <p>
 * 界面背景与设备列表视觉均使用本模组自定义 GUI 贴图 {@code ae_accelerator_gui.png}
 * （由样式 JSON 的 {@code background} 与 {@code images} 驱动），其余 UI 元素使用 AE 原生组件：
 * <ul>
 *   <li>搜索栏：{@link AETextField}（AE 组件，{@code text_field.png} 三段式背景与占位文字），
 *       位置/尺寸由样式 JSON 的 {@code search} 条目驱动；</li>
 *   <li>设备列表：{@link DeviceListWidget}，列表背景与悬浮高亮均取自贴图内的设备列表区域
 *       （样式 JSON {@code images.deviceListBg} / {@code images.deviceListSlotSelected}），
 *       文字使用样式调色板；</li>
 *   <li>滚动条：{@link Scrollbar}、升级卡插槽：{@link UpgradesPanel}（AE 标准右侧垂直面板，
 *       面板与槽格使用 AE 自带 {@code guis/extra_panels.png} 绘制）、设备列表，三者均注册进
 *       {@code widgets} 样式系统，由样式 JSON 定位，绘制、鼠标事件、滚轮、tooltip 均由父类
 *       {@code AEBaseScreen} 通过 {@code WidgetContainer} 统一派发，坐标语义一致（相对界面原点）。</li>
 * </ul>
 * 状态提示文字（未接入网络 / 等待能量 / 工作中 / 无设备）与物品栏标题同一水平线、
 * 靠右对齐绘制（文字右边缘对齐物品栏最右边），使用样式调色板颜色。
 */
public class AEAcceleratorScreen extends AEBaseScreen<AEAcceleratorMenu> {

    // 设备列表控件。
    private final DeviceListWidget deviceList;

    // 设备加速倍数配置弹窗：右键点击设备行时弹出，拖动滑块实时调整该设备加速倍数。
    private final DeviceConfigPopup deviceConfigPopup;

    // AE 滚动条组件：注册进 widgets 样式系统，拖柄绘制、拖拽、点击翻页均由 AE 自身实现。
    private final Scrollbar scrollbar;

    // 搜索输入框（AE 组件）。
    private final AETextField searchField;

    // 状态文字颜色（样式调色板）。
    private final int mutedTextColor;
    private final int errorColor;

    public AEAcceleratorScreen(AEAcceleratorMenu menu, Inventory playerInventory, Component title, ScreenStyle style) {
        super(menu, playerInventory, title, style);

        // 升级卡插槽：AE 标准右侧垂直面板（位置由样式 JSON 的 upgrades 条目驱动，
        // 面板与槽格使用 AE 自带 extra_panels.png 绘制，行为与 AE 机器界面一致）。
        widgets.add("upgrades", new UpgradesPanel(menu.getSlots(SlotSemantics.UPGRADE), menu.getHost()));

        // 滚动条、搜索栏、设备列表统一走 AE 标准 widgets 系统：
        // WidgetContainer.add/addScrollBar/addTextField 会在构造期按样式 JSON 设置尺寸，
        // 父类 AEBaseScreen.init() 中的 populateScreen 会按 widget style 解析出相对界面原点的
        // 坐标并 setPosition/setSize；绘制与鼠标/滚轮/tooltip 事件由父类统一派发。
        // 使用 AE 的 BIG 滚动条滑块（12px 宽，比默认 SMALL 的 7px 更宽更醒目）；仍受 widgets.scrollbar 样式定位。
        this.scrollbar = widgets.addScrollBar("scrollbar", Scrollbar.BIG);
        this.deviceList = new DeviceListWidget(menu, this.scrollbar, style);
        widgets.add("deviceList", deviceList);

        // 加速倍数配置弹窗：位置/尺寸由样式 JSON 的 deviceConfigPopup 条目驱动；
        // 右键点击设备行时打开，拖动滑块实时发送新倍数到服务端。
        this.deviceConfigPopup = new DeviceConfigPopup(menu, deviceList, style);
        widgets.add("deviceConfigPopup", deviceConfigPopup);
        this.deviceList.setOnRightClickDevice(deviceConfigPopup::open);

        this.searchField = widgets.addTextField("search");
        this.searchField.setPlaceholder(Component.translatable(
                "gui." + Torcherinoaemod.MOD_ID + ".ae_accelerator.search"));
        this.searchField.setResponder(deviceList::setFilter);

        // 状态文字颜色取自样式调色板，与 AE 界面文字一致。
        this.mutedTextColor = style.getColor(PaletteColor.MUTED_TEXT_COLOR).toARGB();
        this.errorColor = style.getColor(PaletteColor.ERROR).toARGB();

        // 标题优先使用菜单标题（方块名）；菜单标题为空时（例如菜单未携带 title），
        // 保留样式 JSON 中 dialog_title 的默认文本（block.torcherino_ae_mod.ae_accelerator），
        // 避免空文本把默认标题覆盖掉。参考 AE2 MEStorageScreen 的同样处理。
        // 玩家物品栏标题交由样式 JSON（player_inventory_title）显示。
        if (!title.getString().isEmpty()) {
            setTextContent("dialog_title", title);
        }
    }

    /**
     * 本界面不使用 AE 的垂直工具栏（样式 JSON 未定义 verticalToolbar，也不渲染该栏），
     * 关闭默认工具栏可避免 AEBaseScreen 构造时因缺少该 widget 样式而崩溃。
     * 参考 AE2 的 {@code SkyChestScreen} 的同样处理。
     */
    @Override
    protected boolean shouldAddToolbar() {
        return false;
    }

    /**
     * 弹窗打开时拦截所有鼠标点击：交给弹窗处理（点弹窗外关闭、点滑块调整倍数），
     * 避免点击穿透到设备列表或下层插槽。
     */
    @Override
    public boolean mouseClicked(double xCoord, double yCoord, int btn) {
        if (deviceConfigPopup.isOpen()) {
            return deviceConfigPopup.onMouseDown(
                    new appeng.client.Point((int) Math.round(xCoord - leftPos), (int) Math.round(yCoord - topPos)), btn);
        }
        return super.mouseClicked(xCoord, yCoord, btn);
    }

    /**
     * 弹窗打开时按 Esc 仅关闭弹窗，而不关闭整个界面。
     */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (deviceConfigPopup.isOpen() && keyCode == com.mojang.blaze3d.platform.InputConstants.KEY_ESCAPE) {
            deviceConfigPopup.close();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /**
     * 绘制界面状态提示文字。
     * <p>
     * 当在线且设备表非空、正在工作时，设备列表已由 {@link DeviceListWidget} 绘制，此处不再叠加文字。
     * 其余情况在与物品栏标题同一水平线、靠右对齐的位置绘制一条状态提示
     * （未接入网络 / 等待能量 / 网络中暂无设备），文字右边缘位于 GUI 右边缘右侧 8px。
     * 注意：{@code renderLabels} 期间画布已平移到界面原点，这里使用相对界面原点的坐标；
     * 参数顺序与 AE2 的 {@code drawFG(guiGraphics, offsetX, offsetY, mouseX, mouseY)} 保持一致。
     */
    @Override
    public void drawFG(GuiGraphics guiGraphics, int offsetX, int offsetY, int mouseX, int mouseY) {
        AEAcceleratorBlockEntity host = menu.getHost();
        boolean online = host != null && host.isOnline();
        boolean working = host != null && host.isWorking();

        Component status;
        int color;
        if (!online) {
            // 未接入网络：该状态最重要，优先展示。
            status = Component.translatable("gui." + Torcherinoaemod.MOD_ID + ".ae_accelerator.offline");
            color = errorColor;
        } else if (menu.devices.devices().isEmpty()) {
            // 在线但网络上没有任何可加速设备。
            status = Component.translatable("gui." + Torcherinoaemod.MOD_ID + ".ae_accelerator.empty");
            color = mutedTextColor;
        } else if (working) {
            long acceleratedCount = menu.devices.devices().stream().filter(DeviceEntry::accelerated).count();
            if (acceleratedCount > 0) {
                // 正在加速中：显示被加速设备数量与当前最高加速倍数（取菜单同步值，随升级卡实时变化）。
                status = Component.translatable("gui." + Torcherinoaemod.MOD_ID + ".ae_accelerator.accel_status",
                        acceleratedCount, menu.getMaxMultiplier());
            } else {
                // 在线、有设备、正在工作但尚未选中任何设备：提示点击列表开始加速。
                status = Component.translatable("gui." + Torcherinoaemod.MOD_ID + ".ae_accelerator.accel_hint");
            }
            color = mutedTextColor;
        } else {
            // 在线、有设备但本次不工作（供电不足等）：显示「等待能量」。
            status = Component.translatable("gui." + Torcherinoaemod.MOD_ID + ".ae_accelerator.idle");
            color = mutedTextColor;
        }

        // 状态文字与物品栏标题（样式 JSON bottom=95 → y=186-95=91）同一水平线，
        // 靠右对齐：文字右边缘对齐物品栏最右边
        // （物品栏 left=8，9 列槽 x 18px → 右边缘 x=170）。
        int statusX = 170 - font.width(status);
        int statusY = 91;
        guiGraphics.drawString(font, status, statusX, statusY, color, false);
    }
}
