package com.tianhai.torcherino_ae;

import com.tianhai.torcherino_ae.blockentity.ModBlockEntities;
import com.tianhai.torcherino_ae.client.ModScreens;
import com.tianhai.torcherino_ae.client.render.AEAcceleratorRenderer;
import com.tianhai.torcherino_ae.client.screen.AEAcceleratorScreen;
import com.tianhai.torcherino_ae.client.screen.AETorcherinoScreen;
import com.tianhai.torcherino_ae.menu.AEAcceleratorMenu;
import com.tianhai.torcherino_ae.menu.AETorcherinoMenu;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.bus.api.SubscribeEvent;

/**
 * 客户端初始化：注册 AE 加速器/加速火把方块的界面屏幕，以及「接电发光 + 炫彩流光」方块实体渲染器。
 */
@EventBusSubscriber(modid = Torcherinoaemod.MOD_ID, value = Dist.CLIENT)
public class TorcherinoaemodClient {

    @SubscribeEvent
    public static void registerScreens(RegisterMenuScreensEvent event) {
        // 使用本模组自定义样式：generatedBackground 直接复用 AE2 的 guis/background.png
        // 生成无物品栏的纯背景，并通过样式 JSON 的 upgrades widget 在界面右侧放置升级卡插槽。
        // 样式文件在本模组命名空间下，需用自定义加载器读取（StyleManager 固定读 ae2 命名空间）。
        event.register(AEAcceleratorMenu.TYPE,
                (AEAcceleratorMenu menu, Inventory playerInventory, Component title) -> new AEAcceleratorScreen(
                        menu, playerInventory, title,
                        ModScreens.loadStyleDoc("/screens/ae_accelerator.json")));

        // AE 加速火把界面：背景由 AE2 内部 guis/background.png 九宫格平铺生成，
        // 滑块视觉复用 device_entry_gui.png 的轨道槽与手柄素材。
        event.register(AETorcherinoMenu.TYPE,
                (AETorcherinoMenu menu, Inventory playerInventory, Component title) -> new AETorcherinoScreen(
                        menu, playerInventory, title,
                        ModScreens.loadStyleDoc("/screens/ae_torcherino.json")));
    }

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        // 为 AE 加速器注册自定义方块实体渲染器：叠加发光带并生成炫彩流光粒子。
        event.registerBlockEntityRenderer(ModBlockEntities.AE_ACCELERATOR.get(), AEAcceleratorRenderer::new);
    }

    @SubscribeEvent
    public static void registerAdditionalModels(ModelEvent.RegisterAdditional event) {
        // 发光带模型未出现在任何方块状态里，必须单独注册才能被模型管理器加载。
        event.register(AEAcceleratorRenderer.LIGHTS_MODEL);
    }
}
