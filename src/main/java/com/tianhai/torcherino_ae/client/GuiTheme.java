package com.tianhai.torcherino_ae.client;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import com.mojang.blaze3d.platform.NativeImage;
import com.tianhai.torcherino_ae.Torcherinoaemod;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

/**
 * GUI 文字自动对比度工具。
 * <p>
 * 背景：部分玩家使用暗色 UI 材质包。这类包会整体替换 AE2 的界面贴图（例如
 * {@code ae2:textures/guis/background.png}）甚至其调色板 JSON，本模组界面若只依赖
 * 调色板联动（见 {@link ModScreens} 的 includes 合并），遇到「只换贴图、不改调色板」
 * 的暗色包仍可能出现深色文字压在暗色背景上而不可见。本类提供两道兜底：
 * <ul>
 *   <li>{@link #isDarkRegion}/{@link #isDark}：运行时直接读取<b>实际生效</b>的背景贴图
 *       （经资源包合并后的最终文件），按像素平均亮度判断该区域是暗还是亮，不依赖
 *       任何主题包声明；</li>
 *   <li>{@link #ensureContrast}：给定调色板基准文字色与区域明暗，返回一张保证与背景
 *       亮度方向相反的对比文字色（纯计算提亮/压暗，不引入硬编码色值）。</li>
 * </ul>
 * 因此无论资源包把背景与调色板组合成亮/暗的哪种搭配，本模组自绘文字都能保持可读；
 * 在默认（亮背景 + 深色默认调色板）环境下函数保持原色，界面观感零变化。
 * <p>
 * 采样结果按「纹理 + 区域」做进程级缓存；采样失败一律按亮背景处理（安全降级，
 * 保持原色）。客户端资源热重载后调用 {@link #invalidateCache()} 即可清除。
 */
public final class GuiTheme {

    /** 判定为暗背景的平均亮度阈值（0~255 的相对亮度）。 */
    private static final double DARK_BG_THRESHOLD = 127.5;
    /** 暗背景上保留原色的最低文字亮度。 */
    private static final double DARK_BG_TEXT_MIN_LUMA = 0.5;
    /** 亮背景上保留原色的最高文字亮度。 */
    private static final double LIGHT_BG_TEXT_MAX_LUMA = 0.6;
    /** 提亮/压暗的混合强度。 */
    private static final double ADJUST_STRENGTH = 0.85;

    private static final Map<String, Boolean> DARK_CACHE = new HashMap<>();

    private GuiTheme() {
    }

    /**
     * 判断整张贴图平均是否为暗背景（等价于以整图为区域的 {@link #isDarkRegion}）。
     */
    public static boolean isDark(ResourceLocation texture) {
        return sample(texture, 0, 0, -1, -1);
    }

    /**
     * 判断贴图给定源区域平均是否为暗背景。
     *
     * @param texture 经资源包合并后的最终贴图资源（需可被客户端 ResourceManager 打开）
     * @param srcX    区域内左上角 x（像素坐标）
     * @param srcY    区域内左上角 y
     * @param srcW    区域宽；传 -1 表示取到贴图右边缘
     * @param srcH    区域高；传 -1 表示取到贴图下边缘
     * @return 区域平均亮度低于阈值返回 {@code true}（暗背景）；读取失败返回 {@code false}
     */
    public static boolean isDarkRegion(ResourceLocation texture, int srcX, int srcY, int srcW, int srcH) {
        return sample(texture, srcX, srcY, srcW, srcH);
    }

    /**
     * 清理采样缓存（资源包热重载后应调用）。
     */
    public static void invalidateCache() {
        DARK_CACHE.clear();
    }

    private static boolean sample(ResourceLocation texture, int srcX, int srcY, int srcW, int srcH) {
        String key = texture + "|" + srcX + "|" + srcY + "|" + srcW + "|" + srcH;
        Boolean cached = DARK_CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        boolean dark = computeDark(texture, srcX, srcY, srcW, srcH);
        DARK_CACHE.put(key, dark);
        return dark;
    }

    private static boolean computeDark(ResourceLocation texture, int srcX, int srcY, int srcW, int srcH) {
        var resourceManager = Minecraft.getInstance().getResourceManager();
        try (InputStream in = resourceManager.open(texture);
             NativeImage image = NativeImage.read(in)) {
            int imgW = image.getWidth();
            int imgH = image.getHeight();
            int x0 = Math.max(0, srcX);
            int y0 = Math.max(0, srcY);
            int x1 = srcW < 0 ? imgW : Math.min(imgW, srcX + srcW);
            int y1 = srcH < 0 ? imgH : Math.min(imgH, srcY + srcH);
            if (x1 <= x0 || y1 <= y0) {
                return false;
            }
            double sum = 0.0;
            int count = 0;
            for (int y = y0; y < y1; y++) {
                for (int x = x0; x < x1; x++) {
                    int p = image.getPixelRGBA(x, y);
                    // alpha 全透明的像素不参与亮度统计（多页帧图的空白页等）。
                    if ((p >>> 24 & 0xFF) == 0) {
                        continue;
                    }
                    // NativeImage 的 RGBA 字节按小端序存于 int：低位为红、高位为 alpha。
                    int r = p & 0xFF;
                    int g = p >>> 8 & 0xFF;
                    int b = p >>> 16 & 0xFF;
                    sum += 0.2126 * r + 0.7152 * g + 0.0722 * b;
                    count++;
                }
            }
            if (count == 0) {
                return false;
            }
            return sum / count < DARK_BG_THRESHOLD;
        } catch (IOException e) {
            Torcherinoaemod.LOGGER.debug("无法读取背景贴图 {} 进行明暗判断，按亮背景处理：{}", texture, e.toString());
            return false;
        }
    }

    /**
     * 依据给定背景明暗对基准文字色做自动对比调整，返回实际绘制用色。
     * <p>
     * 规则（纯亮度计算，保持原色相，不引入硬编码色值）：
     * <ul>
     *   <li>暗背景：基准色本身够亮则直接使用；否则向白色方向提亮，保证文字与暗底拉开差距；</li>
     *   <li>亮背景：基准色本身够暗则直接使用；否则向黑色方向压暗。</li>
     * </ul>
     * 默认环境下（亮背景 + AE2 深色默认调色板）所有颜色均落在「直接使用」区间，返回原色。
     */
    public static int ensureContrast(int baseArgb, boolean darkBackground) {
        double luma = relativeLuma(baseArgb);
        if (darkBackground) {
            if (luma >= DARK_BG_TEXT_MIN_LUMA) {
                return baseArgb;
            }
            return mixToward(baseArgb, 0xFFFFFF, ADJUST_STRENGTH);
        }
        if (luma <= LIGHT_BG_TEXT_MAX_LUMA) {
            return baseArgb;
        }
        return mixToward(baseArgb, 0x000000, ADJUST_STRENGTH);
    }

    /**
     * 计算 ARGB 颜色的感知相对亮度（Rec.709 系数，0~1；亮度越高视觉越亮）。
     */
    public static double relativeLuma(int argb) {
        int r = argb >>> 16 & 0xFF;
        int g = argb >>> 8 & 0xFF;
        int b = argb & 0xFF;
        return (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255.0;
    }

    /**
     * 将颜色按比例朝目标色（白/黑）混合，保留原 alpha。
     */
    private static int mixToward(int argb, int target, double amount) {
        int a = argb >>> 24 & 0xFF;
        int r = argb >>> 16 & 0xFF;
        int g = argb >>> 8 & 0xFF;
        int b = argb & 0xFF;
        int tr = target >>> 16 & 0xFF;
        int tg = target >>> 8 & 0xFF;
        int tb = target & 0xFF;
        int nr = (int) Math.round(r + (tr - r) * amount);
        int ng = (int) Math.round(g + (tg - g) * amount);
        int nb = (int) Math.round(b + (tb - b) * amount);
        return (a & 0xFF) << 24 | (nr & 0xFF) << 16 | (ng & 0xFF) << 8 | (nb & 0xFF);
    }
}