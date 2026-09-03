package com.tianhai.torcherino_ae.client.widget;

import java.util.function.Consumer;
import java.util.function.Function;

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

import com.tianhai.torcherino_ae.client.AEGuiMetrics;
import com.tianhai.torcherino_ae.menu.AETorcherinoMenu;

/**
 * 设置滑块控件：由 device_entry_gui.png 的轨道槽与手柄素材绘制，实时发送新值到服务端。
 * <p>
 * 供 AE 加速火把界面（{@link AETorcherinoScreen}）调节加速倍数与三维范围使用；
 * 原为 AETorcherinoScreen 的内部类，P2 分层时独立成文件（client/widget）。
 * 实现为 {@link ICompositeWidget}，由 AE 的 {@code WidgetContainer} 统一派发绘制与鼠标事件。
 * 数值范围 [min, max]；拖动/滚轮/点击轨道都会更新数值并调用 {@link Consumer} 发送到服务端，
 * 服务端下发的最新值会经 {@link #syncFromServer} 在未拖动时同步回来，避免拖动期间被覆盖。
 * <p>
 * P3 配置化：上限 {@code max} 不再写死为构造常量，而是经 {@link Function} 从菜单的
 * {@code @GuiSync} 上限字段（服务端配置 {@code torcherino.maxSpeed/maxXzRange/maxYRange}
 * 的同步结果）读取；每 tick 随 {@link #syncFromServer} 一起刷新，因此服务端配置变更后
 * 新开的界面（以及首包到达后的既有界面）都能拿到正确上限。
 */
public class SettingSliderWidget implements ICompositeWidget {

    // 相对控件原点的布局度量。
    // 轨道槽的宽高一律取自 AEGuiMetrics，与素材源矩形为同一套值：
    // Blitter 的两参 dest(x, y) 会把目标尺寸回退成源矩形尺寸，若此处另设一套宽度，
    // 就会出现「轨道贴图按 98 绘制、手柄行程按 96 计算」的错位（手柄到不了轨道末端）。
    /** 标签文字距控件左边缘的内边距。 */
    private static final int LABEL_PAD_X = 2;
    /** 轨道槽相对控件左边缘的水平位置（区别于素材内的源坐标 {@link AEGuiMetrics#TRACK_X}）。 */
    private static final int TRACK_OFFSET_X = 55;
    /** 轨道槽相对控件顶部的垂直位置（在行高内垂直居中）。 */
    private static final int TRACK_OFFSET_Y = 5;
    /** 数值文字相对轨道右端的间隙。 */
    private static final int VALUE_GAP = 5;

    private final AETorcherinoMenu menu;
    private final Component label;
    private final Function<AETorcherinoMenu, Integer> serverGetter;
    private final Consumer<Integer> sender;
    /** 服务端同步的最大值读取器（经菜单的 @GuiSync 上限字段取得，见 {@link #syncFromServer}）。 */
    private final Function<AETorcherinoMenu, Integer> maxGetter;
    private final int min;
    private final Function<Integer, String> valueFormatter;
    private final int textColor;

    // 控件区域（相对界面原点），由样式 JSON 的 setPosition/setSize 驱动。
    private Rect2i bounds = new Rect2i(0, 0, 174, 17);

    // 当前数值上限（maxGetter 当前结果，随服务端同步刷新；始终 >= min）。
    private int max;
    // 当前数值（min..max）。
    private int value;
    // 是否正在拖动。
    private boolean dragging;

    public SettingSliderWidget(AETorcherinoMenu menu, Component label,
            Function<AETorcherinoMenu, Integer> serverGetter, Consumer<Integer> sender,
            Function<AETorcherinoMenu, Integer> maxGetter, int min,
            Function<Integer, String> valueFormatter, ScreenStyle style) {
        this.menu = menu;
        this.label = label;
        this.serverGetter = serverGetter;
        this.sender = sender;
        this.maxGetter = maxGetter;
        this.min = min;
        this.valueFormatter = valueFormatter;
        this.textColor = style.getColor(PaletteColor.DEFAULT_TEXT_COLOR).toARGB();
        // 初始值与上限均从服务端同步的下发字段读取。
        this.max = Math.max(min, maxGetter.apply(menu));
        this.value = clamp(serverGetter.apply(menu));
    }

    private int clamp(int v) {
        return Mth.clamp(v, min, max);
    }

    /**
     * 每 tick 从菜单刷新服务端下发的最新值：仅在未拖动时刷新当前值，避免覆盖玩家正在拖动的滑块。
     * <p>
     * 同时刷新数值上限：菜单的 {@code @GuiSync} 上限字段可能因服务端配置变更（或打开界面后
     * 首包才到达）而更新，这里把新上限应用到 {@link #max} 并对当前值重新钳制，使滑块范围
     * 始终与服务器权威配置一致。
     */
    public void syncFromServer() {
        int newMax = Math.max(min, maxGetter.apply(menu));
        if (newMax != max) {
            max = newMax;
            value = clamp(value);
        }
        if (!dragging) {
            this.value = clamp(serverGetter.apply(menu));
        }
    }

    @Override
    public boolean isVisible() {
        return true;
    }

    @Override
    public void setPosition(Point position) {
        this.bounds = new Rect2i(position.getX(), position.getY(), bounds.getWidth(), bounds.getHeight());
    }

    @Override
    public void setSize(int width, int height) {
        if (width > 0 && height > 0) {
            this.bounds = new Rect2i(bounds.getX(), bounds.getY(), width, height);
        }
    }

    @Override
    public Rect2i getBounds() {
        return bounds;
    }

    /**
     * 轨道槽区域（相对界面原点），仅用于鼠标命中检测；
     * 背景层绘制需用 screenBounds 换算成窗口绝对坐标，见 {@link #drawBackgroundLayer}。
     */
    private Rect2i getTrack() {
        return new Rect2i(
                bounds.getX() + TRACK_OFFSET_X,
                bounds.getY() + TRACK_OFFSET_Y,
                AEGuiMetrics.TRACK_WIDTH,
                AEGuiMetrics.TRACK_HEIGHT);
    }

    /**
     * 滑块手柄左边缘 x 坐标，由当前数值映射到给定轨道上（坐标系与传入的 track 一致）。
     */
    private int getHandleX(Rect2i track) {
        int handleWidth = AEGuiMetrics.HANDLE_WIDTH;
        if (max <= min) {
            return track.getX();
        }
        double t = (double) (value - min) / (double) (max - min);
        return track.getX() + (int) Math.round(t * (track.getWidth() - handleWidth));
    }

    @Override
    public void populateScreen(Consumer<AbstractWidget> addWidget, Rect2i bounds, AEBaseScreen<?> screen) {
        // 滑块不使用原版 widget，全部通过重绘实现。
    }

    @Override
    public void drawBackgroundLayer(GuiGraphics guiGraphics, Rect2i screenBounds, Point mouse) {
        var font = Minecraft.getInstance().font;

        // 控件原点（窗口绝对坐标）。
        // 背景层画布未做平移，绘制必须用绝对坐标；而鼠标事件传入的是相对界面原点的坐标，
        // 命中检测仍用相对的 bounds/getTrack()，两套坐标互不影响。
        int originX = screenBounds.getX() + bounds.getX();
        int originY = screenBounds.getY() + bounds.getY();

        // 标签文字：垂直居中于行。
        guiGraphics.drawString(font, label,
                originX + LABEL_PAD_X,
                originY + (bounds.getHeight() - font.lineHeight) / 2,
                textColor, false);

        // 轨道槽（绝对坐标）：取自 device_entry_gui.png 中段内嵌的深色槽（复用倍数弹窗素材）。
        // dest 显式给出宽高，避免依赖 Blitter 的「目标尺寸为 0 时回退源尺寸」行为。
        Rect2i track = new Rect2i(
                originX + TRACK_OFFSET_X,
                originY + TRACK_OFFSET_Y,
                AEGuiMetrics.TRACK_WIDTH,
                AEGuiMetrics.TRACK_HEIGHT);
        Blitter.texture(AEGuiMetrics.DEVICE_ENTRY_GUI)
                .src(AEGuiMetrics.TRACK_X, AEGuiMetrics.TRACK_Y,
                        AEGuiMetrics.TRACK_WIDTH, AEGuiMetrics.TRACK_HEIGHT)
                .dest(track.getX(), track.getY(), track.getWidth(), track.getHeight())
                .blit(guiGraphics);

        // 当前数值文字：轨道右端外侧，垂直方向与轨道、标签、手柄共用同一中心线。
        guiGraphics.drawString(font, valueFormatter.apply(value),
                track.getX() + track.getWidth() + VALUE_GAP,
                track.getY() + (track.getHeight() - font.lineHeight) / 2,
                textColor, false);

        // 滑块手柄：取自 device_entry_gui.png 的手柄小方块，垂直居中于轨道。
        int handleX = getHandleX(track);
        int handleY = track.getY() + AEGuiMetrics.HANDLE_VERTICAL_OFFSET;
        Blitter.texture(AEGuiMetrics.DEVICE_ENTRY_GUI)
                .src(AEGuiMetrics.HANDLE_TEX_X, AEGuiMetrics.HANDLE_TEX_Y,
                        AEGuiMetrics.HANDLE_TEX_WIDTH, AEGuiMetrics.HANDLE_TEX_HEIGHT)
                .dest(handleX, handleY)
                .blit(guiGraphics);
    }

    @Override
    public void drawForegroundLayer(GuiGraphics guiGraphics, Rect2i bounds, Point mouse) {
        // 滑块在前景层无额外绘制，均已在背景层完成。
    }

    @Override
    public boolean onMouseDown(Point mousePos, int button) {
        if (!mousePos.isIn(bounds) || button != 0) {
            return false;
        }
        Rect2i track = getTrack();
        // 点击轨道（含手柄及附近）任一位置：跳转到对应数值并开始拖动。
        if (mousePos.getY() >= track.getY() - AEGuiMetrics.TRACK_HIT_PAD_Y
                && mousePos.getY() <= track.getY() + track.getHeight() + AEGuiMetrics.TRACK_HIT_PAD_Y
                && mousePos.getX() >= track.getX() - AEGuiMetrics.HANDLE_WIDTH / 2
                && mousePos.getX() <= track.getX() + track.getWidth() + AEGuiMetrics.HANDLE_WIDTH / 2) {
            setFromX(mousePos.getX());
            dragging = true;
            return true;
        }
        // 点击控件内轨道以外区域：吞掉事件，避免误触下层。
        return true;
    }

    @Override
    public boolean onMouseUp(Point mousePos, int button) {
        if (button == 0 && dragging) {
            dragging = false;
            return true;
        }
        return false;
    }

    @Override
    public boolean wantsAllMouseUpEvents() {
        return dragging;
    }

    @Override
    public boolean onMouseDrag(Point mousePos, int button) {
        if (!dragging || button != 0) {
            return false;
        }
        setFromX(mousePos.getX());
        return true;
    }

    @Override
    public boolean onMouseWheel(Point mousePos, double delta) {
        if (!mousePos.isIn(bounds)) {
            return false;
        }
        int next = clamp(value + (delta > 0 ? 1 : -1));
        if (next != value) {
            value = next;
            sender.accept(value);
        }
        return true;
    }

    /**
     * 根据鼠标 x 坐标计算新的数值（钳制在 min..max），变化时实时发送到服务端。
     */
    private void setFromX(int mouseX) {
        Rect2i track = getTrack();
        int next;
        if (max <= min) {
            next = min;
        } else {
            int usable = track.getWidth() - AEGuiMetrics.HANDLE_WIDTH;
            double t = Mth.clamp((double) (mouseX - track.getX()) / usable, 0.0, 1.0);
            next = min + (int) Math.round(t * (max - min));
        }
        if (next != value) {
            value = next;
            sender.accept(value);
        }
    }

    @Nullable
    @Override
    public Tooltip getTooltip(int mouseX, int mouseY) {
        return null;
    }

    @Override
    public void tick() {
        // 不需要逐 tick 动画，但每 tick 从服务端下发字段刷新未拖动时的值。
        syncFromServer();
    }
}
