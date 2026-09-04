package com.tianhai.torcherino_ae.item;

import java.util.List;

import appeng.items.materials.UpgradeCardItem;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item.TooltipContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

/**
 * AE 加速器升级卡物品。
 * <p>
 * 插入加速器后放大基础加速倍数：各档<b>第一张</b>按卡片记录的标称倍增系数生效
 * （升级卡 I 最高 ×2、升级卡 II 最高 ×4、升级卡 III 最高 ×8）；同一档重复插入时
 * 边际收益按配置递减（见 {@link MultiplierCalculator}），避免同档堆叠造成倍率指数爆炸。
 * <p>
 * 必须继承 AE2 的 {@link UpgradeCardItem}：AE2 的升级卡插槽
 * （{@code RestrictedInputSlot.PlacableItemType.UPGRADES}）在 mayPlace 校验时通过
 * {@code Upgrades.isUpgradeCardItem(stack)} 判定，后者硬编码检查
 * {@code stack.getItem() instanceof UpgradeCardItem}——不继承该类，卡片将永远无法放入插槽。
 * <p>
 * 「实际对基础倍数的放大」由加速器方块实体在
 * {@link com.tianhai.torcherino_ae.blockentity.AEAcceleratorBlockEntity#getAccelMultiplier()}
 * 中依据升级卡库存中各类卡片数量统一计算。
 */
public class AcceleratorUpgradeCardItem extends UpgradeCardItem {

    // 该档升级卡的标称倍增系数（I=2，II=4，III=8）：作为同档第一张时的全价放大倍率。
    private final int multiplier;

    public AcceleratorUpgradeCardItem(Properties properties, int multiplier) {
        super(properties);
        this.multiplier = multiplier;
    }

    /**
     * 该档升级卡的标称倍增系数（同档第一张全价生效，重复堆叠收益递减见
     * {@link com.tianhai.torcherino_ae.core.MultiplierCalculator}）。
     */
    public int getMultiplier() {
        return multiplier;
    }

    /**
     * 工具提示：先保留 AE2 升级卡自带文案（如「兼容升级」列表），再追加本卡的放大效果说明。
     */
    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        tooltip.add(Component.translatable(
                "item.torcherino_ae_mod.accelerator_upgrade_card.tooltip", multiplier).withStyle(ChatFormatting.GRAY));
    }
}
