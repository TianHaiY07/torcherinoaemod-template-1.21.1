package com.tianhai.torcherino_ae.item;

import com.tianhai.torcherino_ae.Torcherinoaemod;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 创造模式物品栏注册容器。
 */
public class ModCreativeTabs {
    // 创造栏注册表，命名空间为本模组 modId。
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, Torcherinoaemod.MOD_ID);

    // 模组专属创造栏，展示 AE 加速器。
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TORCHERINO_AE_TAB =
            CREATIVE_MODE_TABS.register("torcherino_ae",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup." + Torcherinoaemod.MOD_ID))
                    .icon(() -> new ItemStack(ModItems.AE_ACCELERATOR.get()))
                    .displayItems((parameters, output) -> {
                        output.accept(ModItems.AE_ACCELERATOR.get());
                        output.accept(ModItems.AE_TORCHERINO.get());
                        output.accept(ModItems.AE_TORCHERINO_I.get());
                        output.accept(ModItems.AE_TORCHERINO_II.get());
                        output.accept(ModItems.ACCELERATOR_CONFIG_CARD.get());
                        output.accept(ModItems.ACCELERATOR_UPGRADE_CARD_I.get());
                        output.accept(ModItems.ACCELERATOR_UPGRADE_CARD_II.get());
                        output.accept(ModItems.ACCELERATOR_UPGRADE_CARD_III.get());
                    })
                    .build());
}
