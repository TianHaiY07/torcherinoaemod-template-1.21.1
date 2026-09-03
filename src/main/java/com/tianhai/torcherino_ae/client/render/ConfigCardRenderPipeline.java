package com.tianhai.torcherino_ae.client.render;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.tianhai.torcherino_ae.client.render.RenderPass.BufferAllocator;
import com.tianhai.torcherino_ae.client.render.pass.ConfigCardHighlightPass;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;

/**
 * 配置卡高亮渲染管线。
 * <p>
 * 持有本模组所有世界内渲染 pass，并在 {@code RenderLevelStageEvent} 中被每帧驱动：
 * 先重置各通道缓冲，按注册顺序依次渲染各 pass，最后按通道顺序统一 flush
 * （{@code RenderType.draw(MeshData)}）。缓冲采用 {@code ByteBufferBuilder + BufferBuilder}
 * 组织，初始容量不足时自动扩容。
 */
public final class ConfigCardRenderPipeline {

    // 正常角括号渲染类型：LEQUAL 深度测试、半透明、不剔除背面。
    private static final RenderType BRACKET_QUADS = createBracketType();

    // 穿透角括号渲染类型：NO_DEPTH_TEST、半透明、不剔除背面（隔墙可见）。
    private static final RenderType NO_DEPTH_QUADS = createNoDepthType();

    /**
     * 单通道缓冲：ByteBufferBuilder 支撑 + BufferBuilder 顶点写入 + 对应渲染类型。
     * 初始分配 1024KB，超量时 ByteBufferBuilder 自动扩容。
     */
    private static final class Buf {
        final ByteBufferBuilder backing;
        final RenderType type;
        BufferBuilder builder;

        Buf(RenderType type) {
            this.backing = new ByteBufferBuilder(1024 * 1024);
            this.builder = new BufferBuilder(this.backing, type.mode(), type.format());
            this.type = type;
        }

        void reset() {
            this.backing.clear();
            this.builder = new BufferBuilder(this.backing, this.type.mode(), this.type.format());
        }

        void draw() {
            MeshData mesh = this.builder.build();
            if (mesh != null) {
                this.type.draw(mesh);
            }
        }
    }

    private final Buf brackets = new Buf(BRACKET_QUADS);
    private final Buf noDepth = new Buf(NO_DEPTH_QUADS);

    // 已注册的渲染 pass 列表。
    private final List<RenderPass> passes = new ArrayList<>();

    public ConfigCardRenderPipeline() {
        // 注册配置卡手持高亮 pass。
        registerPass(new ConfigCardHighlightPass());
    }

    /**
     * 注册渲染 pass（新增渲染内容时在此追加）。
     */
    public void registerPass(RenderPass pass) {
        this.passes.add(pass);
    }

    /**
     * 每帧执行（已由调用方把 PoseStack 平移到相机处）：
     * 重置缓冲 -> 逐个执行 pass -> 按通道顺序 flush。
     */
    public void onRenderFrame(Minecraft mc, PoseStack poseStack, float partialTick) {
        if (mc.level == null) {
            return;
        }
        reset();
        BufferAllocator alloc = new BufferAllocator(brackets.builder, noDepth.builder);
        for (RenderPass pass : passes) {
            if (pass.shouldRender(mc)) {
                pass.render(mc, alloc, poseStack, partialTick, 0);
            }
        }
        flush();
    }

    private void reset() {
        brackets.reset();
        noDepth.reset();
    }

    private void flush() {
        brackets.draw();
        noDepth.draw();
    }

    /**
     * 创建正常角括号渲染类型（可被世界方块遮挡）。
     */
    private static RenderType createBracketType() {
        return RenderType.create("torcherino_ae_bracket_quads", DefaultVertexFormat.POSITION_COLOR,
                VertexFormat.Mode.QUADS, 512, false, false,
                RenderType.CompositeState.builder()
                        .setShaderState(RenderStateShard.POSITION_COLOR_SHADER)
                        .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                        .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
                        .setOutputState(RenderStateShard.MAIN_TARGET)
                        .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                        .setCullState(RenderStateShard.NO_CULL)
                        .createCompositeState(false));
    }

    /**
     * 创建穿透角括号渲染类型（不写深度，隔着方块也能看到）。
     */
    private static RenderType createNoDepthType() {
        return RenderType.create("torcherino_ae_no_depth_quads", DefaultVertexFormat.POSITION_COLOR,
                VertexFormat.Mode.QUADS, 512, false, false,
                RenderType.CompositeState.builder()
                        .setShaderState(RenderStateShard.POSITION_COLOR_SHADER)
                        .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                        .setDepthTestState(RenderStateShard.NO_DEPTH_TEST)
                        .setOutputState(RenderStateShard.MAIN_TARGET)
                        .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                        .setCullState(RenderStateShard.NO_CULL)
                        .createCompositeState(false));
    }
}
