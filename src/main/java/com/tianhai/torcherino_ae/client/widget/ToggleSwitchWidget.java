package com.tianhai.torcherino_ae.client.widget;

import java.util.List;
import java.util.function.Consumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

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
import com.tianhai.torcherino_ae.menu.AETorcherinoMenu;

/**
 * 总开关控件（滑动开关）：供 AE 加速火把界面（{@link com.tianhai.torcherino_ae.client.screen.AETorcherinoScreen}）
 * 一键开启/关闭火把加速使用。
 * <p>
 * 实现为 {@link ICompositeWidget}，由 AE 的 {@code WidgetContainer} 统一派发绘制与鼠标事件。
 * 点击开关行任意位置即切换状态，通过 {@link AETorcherinoMenu#sendSetEnabled} 实时发送到服务端；
 * 本地先行翻转显示状态获得即时反馈，服务端经 {@code @GuiSync} 下发的权威值在每 tick
 * 的 {@link #tick()} 中回流，异常数据流（如方块已失效）下自动纠正。
 * <p>
 * 视觉沿用火把界面滑块行布局：左侧为标签文字（颜色与滑块一样经主背景明暗自适应），
 * 右侧绘制 AE2 原生复选框切换按钮贴图（AE2 {@code guis/checkbox.png}，与 AE2 自身的
 * {@code AECheckbox} 控件同源同帧）：关闭=未勾选框，开启=勾选框，鼠标悬停自动切换为
 * 对应的高亮帧，亮/暗两套 UI 下均可辨读。
 */
public class ToggleSwitchWidget implements ICompositeWidget {

    /** 标签文字距控件左边缘的内边距。 */
    private static final int LABEL_PAD_X = 2;
    /** 按钮距控件右边缘的内边距。 */
    private static final int RIGHT_PAD = 8;

    // AE2 原生复选框切换按钮素材。注意两点（都曾踩坑）：
    // 1. 目录是 AE2 惯例的 guis（多数 AE2 界面贴图统一放 assets/ae2/textures/guis/ 下），
    //    误写成 textures/gui/ 会导致纹理解析失败而整块发黑；
    // 2. 该图是 64x64，而 Blitter.texture(ResourceLocation) 默认按 256x256 归一化 UV——
    //    直接用于它会把 src 区域采样成图像上的一小块碎片（显示空白）。因此必须显式传
    //    参考尺寸 64x64（与 AE2 自家 AECheckbox 的做法一致）。
    /** AE2 复选框贴图资源（命名空间归 ae2）。 */
    private static final ResourceLocation CHECKBOX_TEXTURE =
            ResourceLocation.fromNamespaceAndPath("ae2", "textures/guis/checkbox.png");
    /** 复选框贴图基准尺寸（checkbox.png 为 64x64，Blitter UV 归一化必须用它）。 */
    private static final int CHECKBOX_TEXTURE_SIZE = 64;
    /** 复选框帧的绘制/源宽度。 */
    private static final int CHECKBOX_WIDTH = 22;
    /** 复选框帧的绘制/源高度。 */
    private static final int CHECKBOX_HEIGHT = 12;
    /** 关闭态普通帧源 X（悬停帧源 X = 其 + 帧宽）。 */
    private static final int FRAME_OFF_X = 0;
    /** 关闭态普通帧源 Y。 */
    private static final int FRAME_OFF_Y = 28;
    /** 开启态普通帧源 Y（悬停帧源 Y = 其 + 帧宽）。 */
    private static final int FRAME_ON_Y = 40;

    private final AETorcherinoMenu menu;
    private final Component label;
    private final int textColor;

    // 控件区域（相对界面原点），由样式 JSON 的 setPosition/setSize 驱动。
    private Rect2i bounds = new Rect2i(0, 0, 174, 17);

    /**
     * 本地乐观显示窗口（单位 tick）。点击后这段时间内信任本地翻转值，服务端确认广播
     * 到达前不再用旧值覆盖显示，防止「点击 → 回弹 → 再翻正」的闪动；窗口仅作兜底，
     * 正常情况下服务端广播远早于窗口结束即已接管。
     */
    private static final int OPTIMISTIC_TICKS = 20;

    // 本地显示状态：点击时先行翻转获得即时反馈。服务端权威值（menu.enabled）每 tick 在
    // tick() 中回流，采用「服务端值发生变化才覆盖 + 乐观窗口超时兜底」的策略，杜绝回弹
    // 闪烁且保留异常数据流纠正（详见 tick()）。初值取服务端权威值，打开界面首帧即正确。
    private boolean on;
    /** 上次 tick 观测到的服务端权威值，用于检测其变化（防回弹的关键）。 */
    private boolean lastServerOn;
    /** 乐观显示窗口剩余 tick 数，见 {@link #OPTIMISTIC_TICKS}。 */
    private int optimisticTicksRemaining;

    public ToggleSwitchWidget(AETorcherinoMenu menu, Component label, ScreenStyle style) {
        this.menu = menu;
        this.label = label;
        // 开关行标签直接画在火把界面的主背景上，颜色适配逻辑与设置滑块完全一致：
        // 以「实际生效的主背景明暗」做对比度兜底，暗色 UI 材质包下自动提亮。
        int base = style.getColor(PaletteColor.DEFAULT_TEXT_COLOR).toARGB();
        boolean mainBgDark = GuiTheme.isDark(AEGuiMetrics.AE2_GUI_BACKGROUND);
        this.textColor = GuiTheme.ensureContrast(base, mainBgDark);
        this.on = menu.isEnabled();
        this.lastServerOn = on;
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

    @Override
    public void populateScreen(Consumer<AbstractWidget> addWidget, Rect2i bounds, AEBaseScreen<?> screen) {
        // 开关不使用原版 widget，全部通过重绘实现。
    }

    @Override
    public void drawBackgroundLayer(GuiGraphics guiGraphics, Rect2i screenBounds, Point mouse) {
        var font = Minecraft.getInstance().font;

        // 控件原点（窗口绝对坐标）。背景层画布未做平移，绘制必须用绝对坐标；
        // 鼠标事件传入的是相对界面原点的坐标，命中检测仍用相对 bounds，两套坐标互不影响。
        int originX = screenBounds.getX() + bounds.getX();
        int originY = screenBounds.getY() + bounds.getY();

        // 标签文字：垂直居中于行。
        guiGraphics.drawString(font, label,
                originX + LABEL_PAD_X,
                originY + (bounds.getHeight() - font.lineHeight) / 2,
                textColor, false);

        // AE2 原生复选框切换按钮（关=空框、开=勾选），垂直居中于行高。
        // 鼠标位于行内（整行皆可点击，见 onMouseDown）时切到同列右侧的悬停高亮帧作反馈；
        // 帧布局：关闭行 y=28、开启行 y=40，普通帧在 x=0、悬停帧在 x=帧宽处。
        int boxX = originX + bounds.getWidth() - RIGHT_PAD - CHECKBOX_WIDTH;
        int boxY = originY + (bounds.getHeight() - CHECKBOX_HEIGHT) / 2;
        boolean hover = mouse.isIn(bounds);
        int srcY = on ? FRAME_ON_Y : FRAME_OFF_Y;
        int srcX = FRAME_OFF_X + (hover ? CHECKBOX_WIDTH : 0);
        Blitter.texture(CHECKBOX_TEXTURE, CHECKBOX_TEXTURE_SIZE, CHECKBOX_TEXTURE_SIZE)
                .src(srcX, srcY, CHECKBOX_WIDTH, CHECKBOX_HEIGHT)
                .dest(boxX, boxY, CHECKBOX_WIDTH, CHECKBOX_HEIGHT)
                .blit(guiGraphics);
    }

    @Override
    public void drawForegroundLayer(GuiGraphics guiGraphics, Rect2i bounds, Point mouse) {
        // 开关在前景层无额外绘制，均已在背景层完成。
    }

    @Override
    public boolean onMouseDown(Point mousePos, int button) {
        if (!mousePos.isIn(bounds) || button != 0) {
            return false;
        }
        // 点击开关行任意位置即切换。本地先行翻转并开启乐观窗口：在窗口期间 tick() 不会
        // 用服务端旧值覆盖显示，避免确认广播到达前按钮先翻正又被顶回、等确认再翻正的闪动；
        // 服务端确认广播到达后由 tick() 检测值变化并接管。
        on = !on;
        optimisticTicksRemaining = OPTIMISTIC_TICKS;
        menu.sendSetEnabled(on);
        return true;
    }

    @Override
    public boolean onMouseUp(Point mousePos, int button) {
        return false;
    }

    @Override
    public boolean onMouseDrag(Point mousePos, int button) {
        return false;
    }

    @Override
    public boolean wantsAllMouseUpEvents() {
        return false;
    }

    @Override
    public boolean onMouseWheel(Point mousePos, double delta) {
        return false;
    }

    @Override
    public Tooltip getTooltip(int mouseX, int mouseY) {
        if (!new Point(mouseX, mouseY).isIn(bounds)) {
            return null;
        }
        return new Tooltip(List.of(Component.translatable(
                "gui." + Torcherinoaemod.MOD_ID + ".ae_torcherino.enabled_hint")));
    }

    @Override
    public void tick() {
        boolean serverOn = menu.isEnabled();
        if (serverOn != lastServerOn) {
            // 服务端权威值发生了变化（本次点击的确认广播到达，或外部变更）：
            // 立即采用权威值并结束乐观期。正常情况下该值恰等于本地乐观翻转的结果，
            // 覆盖时界面不产生任何可见跳动——这正是消除「点击后闪动」的关键：
            // 此前无条件用 serverOn 覆盖 on，确认广播到达前的若干 tick 里本地乐观值
            // 会被旧值顶回一次，等确认到达再翻正，按钮看起来就闪了两下。
            on = serverOn;
            optimisticTicksRemaining = 0;
        } else if (optimisticTicksRemaining > 0 && --optimisticTicksRemaining == 0) {
            // 乐观窗口耗尽而服务端值始终没动（典型：方块已失效，写入被 applySetting 拒绝）：
            // 放弃本地乐观值，强制回到服务端权威值，避免界面与实际长期不一致。
            on = serverOn;
        }
        lastServerOn = serverOn;
    }
}
