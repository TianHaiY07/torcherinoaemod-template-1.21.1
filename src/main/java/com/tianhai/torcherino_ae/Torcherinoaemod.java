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
// 此处值必须与 META-INF/neoforge.mods.toml 中的 mod id 条目一致。
@Mod(Torcherinoaemod.MOD_ID)
public class Torcherinoaemod {
    // 集中定义 MOD_ID，供全项目各处统一引用。
    public static final String MOD_ID = "torcherino_ae_mod";
    // 直接引用 slf4j 日志器。
    public static final Logger LOGGER = LogUtils.getLogger();

    // 模组类构造器是模组加载后最先执行的代码；FML 会自动识别 IEventBus、ModContainer
    // 等参数类型并注入。
    public Torcherinoaemod(IEventBus modEventBus, ModContainer modContainer) {
        // 注册所有 DeferredRegister 容器到模组事件总线
        ModBlocks.BLOCKS.register(modEventBus);
        ModItems.ITEMS.register(modEventBus);
        ModDataComponents.DR.register(modEventBus);
        ModBlockEntities.BLOCK_ENTITY_TYPES.register(modEventBus);
        ModCreativeTabs.CREATIVE_MODE_TABS.register(modEventBus);
        ModMenus.MENUS.register(modEventBus);

        // 注册配置（默认值定义见 config/ConfigDefaults）。
        // 注意：registerConfig 只是「排队」，配置文件由 FML 在 mod 构造完成后统一加载，
        // 构造器内不可读取 ConfigValue（会抛 "Cannot get config value before config is loaded"）。
        // 因此这里不做 RuntimeConfig.refresh*——字段初值即 ConfigDefaults 默认值，配置真正
        // 加载/重载后由下方 ModConfigEvent.Loading / Reloading 处理器（applyConfig）刷新。
        modContainer.registerConfig(net.neoforged.fml.config.ModConfig.Type.SERVER, ModConfig.SERVER_SPEC);
        if (FMLEnvironment.dist.isClient()) {
            modContainer.registerConfig(net.neoforged.fml.config.ModConfig.Type.CLIENT, ModConfig.CLIENT_SPEC);
        }
        // 配置文件加载/重载时刷新快照（热更新，含 debug 开关与类型表重解析）。
        modEventBus.addListener(Torcherinoaemod::onConfigLoading);
        modEventBus.addListener(Torcherinoaemod::onConfigReloading);

        // 注册 commonSetup 阶段处理器（mod 加载阶段执行）。
        modEventBus.addListener(this::commonSetup);
        // 注册能力（capability）注册事件处理器。
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

        // 注意：ticker 已在 ModBlockEntities 注册阶段随方块实体注册一起注入（与 AE2 官方
        // 方块实体一致），此处不要再调用 AEBaseEntityBlock.setBlockEntity(...) 重复注入：
        // 其一，该方法的四参签名为 (class, type, clientTicker, serverTicker)，顺序极易传错，
        // 把 serverTicker 放到 clientTicker 位会导致服务端 ticker 为 null、区块 tick 循环
        // 不调用 commonTick()（加速与状态更新全部失效）；
        // 其二，FMLCommonSetupEvent 晚于 RegisterEvent，会覆盖注册阶段注入的 ticker。

        // 登记方块实体的代表物品（用于掉落/展示等）。
        AEBaseBlockEntity.registerBlockEntityItem(ModBlockEntities.AE_ACCELERATOR.get(), ModItems.AE_ACCELERATOR.get());

        LOGGER.info("Torcherino AE loaded.");
    }
}
