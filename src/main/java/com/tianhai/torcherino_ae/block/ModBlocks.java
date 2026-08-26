package com.tianhai.torcherino_ae.block;

import com.tianhai.torcherino_ae.Torcherinoaemod;
import com.tianhai.torcherino_ae.item.ModBlockItem;
import com.tianhai.torcherino_ae.item.ModItems;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModBlocks {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(Torcherinoaemod.MOD_ID);

    public static final DeferredBlock<TorcherinoBlock> TORCHERINO = BLOCKS.register("torcherino",
            () -> new TorcherinoBlock(BlockBehaviour.Properties.of()
                    .instabreak()
                    .noCollission()
                    .noOcclusion()
                    .lightLevel(state -> 14)));

    public static final DeferredItem<ModBlockItem> TORCHERINO_ITEM =
            ModItems.ITEMS.register("torcherino",
                    () -> new ModBlockItem(TORCHERINO.get(), new Item.Properties(), "block.torcherino_ae_mod.torcherino.tooltip"));
}