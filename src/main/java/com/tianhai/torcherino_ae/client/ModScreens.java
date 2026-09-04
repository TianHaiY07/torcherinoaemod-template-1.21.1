package com.tianhai.torcherino_ae.client;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import com.tianhai.torcherino_ae.Torcherinoaemod;

import appeng.client.gui.style.ScreenStyle;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

/**
 * 自定义界面样式加载器。
 * <p>
 * 实现与 AE2 {@code StyleManager} 等价的「includes 递归合并」机制，让我们这两个
 * 自定义界面（{@code /screens/ae_accelerator.json} 与 {@code /screens/ae_torcherino.json}）
 * 能够直接 include 其它命名空间的样式片段（例如
 * {@code ae2:screens/common/palette.json}），从而在调色板等方面与 AE2 原版界面
 * 保持一致的数据驱动联带——任何面向 AE2 的暗色 / 浅色主题包都能自动联动我们的
 * 界面文字色。
 * <p>
 * include 项语法：
 * <ul>
 *   <li>绝对命名空间路径 {@code "<namespace>:<path>"}（如
 *       {@code "ae2:screens/common/palette.json"}），用于跨命名空间引用；</li>
 *   <li>相对路径（如 {@code "common/palette.json"}），相对当前样式文件所在目录，
 *       命名空间继承当前文件；支持 {@code ..} 上溯，归一化后不得越出命名空间根。</li>
 * </ul>
 * 合并策略完全复刻 AE2：先按列表顺序递归 include，再把自身置于最末层；合并时
 * 顶层键整体覆盖（后层覆盖前层），但对 {@code slots / text / palette / images /
 * terminalStyle / widgets} 六段执行按键合并（后层覆盖前层同名键）。最终生成一棵
 * 可直接喂给 {@link ScreenStyle#GSON} 反序列化的 JSON 树，并经
 * {@link ScreenStyle#validate()} 校验调色板完整性等约束。
 * <p>
 * 本类不属于配置横切或核心逻辑层，仅承担客户端 GUI 样式装配，单独位于
 * {@code client} 包内。AE2 的 {@code StyleManager} 自身基于命名空间 {@code ae2}
 * 写死路径，无法直接被本模组复用。
 */
public final class ModScreens {

    private ModScreens() {
    }

    /**
     * 从本模组命名空间加载样式 JSON：经 includes 合并后由 AE2 的 {@link ScreenStyle#GSON}
     * 反序列化为样式对象，并执行 {@link ScreenStyle#validate()} 校验。
     *
     * @param path 以 {@code /screens/} 开头的资源路径，例如 {@code "/screens/ae_accelerator.json"}
     * @return 解析完成的 {@link ScreenStyle}
     */
    public static ScreenStyle loadStyleDoc(String path) {
        ResourceLocation rootId = ResourceLocation.fromNamespaceAndPath(
                Torcherinoaemod.MOD_ID,
                path.startsWith("/") ? path.substring(1) : path);
        try {
            JsonObject tree = loadMerged(rootId, new HashSet<>());
            ScreenStyle style = ScreenStyle.GSON.fromJson(tree, ScreenStyle.class);
            style.validate();
            return style;
        } catch (FileNotFoundException e) {
            throw new RuntimeException("Style not found: " + path + " (" + e.getMessage() + ")", e);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load screen style: " + path, e);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse screen style: " + path, e);
        }
    }

    /**
     * 递归加载并合并 JSON 树：解析当前文件的 {@code includes} 数组，对每个 include
     * 先递归合并再把自身置于最末层参与合并；最终调用
     * {@link #combineLayers(List)} 按 AE2 规则合并所有层。
     */
    private static JsonObject loadMerged(ResourceLocation id, Set<String> seen) throws IOException {
        if (!seen.add(id.toString())) {
            throw new IllegalStateException("Cycle detected in screen style includes: " + id);
        }
        var resourceManager = Minecraft.getInstance().getResourceManager();
        var resource = resourceManager.getResource(id)
                .orElseThrow(() -> new FileNotFoundException(id.toString()));
        JsonObject self;
        try (InputStream in = resource.open();
             Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            self = ScreenStyle.GSON.fromJson(reader, JsonObject.class);
        }
        if (self == null) {
            self = new JsonObject();
        }
        if (self.has("includes")) {
            String[] incs = ScreenStyle.GSON.fromJson(self.get("includes"), String[].class);
            if (incs != null && incs.length > 0) {
                int slash = id.getPath().lastIndexOf('/');
                String baseDir = slash >= 0 ? id.getPath().substring(0, slash + 1) : "";
                List<JsonObject> layers = new ArrayList<>();
                for (String inc : incs) {
                    if (inc == null || inc.isEmpty()) {
                        continue;
                    }
                    ResourceLocation incId = resolveInclude(inc, id.getNamespace(), baseDir);
                    layers.add(loadMerged(incId, seen));
                }
                layers.add(self);
                return combineLayers(layers);
            }
        }
        return self;
    }

    /**
     * 解析 include 项为完整的 {@link ResourceLocation}。
     * <p>
     * 若 include 项形如 {@code "<namespace>:<path>"}，从对应命名空间读取；
     * 否则视为相对路径（基于 {@code baseDir} 与当前文件的命名空间）。相对路径若包含
     * {@code ..}，使用 {@link URI#normalize()} 归一化，归一化结果越界（即仍含
     * {@code ..}）则报错。
     */
    private static ResourceLocation resolveInclude(String inc, String namespace, String baseDir) throws IOException {
        int colon = inc.indexOf(':');
        if (colon > 0 && colon == inc.lastIndexOf(':')) {
            String ns = inc.substring(0, colon);
            String path = inc.substring(colon + 1);
            if (ResourceLocation.isValidNamespace(ns) && ResourceLocation.isValidPath(path)) {
                return ResourceLocation.fromNamespaceAndPath(ns, path);
            }
        }
        String raw = baseDir + inc;
        if (raw.contains("..")) {
            String normalized = URI.create(raw).normalize().toString();
            if (normalized.contains("..") || normalized.isEmpty()) {
                throw new IOException("Include path escapes namespace root: " + inc);
            }
            raw = normalized;
        }
        if (!ResourceLocation.isValidPath(raw)) {
            throw new IOException("Invalid include path: " + inc);
        }
        return ResourceLocation.fromNamespaceAndPath(namespace, raw);
    }

    /**
     * 按 AE2 {@code combineLayers} 语义合并多层 JSON：先按层序对非段键进行整体覆盖
     * （后层覆盖前层），再对 {@code slots / text / palette / images / terminalStyle /
     * widgets} 六段执行按键合并。
     */
    private static JsonObject combineLayers(List<JsonObject> layers) {
        JsonObject root = new JsonObject();
        for (JsonObject layer : layers) {
            for (Map.Entry<String, JsonElement> e : layer.entrySet()) {
                String key = e.getKey();
                if (!isSectionKey(key)) {
                    root.add(key, e.getValue());
                }
            }
        }
        for (String section : new String[]{
                "slots", "text", "palette", "images", "terminalStyle", "widgets"}) {
            mergeSection(section, layers, root);
        }
        return root;
    }

    private static boolean isSectionKey(String key) {
        return key.equals("slots") || key.equals("text") || key.equals("palette")
                || key.equals("images") || key.equals("terminalStyle") || key.equals("widgets");
    }

    private static void mergeSection(String key, List<JsonObject> layers, JsonObject root) {
        JsonObject merged = null;
        for (JsonObject layer : layers) {
            JsonElement el = layer.get(key);
            if (el != null) {
                if (!el.isJsonObject()) {
                    throw new JsonParseException("Expected object for '" + key + "', got " + el);
                }
                if (merged == null) {
                    merged = new JsonObject();
                }
                for (Map.Entry<String, JsonElement> e : el.getAsJsonObject().entrySet()) {
                    merged.add(e.getKey(), e.getValue());
                }
            }
        }
        if (merged != null) {
            root.add(key, merged);
        }
    }
}