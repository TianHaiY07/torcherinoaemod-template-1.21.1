package com.tianhai.torcherino_ae.item;

import com.tianhai.torcherino_ae.Torcherinoaemod;
import com.tianhai.torcherino_ae.block.ModBlocks;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModCreativeTabs {
    public static final DeferredRegister<CreativeModeTab> CREATIVE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, Torcherinoaemod.MOD_ID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TORCHERINO_AE_TAB =
            CREATIVE_TABS.register("torcherino_ae_tab", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.torcherino_ae_mod"))
                    .icon(() -> new ItemStack(ModBlocks.TORCHERINO_ITEM.get()))
                    .displayItems((parameters, output) -> {
                        output.accept(ModBlocks.TORCHERINO_ITEM.get());
                    })
                    .build());
}