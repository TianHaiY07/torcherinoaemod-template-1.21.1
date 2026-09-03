package com.tianhai.torcherino_ae.client.render.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * 角括号线框渲染的顶点数学单测（§10.2）。
 * <p>
 * 覆盖从 {@link CornerBracketRenderer#computeSegmentQuads} 抽取出的纯几何：
 * 沿 X / Y 轴向线段的方帽与横截面尺寸、退化线段返回 null、任意方向线段的
 * 四边形非退化与端点沿方向延长、以及 6 个四边形的面积守恒。
 * 顶点数学不依赖任何渲染对象（PoseStack / VertexConsumer 均不参与），故可纯 JVM 直测。
 */
class CornerBracketRendererTest {

    /** 允许的双精度误差（斜线运算引入的浮点舍入）。 */
    private static final double EPS = 1.0e-9;

    @Test
    void 沿X轴线段生成两端方帽的长方体() {
        double t = 0.5; // halfThick
        double[] q = CornerBracketRenderer.computeSegmentQuads(0, 0, 0, 10, 0, 0, t);
        assertNotNull(q);
        assertEquals(72, q.length);

        // 起点端面（quad0）：x 全部被压到起点沿 -n 延长 t 的平面，横截面为 2t × 2t 正方形。
        for (int v = 0; v < 4; v++) {
            assertEquals(-t, q[v * 3], EPS, "起点端面应位于 x = 起点 - t");
            // y/z 各取 ±t，四角组合齐全。
        }
        Set<String> yz = cornersYz(q, 0);
        assertEquals(4, yz.size());
        assertTrue(yz.contains("0.5,0.5"));
        assertTrue(yz.contains("-0.5,0.5"));
        assertTrue(yz.contains("-0.5,-0.5"));
        assertTrue(yz.contains("0.5,-0.5"));

        // 终点端面（quad1）：x 全部被压到终点沿 +n 延长 t 的平面。
        for (int v = 0; v < 4; v++) {
            assertEquals(10 + t, q[12 + v * 3], EPS, "终点端面应位于 x = 终点 + t");
        }

        // 顶点集合恰好为 8 个角（6 quad 共享），覆盖 [-t,10+t] × ±t × ±t。
        Set<String> corners = allCorners(q);
        assertEquals(8, corners.size());
    }

    @Test
    void 沿Y轴线段横截面在XZ平面() {
        double[] q = CornerBracketRenderer.computeSegmentQuads(0, 0, 0, 0, 5, 0, 0.4);
        assertNotNull(q);
        for (int v = 0; v < 4; v++) {
            assertEquals(-0.4, q[v * 3 + 1], EPS, "起点端面 y = -t");
            assertEquals(5 + 0.4, q[12 + v * 3 + 1], EPS, "终点端面 y = 终点 + t");
        }
        // 方向为 Y 时 u/v 落于 XZ 平面：四个角 (x,z) 应覆盖 ±t × ±t 全组合。
        Set<String> xz = cornersXz(q, 0);
        assertEquals(4, xz.size());
        assertTrue(xz.contains("0.4,0.4"));
        assertTrue(xz.contains("-0.4,0.4"));
        assertTrue(xz.contains("-0.4,-0.4"));
        assertTrue(xz.contains("0.4,-0.4"));
    }

    @Test
    void 端面与侧面面积符合长方体展开() {
        double len = 10;
        double t = 0.5;
        double[] q = CornerBracketRenderer.computeSegmentQuads(0, 0, 0, len, 0, 0, t);

        // 两个端面：2t × 2t = 1 × 1。
        assertEquals(4 * t * t, quadArea(q, 0), EPS, "起点端面面积");
        assertEquals(4 * t * t, quadArea(q, 1), EPS, "终点端面面积");
        // 四个侧面：侧面横跨整段含两端方帽的柱体，故长 = len + 2t、宽 = 2t。
        for (int side = 2; side < 6; side++) {
            assertEquals((len + 2 * t) * 2 * t, quadArea(q, side), EPS, "侧面面积应为 (len + 2t) × 2t");
        }
    }

    @Test
    void 斜线段各四边形非退化且端点沿方向延长() {
        double t = 0.04;
        // 45° 斜线：方向 n = (√2/2, √2/2, 0)，端点为 (0,0,0) 与 (3,3,0)。
        double[] q = CornerBracketRenderer.computeSegmentQuads(0, 0, 0, 3, 3, 0, t);
        assertNotNull(q);
        for (int i = 0; i < 6; i++) {
            assertTrue(quadArea(q, i) > 1.0e-6, "quad " + i + " 应非退化");
        }

        // 端面中心应落在两端沿方向各偏移 t 的位置：起点 (-t√2/2, -t√2/2, 0)。
        double dx = 3, dy = 3;
        double len = Math.sqrt(dx * dx + dy * dy);
        double nx = dx / len, ny = dy / len;
        double[] c0 = quadCenter(q, 0);
        double[] c1 = quadCenter(q, 1);
        assertEquals(-nx * t, c0[0], 1.0e-6);
        assertEquals(-ny * t, c0[1], 1.0e-6);
        assertEquals(3 + nx * t, c1[0], 1.0e-6);
        assertEquals(3 + ny * t, c1[1], 1.0e-6);
    }

    @Test
    void 退化线段返回null() {
        // 零长度线段：方向不可构造。
        assertNull(CornerBracketRenderer.computeSegmentQuads(1, 2, 3, 1, 2, 3, 0.5));
        // 长度不足阈值同样视为退化。
        assertNull(CornerBracketRenderer.computeSegmentQuads(0, 0, 0, 0.00001, 0, 0, 0.5));
    }

    @Test
    void 厚度与长度自适应参数互不干扰() {
        // 不同的 t（厚度）只改变横截尺寸，不改变方向的端点延长比。
        double[] thin = CornerBracketRenderer.computeSegmentQuads(0, 0, 0, 10, 0, 0, 0.1);
        double[] thick = CornerBracketRenderer.computeSegmentQuads(0, 0, 0, 10, 0, 0, 1.5);
        assertNotNull(thin);
        assertNotNull(thick);

        // 起点端面平面 x 坐标等于 -t；不同 t 的端点面 x 随 t 线性变化。
        assertEquals(-0.1, thin[0], EPS);
        assertEquals(-1.5, thick[0], EPS);
        // 起点端面到终点端面的距离 = len + 2t（方帽各 t）。
        assertEquals(10 + 0.2, quadCenter(thin, 1)[0] - quadCenter(thin, 0)[0], EPS);
        assertEquals(10 + 3.0, quadCenter(thick, 1)[0] - quadCenter(thick, 0)[0], EPS);
    }

    // ===== 测试辅助 =====

    /** 取某 quad 的 4 个三维顶点（double[12]，起点下标 o = quad * 12）。 */
    private static void quadVertices(double[] quads, int quad, double[][] out) {
        int o = quad * 12;
        for (int v = 0; v < 4; v++) {
            out[v][0] = quads[o + v * 3];
            out[v][1] = quads[o + v * 3 + 1];
            out[v][2] = quads[o + v * 3 + 2];
        }
    }

    /** 四边形面积 = 沿对角线拆两三角形的面积和。 */
    private static double quadArea(double[] quads, int quad) {
        double[][] p = new double[4][3];
        quadVertices(quads, quad, p);
        return triArea(p[0], p[1], p[2]) + triArea(p[0], p[2], p[3]);
    }

    private static double triArea(double[] a, double[] b, double[] c) {
        double abx = b[0] - a[0], aby = b[1] - a[1], abz = b[2] - a[2];
        double acx = c[0] - a[0], acy = c[1] - a[1], acz = c[2] - a[2];
        double cx = aby * acz - abz * acy;
        double cy = abz * acx - abx * acz;
        double cz = abx * acy - aby * acx;
        return 0.5 * Math.sqrt(cx * cx + cy * cy + cz * cz);
    }

    /** quad 四个顶点的几何中心。 */
    private static double[] quadCenter(double[] quads, int quad) {
        double[][] p = new double[4][3];
        quadVertices(quads, quad, p);
        return new double[] {
                (p[0][0] + p[1][0] + p[2][0] + p[3][0]) / 4,
                (p[0][1] + p[1][1] + p[2][1] + p[3][1]) / 4,
                (p[0][2] + p[1][2] + p[2][2] + p[3][2]) / 4 };
    }

    /** 某个 quad 的 y/z 角组合键集合（x 固定的轴对齐端面用）。 */
    private static Set<String> cornersYz(double[] quads, int quad) {
        double[][] p = new double[4][3];
        quadVertices(quads, quad, p);
        Set<String> set = new HashSet<>();
        for (double[] v : p) {
            set.add(v[1] + "," + v[2]);
        }
        return set;
    }

    /** 某个 quad 的 x/z 角组合键集合（y 固定的轴对齐端面用）。 */
    private static Set<String> cornersXz(double[] quads, int quad) {
        double[][] p = new double[4][3];
        quadVertices(quads, quad, p);
        Set<String> set = new HashSet<>();
        for (double[] v : p) {
            set.add(v[0] + "," + v[2]);
        }
        return set;
    }

    /** 全部顶点的归一化键集合（用于统计 6 quad 共享的唯一角数）。 */
    private static Set<String> allCorners(double[] quads) {
        Set<String> set = new HashSet<>();
        for (int i = 0; i < quads.length; i += 3) {
            set.add(quads[i] + "," + quads[i + 1] + "," + quads[i + 2]);
        }
        return set;
    }
}
