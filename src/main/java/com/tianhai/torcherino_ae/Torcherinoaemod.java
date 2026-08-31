package com.tianhai.torcherino_ae;

import com.mojang.logging.LogUtils;
import com.tianhai.torcherino_ae.block.ModBlocks;
import com.tianhai.torcherino_ae.block.AEAcceleratorBlock;
import com.tianhai.torcherino_ae.blockentity.AEAcceleratorBlockEntity;
import com.tianhai.torcherino_ae.blockentity.ModBlockEntities;
import com.tianhai.torcherino_ae.item.ModCreativeTabs;
import com.tianhai.torcherino_ae.item.ModDataComponents;
import com.tianhai.torcherino_ae.item.ModItems;
import com.tianhai.torcherino_ae.menu.ModMenus;
import appeng.api.AECapabilities;
import appeng.api.networking.IInWorldGridNodeHost;
import appeng.api.upgrades.Upgrades;
import appeng.blockentity.AEBaseBlockEntity;
import appeng.core.definitions.AEItems;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import org.slf4j.Logger;

/**
 * 模组主入口。
 * 负责注册所有的 DeferredRegister 容器，并在 commonSetup 阶段完成方块实体与方块、
 * 升级卡关联等运行时初始化。
 */
// The value here should match an entry in the META-INF/neoforge.mods.toml file
@Mod(Torcherinoaemod.MOD_ID)
public class Torcherinoaemod {
    // Define mod id in a common place for everything to reference
    public static final String MOD_ID = "torcherino_ae_mod";
    // Directly reference a slf4j logger
    public static final Logger LOGGER = LogUtils.getLogger();

    // The constructor for the mod class is the first code that is run when your mod is loaded.
    // FML will recognize some parameter types like IEventBus or ModContainer and pass them in automatically.
    public Torcherinoaemod(IEventBus modEventBus) {
        // 注册所有 DeferredRegister 容器到模组事件总线
        ModBlocks.BLOCKS.register(modEventBus);
        ModItems.ITEMS.register(modEventBus);
        ModDataComponents.DR.register(modEventBus);
        ModBlockEntities.BLOCK_ENTITY_TYPES.register(modEventBus);
        ModCreativeTabs.CREATIVE_MODE_TABS.register(modEventBus);
        ModMenus.MENUS.register(modEventBus);

        // Register the commonSetup method for modloading
        modEventBus.addListener(this::commonSetup);
        // Register the capability listener for modloading
        modEventBus.addListener(Torcherinoaemod::registerCapabilities);
    }

    /**
     * 为 AE 加速器方块实体注册「世界内网格节点宿主」能力（capability）。
     * <p>
     * AE2 只会为其自身的方块实体注册 {@link IInWorldGridNodeHost} 能力，因此自定义方块必须自行注册，
     * 否则 AE 线缆无法发现该方块并接入网络。
     */
    private static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(AECapabilities.IN_WORLD_GRID_NODE_HOST, ModBlockEntities.AE_ACCELERATOR.get(),
                (blockEntity, unused) -> (IInWorldGridNodeHost) blockEntity);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        // 声明该机器支持的升级卡类型：AE2 的「速度升级卡」，最多 4 张。
        Upgrades.add(ModBlocks.AE_ACCELERATOR.get(), AEItems.SPEED_CARD.get(), AEAcceleratorBlockEntity.UPGRADE_SLOTS);

        // 注意：ticker 已在 ModBlockEntities 注册阶段注入（与 AE2 官方方块一致）。
        // 不在此处再次调用 setBlockEntity(...) —— 之前在 commonSetup 里手动注入时
        // 由于 AEBaseEntityBlock.setBlockEntity 的签名为 (class, type, clientTicker, serverTicker)，
        // 误把 serverTicker 传到了 clientTicker 位置导致服务端 ticker 为 null，
        // 原版区块 tick 循环因而不会调用 commonTick()，加速从未生效。
        // （FMLCommonSetupEvent 晚于 RegisterEvent 阶段，会覆盖注册阶段注入的 ticker。）

        // 登记方块实体的代表物品（用于掉落/展示等）。
        AEBaseBlockEntity.registerBlockEntityItem(ModBlockEntities.AE_ACCELERATOR.get(), ModItems.AE_ACCELERATOR.get());

        LOGGER.info("Torcherino AE loaded.");
    }
}
