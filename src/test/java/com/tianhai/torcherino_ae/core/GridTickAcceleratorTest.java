package com.tianhai.torcherino_ae.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import appeng.api.networking.IGridNode;
import appeng.api.networking.ticking.IGridTickable;
import appeng.api.networking.ticking.TickRateModulation;

/**
 * 网格 tick 黑盒加速原语 {@link GridTickAccelerator} 的纯逻辑单测。
 * <p>
 * 覆盖：按 maxCalls 全额调用、中途 {@code SLEEP} 提前结束（计数含 SLEEP 那一次）、
 * maxCalls≤0、空参数防御。通过 {@link java.lang.reflect.Proxy} 伪造 AE2 的
 * {@link IGridNode}/{@link IGridTickable} 接口，不依赖 Minecraft 运行时。
 */
class GridTickAcceleratorTest {

    // ============================== 脚本化 IGridTickable（含调用计数） ==============================

    /**
     * 构造一个脚本化 {@link IGridTickable}：每次 {@code tickingRequest} 依次给出脚本中的返回值，
     * 脚本耗尽后固定返回 {@code onExhausted}；同时记录实际调用次数。
     */
    private static IGridTickable scriptedTickable(List<TickRateModulation> script, TickRateModulation onExhausted,
            AtomicInteger invocations) {
        Deque<TickRateModulation> queue = new ArrayDeque<>(script);
        InvocationHandler handler = (proxy, method, args) -> {
            if (method.getName().equals("tickingRequest")) {
                invocations.incrementAndGet();
                TickRateModulation next = queue.poll();
                return next != null ? next : onExhausted;
            }
            if (method.getName().equals("hashCode")) {
                return System.identityHashCode(proxy);
            }
            if (method.getName().equals("equals")) {
                return proxy == args[0];
            }
            return defaultValue(method.getReturnType());
        };
        return (IGridTickable) Proxy.newProxyInstance(IGridTickable.class.getClassLoader(),
                new Class<?>[]{IGridTickable.class}, handler);
    }

    // 一个「非空但无行为」的 IGridNode 桩（tick() 仅把它原样传给 tickingRequest，不读取其字段）。
    private static IGridNode dummyNode(AtomicInteger inspections) {
        InvocationHandler handler = (proxy, method, args) -> {
            if (method.getName().equals("hashCode")) {
                return System.identityHashCode(proxy);
            }
            if (method.getName().equals("equals")) {
                return proxy == args[0];
            }
            // 访问节点字段（如 getOwner/getGrid）的防御：标记被触碰，便于断言「原语未解析节点」。
            inspections.incrementAndGet();
            return defaultValue(method.getReturnType());
        };
        return (IGridNode) Proxy.newProxyInstance(IGridNode.class.getClassLoader(),
                new Class<?>[]{IGridNode.class}, handler);
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == double.class) {
            return 0.0;
        }
        if (type == float.class) {
            return 0.0F;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == char.class) {
            return (char) 0;
        }
        return 0;
    }

    // ============================== 测试 ==============================

    @Test
    void 设备始终不睡时按maxCalls全额推进() {
        AtomicInteger invocations = new AtomicInteger();
        IGridTickable tickable = scriptedTickable(List.of(), TickRateModulation.SAME, invocations);
        // 脚本为空 → 每次都返回 SAME（不睡），因此循环跑满 maxCalls。
        assertEquals(5, GridTickAccelerator.tick(dummyNode(new AtomicInteger()), tickable, 5));
        assertEquals(5, invocations.get());
    }

    @Test
    void 设备中途睡眠时提前结束且计数含睡眠那次() {
        AtomicInteger invocations = new AtomicInteger();
        // 前两次不睡、第三次 SLEEP：循环在第三次(睡眠)提前 break，计数应为 3（含睡眠那次）。
        IGridTickable tickable = scriptedTickable(
                List.of(TickRateModulation.SLOWER, TickRateModulation.SAME, TickRateModulation.SLEEP),
                TickRateModulation.SAME, invocations);
        assertEquals(3, GridTickAccelerator.tick(dummyNode(new AtomicInteger()), tickable, 10));
        assertEquals(3, invocations.get());
    }

    @Test
    void maxCalls为零时不发起任何调用() {
        IGridTickable tickable = scriptedTickable(List.of(), TickRateModulation.SAME, new AtomicInteger());
        assertEquals(0, GridTickAccelerator.tick(dummyNode(new AtomicInteger()), tickable, 0));
    }

    @Test
    void 参数为null时返回零() {
        assertEquals(0, GridTickAccelerator.tick(null,
                scriptedTickable(List.of(), TickRateModulation.SAME, new AtomicInteger()), 5));
        IGridNode node = dummyNode(new AtomicInteger());
        assertEquals(0, GridTickAccelerator.tick(node, null, 5));
    }

    @Test
    void 原语不解析节点自身字段() {
        // 原语只把 node 原样传给 tickingRequest，不应触发对 IGridNode 的 getOwner/getGrid 等读取，
        // 这保证它是一枚轻量代理、无网格依赖（节点安全取值交给调用方）。
        AtomicInteger inspections = new AtomicInteger();
        IGridNode node = dummyNode(inspections);
        IGridTickable tickable = scriptedTickable(List.of(), TickRateModulation.SAME, new AtomicInteger());
        GridTickAccelerator.tick(node, tickable, 3);
        assertEquals(0, inspections.get());
    }
}
