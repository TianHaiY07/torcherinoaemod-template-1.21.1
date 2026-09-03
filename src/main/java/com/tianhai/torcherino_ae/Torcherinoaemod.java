package com.tianhai.torcherino_ae;

import com.mojang.logging.LogUtils;
import com.tianhai.torcherino_ae.block.ModBlocks;
import com.tianhai.torcherino_ae.block.AEAcceleratorBlock;
import com.tianhai.torcherino_ae.blockentity.AEAcceleratorBlockEntity;
import com.tianhai.torcherino_ae.blockentity.ModBlockEntities;
import com.tianhai.torcherino_ae.config.ModConfig;
import com.tianhai.torcherino_ae.config.RuntimeConfig;
import com.tianhai.torcherino_ae.item.ModCreativeTabs;
import com.tianhai.torcherino_ae.item.ModDataComponents;
import com.tianhai.torcherino_ae.item.ModItems;
import com.tianhai.torcherino_ae.menu.ModMenus;
import com.tianhai.torcherino_ae.util.DebugLog;
import appeng.api.AECapabilities;
import appeng.api.networking.IInWorldGridNodeHost;
import appeng.api.upgrades.Upgrades;
import appeng.blockentity.AEBaseBlockEntity;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.api.distmarker.Dist;
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
    public Torcherinoaemod(IEventBus modEventBus, ModContainer modContainer) {
        // 注册所有 DeferredRegister 容器到模组事件总线
        ModBlocks.BLOCKS.register(modEventBus);
        ModItems.ITEMS.register(modEventBus);
        ModDataComponents.DR.register(modEventBus);
        ModBlockEntities.BLOCK_ENTITY_TYPES.register(modEventBus);
        ModCreativeTabs.CREATIVE_MODE_TABS.register(modEventBus);
        ModMenus.MENUS.register(modEventBus);

        // 注册配置：默认值 = 现网行为基线（见 config/ConfigDefaults）。
        // 注意：registerConfig 只是「排队」，配置文件由 FML 在 mod 构造完成后统一加载，
        // 构造器内不可读取 ConfigValue（会抛 "Cannot get config value before config is loaded"）。
        // 因此这里不做 RuntimeConfig.refresh*——字段初值即 ConfigDefaults 基线，配置真正
        // 加载/重载后由下方 ModConfigEvent.Loading / Reloading 处理器（applyConfig）刷新。
        modContainer.registerConfig(net.neoforged.fml.config.ModConfig.Type.SERVER, ModConfig.SERVER_SPEC);
        if (FMLEnvironment.dist.isClient()) {
            modContainer.registerConfig(net.neoforged.fml.config.ModConfig.Type.CLIENT, ModConfig.CLIENT_SPEC);
        }
        // 配置文件加载/重载时刷新快照（热更新，含 debug 开关与类型表重解析）。
        modEventBus.addListener(Torcherinoaemod::onConfigLoading);
        modEventBus.addListener(Torcherinoaemod::onConfigReloading);

        // Register the commonSetup method for modloading
        modEventBus.addListener(this::commonSetup);
        // Register the capability listener for modloading
        modEventBus.addListener(Torcherinoaemod::registerCapabilities);
    }

    /**
     * 配置文件加载完成后：把生效值刷入 {@link RuntimeConfig} 快照。
     * <p>
     * 诊断日志门面（{@link DebugLog}）的开关跟随服务端配置段 {@code debug.enabled}，
     * 默认关闭；改配置后无需重启即可即时启用/停用。
     */
    private static void onConfigLoading(ModConfigEvent.Loading event) {
        applyConfig(event);
    }

    /** 配置文件重载后：同上（便于 /reload 即时热更新数值）。 */
    private static void onConfigReloading(ModConfigEvent.Reloading event) {
        applyConfig(event);
    }

    private static void applyConfig(ModConfigEvent event) {
        if (event.getConfig().getSpec() == ModConfig.SERVER_SPEC) {
            RuntimeConfig.refreshServer(ModConfig.SERVER);
            DebugLog.setEnabled(RuntimeConfig.debugEnabled());
        } else if (event.getConfig().getSpec() == ModConfig.CLIENT_SPEC) {
            RuntimeConfig.refreshClient(ModConfig.CLIENT);
        }
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
        // 声明该机器支持的升级卡类型：本模组 3 张自定义升级卡（每张可重复插入，最多占用整个升级卡槽）。
        // 注意参数顺序：AE2 的 Upgrades.add 签名为 (升级卡, 机器, 数量)——第一个参数是升级卡项，
        // 第二个是承载升级卡的机器（方块/部件）项。顺序颠倒会导致关联表按「机器项」作键存储，
        // 升级卡库存校验时 getMaxInstalled(升级卡) 返回 0，卡片将无法放入插槽。
        Upgrades.add(ModItems.ACCELERATOR_UPGRADE_CARD_I.get(), ModBlocks.AE_ACCELERATOR.get(),
                AEAcceleratorBlockEntity.UPGRADE_SLOTS);
        Upgrades.add(ModItems.ACCELERATOR_UPGRADE_CARD_II.get(), ModBlocks.AE_ACCELERATOR.get(),
                AEAcceleratorBlockEntity.UPGRADE_SLOTS);
        Upgrades.add(ModItems.ACCELERATOR_UPGRADE_CARD_III.get(), ModBlocks.AE_ACCELERATOR.get(),
                AEAcceleratorBlockEntity.UPGRADE_SLOTS);

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
