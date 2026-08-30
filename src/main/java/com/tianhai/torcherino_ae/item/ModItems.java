package com.tianhai.torcherino_ae.item;

import com.tianhai.torcherino_ae.Torcherinoaemod;
import com.tianhai.torcherino_ae.block.ModBlocks;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 物品注册容器。
 * 集中管理本模组所有物品，含方块的 BlockItem。
 */
public class ModItems {
    // 物品注册表，命名空间为本模组 modId。
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Torcherinoaemod.MOD_ID);

    // AE 加速器方块的物品形态。
    public static final DeferredItem<BlockItem> AE_ACCELERATOR = ITEMS.register("ae_accelerator",
            () -> new BlockItem(ModBlocks.AE_ACCELERATOR.get(), new Item.Properties()));
}
