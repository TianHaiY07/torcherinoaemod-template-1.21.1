package com.tianhai.torcherino_ae.client.widget;

import java.util.function.Consumer;
import java.util.function.Function;

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

import com.tianhai.torcherino_ae.client.AEGuiMetrics;
import com.tianhai.torcherino_ae.client.GuiTheme;
import com.tianhai.torcherino_ae.menu.AETorcherinoMenu;

/**
 * 设置滑块控件：由 device_entry_gui.png 的轨道槽与手柄素材绘制，实时发送新值到服务端。
 * <p>
 * 供 AE 加速火把界面（{@link AETorcherinoScreen}）调节加速倍数与三维范围使用。
 * 实现为 {@link ICompositeWidget}，由 AE 的 {@code WidgetContainer} 统一派发绘制与鼠标事件。
 * 数值范围 [min, max]；拖动/滚轮/点击轨道都会更新数值并调用 {@link Consumer} 发送到服务端。
 * 服务端下发的最新值经 {@link #syncFromServer} 每 tick 回读，但本地输入（拖动中、以及滚轮/
 * 点击等待服务端广播确认的窗口内）不被旧广播值覆盖，避免滑块被「拉回-跳回」造成回弹抖动。
 * <p>
 * 交互手感：抓住手柄本体按下时不跳值，记录「光标-手柄中心」偏移做跟随拖动（跟手）；
 * 点击轨道空白处则跳转到该位置并开始拖动。悬停与拖动期间手柄有高亮反馈。
 * <p>
 * 滚轮调节：鼠标悬停在控件行内滚动即逐格调整；按住 Shift 滚动按 10 格、按住 Ctrl 滚动
 * 按 100 格步进（Ctrl 优先于 Shift），方便在宽范围滑块（如分级火把最高 324x）上快速定位。
 * <p>
 * 上限 {@code max} 经 {@link Function} 从菜单的 {@code @GuiSync} 上限字段
 * （服务端配置 {@code torcherino.maxSpeed/maxXzRange/maxYRange} 的同步结果）读取；
 * 每 tick 随 {@link #syncFromServer} 一起刷新，因此服务端配置变更后新开的界面
 * （以及首包到达后的既有界面）都能拿到正确上限。
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
    /** 手柄「抓取判定」的水平放宽量：光标落在手柄两侧该距离内均视为抓住手柄，避免误跳值。 */
    private static final int GRAB_PAD_X = 3;
    /** 本地待确认输入的防呆上限（tick）：正常一个网络往返内即被服务端广播确认，
     *  超时视为服务端权威值已变化（如其它来源改值/配置收紧），放弃本地值采纳服务端值。 */
    private static final int MAX_PENDING_TICKS = 30;

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
    // 拖动抓取偏移：按下瞬间「光标 x - 手柄中心 x」。抓住手柄本体拖动时用它扣除，
    // 使手柄始终跟手而不跳值；点轨道空白处开始拖动时偏移为 0（手柄中心即吸附到光标）。
    private int dragGrabOffset;
    // 是否存在「本地已修改、但尚未等到服务端广播确认」的输入（滚轮/点击/拖动释放）。
    // 发送动作后到服务器广播回来前，serverGetter 仍是上一拍旧值：若 tick 同步此时用旧值
    // 覆盖本地 value，滑块会被拉回旧位置、新广播到达又跳回，连续输入时表现为
    // 「一卡一卡地反复移动」。因此确认前保持本地值，确认（serverGetter == value）后清除。
    private boolean pendingLocalChange;
    // 上述待确认状态已持续的 tick 数，超过 {@link #MAX_PENDING_TICKS} 强制收尾（见 syncFromServer）。
    private int pendingTicks;

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
        // 滑块文字（标签与数值）直接画在火把界面的主背景上——该背景由 AE2 的
        // BackgroundGenerator 以 guis/background.png 平铺，暗色材质包常把这张图整体替换。
        // 因此文字色以「实际主背景明暗」做自适应：背景被换暗则自动提亮，
        // 默认亮背景 + 深色调色板环境下返回基色不变。
        int base = style.getColor(PaletteColor.DEFAULT_TEXT_COLOR).toARGB();
        boolean mainBgDark = GuiTheme.isDark(AEGuiMetrics.AE2_GUI_BACKGROUND);
        this.textColor = GuiTheme.ensureContrast(base, mainBgDark);
        // 初始值与上限均从服务端同步的下发字段读取。
        this.max = Math.max(min, maxGetter.apply(menu));
        this.value = clamp(serverGetter.apply(menu));
    }

    private int clamp(int v) {
        return Mth.clamp(v, min, max);
    }

    /**
     * 每 tick 从菜单刷新服务端下发的最新值，规则分三种：
     * <ul>
     *   <li>拖动中：不回读，保证手柄跟手；</li>
     *   <li>有本地输入待确认（滚轮/点击/刚松手）：保持本地值，等服务端广播确认，
     *       避免被上一拍旧值覆盖造成「回弹/反复」——见 {@link #pendingLocalChange}；</li>
     *   <li>其余：回读服务端权威值（覆盖本地显示）。</li>
     * </ul>
     * <p>
     * 同时刷新数值上限：菜单的 {@code @GuiSync} 上限字段可能因服务端配置变更（或打开界面后
     * 首包才到达）而更新，这里把新上限应用到 {@link #max} 并对当前值重新钳制，使滑块范围
     * 始终与服务器权威配置一致（该钳制在所有状态下都执行，含拖动中）。
     */
    public void syncFromServer() {
        int newMax = Math.max(min, maxGetter.apply(menu));
        if (newMax != max) {
            max = newMax;
            value = clamp(value);
        }
        if (dragging) {
            // 拖动中不回读（值已由本地连续发送，且可能领先服务器一拍）。
            return;
        }
        int serverValue = clamp(serverGetter.apply(menu));
        if (pendingLocalChange && serverValue != value) {
            // 本地输入尚未被确认：保持本地值。仅当长时间等不到确认（防呆上限内）
            // 才视为服务端权威值已改变，放弃本地值收尾，避免状态永久悬挂。
            if (++pendingTicks > MAX_PENDING_TICKS) {
                pendingLocalChange = false;
                pendingTicks = 0;
                this.value = serverValue;
            }
            return;
        }
        pendingLocalChange = false;
        pendingTicks = 0;
        this.value = serverValue;
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
     * 鼠标可点区域（相对界面原点）：轨道横向两端各放宽一个手柄宽度，竖向放宽到整行高度。
     * 命中后即可定位/拖动滑块——扩大点击热区，行内点选不再要求精确对准 6px 高的轨道槽；
     * 行内该区域以外的部分只吞事件、不响应（避免误触下层控件）。
     */
    private Rect2i getTrackHitArea() {
        return new Rect2i(
                bounds.getX() + TRACK_OFFSET_X - AEGuiMetrics.HANDLE_WIDTH,
                bounds.getY(),
                AEGuiMetrics.TRACK_WIDTH + AEGuiMetrics.HANDLE_WIDTH * 2,
                bounds.getHeight());
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

    /**
     * 悬停判定：鼠标是否真的落在滑块手柄贴图矩形上。
     * <p>
     * 入参 {@code mouse} 是相对界面原点的坐标，而 {@code handleX/handleY} 是背景层绘制用的
     * 绝对窗口坐标，因此先加 {@code screenBounds} 偏移换算再比较。刻意使用手柄自身的小矩形
     * 而非整条轨道/放宽区域（{@link #getTrackHitArea()}），避免鼠标滑到轨道空白处也被误提示
     * 为「可抓取」——真正的可抓取部件只有手柄本身。
     */
    private boolean isMouseOnHandle(Point mouse, Rect2i screenBounds, int handleX, int handleY) {
        int mouseX = mouse.getX() + screenBounds.getX();
        int mouseY = mouse.getY() + screenBounds.getY();
        return mouseX >= handleX && mouseX < handleX + AEGuiMetrics.HANDLE_TEX_WIDTH
                && mouseY >= handleY && mouseY < handleY + AEGuiMetrics.HANDLE_TEX_HEIGHT;
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

        // 交互反馈：拖动中始终高亮；悬停时仅当鼠标真正落在滑块手柄贴图上才高亮，
        // 提示该处可抓取、当前正在被操作；拖动比悬停更亮以示区别。
        // 悬停判定刻意收窄到手柄本身（而非整条轨道/放宽区域），鼠标在轨道空白处滑动时
        // 不再被整片高亮误导为「可抓取」。
        if (dragging || isMouseOnHandle(mouse, screenBounds, handleX, handleY)) {
            int highlight = dragging ? 0x55FFFFFF : 0x28FFFFFF;
            guiGraphics.fill(handleX, handleY,
                    handleX + AEGuiMetrics.HANDLE_TEX_WIDTH,
                    handleY + AEGuiMetrics.HANDLE_TEX_HEIGHT, highlight);
        }
    }

    @Override
    public void drawForegroundLayer(GuiGraphics guiGraphics, Rect2i bounds, Point mouse) {
        // 滑块在前景层无额外绘制，均已在背景层完成。
    }

    @Override
    public boolean onMouseDown(Point mousePos, int button) {
        if (button != 0) {
            return false;
        }
        // 点击本行（含标签/数值文字等整块行高区域）之外的其它位置不响应。
        if (!mousePos.isIn(bounds)) {
            return false;
        }
        Rect2i track = getTrack();
        // 仅轨道附近（整行高 + 横向放宽）才接受定位/拖动。
        if (!mousePos.isIn(getTrackHitArea())) {
            // 点击行内非轨道区域：吞掉事件，避免误触下层。
            return true;
        }
        // 抓住手柄本体（含两侧放宽）：不跳值，记录光标相对手柄中心的偏移做跟随拖动，
        // 保证手柄任意部位按下都跟手，不会因「中心吸附光标」而瞬间跳变。
        if (isOnHandle(mousePos.getX())) {
            int handleCenterX = getHandleX(track) + AEGuiMetrics.HANDLE_WIDTH / 2;
            dragGrabOffset = mousePos.getX() - handleCenterX;
        } else {
            // 点击轨道空白处：跳转到该位置（手柄中心吸附到光标）并开始拖动。
            dragGrabOffset = 0;
            setFromX(mousePos.getX());
        }
        dragging = true;
        return true;
    }

    @Override
    public boolean onMouseUp(Point mousePos, int button) {
        if (button == 0 && dragging) {
            dragging = false;
            dragGrabOffset = 0;
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
        // 拖动期间光标可能离开轨道/控件行，仍按扣除抓取偏移后的 x 换算并钳制数值。
        setFromX(mousePos.getX() - dragGrabOffset);
        return true;
    }

    @Override
    public boolean onMouseWheel(Point mousePos, double delta) {
        if (!mousePos.isIn(bounds)) {
            return false;
        }
        // 基准步数 = 滚轮事件的实际幅度（常规鼠标每格为 1；高分辨率滚轮/触控板可能一次带多格），
        // 先按实际幅度走，避免丢弃大步进事件。
        int amount = Math.max(1, (int) Math.round(Math.abs(delta)));
        // 修饰键放大步进：按住 Ctrl 滚动按 100 格、按住 Shift 滚动按 10 格（Ctrl 优先于 Shift），
        // 便于在宽范围滑块（如分级火把最高 324x）上快速定位；不按修饰键即逐格微调。
        if (Screen.hasControlDown()) {
            amount *= 100;
        } else if (Screen.hasShiftDown()) {
            amount *= 10;
        }
        int next = clamp(value + (delta > 0 ? amount : -amount));
        if (next != value) {
            value = next;
            sender.accept(value);
            // 标记待服务端确认，避免 tick 同步用旧广播值把刚滚动的值拉回去（见 syncFromServer）。
            pendingLocalChange = true;
            pendingTicks = 0;
        }
        return true;
    }

    /**
     * 光标 x 是否落在手柄本体上（含两侧 {@link #GRAB_PAD_X} 的放宽，便于抓取小手柄）。
     */
    private boolean isOnHandle(int mouseX) {
        Rect2i track = getTrack();
        int handleX = getHandleX(track);
        return mouseX >= handleX - GRAB_PAD_X
                && mouseX <= handleX + AEGuiMetrics.HANDLE_WIDTH + GRAB_PAD_X;
    }

    /**
     * 根据鼠标 x 坐标计算新的数值（钳制在 min..max），变化时实时发送到服务端。
     * <p>
     * 调用方负责扣除 {@link #dragGrabOffset}（抓取手柄时），因此入参即为手柄中心的目标 x。
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
            // 点击跳转/拖动改值均标记待确认：拖动松开后一拍内服务端广播未到，
            // 若不保护会被旧值回弹一次（见 syncFromServer 与 {@link #pendingLocalChange}）。
            pendingLocalChange = true;
            pendingTicks = 0;
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
