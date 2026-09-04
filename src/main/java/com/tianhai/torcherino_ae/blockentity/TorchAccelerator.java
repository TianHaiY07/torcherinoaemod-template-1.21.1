package com.tianhai.torcherino_ae.blockentity;

import org.jetbrains.annotations.Nullable;

import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.security.IActionHost;
import appeng.api.networking.ticking.IGridTickable;
import appeng.api.networking.ticking.ITickManager;
import appeng.api.networking.ticking.TickRateModulation;
import appeng.me.helpers.IGridConnectedBlockEntity;
import com.tianhai.torcherino_ae.Torcherinoaemod;
import com.tianhai.torcherino_ae.api.BudgetMeter;
import com.tianhai.torcherino_ae.config.RuntimeConfig;
import com.tianhai.torcherino_ae.util.AeGrid;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 加速火把的「三条加速路径」执行器（原始 Torcherino 式）。
 * <p>
 * 由 {@link AETorcherinoBlockEntity} 每 tick 对每个已扫描目标调用 {@link #accelerate}，
 * 三条路径对同一目标并行生效（是 AE 设备又带方块实体 tick/随机 tick 时各自叠加）：
 * <ol>
 *   <li><b>AE 网格 tick</b>：方块实现 {@link IActionHost} 或 {@link IGridConnectedBlockEntity}
 *       （覆盖 AE2 原版机器与所有附属模组）时，拿到 {@link IGridTickable} 后重复驱动其处理进度；</li>
 *   <li><b>方块实体 tick</b>：重复调用目标方块 {@link net.minecraft.world.level.block.EntityBlock#getTicker}
 *       返回的 ticker（原版熔炉、第三方机器）；</li>
 *   <li><b>随机 tick</b>：对可随机 tick 的方块（作物/树苗等）采用<b>概率式分摊</b>加速——把
 *       「加速 N 次」折成「提高每 tick 触发概率」，单格单 tick 至多触发 1 次
 *       {@link BlockState#randomTick}，使随机 tick 加速的工作量与 speed 基本解耦（参照原版 Torcherino）。</li>
 * </ol>
 * 所有调用共享同一份 {@link BudgetMeter}（逐次按需申请，额度耗尽即停）；AE 网格 tick 与方块实体
 * tick 按 {@code speed - 1} 的次数上限反复推进对象，随机 tick 按概率命中一次触发。面向任意方块，
 * 每个目标都包一层防御性 {@code try/catch}，防止某个异常方块在单 tick 路径上拖垮服务端。
 */
final class TorchAccelerator {

    private TorchAccelerator() {
    }

    /**
     * 按缓存目标自身的类型执行命中路径，返回本 tick 是否确实发起过任何加速调用。
     *
     * @param speed 当前加速倍数（{@link AETorcherinoBlockEntity#getSpeed()}）
     */
    static boolean accelerate(Level level, TorchTargetScanner.Target target, int speed, BudgetMeter budget) {
        boolean didWork = false;
        BlockPos pos = target.pos();
        BlockEntity be = level.getBlockEntity(pos);
        // 仅当方块实体类型未变化时复用缓存，避免误加速已被替换的方块；否则该目标本次跳过，
        // 下一次重扫会重新缓存，自会纠正。
        if (be != null && !be.isRemoved() && target.beType() != null && be.getType() == target.beType()) {
            if (target.isAeMachine()) {
                didWork |= accelerateGridTicks(be, speed, budget);
            }
            if (target.ticker() != null) {
                didWork |= accelerateBlockEntityTicks(be, target.ticker(), speed, budget);
            }
        }
        if (target.randomlyTicking()) {
            didWork |= accelerateRandomTicks((ServerLevel) level, pos, level.getBlockState(pos), speed, budget);
        }
        return didWork;
    }

    /**
     * 核心加速路径之一：AE 机器的实际处理逻辑大多注册为 {@link IGridTickable}（网格 tick）。
     * 通过 {@link IActionHost} 或 {@link IGridConnectedBlockEntity} 拿到网格节点；这两种接口
     * 能覆盖 AE2 原版机器与所有附属模组（如 DataEnergistics）的网络设备。
     * <p>
     * AE2 机器的处理进度由网格 tick 驱动。多数机器在 {@code tickingRequest} 中只推进一个
     * 离散步骤并忽略第二个参数（ticksSinceLastCall），因此必须循环调用多次才能真正加速；
     * 仅少数机器会把该参数当作倍率做乘法。这里统一按 1 tick 循环调用，保证对所有机器都有效。
     *
     * @return 是否确实发起过网格 tick 调用
     */
    private static boolean accelerateGridTicks(BlockEntity blockEntity, int speed, BudgetMeter budget) {
        IGridNode node = getGridNode(blockEntity);
        if (node == null) {
            return false;
        }
        IGrid grid = AeGrid.gridOf(node);
        if (grid == null) {
            return false;
        }
        IGridTickable tickable = node.getService(IGridTickable.class);
        if (tickable == null) {
            return false;
        }
        // 空闲（睡眠）中的设备无需驱动，直接跳过，避免高倍率下对空闲设备做大量无意义调用。
        try {
            if (tickable.getTickingRequest(node).isSleeping()) {
                return false;
            }
        } catch (RuntimeException e) {
            return false;
        }
        boolean didWork = false;
        for (int i = 0; i < speed - 1; i++) {
            // 每 tick 预算按次申请：额度耗尽立即停止本目标的剩余调用，后续目标同样受限。
            if (budget.request(1) <= 0) {
                return didWork;
            }
            try {
                TickRateModulation modulation = tickable.tickingRequest(node, 1);
                didWork = true;
                // 设备在工作结束后会返回 SLEEP，通知 AE2 tick 管理器停止调度，避免无效唤醒。
                if (modulation == TickRateModulation.SLEEP) {
                    ITickManager tickManager = grid.getTickManager();
                    if (tickManager != null) {
                        tickManager.sleepDevice(node);
                    }
                    break;
                }
            } catch (Exception e) {
                Torcherinoaemod.LOGGER.error("Failed while accelerating AE grid tick for {} at {}",
                        blockEntity.getType(), blockEntity.getBlockPos(), e);
                return didWork;
            }
        }
        return didWork;
    }

    /**
     * 加速路径之二：重复调用目标方块的 {@link net.minecraft.world.level.block.EntityBlock#getTicker}
     * 返回的方块实体 ticker（原版熔炉、第三方机器的处理进度多由方块实体 tick 驱动）。
     */
    private static boolean accelerateBlockEntityTicks(BlockEntity blockEntity, BlockEntityTicker<BlockEntity> ticker,
            int speed, BudgetMeter budget) {
        Level level = blockEntity.getLevel();
        BlockPos pos = blockEntity.getBlockPos();
        BlockState state = blockEntity.getBlockState();
        if (level == null) {
            return false;
        }
        boolean didWork = false;
        for (int i = 0; i < speed - 1; i++) {
            if (blockEntity.isRemoved()) {
                return didWork;
            }
            // 每 tick 预算按次申请：额度耗尽立即停止本目标的剩余调用，后续目标同样受限。
            if (budget.request(1) <= 0) {
                return didWork;
            }
            try {
                ticker.tick(level, pos, state, blockEntity);
                didWork = true;
            } catch (Exception e) {
                Torcherinoaemod.LOGGER.error("Failed while accelerating block entity {} at {}",
                        blockEntity.getType(), pos, e);
                return didWork;
            }
        }
        return didWork;
    }

    /**
     * 加速路径之三：对可随机 tick 的方块（作物、树苗、原木等）加速随机 tick。
     * <p>
     * 采用<b>概率式分摊</b>（参照原版 Torcherino）：不是每 tick 对每个方块暴力调
     * {@code speed-1} 次 {@code randomTick}（那会在高倍率 + 大型农场下造成主线程尖峰），
     * 而是把「加速 N 次随机 tick」折成「提高每 tick 触发随机 tick 的概率」，单格单 tick
     * 至多触发 1 次 {@code randomTick}。概率由
     * {@code vanillaRandomTicks / clamp(4096/(speed × rate), 1, 4096)} 决定，使随机 tick
     * 加速的工作量与 speed 基本解耦、始终有上界。
     *
     * @return 本 tick 是否确实触发了一次随机 tick
     */
    private static boolean accelerateRandomTicks(ServerLevel level, BlockPos targetPos, BlockState blockState,
            int speed, BudgetMeter budget) {
        if (!blockState.isRandomlyTicking()) {
            return false;
        }
        if (budget.isExhausted()) {
            return false;
        }
        // 原版随机 tick 节奏由游戏规则 randomTickSpeed 驱动，值为 0 表示随机 tick 关闭，无需加速。
        int vanillaTicks = level.getGameRules().getInt(GameRules.RULE_RANDOMTICKING);
        if (vanillaTicks <= 0) {
            return false;
        }
        int denominator = randomTickDenominator(speed, RuntimeConfig.torcherinoRandomTickRate());
        if (!randomTickHit(denominator, vanillaTicks, level.getRandom().nextInt(denominator))) {
            return false;
        }
        // 命中后再校验当前方块仍可随机 tick（防止触发了已锁定/被替换方块的状态机）。
        BlockState current = level.getBlockState(targetPos);
        if (!current.isRandomlyTicking()) {
            return false;
        }
        if (budget.request(1) <= 0) {
            return false;
        }
        try {
            current.randomTick(level, targetPos, level.getRandom());
            return true;
        } catch (Exception e) {
            Torcherinoaemod.LOGGER.error("Failed while accelerating random tick block at {}", targetPos, e);
            return false;
        }
    }

    /**
     * 概率式随机 tick 加速的<b>分母</b>（越小概率越高）：{@code clamp(4096 / (speed × rate), 1, 4096)}。
     * <p>
     * 与原版 Torcherino 的公式一致：真实概率 = {@code vanillaRandomTicks / denominator}。
     * 倍率越高分母越小（触发越频繁），但恒有下界 1，保证单格单 tick 至多触发 1 次随机 tick。
     */
    static int randomTickDenominator(int speed, int randomTickRate) {
        long rate = 4096L / ((long) Math.max(1, speed) * Math.max(1, randomTickRate));
        return (int) Math.max(1, Math.min(4096, rate));
    }

    /**
     * 概率式随机 tick 是否命中：给定分母 {@code denominator} 与随机「掷出值」{@code roll}，
     * 当原版随机 tick 数大于 0 且 {@code roll < vanillaRandomTicks} 时返回 {@code true}。
     */
    static boolean randomTickHit(int denominator, int vanillaRandomTicks, int roll) {
        return vanillaRandomTicks > 0 && roll >= 0 && roll < vanillaRandomTicks;
    }

    /**
     * 从方块实体解析网格节点：优先取 {@link IActionHost} 的可行动节点，其次取
     * {@link IGridConnectedBlockEntity} 的主节点。
     */
    @Nullable
    private static IGridNode getGridNode(BlockEntity blockEntity) {
        if (blockEntity instanceof IActionHost actionHost) {
            IGridNode node = actionHost.getActionableNode();
            if (node != null) {
                return node;
            }
        }
        if (blockEntity instanceof IGridConnectedBlockEntity gridConnected) {
            return gridConnected.getMainNode().getNode();
        }
        return null;
    }
}
