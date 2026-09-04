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

    // AE 加速火把方块的物品形态。
    public static final DeferredItem<BlockItem> AE_TORCHERINO = ITEMS.register("ae_torcherino",
            () -> new BlockItem(ModBlocks.AE_TORCHERINO.get(), new Item.Properties()));

    // AE 加速火把 I 方块的物品形态（倍率上限 64x）。
    public static final DeferredItem<BlockItem> AE_TORCHERINO_I = ITEMS.register("ae_torcherino_i",
            () -> new BlockItem(ModBlocks.AE_TORCHERINO_I.get(), new Item.Properties()));

    // AE 加速火把 II 方块的物品形态（倍率上限 324x）。
    public static final DeferredItem<BlockItem> AE_TORCHERINO_II = ITEMS.register("ae_torcherino_ii",
            () -> new BlockItem(ModBlocks.AE_TORCHERINO_II.get(), new Item.Properties()));

    // 加速器配置卡：绑定加速器及网络内的加速目标设备，放入加速器后自动启用加速。
    public static final DeferredItem<AcceleratorConfigCardItem> ACCELERATOR_CONFIG_CARD = ITEMS.register("accelerator_config_card",
            () -> new AcceleratorConfigCardItem(new Item.Properties()));

    // 加速器升级卡 I：每插入一张，将基础加速倍数乘以 2（可重复插入）。
    public static final DeferredItem<AcceleratorUpgradeCardItem> ACCELERATOR_UPGRADE_CARD_I = ITEMS.register("ae_accelerator_up_card_i",
            () -> new AcceleratorUpgradeCardItem(new Item.Properties(), 2));

    // 加速器升级卡 II：每插入一张，将基础加速倍数乘以 4（可重复插入）。
    public static final DeferredItem<AcceleratorUpgradeCardItem> ACCELERATOR_UPGRADE_CARD_II = ITEMS.register("ae_accelerator_up_card_ii",
            () -> new AcceleratorUpgradeCardItem(new Item.Properties(), 4));

    // 加速器升级卡 III：每插入一张，将基础加速倍数乘以 8（可重复插入）。
    public static final DeferredItem<AcceleratorUpgradeCardItem> ACCELERATOR_UPGRADE_CARD_III = ITEMS.register("ae_accelerator_up_card_iii",
            () -> new AcceleratorUpgradeCardItem(new Item.Properties(), 8));
}
