package com.tianhai.torcherino_ae.item;

import java.util.function.Consumer;

import com.tianhai.torcherino_ae.Torcherinoaemod;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 物品 Data Component 注册容器。
 * 集中管理本模组物品自定义数据组件（仿 AE2 的组件注册方式），
 * 供 ItemStack 持久化/同步物品绑定数据。
 */
public final class ModDataComponents {

    // DataComponentType 注册表，命名空间为本模组 modId。
    public static final DeferredRegister<DataComponentType<?>> DR = DeferredRegister
            .create(Registries.DATA_COMPONENT_TYPE, Torcherinoaemod.MOD_ID);

    // 加速器配置卡的绑定数据（绑定的加速器坐标 + 设备标识列表）。
    public static final DataComponentType<ConfigCardData> CONFIG_CARD_DATA = register("config_card_data",
            builder -> builder.persistent(ConfigCardData.CODEC)
                    .networkSynchronized(ConfigCardData.STREAM_CODEC));

    private ModDataComponents() {
    }

    /**
     * 注册并返回自定义 DataComponentType（保留类型参数，便于直接用于 ItemStack 读写）。
     */
    private static <T> DataComponentType<T> register(String name, Consumer<DataComponentType.Builder<T>> customizer) {
        var builder = DataComponentType.<T>builder();
        customizer.accept(builder);
        var componentType = builder.build();
        DR.register(name, () -> componentType);
        return componentType;
    }
}
