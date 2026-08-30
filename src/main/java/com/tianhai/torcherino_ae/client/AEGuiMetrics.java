package com.tianhai.torcherino_ae.client;

import com.tianhai.torcherino_ae.Torcherinoaemod;
import net.minecraft.resources.ResourceLocation;

/**
 * AE 加速器客户端界面布局度量常量。
 * <p>
 * 集中管理设备列表、倍数配置弹窗等控件的尺寸、贴图素材区域与像素偏移，
 * 这些值与 GUI 贴图强耦合，统一放在此处便于随贴图调整而不散落在各控件内。
 */
public final class AEGuiMetrics {

    // ===== 贴图素材 =====

    /** 加速器主界面 GUI 贴图（含设备列表背景、行尾状态图标等）。 */
    public static final ResourceLocation ACCELERATOR_GUI = ResourceLocation.fromNamespaceAndPath(
            Torcherinoaemod.MOD_ID, "textures/gui/ae_accelerator_gui.png");

    /** 倍数配置弹窗 GUI 贴图（横向面板条 + 内嵌滑块轨道 + 手柄）。 */
    public static final ResourceLocation DEVICE_ENTRY_GUI = ResourceLocation.fromNamespaceAndPath(
            Torcherinoaemod.MOD_ID, "textures/gui/device_entry_gui.png");

    // ===== 设备列表控件 =====

    /** 列表单行背景条高度（与贴图底部设备条目背景条一致）。 */
    public static final int ROW_HEIGHT = 22;
    /** 相邻背景条之间的垂直间隙。 */
    public static final int ROW_SPACING = 1;
    /** 列表整体相对列表区域上移的偏移。 */
    public static final int LIST_OFFSET_Y = -2;
    /** 设备图标左内边距。 */
    public static final int PAD_X = 9;
    /** 设备图标尺寸。 */
    public static final int ICON_SIZE = 16;
    /** 图标与文字的水平微调。 */
    public static final int CONTENT_SHIFT_X = -4;
    /** 图标与文字之间的间距。 */
    public static final int ICON_TEXT_GAP = 2;
    /** 文字区域右侧预留宽度。 */
    public static final int TEXT_TRAIL_PAD = 3;

    /** 行尾加速状态图标素材源矩形（相对主 GUI 贴图）。 */
    public static final int MARK_TEX_X = 0;
    /** 未加速状态图标源矩形 Y。 */
    public static final int MARK_TEX_Y_IDLE = 230;
    /** 加速中状态图标源矩形 Y。 */
    public static final int MARK_TEX_Y_ACCEL = 242;
    /** 状态图标宽度。 */
    public static final int MARK_TEX_WIDTH = 12;
    /** 未加速状态图标高度。 */
    public static final int MARK_TEX_HEIGHT_IDLE = 12;
    /** 加速中状态图标高度。 */
    public static final int MARK_TEX_HEIGHT_ACCEL = 11;
    /** 状态图标与文字的间距。 */
    public static final int MARK_GAP = 3;
    /** 状态图标距行右边缘的偏移。 */
    public static final int MARK_RIGHT_PAD = 2;
    /** 加速中图标相对未加速图标的垂直下移（用于对齐视觉）。 */
    public static final int MARK_Y_ACCEL_SHIFT = 1;

    // ===== 倍数配置弹窗 =====

    /** 弹窗默认位置与尺寸（相对界面原点），实际以样式 JSON 为准。 */
    public static final int POPUP_X = -13;
    public static final int POPUP_Y = 188;
    public static final int POPUP_WIDTH = 203;
    public static final int POPUP_HEIGHT = 32;
    /** 弹窗面板素材源矩形起点（贴图为整块 203x32 面板条）。 */
    public static final int POPUP_SRC_X = 0;
    public static final int POPUP_SRC_Y = 0;

    /** 素材内嵌滑块轨道槽（相对素材左上角）。 */
    public static final int TRACK_X = 70;
    public static final int TRACK_Y = 12;
    public static final int TRACK_WIDTH = 98;
    public static final int TRACK_HEIGHT = 6;
    /** 滑块手柄宽度（即贴图手柄纹理宽度）。 */
    public static final int HANDLE_WIDTH = 15;
    /** 滑块手柄纹理源矩形（相对素材左上角）。 */
    public static final int HANDLE_TEX_X = 0;
    public static final int HANDLE_TEX_Y = 32;
    public static final int HANDLE_TEX_WIDTH = 15;
    public static final int HANDLE_TEX_HEIGHT = 12;
    /** 手柄垂直居中于轨道时的相对轨道上沿偏移：6/2 - 12/2 = -3。 */
    public static final int HANDLE_VERTICAL_OFFSET = (TRACK_HEIGHT - HANDLE_TEX_HEIGHT) / 2;

    /** 标题相对弹窗左/上边缘的内边距。 */
    public static final int TITLE_PAD_X = 6;
    public static final int TITLE_PAD_Y = 11;
    /** 标题右侧与轨道之间额外预留宽度（用于截断省略号）。 */
    public static final int TITLE_RIGHT_PAD = 6;
    /** 倍数文字与轨道右端/上沿的间隙与偏移。 */
    public static final int MULT_LABEL_GAP = 3;
    public static final int MULT_LABEL_OFFSET_Y = -2;
    /** 轨道点击命中区域在垂直方向的放宽量。 */
    public static final int TRACK_HIT_PAD_Y = 2;

    private AEGuiMetrics() {
    }
}
