package com.tianhai.torcherino_ae.block;

import appeng.block.AEBaseBlock;
import com.tianhai.torcherino_ae.Torcherinoaemod;
import com.tianhai.torcherino_ae.blockentity.AETorcherinoBlockEntity;
import com.tianhai.torcherino_ae.blockentity.AETorcherinoTier1BlockEntity;
import com.tianhai.torcherino_ae.blockentity.AETorcherinoTier2BlockEntity;
import com.tianhai.torcherino_ae.blockentity.ModBlockEntities;
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
    // 方块实体类型与工厂以 Supplier 形式传入，避免在静态初始化阶段解析 ModBlockEntities
    // 的 DeferredHolder（两个 DeferredRegister 的注册事件存在先后顺序）。
    public static final DeferredBlock<AETorcherinoBlock> AE_TORCHERINO = BLOCKS.register("ae_torcherino",
            () -> new AETorcherinoBlock(AEBaseBlock.metalProps(),
                    ModBlockEntities.AE_TORCHERINO::get, AETorcherinoBlockEntity::create));

    // AE 加速火把 I：与基础火把行为一致，倍率上限固定为 64x（见 AETorcherinoTier1BlockEntity）。
    public static final DeferredBlock<AETorcherinoBlock> AE_TORCHERINO_I = BLOCKS.register("ae_torcherino_i",
            () -> new AETorcherinoBlock(AEBaseBlock.metalProps(),
                    ModBlockEntities.AE_TORCHERINO_TIER_I::get, AETorcherinoTier1BlockEntity::create));

    // AE 加速火把 II：与基础火把行为一致，倍率上限固定为 324x（见 AETorcherinoTier2BlockEntity）。
    public static final DeferredBlock<AETorcherinoBlock> AE_TORCHERINO_II = BLOCKS.register("ae_torcherino_ii",
            () -> new AETorcherinoBlock(AEBaseBlock.metalProps(),
                    ModBlockEntities.AE_TORCHERINO_TIER_II::get, AETorcherinoTier2BlockEntity::create));
}
