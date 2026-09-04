package com.tianhai.torcherino_ae.client.render.pass;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import appeng.api.parts.IPart;
import appeng.api.parts.IPartHost;
import appeng.parts.BusCollisionHelper;
import com.mojang.blaze3d.vertex.PoseStack;
import com.tianhai.torcherino_ae.api.DeviceId;
import com.tianhai.torcherino_ae.api.DeviceKind;
import com.tianhai.torcherino_ae.client.render.RenderPass;
import com.tianhai.torcherino_ae.client.render.util.CornerBracketRenderer;
import com.tianhai.torcherino_ae.config.RuntimeConfig;
import com.tianhai.torcherino_ae.item.ConfigCardData;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 配置卡手持高亮渲染 pass。
 * <p>
 * 玩家手持「加速器配置卡」时：
 * <ul>
 *   <li>卡绑定的加速器以<b>蓝色</b>角括号高亮；</li>
 *   <li>卡绑定的设备以<b>绿色</b>角括号高亮：方块实体按整格框；线缆部件（输入/输出总线、
 *       破坏面板等，一条线缆可同时挂多台）按各自<b>在世界中的实际碰撞箱</b>单独画框，
 *       与 AE2 的选择框对齐，避免同格多台设备框全部重叠、看不出指哪台；合成 CPU 是
 *       多块连成的组，卡上记录有整组的外包围盒几何，因此按整组外围画线框
 *       （组内任意成员块都只产生一个整组框）。</li>
 * </ul>
 * 采用深浅双通道画法：同一 AABB 先画 {@code brackets}（LEQUAL 深度，正常遮挡、
 * 高透明度），再画 {@code noDepth}（NO_DEPTH_TEST 穿透、低透明度），隔墙也隐约可见。
 */
public class ConfigCardHighlightPass implements RenderPass {

    // 加速器高亮颜色（蓝色）。
    private static final int COLOR_ACCELERATOR = 0xFF4D99FF;

    // 设备高亮颜色（绿色）。
    private static final int COLOR_DEVICE = 0xFF3ADB3A;

    // 高亮线框向外的膨胀量（格）。
    private static final double INFLATE = 0.03D;

    // 是否启用无深度穿透通道（false 时仅画正常遮挡通道）；可作渲染级调试开关。
    public static boolean depthTestEnabled = true;

    @Override
    public boolean shouldRender(Minecraft mc) {
        // 总开关（配置项 client.renderBracketHighlight，默认开）由 render 内复核，
        // 使关闭时连 render 阶段都完全跳过绘制调用。
        if (!RuntimeConfig.clientRenderBracketHighlight()) {
            return false;
        }
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

        // 只高亮「当前维度」的绑定目标：设备标识含维度，其他维度的目标不应在本世界绘制。
        ResourceKey<Level> currentDimension = mc.level.dimension();

        // 绑定的加速器：蓝色角括号。
        DeviceId acceleratorId = ConfigCardData.getBoundAccelerator(card);
        if (acceleratorId != null && acceleratorId.dimension().equals(currentDimension)
                && mc.level.hasChunkAt(acceleratorId.pos())) {
            drawBracket(alloc, poseStack, acceleratorId.pos(), COLOR_ACCELERATOR, eye);
        }
        // 绑定的设备：逐个画绿色角括号（数量受卡片上限约束，开销可控）。
        for (DeviceId deviceId : ConfigCardData.getBoundDevices(card)) {
            if (!deviceId.dimension().equals(currentDimension) || !mc.level.hasChunkAt(deviceId.pos())) {
                continue;
            }
            if (deviceId.isCpu()) {
                // 合成 CPU 为多块组：按卡上记录的「整组外包围盒」画线框（标记合并）。
                // 无几何记录（旧数据/防御）或包围盒所在区块未加载时退化为单格角括号。
                BlockPos boundsMax = ConfigCardData.cpuBoundsMaxOf(card, deviceId);
                if (boundsMax != null && mc.level.hasChunkAt(boundsMax)) {
                    drawBracket(alloc, poseStack, deviceId.pos(), boundsMax, COLOR_DEVICE, eye);
                    continue;
                }
            }
            if (deviceId.kind() == DeviceKind.PART) {
                // 线缆部件：按该部件在世界中的实际碰撞箱画框（一条线缆可挂多个部件，
                // 逐台按附着面取各自几何，避免同格多台设备共用整格框）。
                AABB partBox = partBoxInWorld(mc.level, deviceId);
                if (partBox != null) {
                    drawBracket(alloc, poseStack, partBox, COLOR_DEVICE, eye);
                    continue;
                }
            }
            // 方块实体 / 几何缺失时退化：整格角括号。
            drawBracket(alloc, poseStack, deviceId.pos(), COLOR_DEVICE, eye);
        }
    }

    /**
     * 返回玩家主手/副手中持有的配置卡（无则返回空栈）。
     */
    private static ItemStack heldConfigCard(Player player) {
        ItemStack mainHand = player.getMainHandItem();
        if (ConfigCardData.isConfigCard(mainHand)) {
            return mainHand;
        }
        ItemStack offHand = player.getOffhandItem();
        return ConfigCardData.isConfigCard(offHand) ? offHand : ItemStack.EMPTY;
    }

    /**
     * 对指定单格方块位置绘制深浅双通道角括号。
     */
    private static void drawBracket(BufferAllocator alloc, PoseStack poseStack, BlockPos pos, int argb, Vec3 eye) {
        drawBracket(alloc, poseStack, pos, pos, argb, eye);
    }

    /**
     * 对从最小角到最大角（角坐标均含该方块）的整组 AABB 绘制深浅双通道角括号。
     * <p>
     * 用于合成 CPU 多块组：以卡上记录的组最小/最大角确定整组外围，画一圈外轮廓线框；
     * 退化调用（最小角 == 最大角）等价于单格高亮。
     */
    private static void drawBracket(BufferAllocator alloc, PoseStack poseStack, BlockPos minPos, BlockPos maxPos,
            int argb, Vec3 eye) {
        drawBracket(alloc, poseStack,
                minPos.getX() - INFLATE, minPos.getY() - INFLATE, minPos.getZ() - INFLATE,
                maxPos.getX() + 1 + INFLATE, maxPos.getY() + 1 + INFLATE, maxPos.getZ() + 1 + INFLATE,
                argb, eye);
    }

    /**
     * 对指定世界坐标 AABB（线缆部件的实际碰撞箱等）绘制深浅双通道角括号。
     */
    private static void drawBracket(BufferAllocator alloc, PoseStack poseStack, AABB box, int argb, Vec3 eye) {
        drawBracket(alloc, poseStack,
                box.minX - INFLATE, box.minY - INFLATE, box.minZ - INFLATE,
                box.maxX + INFLATE, box.maxY + INFLATE, box.maxZ + INFLATE,
                argb, eye);
    }

    /**
     * 对指定世界 AABB 绘制深浅双通道角括号（真正的绘制入口）。
     * <p>
     * 距离用于厚度自适应（16 格内基础厚度，超出则按距离线性加粗）。
     */
    private static void drawBracket(BufferAllocator alloc, PoseStack poseStack,
            double minX, double minY, double minZ, double maxX, double maxY, double maxZ,
            int argb, Vec3 eye) {
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

    /**
     * 解析部件设备的<b>实际碰撞箱</b>（世界坐标并集）。
     * <p>
     * 一条线缆可同时挂多个部件设备，若按整格高亮，同格多台设备的框会完全重叠、无法分辨指哪台。
     * 故部件型绑定目标经线缆宿主的 {@link IPartHost#getPart(Direction)} 取「绑定朝向」上的部件，
     * 并复用与 AE2 选择框相同的几何换算（{@link BusCollisionHelper}：以部件附着面为局部 z 轴，
     * 把部件声明的 1/16 局部盒换算成线缆格内的世界盒），多盒取并集后平移到线缆坐标。
     * <p>
     * 取不到部件（已拆除 / 区块内方块实体缺失）或部件未贡献任何几何时返回 {@code null}，
     * 由调用方退化为整格角括号。
     */
    @Nullable
    private static AABB partBoxInWorld(Level level, DeviceId deviceId) {
        Direction side = deviceId.side();
        if (side == null) {
            return null;
        }
        BlockPos pos = deviceId.pos();
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof IPartHost host)) {
            return null;
        }
        IPart part = host.getPart(side);
        if (part == null) {
            return null;
        }
        List<AABB> boxes = new ArrayList<>(4);
        // visual=true：与 AE2 的鼠标选择框（selectPartLocal / getShape）同一几何口径，
        // 不使用真实物理碰撞用的加厚变体。
        BusCollisionHelper helper = new BusCollisionHelper(boxes, side, true);
        part.getBoxes(helper);
        if (boxes.isEmpty()) {
            return null;
        }
        AABB result = boxes.get(0);
        for (int i = 1; i < boxes.size(); i++) {
            result = result.minmax(boxes.get(i));
        }
        return result.move(pos.getX(), pos.getY(), pos.getZ());
    }

    @Override
    public int requiredBuffers() {
        // 位标志：4=brackets、8=noDepth（本 pass 两个通道都要用）。
        return 4 | 8;
    }
}
