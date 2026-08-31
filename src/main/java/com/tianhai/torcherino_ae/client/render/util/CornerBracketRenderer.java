package com.tianhai.torcherino_ae.client.render.util;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

/**
 * 角括号线框渲染器（移植自 RTSBuilding 的 CornerBracketRenderer）。
 * <p>
 * 把 AABB 轮廓渲染成 12 条带厚度的「块状」粗线段（顶部水平环 + 底部水平环 + 4 条垂直棱），
 * 线条厚度随距离自适应（距相机 16 格内厚度不变，超出后线性增大），称为「角括号」。
 * 粗线段由两端方帽 + 4 侧面共 6 个四边形组成，配合 POSITION_COLOR 渲染类型即可叠加到世界场景，
 * 用于配置卡高亮（加速器蓝色、设备绿色等场景）。
 */
public final class CornerBracketRenderer {

    // 角括号粗线段基础厚度（单位：格）。
    private static final double BRACKET_THICKNESS = 0.04D;

    // 厚度随距离放大的基准距离（距相机 16 格内厚度不变，超出后线性增大）。
    private static final double THICKNESS_SCALE_DISTANCE = 16.0D;

    // 最小厚度系数（距离很小时不至于过细）。
    private static final double MIN_THICKNESS_MULTIPLIER = 0.25D;

    // noDepth 穿透通道默认 alpha（挡住视线时隐约可见）。
    public static final float DEFAULT_NO_DEPTH_ALPHA = 0.10f;

    private CornerBracketRenderer() {
    }

    /**
     * 渲染实心角括号（alpha=1.0 简化重载）。
     *
     * @param poseStack 相机处 PoseStack
     * @param consumer  目标渲染通道的顶点消费者
     * @param distance  相机 AABB 中心距离（用于厚度自适应）
     */
    public static void renderCornerBrackets(PoseStack poseStack, VertexConsumer consumer,
            double minX, double minY, double minZ,
            double maxX, double maxY, double maxZ,
            float r, float g, float b,
            double distance) {
        renderCornerBrackets(poseStack, consumer, minX, minY, minZ, maxX, maxY, maxZ, r, g, b, 1.0F, distance);
    }

    /**
     * 渲染实心角括号（指定 alpha）。
     */
    public static void renderCornerBrackets(PoseStack poseStack, VertexConsumer consumer,
            double minX, double minY, double minZ,
            double maxX, double maxY, double maxZ,
            float r, float g, float b, float a,
            double distance) {
        double scaledThickness = BRACKET_THICKNESS
                * Math.max(MIN_THICKNESS_MULTIPLIER, 1.0D)
                * Math.max(1.0D, distance / THICKNESS_SCALE_DISTANCE);
        double halfThick = scaledThickness * 0.5D;

        // 顶部 + 底部两个水平环与 4 条垂直棱，围成带厚度的 AABB 外轮廓。
        drawHorizontalRing(consumer, poseStack, minX, minZ, maxX, maxZ, minY, r, g, b, a, halfThick);
        drawHorizontalRing(consumer, poseStack, minX, minZ, maxX, maxZ, maxY, r, g, b, a, halfThick);
        drawVerticalEdges(consumer, poseStack, minX, minZ, maxX, maxZ, minY, maxY, r, g, b, a, halfThick);
    }

    /**
     * 单四边形顶点提交（4 顶点 + 颜色）。
     */
    public static void quad(VertexConsumer consumer, PoseStack poseStack,
            double x1, double y1, double z1,
            double x2, double y2, double z2,
            double x3, double y3, double z3,
            double x4, double y4, double z4,
            float r, float g, float b, float a) {
        var pose = poseStack.last();
        consumer.addVertex(pose, (float) x1, (float) y1, (float) z1).setColor(r, g, b, a);
        consumer.addVertex(pose, (float) x2, (float) y2, (float) z2).setColor(r, g, b, a);
        consumer.addVertex(pose, (float) x3, (float) y3, (float) z3).setColor(r, g, b, a);
        consumer.addVertex(pose, (float) x4, (float) y4, (float) z4).setColor(r, g, b, a);
    }

    /**
     * 水平环：4 条粗线段围成长方形。
     */
    private static void drawHorizontalRing(VertexConsumer consumer, PoseStack poseStack,
            double minX, double minZ, double maxX, double maxZ,
            double y, float r, float g, float b, float a, double t) {
        drawSegment(consumer, poseStack, minX, y, minZ, maxX, y, minZ, r, g, b, a, t);
        drawSegment(consumer, poseStack, maxX, y, minZ, maxX, y, maxZ, r, g, b, a, t);
        drawSegment(consumer, poseStack, maxX, y, maxZ, minX, y, maxZ, r, g, b, a, t);
        drawSegment(consumer, poseStack, minX, y, maxZ, minX, y, minZ, r, g, b, a, t);
    }

    /**
     * 4 条垂直棱。
     */
    private static void drawVerticalEdges(VertexConsumer consumer, PoseStack poseStack,
            double minX, double minZ, double maxX, double maxZ,
            double minY, double maxY, float r, float g, float b, float a, double t) {
        drawSegment(consumer, poseStack, minX, minY, minZ, minX, maxY, minZ, r, g, b, a, t);
        drawSegment(consumer, poseStack, maxX, minY, minZ, maxX, maxY, minZ, r, g, b, a, t);
        drawSegment(consumer, poseStack, maxX, minY, maxZ, maxX, maxY, maxZ, r, g, b, a, t);
        drawSegment(consumer, poseStack, minX, minY, maxZ, minX, maxY, maxZ, r, g, b, a, t);
    }

    /**
     * 关键几何：把一条线段渲染成「两端方帽 + 六面柱体」共 6 个四边形（四方体）。
     * 方向 n、法向量 u（选最小分量轴向构造）+ 切向量 v = u × n，
     * 端点沿 n 延长 t（方帽）、横截面为 u/v 方向的 ±t 方框，构成一根粗「方块线段」。
     */
    private static void drawSegment(VertexConsumer consumer, PoseStack poseStack,
            double x1, double y1, double z1,
            double x2, double y2, double z2,
            float r, float g, float b, float a, double t) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        double dz = z2 - z1;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1.0e-4) {
            return;
        }

        // 单位方向 n。
        double nx = dx / len, ny = dy / len, nz = dz / len;

        // 两端各延长 t 形成方帽。
        double sx = x1 - nx * t, sy = y1 - ny * t, sz = z1 - nz * t;
        double ex = x2 + nx * t, ey = y2 + ny * t, ez = z2 + nz * t;

        // 构造 u（与 n 垂直）：取分量最小的轴计算。
        double ux, uy, uz;
        double ax = Math.abs(nx), ay = Math.abs(ny), az = Math.abs(nz);
        if (ax <= ay && ax <= az) {
            ux = 0;
            uy = nz;
            uz = -ny;
        } else if (ay <= ax && ay <= az) {
            ux = -nz;
            uy = 0;
            uz = nx;
        } else {
            ux = ny;
            uy = -nx;
            uz = 0;
        }
        double uLen = Math.sqrt(ux * ux + uy * uy + uz * uz);
        if (uLen < 1.0e-8) {
            return;
        }
        ux /= uLen;
        uy /= uLen;
        uz /= uLen;

        // v = u × n。
        double vx = ny * uz - nz * uy;
        double vy = nz * ux - nx * uz;
        double vz = nx * uy - ny * ux;

        // 起点端面 4 角。
        double s1x = sx + ux * t + vx * t, s1y = sy + uy * t + vy * t, s1z = sz + uz * t + vz * t;
        double s2x = sx + ux * t - vx * t, s2y = sy + uy * t - vy * t, s2z = sz + uz * t - vz * t;
        double s3x = sx - ux * t - vx * t, s3y = sy - uy * t - vy * t, s3z = sz - uz * t - vz * t;
        double s4x = sx - ux * t + vx * t, s4y = sy - uy * t + vy * t, s4z = sz - uz * t + vz * t;

        // 终点端面 4 角。
        double e1x = ex + ux * t + vx * t, e1y = ey + uy * t + vy * t, e1z = ez + uz * t + vz * t;
        double e2x = ex + ux * t - vx * t, e2y = ey + uy * t - vy * t, e2z = ez + uz * t - vz * t;
        double e3x = ex - ux * t - vx * t, e3y = ey - uy * t - vy * t, e3z = ez - uz * t - vz * t;
        double e4x = ex - ux * t + vx * t, e4y = ey - uy * t + vy * t, e4z = ez - uz * t + vz * t;

        // 6 个四边形：起点端面 / 终点端面 / 4 个侧面。
        quad(consumer, poseStack, s1x, s1y, s1z, s2x, s2y, s2z, s3x, s3y, s3z, s4x, s4y, s4z, r, g, b, a);
        quad(consumer, poseStack, e4x, e4y, e4z, e3x, e3y, e3z, e2x, e2y, e2z, e1x, e1y, e1z, r, g, b, a);
        quad(consumer, poseStack, s1x, s1y, s1z, e1x, e1y, e1z, e2x, e2y, e2z, s2x, s2y, s2z, r, g, b, a);
        quad(consumer, poseStack, s2x, s2y, s2z, e2x, e2y, e2z, e3x, e3y, e3z, s3x, s3y, s3z, r, g, b, a);
        quad(consumer, poseStack, s3x, s3y, s3z, e3x, e3y, e3z, e4x, e4y, e4z, s4x, s4y, s4z, r, g, b, a);
        quad(consumer, poseStack, s4x, s4y, s4z, e4x, e4y, e4z, e1x, e1y, e1z, s1x, s1y, s1z, r, g, b, a);
    }
}
