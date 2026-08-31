package com.tianhai.torcherino_ae.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.tianhai.torcherino_ae.Torcherinoaemod;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * 配置卡高亮渲染管线的客户端驱动。
 * <p>
 * 在 {@code RenderLevelStageEvent} 的 {@code AFTER_TRANSLUCENT_BLOCKS} 阶段
 * （世界不透明/透明几何渲染完成、深度缓冲含世界内容之后）把相机位置平移到
 * PoseStack 原点，然后驱动 {@link ConfigCardRenderPipeline} 渲染各 pass。
 * 与参考实现（RTSBuilding ClientRenderHandler）的调用链保持一致。
 */
@EventBusSubscriber(modid = Torcherinoaemod.MOD_ID, value = Dist.CLIENT)
public final class ConfigCardRenderHandler {

    // 渲染管线单例（pass 无状态跨帧复用）。
    private static final ConfigCardRenderPipeline PIPELINE = new ConfigCardRenderPipeline();

    private ConfigCardRenderHandler() {
    }

    /**
     * 接收渲染关卡事件：仅处理 AFTER_TRANSLUCENT_BLOCKS 阶段。
     */
    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        PoseStack poseStack = event.getPoseStack();
        // 持牌相机：用 event.getCamera() 的位置把 PoseStack 原点「平移」到相机处，
        // 此后所有 pass 直接以世界坐标提交顶点即可（参考实现同款做法）。
        Vec3 cameraPos = event.getCamera().getPosition();
        poseStack.pushPose();
        poseStack.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);

        PIPELINE.onRenderFrame(mc, poseStack, event.getPartialTick().getGameTimeDeltaPartialTick(false));

        poseStack.popPose();
    }
}
