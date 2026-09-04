package com.tianhai.torcherino_ae.client.screen;

import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.world.entity.player.Inventory;

import appeng.client.Point;
import appeng.client.gui.AEBaseScreen;
import appeng.client.gui.style.PaletteColor;
import appeng.client.gui.style.ScreenStyle;
import appeng.client.gui.style.Text;

import com.tianhai.torcherino_ae.Torcherinoaemod;
import com.tianhai.torcherino_ae.client.AEGuiMetrics;
import com.tianhai.torcherino_ae.client.GuiTheme;
import com.tianhai.torcherino_ae.client.widget.SettingSliderWidget;
import com.tianhai.torcherino_ae.client.widget.ToggleSwitchWidget;
import com.tianhai.torcherino_ae.menu.AETorcherinoMenu;

/**
 * AE 加速火把的客户端界面。
 * <p>
 * 火把为独立范围扫描方块，界面显示总开关（一键开启/关闭加速）与四个设置滑块（加速倍数、
 * X 范围、Z 范围、Y 范围）；无升级卡插槽、无设备列表。背景由样式 JSON 的 {@code generatedBackground} 交给 AE2 的
 * {@code BackgroundGenerator} 平铺绘制（九宫格素材为 AE2 内部 {@code guis/background.png}）；
 * 滑块视觉复用 {@code device_entry_gui.png} 的轨道槽与手柄素材（见
 * {@link SettingSliderWidget}）。滑块拖动/滚轮/点击实时通过客户端动作把新值发送到服务端。
 * <p>
 * 各滑块的范围上限取菜单中经 {@code @GuiSync} 同步的服务端配置值
 * （{@code AETorcherinoMenu.maxSpeed/maxXzRange/maxYRange}，对应服务端配置
 * {@code torcherino.maxSpeed/maxXzRange/maxYRange}）；滑块每 tick 刷新上限，
 * 配置变更后新开的界面即生效。
 * <p>
 * 界面不使用 AE 垂直工具栏（参考 {@code SkyChestScreen}，样式未定义 verticalToolbar，
 * 不关闭会崩溃）。
 */
public class AETorcherinoScreen extends AEBaseScreen<AETorcherinoMenu> {

    // 总开关与四个设置滑块控件。
    private final ToggleSwitchWidget enableSwitch;
    private final SettingSliderWidget speedSlider;
    private final SettingSliderWidget xRangeSlider;
    private final SettingSliderWidget zRangeSlider;
    private final SettingSliderWidget yRangeSlider;

    // 标题文字颜色：取样式调色板默认色后按界面主背景明暗自适应（默认亮背景环境返回原色）。
    private final int titleColor;

    // 样式 JSON text 段的标题条目 id。
    private static final String TEXT_ID_DIALOG_TITLE = "dialog_title";

    public AETorcherinoScreen(AETorcherinoMenu menu, Inventory playerInventory, Component title, ScreenStyle style) {
        super(menu, playerInventory, title, style);

        // 总开关：点击开启/关闭火把加速（服务端状态经 @GuiSync(8) 同步的 menu.enabled 字段）。
        this.enableSwitch = new ToggleSwitchWidget(menu,
                Component.translatable("gui." + Torcherinoaemod.MOD_ID + ".ae_torcherino.enabled"), style);

        // 加速倍数滑块：范围 1..menu.maxSpeed（服务端配置 torcherino.maxSpeed 同步值），文案显示为 "x"。
        this.speedSlider = new SettingSliderWidget(
                menu,
                Component.translatable("gui." + Torcherinoaemod.MOD_ID + ".ae_torcherino.speed"),
                m -> m.speed, v -> menu.sendSetSpeed(v), m -> m.maxSpeed, 1,
                v -> v + "x", style);
        // X/Z 范围滑块：范围 0..menu.maxXzRange（服务端配置 torcherino.maxXzRange 同步值）。
        this.xRangeSlider = new SettingSliderWidget(
                menu,
                Component.translatable("gui." + Torcherinoaemod.MOD_ID + ".ae_torcherino.range_x"),
                m -> m.xRange, menu::sendSetXRange, m -> m.maxXzRange, 0,
                String::valueOf, style);
        this.zRangeSlider = new SettingSliderWidget(
                menu,
                Component.translatable("gui." + Torcherinoaemod.MOD_ID + ".ae_torcherino.range_z"),
                m -> m.zRange, menu::sendSetZRange, m -> m.maxXzRange, 0,
                String::valueOf, style);
        // Y 范围滑块：范围 0..menu.maxYRange（服务端配置 torcherino.maxYRange 同步值）。
        this.yRangeSlider = new SettingSliderWidget(
                menu,
                Component.translatable("gui." + Torcherinoaemod.MOD_ID + ".ae_torcherino.range_y"),
                m -> m.yRange, menu::sendSetYRange, m -> m.maxYRange, 0,
                String::valueOf, style);

        // 开关与四个滑块统一注册进 widgets 样式系统，由样式 JSON 按 left/top 定位。
        widgets.add("enabled", enableSwitch);
        widgets.add("speed", speedSlider);
        widgets.add("xRange", xRangeSlider);
        widgets.add("zRange", zRangeSlider);
        widgets.add("yRange", yRangeSlider);

        // 标题默认由 AEBaseScreen 的样式文本渲染管线绘制，其颜色无法运行时感知背景明暗；
        // 因此隐藏该默认文本，改由 drawFG 自绘（见 drawStyledTitle），以支持暗色 UI 材质包。
        // 样式 JSON 的 text 条目仍提供默认翻译文案与坐标布局。
        setTextHidden(TEXT_ID_DIALOG_TITLE, true);

        // 标题落在主背景上：该背景由 AE2 BackgroundGenerator 平铺 guis/background.png 生成，
        // 暗色材质包可能整体替换该贴图，故以它实际生效文件的明暗作为文字自适应依据。
        boolean mainBgDark = GuiTheme.isDark(AEGuiMetrics.AE2_GUI_BACKGROUND);
        this.titleColor = GuiTheme.ensureContrast(
                style.getColor(PaletteColor.DEFAULT_TEXT_COLOR).toARGB(), mainBgDark);
    }

    /**
     * 本界面不使用 AE 垂直工具栏（样式 JSON 未定义 verticalToolbar）。
     */
    @Override
    protected boolean shouldAddToolbar() {
        return false;
    }

    /**
     * 自绘标题（原 dialog_title 已 setTextHidden）：按样式 JSON resolve 坐标，颜色使用
     * 经主背景明暗自适应后的 titleColor。菜单标题为空时回退到样式默认文案。
     */
    @Override
    public void drawFG(GuiGraphics guiGraphics, int offsetX, int offsetY, int mouseX, int mouseY) {
        Text dialogTitle = style.getText().get(TEXT_ID_DIALOG_TITLE);
        if (dialogTitle != null && dialogTitle.getPosition() != null) {
            Component heading = title.getString().isEmpty() ? dialogTitle.getText() : title;
            Point point = dialogTitle.getPosition().resolve(new Rect2i(0, 0, imageWidth, imageHeight));
            guiGraphics.drawString(font, heading, point.getX(), point.getY(), titleColor, false);
        }
    }
}
