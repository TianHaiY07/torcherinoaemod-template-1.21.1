package com.tianhai.torcherino_ae.blockentity;

import com.tianhai.torcherino_ae.Torcherinoaemod;
import com.tianhai.torcherino_ae.block.ModBlocks;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import appeng.blockentity.ClientTickingBlockEntity;
import appeng.blockentity.ServerTickingBlockEntity;

/**
 * 方块实体注册容器。
 */
public class ModBlockEntities {
    // 方块实体类型注册表，命名空间为本模组 modId。
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, Torcherinoaemod.MOD_ID);

    // AE 加速器的方块实体。
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<AEAcceleratorBlockEntity>> AE_ACCELERATOR =
            BLOCK_ENTITY_TYPES.register("ae_accelerator", () -> {
                BlockEntityType<AEAcceleratorBlockEntity> type = BlockEntityType.Builder
                        .of(AEAcceleratorBlockEntity::create, ModBlocks.AE_ACCELERATOR.get())
                        .build(null);
                // 关键：必须把每 tick 的 ticker 注入方块，否则原版区块 tick 循环不会调用
                // 方块实体的 serverTick()/clientTick()（即 commonTick() 永不执行，加速与
                // working 状态都不会更新）。AE2 官方方块在 AEBlockEntities 中自动注入，
                // 第三方方块必须手动注入。BLOCK registry 的注册事件先于 BLOCK_ENTITY_TYPE，
                // 因此此处 ModBlocks.AE_ACCELERATOR.get() 已可用。
                ModBlocks.AE_ACCELERATOR.get().setBlockEntity(
                        AEAcceleratorBlockEntity.class,
                        type,
                        (level, pos, state, entity) -> ((ClientTickingBlockEntity) entity).clientTick(),
                        (level, pos, state, entity) -> ((ServerTickingBlockEntity) entity).serverTick());
                // 启动日志：便于确认 ticker 已正确注入（启动后无此日志说明注册阶段出问题）。
                Torcherinoaemod.LOGGER.info("[INIT] AE 加速器 ticker 已注入 (clientTick + serverTick)。");
                return type;
            });
}
