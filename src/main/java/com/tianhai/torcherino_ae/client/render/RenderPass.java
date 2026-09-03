package com.tianhai.torcherino_ae.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;

/**
 * 客户端世界渲染 pass 接口（保留最小必要的缓冲通道）。
 * <p>
 * 用于在渲染关卡时把自定义线框叠加到场景中：每个 pass 声明自己需要哪些顶点缓冲通道，
 * 由 {@link ConfigCardRenderPipeline} 在 {@code RenderLevelStageEvent} 中构造
 * {@link BufferAllocator} 并逐帧驱动。所有 pass 都直接使用世界坐标提交顶点
 * （pipeline 已把 PoseStack 原点平移到相机处）。
 */
public interface RenderPass {

    /**
     * 该 pass 当前帧是否需要渲染（默认总是渲染）。
     */
    default boolean shouldRender(Minecraft mc) {
        return true;
    }

    /**
     * 渲染入口：把世界坐标顶点提交到 allocator 的各通道，管线会在帧末统一 flush。
     *
     * @param mc          客户端实例
     * @param alloc       各渲染通道的顶点消费者
     * @param poseStack   已平移到相机处的 PoseStack
     * @param partialTick 渲染帧局部 tick
     * @param frameIndex  帧索引（可用于动画进度）
     */
    void render(Minecraft mc, BufferAllocator alloc, PoseStack poseStack, float partialTick, int frameIndex);

    /**
     * 声明该 pass 所需的缓冲位（位标志：4=角括号 brackets、8=无深度 noDepth）。
     * 当前仅作声明性信息，便于后续优化/诊断。
     */
    default int requiredBuffers() {
        return 0;
    }

    /**
     * 所有渲染通道的顶点消费者集合（每个通道对应一个独立 RenderType 的 BufferBuilder）：
     * <ul>
     *   <li>{@code brackets}：LEQUAL 深度测试的粗线段角括号（会被世界方块正常遮挡）；</li>
     *   <li>{@code noDepth}：NO_DEPTH_TEST 的穿透角括号（隔墙可见、低透明度）。</li>
     * </ul>
     */
    record BufferAllocator(VertexConsumer brackets, VertexConsumer noDepth) {
    }
}
