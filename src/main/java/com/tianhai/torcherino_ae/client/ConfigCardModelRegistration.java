package com.tianhai.torcherino_ae.client;

import com.tianhai.torcherino_ae.Torcherinoaemod;
import com.tianhai.torcherino_ae.item.ConfigCardData;
import com.tianhai.torcherino_ae.item.ModItems;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

/**
 * 加速器配置卡「已绑定加速器」贴图切换的客户端注册。
 * <p>
 * 物品模型属性（ItemProperty）是纯客户端机制，需在客户端初始化阶段、模型烘焙之前完成注册，
 * 因此挂在 {@link FMLClientSetupEvent}（mod 事件总线）上：
 * <ul>
 *   <li>注册 {@code torcherino_ae_mod:bound} 谓词：卡片绑定过加速器时返回 1.0F，否则 0.0F；</li>
 *   <li>{@code models/item/accelerator_config_card.json} 中的 overrides 依据该值把贴图
 *       从普通卡片切换到 {@code accelerator_config_card_work}（绑定态动态贴图）。</li>
 * </ul>
 * 绑定数据以 Data Component 形式随 ItemStack 同步，客户端可直接读取判定，无需额外发包。
 */
@EventBusSubscriber(modid = Torcherinoaemod.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ConfigCardModelRegistration {

    // 物品模型属性键，须与模型 JSON 中 overrides 的 predicate 键完全一致。
    private static final ResourceLocation BOUND = ResourceLocation.fromNamespaceAndPath(Torcherinoaemod.MOD_ID, "bound");

    private ConfigCardModelRegistration() {
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        // 卡片是否已绑定加速器（写入过加速器坐标）作为贴图切换依据。
        event.enqueueWork(() -> ItemProperties.register(
                ModItems.ACCELERATOR_CONFIG_CARD.get(), BOUND,
                (stack, level, entity, seed) ->
                        ConfigCardData.getBoundAccelerator(stack) != null ? 1.0F : 0.0F));
    }
}
