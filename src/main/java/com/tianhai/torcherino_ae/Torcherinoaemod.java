package com.tianhai.torcherino_ae;

import com.tianhai.torcherino_ae.block.ModBlocks;
import com.tianhai.torcherino_ae.block.entity.ModBlockEntityTypes;
import com.tianhai.torcherino_ae.item.ModCreativeTabs;
import com.tianhai.torcherino_ae.item.ModItems;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;

// The value here should match an entry in the META-INF/neoforge.mods.toml file
@Mod(Torcherinoaemod.MOD_ID)
public class Torcherinoaemod {
    // Define mod id in a common place for everything to reference
    public static final String MOD_ID = "torcherino_ae_mod";
    // Directly reference a slf4j logger
    public static final Logger LOGGER = LogUtils.getLogger();
    // The constructor for the mod class is the first code that is run when your mod is loaded.
    // FML will recognize some parameter types like IEventBus or ModContainer and pass them in automatically.
    public Torcherinoaemod(IEventBus modEventBus, ModContainer modContainer) {
        // Register the commonSetup method for modloading
        modEventBus.addListener(this::commonSetup);


        // Register our DeferredRegisters for blocks, items, block entities and creative tab
        ModBlocks.BLOCKS.register(modEventBus);
        ModBlockEntityTypes.BLOCK_ENTITY_TYPES.register(modEventBus);
        ModItems.ITEMS.register(modEventBus);
        ModCreativeTabs.CREATIVE_TABS.register(modEventBus);

        // Register our mod's ModConfigSpec so that FML can create and load the config file for us
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("{} loaded as an Applied Energistics 2 addon.", MOD_ID);
        LOGGER.info("Acceleration torch defaults: speed={}, range(x/y/z)={}/{}/{}, active={}",
                Config.DEFAULT_SPEED.get(), Config.DEFAULT_RANGE_X.get(), Config.DEFAULT_RANGE_Y.get(),
                Config.DEFAULT_RANGE_Z.get(), Config.DEFAULT_ACTIVE.get());
    }
}
