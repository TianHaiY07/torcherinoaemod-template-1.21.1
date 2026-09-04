package com.tianhai.torcherino_ae.blockentity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelAccessor;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 加速火把<b>事件唤醒</b>控制器——解决「自适应退避扫描下，新增设备响应慢」的问题，
 * 且支持<b>大量火把与范围重叠</b>的可扩展实现（参照 RTS 输电塔的 {@code PowerTowerWakeup}）。
 * <p>
 * 火把范围稳定时 {@link AETorcherinoBlockEntity} 会把扫描周期退避到低频（省主线程成本，
 * 默认最长 200 tick），导致范围内<b>新放置的设备</b>要等很久才被识别加速。本类监听服务器的
 * <b>方块放置 / 破坏</b>事件，若事件位置落在某个火把的影响范围内，立即调用
 * {@link AETorcherinoBlockEntity#forceRescan()} 唤醒它——下个 tick 即开始扫一圈
 * （≤{@code scanIntervalTicks} tick），把发现延迟从「最长退避周期」降到「一键扫描内」。
 * 退避仍在稳态省成本，两者兼得。
 * <p>
 * <b>可扩展性</b>：用<b>空间哈希</b>而非线性遍历所有火把。每座火把把影响范围 AABB 覆盖的
 * 所有网格单元（{@link #CELL} 大小）登记到桶中；事件时只查「事件位置所在单元」的候选火把，
 * 再 AABB 精确校验。复杂度由 <b>O(维度内全部火把数)</b> 降为 <b>O(覆盖该单元的重叠火把数)</b>。
 * 仅服务端生效。
 */
public final class TorcherinoWakeup {

    private TorcherinoWakeup() {
    }

    /** 空间哈希单元大小（格）。火把影响范围 AABB 覆盖多少个单元就登记到多少个桶。 */
    private static final int CELL = 32;

    /** 维度 → (单元 key → 覆盖该单元的加速火把)。维度随 WeakHashMap 卸载自动回收。 */
    private static final Map<ServerLevel, Map<Long, List<AETorcherinoBlockEntity>>> BUCKETS = new WeakHashMap<>();

    /** 加速火把装载（chunk 加载）时注册，把其影响范围覆盖的所有单元登记到桶。 */
    public static void register(ServerLevel level, AETorcherinoBlockEntity torch) {
        Map<Long, List<AETorcherinoBlockEntity>> buckets = BUCKETS.computeIfAbsent(level, k -> new HashMap<>());
        for (long cell : coveredCells(torch)) {
            List<AETorcherinoBlockEntity> list = buckets.computeIfAbsent(cell, k -> new ArrayList<>());
            if (!list.contains(torch)) {
                list.add(torch);
            }
        }
    }

    /** 加速火把卸载时注销：从其覆盖的所有单元桶中移除。 */
    public static void unregister(ServerLevel level, AETorcherinoBlockEntity torch) {
        Map<Long, List<AETorcherinoBlockEntity>> buckets = BUCKETS.get(level);
        if (buckets == null || buckets.isEmpty()) {
            return;
        }
        for (long cell : coveredCells(torch)) {
            List<AETorcherinoBlockEntity> list = buckets.get(cell);
            if (list != null && !list.isEmpty()) {
                list.remove(torch);
                if (list.isEmpty()) {
                    buckets.remove(cell);
                }
            }
        }
        if (buckets.isEmpty()) {
            BUCKETS.remove(level);
        }
    }

    /** 单方块放置：唤醒覆盖该位置的加速火把。 */
    @SubscribeEvent
    public static void onEntityPlace(BlockEvent.EntityPlaceEvent event) {
        wakeup(event.getLevel(), event.getPos());
    }

    /** 多方块放置（桶/平铺等一批）：唤醒覆盖各位置的所有加速火把。 */
    @SubscribeEvent
    public static void onEntityMultiPlace(BlockEvent.EntityMultiPlaceEvent event) {
        wakeup(event.getLevel(), event.getPos());
    }

    /** 方块破坏：唤醒（拆除范围内设备也可能腾出空间，让火把及时重扫）。 */
    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent event) {
        wakeup(event.getLevel(), event.getPos());
    }

    /** 事件位置所在单元内的候选火把，经 AABB 精确校验后唤醒。 */
    private static void wakeup(LevelAccessor level, BlockPos pos) {
        if (!(level instanceof ServerLevel serverLevel) || pos == null) {
            return;
        }
        Map<Long, List<AETorcherinoBlockEntity>> buckets = BUCKETS.get(serverLevel);
        if (buckets == null || buckets.isEmpty()) {
            return;
        }
        List<AETorcherinoBlockEntity> list = buckets.get(cellKey(pos));
        if (list == null || list.isEmpty()) {
            return;
        }
        for (AETorcherinoBlockEntity torch : list) {
            if (torch.isInRange(pos)) {
                torch.forceRescan();
            }
        }
    }

    /** 火把影响范围 AABB 覆盖的所有单元 key 列表（供注册/注销）。 */
    private static List<Long> coveredCells(AETorcherinoBlockEntity torch) {
        BlockPos p = torch.getBlockPos();
        int rX = torch.getXRange();
        int rY = torch.getYRange();
        int rZ = torch.getZRange();
        int x0 = Math.floorDiv(p.getX() - rX, CELL), x1 = Math.floorDiv(p.getX() + rX, CELL);
        int y0 = Math.floorDiv(p.getY() - rY, CELL), y1 = Math.floorDiv(p.getY() + rY, CELL);
        int z0 = Math.floorDiv(p.getZ() - rZ, CELL), z1 = Math.floorDiv(p.getZ() + rZ, CELL);
        List<Long> cells = new ArrayList<>((x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1));
        for (int cx = x0; cx <= x1; cx++) {
            for (int cy = y0; cy <= y1; cy++) {
                for (int cz = z0; cz <= z1; cz++) {
                    cells.add(cellKey(cx, cy, cz));
                }
            }
        }
        return cells;
    }

    /** 某世界坐标所在单元的 key。 */
    private static long cellKey(BlockPos pos) {
        return cellKey(Math.floorDiv(pos.getX(), CELL),
                Math.floorDiv(pos.getY(), CELL),
                Math.floorDiv(pos.getZ(), CELL));
    }

    /** 单元坐标 → 无碰撞 long key（每维 20 bit ≈ ±1,048,575 单元，×32 格 ≥ MC 世界半径）。 */
    private static long cellKey(int cx, int cy, int cz) {
        return ((long) (cx & 0xFFFFF) << 40) | ((long) (cy & 0xFFFFF) << 20) | ((long) (cz & 0xFFFFF));
    }
}
