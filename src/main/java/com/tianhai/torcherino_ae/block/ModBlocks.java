package com.tianhai.torcherino_ae.block;

import appeng.block.AEBaseBlock;
import com.tianhai.torcherino_ae.Torcherinoaemod;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 方块注册容器。
 * 集中管理本模组所有方块，采用 DeferredRegister / DeferredBlock 现代注册风格。
 */
public class ModBlocks {
    // 方块注册表，命名空间为本模组 modId。
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(Torcherinoaemod.MOD_ID);

    // AE 加速器方块：一块可插入升级卡的 AE2 机器。
    public static final DeferredBlock<AEAcceleratorBlock> AE_ACCELERATOR = BLOCKS.register("ae_accelerator",
            () -> new AEAcceleratorBlock(AEBaseBlock.metalProps()));

    // AE 加速火把：独立范围扫描的加速方块（Torcherino 式），无碰撞体积、无升级卡。
    public static final DeferredBlock<AETorcherinoBlock> AE_TORCHERINO = BLOCKS.register("ae_torcherino",
            () -> new AETorcherinoBlock(AEBaseBlock.metalProps()));
}
