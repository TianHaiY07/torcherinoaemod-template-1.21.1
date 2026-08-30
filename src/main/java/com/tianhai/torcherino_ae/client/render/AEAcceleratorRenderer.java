package com.tianhai.torcherino_ae.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import com.tianhai.torcherino_ae.Torcherinoaemod;
import com.tianhai.torcherino_ae.blockentity.AEAcceleratorBlockEntity;

import appeng.client.render.effects.ParticleTypes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.model.data.ModelData;

/**
 * AE 加速器的自定义方块实体渲染器。
 * <p>
 * 参考 AE2 分子装配机（{@code MolecularAssemblerRenderer}）：当方块「接电工作」时，
 * 在其上层叠一个全亮度、半透明合成的「发光带」模型，营造接电发光效果；
 * 同时在方块中心持续生成向内收敛的「炫彩流光」粒子（复用 AE2 的 {@link ParticleTypes#CRAFTING}）。
 */
@OnlyIn(Dist.CLIENT)
public class AEAcceleratorRenderer implements BlockEntityRenderer<AEAcceleratorBlockEntity> {

    // 叠加上层的发光带模型（独立模型，不随方块状态切换）。
    public static final ModelResourceLocation LIGHTS_MODEL = ModelResourceLocation.standalone(
            ResourceLocation.fromNamespaceAndPath(Torcherinoaemod.MOD_ID, "block/ae_accelerator_lights"));

    // 粒子生成间隔（游戏 tick 数），约 1 秒生成一次。
    private static final float PARTICLE_INTERVAL = 4;

    // 粒子的随机数源。
    private final RandomSource particleRandom = RandomSource.create();

    // 「炫彩流光」粒子的生成节奏计数（仅客户端渲染线程使用）。
    // 放在渲染器内独立维护，不污染方块实体（渲染状态本就不该塞进方块实体）。
    private float particleCountdown;

    public AEAcceleratorRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(AEAcceleratorBlockEntity blockEntity, float partialTicks, PoseStack poseStack,
            MultiBufferSource buffer, int combinedLight, int combinedOverlay) {
        Minecraft mc = Minecraft.getInstance();

        boolean working = blockEntity.isWorking();

        // 炫彩流光：方块处于工作状态时，按节奏在中心生成向内收敛粒子。
        if (working && !mc.isPaused()) {
            spawnFlowingParticles(mc, blockEntity, partialTicks);
        }

        // 发光：接电工作时叠加全发光 + 半透明的发光带模型。
        if (working) {
            renderPowerLight(mc, poseStack, buffer, combinedLight, combinedOverlay);
        }
    }

    /**
     * 以一定节奏向方块中心生成「炫彩流光」粒子。
     */
    private void spawnFlowingParticles(Minecraft mc, AEAcceleratorBlockEntity blockEntity, float partialTicks) {
        float countdown = this.particleCountdown - partialTicks;
        if (countdown <= 0) {
            countdown = PARTICLE_INTERVAL;
            spawnParticleBurst(mc, blockEntity.getBlockPos());
        }
        this.particleCountdown = countdown;
    }

    /**
     * 在当前方块中心生成一小撮向内收敛的炫彩粒子。
     */
    private void spawnParticleBurst(Minecraft mc, BlockPos pos) {
        double centerX = pos.getX() + 0.5;
        double centerY = pos.getY() + 0.5;
        double centerZ = pos.getZ() + 0.5;
        // 每次生成 1-3 个粒子，营造持续涌动的流光感。
        int count = 1 + particleRandom.nextInt(3);
        for (int i = 0; i < count; i++) {
            mc.particleEngine.createParticle(ParticleTypes.CRAFTING, centerX, centerY, centerZ, 0, 0, 0);
        }
    }

    /**
     * 渲染叠加在方块上方的发光带模型。
     * <p>
     * 使用 {@link RenderType#tripwire()}：该渲染层具备 alpha 测试与半透明合成属性；
     * 配合模型里 {@code neoforge_data} 的全亮度（light 15）即可呈现「接电发光」的观感。
     */
    private void renderPowerLight(Minecraft mc, PoseStack poseStack, MultiBufferSource buffer,
            int combinedLight, int combinedOverlay) {
        BakedModel lightsModel = mc.getModelManager().getModel(LIGHTS_MODEL);
        VertexConsumer vertexConsumer = buffer.getBuffer(RenderType.tripwire());
        mc.getBlockRenderer().getModelRenderer().renderModel(poseStack.last(), vertexConsumer, null,
                lightsModel, 1, 1, 1, combinedLight, combinedOverlay, ModelData.EMPTY, null);
    }
}
