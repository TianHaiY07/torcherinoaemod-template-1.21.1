package com.tianhai.torcherino_ae.client.render.pass;

import com.mojang.blaze3d.vertex.PoseStack;
import com.tianhai.torcherino_ae.client.render.RenderPass;
import com.tianhai.torcherino_ae.client.render.util.CornerBracketRenderer;
import com.tianhai.torcherino_ae.common.AE2GridSupport;
import com.tianhai.torcherino_ae.item.AcceleratorConfigCardItem;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * 配置卡手持高亮渲染 pass（移植自 RTSBuilding 的 EntitySelectHighlightPass）。
 * <p>
 * 玩家手持「加速器配置卡」时：
 * <ul>
 *   <li>卡绑定的加速器以<b>蓝色</b>角括号高亮；</li>
 *   <li>卡绑定的设备以<b>绿色</b>角括号高亮。</li>
 * </ul>
 * 采用深浅双通道画法（参考参考实现 EntitySelectHighlightPass）：同一 AABB 先画
 * {@code brackets}（LEQUAL 深度，正常遮挡、高透明度），再画 {@code noDepth}
 * （NO_DEPTH_TEST 穿透、低透明度），隔墙也隐约可见。
 */
public class ConfigCardHighlightPass implements RenderPass {

    // 加速器高亮颜色（蓝色）。
    private static final int COLOR_ACCELERATOR = 0xFF4D99FF;

    // 设备高亮颜色（绿色）。
    private static final int COLOR_DEVICE = 0xFF3ADB3A;

    // 高亮线框向外的膨胀量（格）。
    private static final double INFLATE = 0.03D;

    // 是否启用无深度穿透通道（false 时仅画正常遮挡通道）。
    public static boolean depthTestEnabled = true;

    @Override
    public boolean shouldRender(Minecraft mc) {
        if (mc.player == null) {
            return false;
        }
        // 打开任意界面时停止渲染世界内高亮（避免线框穿透到 GUI 后面产生视觉干扰）。
        return mc.screen == null;
    }

    @Override
    public void render(Minecraft mc, BufferAllocator alloc, PoseStack poseStack, float partialTick, int frameIndex) {
        if (mc.level == null || mc.player == null) {
            return;
        }
        ItemStack card = heldConfigCard(mc.player);
        if (card.isEmpty()) {
            return;
        }
        Vec3 eye = mc.player.getEyePosition(partialTick);

        // 绑定的加速器：蓝色角括号。
        BlockPos acceleratorPos = AcceleratorConfigCardItem.getBoundAccelerator(card);
        if (acceleratorPos != null && mc.level.hasChunkAt(acceleratorPos)) {
            drawBracket(alloc, poseStack, acceleratorPos, COLOR_ACCELERATOR, eye);
        }
        // 绑定的设备：逐个画绿色角括号（数量受卡片上限约束，开销可控）。
        for (String deviceId : AcceleratorConfigCardItem.getBoundDevices(card)) {
            BlockPos devicePos = AE2GridSupport.resolveDeviceIdPos(deviceId);
            if (devicePos != null && mc.level.hasChunkAt(devicePos)) {
                drawBracket(alloc, poseStack, devicePos, COLOR_DEVICE, eye);
            }
        }
    }

    /**
     * 返回玩家主手/副手中持有的配置卡（无则返回空栈）。
     */
    private static ItemStack heldConfigCard(Player player) {
        ItemStack mainHand = player.getMainHandItem();
        if (AcceleratorConfigCardItem.isConfigCard(mainHand)) {
            return mainHand;
        }
        ItemStack offHand = player.getOffhandItem();
        return AcceleratorConfigCardItem.isConfigCard(offHand) ? offHand : ItemStack.EMPTY;
    }

    /**
     * 对指定方块位置绘制深浅双通道角括号。
     */
    private static void drawBracket(BufferAllocator alloc, PoseStack poseStack, BlockPos pos, int argb, Vec3 eye) {
        double minX = pos.getX() - INFLATE;
        double minY = pos.getY() - INFLATE;
        double minZ = pos.getZ() - INFLATE;
        double maxX = pos.getX() + 1 + INFLATE;
        double maxY = pos.getY() + 1 + INFLATE;
        double maxZ = pos.getZ() + 1 + INFLATE;
        // 距离用于厚度自适应（16 格内基础厚度，超出则按距离线性加粗）。
        double distance = eye.distanceTo(new Vec3((minX + maxX) / 2, (minY + maxY) / 2, (minZ + maxZ) / 2));
        float r = ((argb >> 16) & 0xFF) / 255.0F;
        float g = ((argb >> 8) & 0xFF) / 255.0F;
        float b = (argb & 0xFF) / 255.0F;

        // 通道一：brackets（LEQUAL 深度测试，被世界方块正常遮挡）。
        CornerBracketRenderer.renderCornerBrackets(poseStack, alloc.brackets(),
                minX, minY, minZ, maxX, maxY, maxZ, r, g, b, 0.9F, distance);
        // 通道二：noDepth（NO_DEPTH_TEST 穿透，隔墙可见，低透明度）。
        if (depthTestEnabled) {
            CornerBracketRenderer.renderCornerBrackets(poseStack, alloc.noDepth(),
                    minX, minY, minZ, maxX, maxY, maxZ, r, g, b, CornerBracketRenderer.DEFAULT_NO_DEPTH_ALPHA, distance);
        }
    }

    @Override
    public int requiredBuffers() {
        // 4=brackets、8=noDepth，与参考实现 EntitySelectHighlightPass 一致。
        return 4 | 8;
    }
}
