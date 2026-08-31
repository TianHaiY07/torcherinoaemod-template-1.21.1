package com.tianhai.torcherino_ae.menu;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.jetbrains.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
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
import appeng.client.gui.widgets.Scrollbar;

import com.tianhai.torcherino_ae.Torcherinoaemod;
import com.tianhai.torcherino_ae.client.AEGuiMetrics;

/**
 * 「可加速设备」列表控件。
 * <p>
 * 作为 {@link ICompositeWidget} 挂载到加速器界面，把加速器所在网格的设备以列表形式绘制在界面中间区域。
 * 列表背景与悬浮高亮均取自本模组 GUI 贴图 {@code ae_accelerator_gui.png}：
 * 背景为样式 JSON 的 {@code deviceListBg}（对应贴图内的设备列表面板区域），
 * 只要列表非空就始终铺上（不依赖悬浮状态）；悬浮高亮为样式 JSON 的 {@code deviceListSlotSelected}
 * （对应贴图内的单行区域），鼠标悬停所在行时直接绘制该贴图区域。文字颜色取自样式调色板
 * （{@link PaletteColor}），滚动条、搜索框等交互组件均为 AE 原生组件。控件职责：
 * <ul>
 *   <li>搜索过滤（由外界通过 {@link #setFilter(String)} 设置关键字）</li>
 *   <li>滚动条联动（与传入的 {@link Scrollbar} 协作控制可见行；拖柄由 AE 的 {@link Scrollbar}
 *       组件自行绘制，本控件只负责计算滚动范围与按需显示）</li>
 *   <li>活动状态展示（活动设备用默认文字色，非活动设备用弱化色）</li>
 *   <li>悬浮高亮（悬停行直接绘制贴图单行区域）</li>
 *   <li>点击切换加速（左键点击某行即向服务端发送「切换该设备加速状态」请求）</li>
 *   <li>加速状态展示（正在被加速的设备行持续铺高亮背景、文字保持默认色；
 *       每行行尾绘制状态图标，未加速与加速中分别取贴图两张小图标）</li>
 * </ul>
 * 注意：列表区域高度为行高(AEGuiMetrics.AEGuiMetrics.ROW_HEIGHT)的整数倍，宽度由样式 JSON 决定；
 * 控件只绘制完整行，不依赖 {@link GuiGraphics#enableScissor 裁剪}——与 AE 终端网格的做法一致，
 * 避免 scissor 在不同 GUI Scale 下的坐标换算异常导致列表内容丢失。
 */
public class DeviceListWidget implements ICompositeWidget {

    // 设备图标与格子的布局、行尾状态图标素材区域等与贴图强耦合的度量，
    // 全部集中在 AEGuiMetrics，便于随贴图统一调整。

    // 引用菜单以读取设备列表数据。
    private final AEAcceleratorMenu menu;

    // 引用滚动条以联动可见行。
    private final Scrollbar scrollbar;

    // 列表背景 Blitter（本模组 GUI 贴图内的设备列表面板区域，样式 JSON images.deviceListBg）。
    private final Blitter listBackground;

    // 悬浮高亮 Blitter（本模组 GUI 贴图内的单行区域，样式 JSON images.deviceListSlotSelected）。
    private final Blitter selection;

    // 行文字颜色（样式调色板）。
    private final int textColor;
    private final int mutedTextColor;
    private final int accentColor;

    // 搜索过滤关键字（空串表示不过滤）。由搜索文本框的回调设置。
    private String filter = "";

    // 控件是否可交互：配置弹窗打开时被禁用，避免点击穿透到列表行。
    private boolean enabled = true;

    // 右键点击设备行的回调（由屏幕设置，用于打开「加速倍数配置弹窗」）。
    private java.util.function.Consumer<DeviceEntry> onRightClickDevice;

    // 相对界面原点的控件区域。
    private Rect2i bounds = new Rect2i(0, 0, 0, 0);

    public DeviceListWidget(AEAcceleratorMenu menu, Scrollbar scrollbar, ScreenStyle style) {
        this.menu = menu;
        this.scrollbar = scrollbar;
        // 列表背景与悬浮高亮取自样式 JSON 的 images.deviceListBg / deviceListSlotSelected。
        this.listBackground = style.getImage("deviceListBg");
        this.selection = style.getImage("deviceListSlotSelected");
        this.textColor = style.getColor(PaletteColor.DEFAULT_TEXT_COLOR).toARGB();
        this.mutedTextColor = style.getColor(PaletteColor.MUTED_TEXT_COLOR).toARGB();
        this.accentColor = style.getColor(PaletteColor.SELECTION_COLOR).toARGB();
        // 该控件自己处理滚轮（优先于滚动条全局捕获），避免双重滚动。
        this.scrollbar.setCaptureMouseWheel(false);
    }

    /**
     * 设置控件是否可交互：配置弹窗打开时应传入 {@code false}，防止点击穿透到列表行。
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * 设置右键点击设备行的回调（由屏幕用于打开「加速倍数配置弹窗」）。
     */
    public void setOnRightClickDevice(java.util.function.Consumer<DeviceEntry> onRightClickDevice) {
        this.onRightClickDevice = onRightClickDevice;
    }

    @Override
    public void setPosition(Point position) {
        this.bounds = new Rect2i(position.getX(), position.getY(), bounds.getWidth(), bounds.getHeight());
    }

    @Override
    public void setSize(int width, int height) {
        this.bounds = new Rect2i(bounds.getX(), bounds.getY(), width, height);
    }

    @Override
    public Rect2i getBounds() {
        return bounds;
    }

    /**
     * 设置搜索过滤关键字并重置滚动位置。
     */
    public void setFilter(String filter) {
        this.filter = filter == null ? "" : filter.trim().toLowerCase(Locale.ROOT);
        this.scrollbar.setCurrentScroll(0);
    }

    /**
     * 返回经过搜索过滤的设备列表快照。
     */
    private List<DeviceEntry> getFilteredDevices() {
        List<DeviceEntry> all = menu.devices.devices();
        if (filter.isEmpty()) {
            return all;
        }
        String posFilter = filter;
        List<DeviceEntry> result = new ArrayList<>();
        for (DeviceEntry device : all) {
            String posString = device.pos().getX() + "," + device.pos().getY() + "," + device.pos().getZ();
            if (device.name().getString().toLowerCase(Locale.ROOT).contains(filter)
                    || posString.contains(posFilter)) {
                result.add(device);
            }
        }
        return result;
    }

    /**
     * 当前控件内可容纳的行数。
     */
    public int getVisibleRows() {
        return Math.max(1, bounds.getHeight() / AEGuiMetrics.ROW_HEIGHT);
    }

    /**
     * 相对列表区域原点的 y 坐标对应的行序号（行距 = 条高 + 1px 间隙）。
     */
    private int rowAt(int relY) {
        return relY / (AEGuiMetrics.ROW_HEIGHT + AEGuiMetrics.ROW_SPACING);
    }

    // 列表内容区可用的文字宽度（控件宽度减去图标与左右内边距），用于文字截断。
    private int getTextMaxWidth() {
        return Math.max(16, bounds.getWidth() - AEGuiMetrics.PAD_X - AEGuiMetrics.ICON_SIZE
                - AEGuiMetrics.TEXT_TRAIL_PAD);
    }

    /**
     * 经过搜索过滤后的设备总数。
     */
    public int getTotalRows() {
        return getFilteredDevices().size();
    }

    @Override
    public void updateBeforeRender() {
        List<DeviceEntry> filtered = getFilteredDevices();
        // 可见行数以外的部分通过与滚动条的最大偏移（maxScroll）体现；每次滚轮移动 1 行。
        int hiddenRows = Math.max(0, filtered.size() - getVisibleRows());
        scrollbar.setRange(0, hiddenRows, 1);
        // 滚动条滑块常态渲染：无论列表是否溢出都显示（范围 0 时 AE 组件会绘制禁用态滑块），
        // 避免"内容不溢出时滑块消失"的突兀体验。
        scrollbar.setVisible(true);
    }

    @Override
    public boolean onMouseWheel(Point mousePos, double delta) {
        if (!enabled) {
            return false;
        }
        scrollbar.onMouseWheel(mousePos, delta);
        return true;
    }

    @Override
    public boolean wantsAllMouseWheelEvents() {
        return enabled;
    }

    @Override
    public boolean onMouseDown(Point mousePos, int button) {
        if (!enabled || !mousePos.isIn(bounds)) {
            return false;
        }
        Point rel = new Point(mousePos.getX() - bounds.getX(), mousePos.getY() - bounds.getY());
        DeviceEntry hit = hitTestDevice(rel);
        if (hit != null) {
            if (button == 1) {
                // 右键点击设备行：交给屏幕打开「加速倍数配置弹窗」。
                if (onRightClickDevice != null) {
                    onRightClickDevice.accept(hit);
                }
            } else if (button == 0) {
                // 左键点击设备行：切换该设备的加速状态（加速 ⇄ 取消加速）。
                menu.sendToggleAcceleration(hit.id());
            }
        }
        // 点击列表区域吞掉事件，避免误触到下层插槽。
        return true;
    }

    @Nullable
    @Override
    public Tooltip getTooltip(int mouseX, int mouseY) {
        if (!enabled) {
            return null;
        }
        DeviceEntry hovered = hitTestDevice(new Point(mouseX - bounds.getX(), mouseY - bounds.getY()));
        if (hovered != null) {
            // 提示语：合成 CPU 用「智能加速」语义，普通设备用普通「加速」语义。
            // 加速中的设备提示「左键取消」，未加速提示「左键开始」；均提示右键可调倍数。
            String actionKey;
            if (hovered.craftingCpu()) {
                actionKey = hovered.accelerated() ? "smart_accelerating" : "smart_accelerate";
            } else {
                actionKey = hovered.accelerated() ? "accelerating" : "accelerate";
            }
            Component hint = Component.translatable("gui." + Torcherinoaemod.MOD_ID + ".ae_accelerator." + actionKey);
            Component rightHint = Component.translatable("gui." + Torcherinoaemod.MOD_ID + ".ae_accelerator.right_hint");
            return new Tooltip(java.util.List.of(hovered.name(), hint, rightHint));
        }
        return null;
    }

    /**
     * 命中检测：返回鼠标指向的设备行（未命中返回 null）。
     *
     * @param rel 相对控件原点的鼠标位置
     */
    @Nullable
    private DeviceEntry hitTestDevice(Point rel) {
        List<DeviceEntry> filtered = getFilteredDevices();
        if (filtered.isEmpty()) {
            return null;
        }
        int row = scrollbar.getCurrentScroll() + rowAt(rel.getY());
        if (row < 0 || row >= filtered.size()) {
            return null;
        }
        return filtered.get(row);
    }

    @Override
    public void drawBackgroundLayer(GuiGraphics guiGraphics, Rect2i screenBounds, Point mouse) {
        // 列表区域左上角（窗口绝对坐标）。
        int x = screenBounds.getX() + bounds.getX();
        int y = screenBounds.getY() + bounds.getY();

        var font = Minecraft.getInstance().font;
        var devices = getFilteredDevices();

        // 列表背景：逐行绘制贴图内的设备条目背景条（139x22），整体上移 2px、条间留 1px 间隙。
        if (!devices.isEmpty()) {
            int visibleRows = getVisibleRows();
            for (int i = 0; i < visibleRows; i++) {
                int rowY = y + AEGuiMetrics.LIST_OFFSET_Y + i * (AEGuiMetrics.ROW_HEIGHT + AEGuiMetrics.ROW_SPACING);
                listBackground.copy().dest(x, rowY, bounds.getWidth(), AEGuiMetrics.ROW_HEIGHT).blit(guiGraphics);
            }
        }

        // 悬浮高亮：鼠标位于列表区域内时，对所在行直接绘制贴图内的单行高亮区域（不再叠加颜色蒙版）。
        // 高亮在行内容之前绘制，避免遮住图标与文字。
        if (mouse.isIn(bounds)) {
            int visibleRow = rowAt(mouse.getY() - bounds.getY());
            int absRow = scrollbar.getCurrentScroll() + visibleRow;
            if (visibleRow >= 0 && visibleRow < getVisibleRows() && absRow >= 0 && absRow < devices.size()) {
                int highlightY = y + AEGuiMetrics.LIST_OFFSET_Y + visibleRow * (AEGuiMetrics.ROW_HEIGHT + AEGuiMetrics.ROW_SPACING);
                selection.copy()
                        .dest(x, highlightY, bounds.getWidth(), AEGuiMetrics.ROW_HEIGHT)
                        .blit(guiGraphics);
            }
        }

        // 从滚动位置开始绘制可见行。
        // 由于列表区域高度是行高的整数倍，这里只会绘制完整行，行内容天然不会越出列表
        // 区域，因此无需开启 scissor（与 AE 终端网格相同）。
        int startRow = Mth.clamp(scrollbar.getCurrentScroll(), 0, Math.max(0, devices.size() - 1));
        int endRow = Math.min(devices.size(), startRow + getVisibleRows());

        for (int i = startRow; i < endRow; i++) {
            DeviceEntry device = devices.get(i);
            // 行内容与背景条共用同一套行定位（整体上移 2px、条间 1px 间隙）。
            int rowY = y + AEGuiMetrics.LIST_OFFSET_Y + (i - startRow) * (AEGuiMetrics.ROW_HEIGHT + AEGuiMetrics.ROW_SPACING);

            // 正在被加速的设备行：先铺一层选中态背景，使其在列表中直观可辨。
            if (device.accelerated()) {
                selection.copy()
                        .dest(x, rowY, bounds.getWidth(), AEGuiMetrics.ROW_HEIGHT)
                        .blit(guiGraphics);
            }

            // 设备图标：垂直中心与背景条中心对齐（水平保留左移 4px 微调）。
            if (!device.icon().isEmpty()) {
                guiGraphics.renderItem(device.icon(),
                        x + AEGuiMetrics.PAD_X + AEGuiMetrics.CONTENT_SHIFT_X,
                        rowY + (AEGuiMetrics.ROW_HEIGHT - AEGuiMetrics.ICON_SIZE) / 2);
            }

            // 设备名称：超出可用文字宽度时截断并追加省略号（水平保留左移 4px 微调）。
            // 每行都为行尾状态图标预留宽度。
            int markWidth = AEGuiMetrics.MARK_TEX_WIDTH + AEGuiMetrics.MARK_GAP;
            int textX = x + AEGuiMetrics.PAD_X + AEGuiMetrics.ICON_SIZE
                    + AEGuiMetrics.ICON_TEXT_GAP + AEGuiMetrics.CONTENT_SHIFT_X;
            int textMaxWidth = getTextMaxWidth() - markWidth;
            String name = device.name().getString();
            String displayName = font.plainSubstrByWidth(name, textMaxWidth);
            if (!displayName.equals(name)) {
                displayName = font.plainSubstrByWidth(name, Math.max(0, textMaxWidth - font.width("..."))) + "...";
            }
            // 加速中设备不再用强调色高亮文字，一律用默认文字色；非活动设备用弱化色。
            int color = (device.accelerated() || device.active()) ? textColor : mutedTextColor;
            guiGraphics.drawString(font, displayName, textX,
                    rowY + (AEGuiMetrics.ROW_HEIGHT - font.lineHeight) / 2, color, false);

            // 行尾加速状态图标：未加速绘制贴图 (0,230,12,12)，加速中绘制 (0,242,12,11)。
            // 鼠标悬浮该行时，图标同样切换为「加速中」形式（无论是否真正在加速）。
            boolean rowHovered = mouse.isIn(bounds)
                    && (i - startRow) == rowAt(mouse.getY() - bounds.getY());
            boolean accelShown = device.accelerated() || rowHovered;
            int markHeight = accelShown ? AEGuiMetrics.MARK_TEX_HEIGHT_ACCEL : AEGuiMetrics.MARK_TEX_HEIGHT_IDLE;
            int markX = x + bounds.getWidth() - AEGuiMetrics.MARK_TEX_WIDTH - AEGuiMetrics.MARK_RIGHT_PAD;
            // 垂直居中；仅加速中形式图标下移 1px 与未加速图标对齐视觉效果。
            int markY = rowY + (AEGuiMetrics.ROW_HEIGHT - markHeight) / 2
                    + (accelShown ? AEGuiMetrics.MARK_Y_ACCEL_SHIFT : 0);
            Blitter.texture(AEGuiMetrics.ACCELERATOR_GUI)
                    .src(AEGuiMetrics.MARK_TEX_X, accelShown ? AEGuiMetrics.MARK_TEX_Y_ACCEL : AEGuiMetrics.MARK_TEX_Y_IDLE,
                            AEGuiMetrics.MARK_TEX_WIDTH, markHeight)
                    .dest(markX, markY)
                    .blit(guiGraphics);
        }
    }

    @Override
    public void populateScreen(java.util.function.Consumer<AbstractWidget> addWidget, Rect2i bounds,
            AEBaseScreen<?> screen) {
        // 本控件不使用原版 widget，仅通过重绘实现。
    }

    @Override
    public void tick() {
        // 无逐 tick 动画需求。
    }
}
