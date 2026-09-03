package com.tianhai.torcherino_ae.client.screen;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

import appeng.client.gui.AEBaseScreen;
import appeng.client.gui.style.ScreenStyle;

import com.tianhai.torcherino_ae.Torcherinoaemod;
import com.tianhai.torcherino_ae.client.widget.SettingSliderWidget;
import com.tianhai.torcherino_ae.menu.AETorcherinoMenu;

/**
 * AE 加速火把的客户端界面。
 * <p>
 * 火把为独立范围扫描方块，界面仅显示四个设置滑块（加速倍数、X 范围、Z 范围、Y 范围）；
 * 无升级卡插槽、无设备列表。背景由样式 JSON 的 {@code generatedBackground} 交给 AE2 的
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

    // 四个设置滑块控件。
    private final SettingSliderWidget speedSlider;
    private final SettingSliderWidget xRangeSlider;
    private final SettingSliderWidget zRangeSlider;
    private final SettingSliderWidget yRangeSlider;

    public AETorcherinoScreen(AETorcherinoMenu menu, Inventory playerInventory, Component title, ScreenStyle style) {
        super(menu, playerInventory, title, style);

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

        // 四个滑块统一注册进 widgets 样式系统，由样式 JSON 按 left/top 定位。
        widgets.add("speed", speedSlider);
        widgets.add("xRange", xRangeSlider);
        widgets.add("zRange", zRangeSlider);
        widgets.add("yRange", yRangeSlider);

        // 标题优先使用菜单标题（方块名）；菜单标题为空时保留样式 JSON 默认文本。
        if (!title.getString().isEmpty()) {
            setTextContent("dialog_title", title);
        }
    }

    /**
     * 本界面不使用 AE 垂直工具栏（样式 JSON 未定义 verticalToolbar）。
     */
    @Override
    protected boolean shouldAddToolbar() {
        return false;
    }
}
