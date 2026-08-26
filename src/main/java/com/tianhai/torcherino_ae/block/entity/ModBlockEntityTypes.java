package com.tianhai.torcherino_ae.block.entity;

import com.tianhai.torcherino_ae.Torcherinoaemod;
import com.tianhai.torcherino_ae.block.ModBlocks;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModBlockEntityTypes {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, Torcherinoaemod.MOD_ID);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<TorcherinoBlockEntity>> TORCHERINO =
            BLOCK_ENTITY_TYPES.register("torcherino",
                    () -> BlockEntityType.Builder.of(TorcherinoBlockEntity::new, ModBlocks.TORCHERINO.get()).build(null));
}