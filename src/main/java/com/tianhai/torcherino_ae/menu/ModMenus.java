package com.tianhai.torcherino_ae.menu;

import com.tianhai.torcherino_ae.Torcherinoaemod;
import com.tianhai.torcherino_ae.blockentity.AEAcceleratorBlockEntity;
import appeng.menu.implementations.MenuTypeBuilder;
import appeng.menu.implementations.UpgradeableMenu;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 菜单注册容器。
 * 使用 AE2 的 {@link MenuTypeBuilder} 构建升级卡机器的菜单类型，并注册到游戏注册表。
 */
public class ModMenus {
    // 菜单类型注册表，命名空间为本模组 modId。
    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(net.minecraft.core.registries.Registries.MENU, Torcherinoaemod.MOD_ID);

    // AE 加速器菜单类型。通过 buildUnregistered 创建后放入注册表，避免重复注册。
    public static final DeferredHolder<MenuType<?>, MenuType<AEAcceleratorMenu>> AE_ACCELERATOR =
            MENUS.register("ae_accelerator", () -> AEAcceleratorMenu.TYPE);
}
