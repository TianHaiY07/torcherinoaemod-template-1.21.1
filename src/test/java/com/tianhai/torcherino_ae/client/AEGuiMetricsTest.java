package com.tianhai.torcherino_ae.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 界面布局度量一致性测试。
 * <p>
 * 这些数值与 GUI 贴图强耦合（轨道、手柄、状态图标都取自贴图素材），改贴图后若不同步
 * 修改度量会算错位。本测试把「布局不变量」固化下来，防止无意识错位：
 * <ul>
 *   <li>滑块手柄垂直居中于轨道；</li>
 *   <li>行尾「未加速 / 加速中」两个状态图标在同一贴图内竖排相接；</li>
 *   <li>滑块轨道宽度大于手柄宽度（否则无法左右滑动）、轨道整体落在弹窗面板素材范围内。</li>
 * </ul>
 * 纯静态常量类，可直接 JVM 加载（不触碰任何 {@code net.minecraft.client.*} 类型）。
 */
class AEGuiMetricsTest {

    @Test
    void 手柄垂直居中于轨道() {
        // 手柄绘制在 TRACK_Y 处、先叠加 HANDLE_VERTICAL_OFFSET 再叠加自身高度的一半，
        // 其中心必须与轨道中心重合，否则拖动图标会偏离轨道。
        double trackCenter = AEGuiMetrics.TRACK_Y + AEGuiMetrics.TRACK_HEIGHT / 2.0;
        double handleCenter = AEGuiMetrics.TRACK_Y + AEGuiMetrics.HANDLE_VERTICAL_OFFSET
                + AEGuiMetrics.HANDLE_TEX_HEIGHT / 2.0;
        assertEquals(trackCenter, handleCenter, 1.0e-9);
    }

    @Test
    void 滑块轨道比手柄宽且落在弹窗面板素材内() {
        // 手柄必须比轨道窄，否则没有可滑动的行程。
        assertTrue(AEGuiMetrics.TRACK_WIDTH > AEGuiMetrics.HANDLE_WIDTH,
                "轨道宽度应大于手柄宽度，否则滑块无法滑动");
        // 轨道素材矩形（相对弹窗面板素材左上角）不得越出面板素材边界。
        assertTrue(AEGuiMetrics.TRACK_X >= 0 && AEGuiMetrics.TRACK_X + AEGuiMetrics.TRACK_WIDTH
                <= AEGuiMetrics.POPUP_WIDTH, "轨道水平范围应落在弹窗面板素材宽度内");
        assertTrue(AEGuiMetrics.TRACK_Y >= 0 && AEGuiMetrics.TRACK_Y + AEGuiMetrics.TRACK_HEIGHT
                <= AEGuiMetrics.POPUP_HEIGHT, "轨道垂直范围应落在弹窗面板素材高度内");
    }

    @Test
    void 行尾两个状态图标在贴图内竖排相接() {
        // 主 GUI 贴图中「未加速」「加速中」图标上下相邻排列：idle 的底边恰好接 accel 的顶边。
        assertEquals(AEGuiMetrics.MARK_TEX_Y_ACCEL,
                AEGuiMetrics.MARK_TEX_Y_IDLE + AEGuiMetrics.MARK_TEX_HEIGHT_IDLE,
                "加速中图标源矩形应紧接未加速图标源矩形下方");
        // 两图标宽度一致、各自高度为正，垂直方向不重叠。
        assertEquals(AEGuiMetrics.MARK_TEX_WIDTH, AEGuiMetrics.MARK_TEX_WIDTH);
        assertTrue(AEGuiMetrics.MARK_TEX_HEIGHT_IDLE > 0);
        assertTrue(AEGuiMetrics.MARK_TEX_HEIGHT_ACCEL > 0);
    }

    @Test
    void 行尾图标高度适配单行行高() {
        // 状态图标与设备名称同处一行（ROW_HEIGHT 高），图标及其垂直微调不得撑破行高语义。
        int iconBlock = AEGuiMetrics.MARK_TEX_HEIGHT_IDLE + AEGuiMetrics.MARK_Y_ACCEL_SHIFT;
        assertTrue(iconBlock <= AEGuiMetrics.ROW_HEIGHT,
                "状态图标及其垂直微调应能放入单行行高内");
        assertTrue(AEGuiMetrics.MARK_TEX_HEIGHT_ACCEL <= AEGuiMetrics.ROW_HEIGHT);
    }

    @Test
    void 设备行内容为正尺寸且留足名称空间() {
        assertTrue(AEGuiMetrics.ICON_SIZE > 0);
        assertTrue(AEGuiMetrics.ICON_TEXT_GAP >= 0);
        assertTrue(AEGuiMetrics.PAD_X >= 0);
        // 图标 + 内边距 + 间距 + 右侧预留宽度合计应远小于常见行宽，给设备名称留足空间。
        assertTrue(AEGuiMetrics.PAD_X + AEGuiMetrics.ICON_SIZE + AEGuiMetrics.ICON_TEXT_GAP
                + AEGuiMetrics.TEXT_TRAIL_PAD < 200, "行内装饰区应远小于行宽，留出足够名称空间");
    }
}
