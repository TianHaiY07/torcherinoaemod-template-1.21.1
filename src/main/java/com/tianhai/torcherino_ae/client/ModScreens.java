package com.tianhai.torcherino_ae.client;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import com.tianhai.torcherino_ae.Torcherinoaemod;
import appeng.client.gui.style.ScreenStyle;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

/**
 * 自定义界面样式加载器。
 * <p>
 * AE2 的 {@code StyleManager.loadStyleDoc} 固定从 {@code ae2} 命名空间读取样式 JSON，
 * 因此本模组无法把自定义样式文件放进自己的资源目录并通过它加载。这里仿照其内部实现，
 * 复用 AE2 公开的 {@link ScreenStyle#GSON}（含背景、widget、text 等全部反序列化器），
 * 改为从本模组的命名空间读取，从而可以在样式 JSON 中直接引用 AE2 的 UI 贴图
 * （如 {@code guis/background.png}，不带命名空间时自动补 {@code ae2:} 前缀）。
 */
public final class ModScreens {

    private ModScreens() {
    }

    /**
     * 从本模组命名空间加载样式 JSON。
     *
     * @param path 以 {@code /screens/} 开头的资源路径，例如 {@code "/screens/ae_accelerator.json"}
     * @return 解析完成的 {@link ScreenStyle}
     */
    public static ScreenStyle loadStyleDoc(String path) {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(Torcherinoaemod.MOD_ID, path.substring(1));
        try (var in = Minecraft.getInstance().getResourceManager().open(id)) {
            return ScreenStyle.GSON.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), ScreenStyle.class);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load screen style: " + path, e);
        }
    }
}
