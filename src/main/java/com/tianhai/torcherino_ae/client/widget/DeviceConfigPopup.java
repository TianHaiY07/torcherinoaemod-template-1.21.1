package com.tianhai.torcherino_ae.client.widget;
import com.tianhai.torcherino_ae.menu.AEAcceleratorMenu;
import com.tianhai.torcherino_ae.menu.DeviceEntry;

import java.util.List;
import java.util.function.Consumer;

import org.jetbrains.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import appeng.client.Point;
import appeng.client.gui.AEBaseScreen;
import appeng.client.gui.ICompositeWidget;
import appeng.client.gui.Tooltip;
import appeng.client.gui.style.Blitter;
import appeng.client.gui.style.PaletteColor;
import appeng.client.gui.style.ScreenStyle;

import com.tianhai.torcherino_ae.Torcherinoaemod;
import com.tianhai.torcherino_ae.client.AEGuiMetrics;
import com.tianhai.torcherino_ae.client.GuiTheme;

/**
 * 设备加速倍数配置弹窗。
 * <p>
 * 当玩家在设备列表中右键点击某台设备时弹出，用于精确调整该设备的加速倍数（1x ~ 当前最高倍数）。
 * 弹窗背景使用专为此弹窗绘制的素材 {@code textures/gui/device_entry_gui.png}（203x32 横向面板条，
 * 素材中段内嵌一条深色滑块轨道槽）；拖动轨道上的手柄时实时通过
 * {@link AEAcceleratorMenu#sendSetAccelMultiplier} 向服务端发送新倍数，
 * 服务端会持久化倍数并在每个游戏 tick 按该倍数推进设备工作进度（倍数 1 表示取消加速）。
 * 点击弹窗外任意位置即关闭弹窗；弹窗打开期间设备列表、插槽等下层控件不响应鼠标。
 * <p>
 * 实现为 {@link ICompositeWidget} 挂载到加速器界面：绘制面板、标题与滑块手柄（手柄取自
 * 贴图 {@code (0,32,15,12)} 的小方块），并通过 {@code wantsAllMouseDownEvents} 等接口
 * 拦截全部鼠标事件，避免点击穿透。
 * <p>
 * 滚轮调节：鼠标悬停在弹窗范围内滚动即逐格调整倍数；按住 Shift 滚动按 10 格、按住
 * Ctrl 滚动按 100 格步进（Ctrl 优先于 Shift），便于在高倍率设备上快速定位。
 */
public class DeviceConfigPopup implements ICompositeWidget {

    // 弹窗面板位置/尺寸、内嵌滑块轨道、手柄等与贴图强耦合的度量，集中放在 AEGuiMetrics。

    // 引用菜单以发送倍数修改请求。
    private final AEAcceleratorMenu menu;

    // 引用设备列表：弹窗打开/关闭时禁用/恢复其交互，避免点击穿透到列表。
    private final DeviceListWidget deviceList;

    // 样式调色板颜色。
    private final int textColor;

    // 弹窗当前配置的设备（null 表示弹窗未打开）。
    @Nullable
    private DeviceEntry device;

    // 当前滑块值（加速倍数，1..getMaxMultiplier()）。
    private int multiplier = 1;

    // 滑块是否正在被拖动。
    private boolean dragging;

    // 相对界面原点的弹窗边界。
    private Rect2i bounds = new Rect2i(AEGuiMetrics.POPUP_X, AEGuiMetrics.POPUP_Y,
            AEGuiMetrics.POPUP_WIDTH, AEGuiMetrics.POPUP_HEIGHT);

    public DeviceConfigPopup(AEAcceleratorMenu menu, DeviceListWidget deviceList, ScreenStyle style) {
        this.menu = menu;
        this.deviceList = deviceList;
        // 素材面板默认是浅色背景，文字使用界面默认深色；但对暗色 UI 材质包做明暗自适应：
        // 若面板实际偏暗则自动提亮文字，若调色板被主题包整体改浅（浅字 + 浅面板）则自动压暗。
        // 默认环境下返回基色不变，观感与原先一致。
        int base = style.getColor(PaletteColor.DEFAULT_TEXT_COLOR).toARGB();
        boolean panelDark = GuiTheme.isDarkRegion(AEGuiMetrics.DEVICE_ENTRY_GUI,
                AEGuiMetrics.POPUP_SRC_X, AEGuiMetrics.POPUP_SRC_Y,
                AEGuiMetrics.POPUP_WIDTH, AEGuiMetrics.POPUP_HEIGHT);
        this.textColor = GuiTheme.ensureContrast(base, panelDark);
    }

    /**
     * 打开弹窗并锁定到指定设备，初始倍数取该设备当前倍数（未加速时为最高倍数）。
     */
    public void open(DeviceEntry device) {
        this.device = device;
        this.multiplier = Mth.clamp(device.multiplier(), 1, getMaxMultiplier());
        this.dragging = false;
        // 弹窗打开期间禁用设备列表交互，避免点击穿透到列表行。
        this.deviceList.setEnabled(false);
    }

    /**
     * 关闭弹窗并恢复设备列表交互。
     */
    public void close() {
        this.device = null;
        this.dragging = false;
        this.deviceList.setEnabled(true);
    }

    /**
     * 弹窗是否处于打开状态。
     */
    public boolean isOpen() {
        return device != null;
    }

    /**
     * 当前可调的最大加速倍数：取菜单经 {@code @GuiSync} 同步的实时值
     * （服务端按已插入升级卡计算，同档堆叠含边际收益递减），避免依赖客户端方块实体副本。
     */
    private int getMaxMultiplier() {
        return Math.max(1, menu.getMaxMultiplier());
    }

    /**
     * 滑块轨道区域（相对界面原点），复用素材中段内嵌的深色槽。
     */
    private Rect2i getTrack() {
        return new Rect2i(
                bounds.getX() + AEGuiMetrics.TRACK_X,
                bounds.getY() + AEGuiMetrics.TRACK_Y,
                AEGuiMetrics.TRACK_WIDTH,
                AEGuiMetrics.TRACK_HEIGHT);
    }

    /**
     * 滑块手柄左边缘 x 坐标（相对界面原点），由当前倍数映射到轨道位置。
     */
    private int getHandleX() {
        Rect2i track = getTrack();
        int max = getMaxMultiplier();
        if (max <= 1) {
            return track.getX();
        }
        double t = (double) (multiplier - 1) / (max - 1);
        return track.getX() + (int) Math.round(t * (track.getWidth() - AEGuiMetrics.HANDLE_WIDTH));
    }

    /**
     * 滑块手柄顶部 y 坐标（相对界面原点），在轨道垂直方向居中。
     */
    private int getHandleY() {
        Rect2i track = getTrack();
        return track.getY() + AEGuiMetrics.HANDLE_VERTICAL_OFFSET;
    }

    @Override
    public boolean isVisible() {
        return isOpen();
    }

    @Override
    public void setPosition(Point position) {
        // 位置由样式 JSON 的 deviceConfigPopup 条目驱动（相对界面原点）。
        this.bounds = new Rect2i(position.getX(), position.getY(), bounds.getWidth(), bounds.getHeight());
    }

    @Override
    public void setSize(int width, int height) {
        // 尺寸由样式 JSON 的 deviceConfigPopup 条目驱动；样式未给宽高时保持默认。
        if (width > 0 && height > 0) {
            this.bounds = new Rect2i(bounds.getX(), bounds.getY(), width, height);
        }
    }

    @Override
    public Rect2i getBounds() {
        return bounds;
    }

    @Override
    public void populateScreen(Consumer<AbstractWidget> addWidget, Rect2i bounds, AEBaseScreen<?> screen) {
        // 弹窗不使用原版 widget，全部通过重绘实现。
    }

    /**
     * 弹窗面板必须在前景层（{@code drawForegroundLayer}）绘制：
     * 物品图标（插槽渲染）在背景层之后、前景层之前渲染，若只在背景层画面板，
     * 会被物品图标、设备列表文字等下层元素盖住。绘制时不做全屏遮罩，
     * 弹窗唤出后背后的 UI 保持正常亮度显示，仅弹窗面板本身覆盖其下层区域。
     */
    @Override
    public void drawBackgroundLayer(GuiGraphics guiGraphics, Rect2i screenBounds, Point mouse) {
        // 面板在前景层绘制，背景层无需任何绘制。
    }

    @Override
    public void drawForegroundLayer(GuiGraphics guiGraphics, Rect2i bounds, Point mouse) {
        if (!isOpen() || device == null) {
            return;
        }
        // 弹窗面板：绘制素材左上角 203x32 的横向面板条（前景层，覆盖下层全部元素，不变暗背后 UI）。
        Blitter.texture(AEGuiMetrics.DEVICE_ENTRY_GUI)
                .src(AEGuiMetrics.POPUP_SRC_X, AEGuiMetrics.POPUP_SRC_Y,
                        AEGuiMetrics.POPUP_WIDTH, AEGuiMetrics.POPUP_HEIGHT)
                .dest(this.bounds.getX(), this.bounds.getY())
                .blit(guiGraphics);
        var font = Minecraft.getInstance().font;

        // 标题：设备名称（超出左侧区域宽度则截断并追加省略号）。
        String name = device.name().getString();
        int maxNameW = AEGuiMetrics.TRACK_X - AEGuiMetrics.TITLE_PAD_X - AEGuiMetrics.TITLE_RIGHT_PAD;
        String displayName = font.plainSubstrByWidth(name, maxNameW);
        if (!displayName.equals(name)) {
            displayName = font.plainSubstrByWidth(name, Math.max(0, maxNameW - font.width("..."))) + "...";
        }
        guiGraphics.drawString(font, displayName, this.bounds.getX() + AEGuiMetrics.TITLE_PAD_X,
                this.bounds.getY() + AEGuiMetrics.TITLE_PAD_Y, textColor, false);

        // 当前倍数文字（轨道右端外侧）。
        guiGraphics.drawString(font, "x" + multiplier,
                this.bounds.getX() + AEGuiMetrics.TRACK_X + AEGuiMetrics.TRACK_WIDTH + AEGuiMetrics.MULT_LABEL_GAP,
                this.bounds.getY() + AEGuiMetrics.TRACK_Y + AEGuiMetrics.MULT_LABEL_OFFSET_Y,
                textColor, false);

        // 滑块手柄：绘制贴图 (0,32,15,12) 的小方块，垂直居中于轨道槽。
        int handleX = getHandleX();
        int handleY = getHandleY();
        Blitter.texture(AEGuiMetrics.DEVICE_ENTRY_GUI)
                .src(AEGuiMetrics.HANDLE_TEX_X, AEGuiMetrics.HANDLE_TEX_Y,
                        AEGuiMetrics.HANDLE_TEX_WIDTH, AEGuiMetrics.HANDLE_TEX_HEIGHT)
                .dest(handleX, handleY)
                .blit(guiGraphics);
    }

    @Override
    public boolean onMouseDown(Point mousePos, int button) {
        if (!isOpen()) {
            return false;
        }
        // 点击弹窗外任意位置：关闭弹窗。
        if (!mousePos.isIn(bounds)) {
            close();
            return true;
        }
        // 点击滑块轨道（含手柄及附近区域）：跳转到对应倍数并开始拖动。
        if (button == 0) {
            Rect2i track = getTrack();
            if (mousePos.getY() >= track.getY() - AEGuiMetrics.TRACK_HIT_PAD_Y
                    && mousePos.getY() <= track.getY() + track.getHeight() + AEGuiMetrics.TRACK_HIT_PAD_Y
                    && mousePos.getX() >= track.getX() - AEGuiMetrics.HANDLE_WIDTH / 2
                    && mousePos.getX() <= track.getX() + track.getWidth() + AEGuiMetrics.HANDLE_WIDTH / 2) {
                setMultiplierFromX(mousePos.getX());
                dragging = true;
            }
        }
        // 弹窗内其余区域：吞掉事件，避免穿透。
        return true;
    }

    @Override
    public boolean wantsAllMouseDownEvents() {
        return isOpen();
    }

    @Override
    public boolean onMouseUp(Point mousePos, int button) {
        if (button == 0) {
            dragging = false;
        }
        return isOpen();
    }

    @Override
    public boolean wantsAllMouseUpEvents() {
        return isOpen();
    }

    @Override
    public boolean onMouseDrag(Point mousePos, int button) {
        if (!isOpen() || !dragging || button != 0) {
            return false;
        }
        setMultiplierFromX(mousePos.getX());
        return true;
    }

    @Override
    public boolean onMouseWheel(Point mousePos, double delta) {
        if (!isOpen()) {
            return false;
        }
        // 基准步数 = 滚轮事件的实际幅度（常规鼠标每格为 1；高分辨率滚轮/触控板可能一次带多格），
        // 先按实际幅度走，避免丢弃大步进事件。
        int amount = Math.max(1, (int) Math.round(Math.abs(delta)));
        // 修饰键放大步进：按住 Ctrl 滚动按 100 格、按住 Shift 滚动按 10 格（Ctrl 优先于 Shift），
        // 便于在高倍率设备上快速定位；不按修饰键即逐格微调。
        if (Screen.hasControlDown()) {
            amount *= 100;
        } else if (Screen.hasShiftDown()) {
            amount *= 10;
        }
        int max = getMaxMultiplier();
        int next = Mth.clamp(multiplier + (delta > 0 ? amount : -amount), 1, max);
        if (next != multiplier) {
            multiplier = next;
            sendMultiplier();
        }
        return true;
    }

    @Override
    public boolean wantsAllMouseWheelEvents() {
        return isOpen();
    }

    /**
     * 根据鼠标 x 坐标计算新的加速倍数（钳制在 1..max），变化时实时发送到服务端。
     */
    private void setMultiplierFromX(int mouseX) {
        Rect2i track = getTrack();
        int max = getMaxMultiplier();
        int next;
        if (max <= 1) {
            next = 1;
        } else {
            int usable = track.getWidth() - AEGuiMetrics.HANDLE_WIDTH;
            double t = Mth.clamp((double) (mouseX - track.getX()) / usable, 0.0, 1.0);
            next = 1 + (int) Math.round(t * (max - 1));
        }
        if (next != multiplier) {
            multiplier = next;
            sendMultiplier();
        }
    }

    /**
     * 将当前倍数通过客户端动作发送到服务端（由服务端持久化并切换设备加速状态）。
     */
    private void sendMultiplier() {
        if (device != null) {
            menu.sendSetAccelMultiplier(device.id(), multiplier);
        }
    }

    @Nullable
    @Override
    public Tooltip getTooltip(int mouseX, int mouseY) {
        if (!isOpen()) {
            return null;
        }
        if (inArea(new Point(mouseX, mouseY), bounds)) {
            return new Tooltip(List.of(Component.translatable(
                    "gui." + Torcherinoaemod.MOD_ID + ".ae_accelerator.popup_hint",
                    multiplier, getMaxMultiplier())));
        }
        return null;
    }

    /**
     * 点是否位于给定矩形内（含边界）。
     */
    private static boolean inArea(Point p, Rect2i area) {
        return p.getX() >= area.getX()
                && p.getX() <= area.getX() + area.getWidth()
                && p.getY() >= area.getY()
                && p.getY() <= area.getY() + area.getHeight();
    }
}
