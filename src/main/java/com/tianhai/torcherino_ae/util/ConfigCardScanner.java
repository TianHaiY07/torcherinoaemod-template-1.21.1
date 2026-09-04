package com.tianhai.torcherino_ae.util;

import java.util.function.Consumer;

import com.tianhai.torcherino_ae.item.ConfigCardData;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * 「在玩家背包物件栏里遍历加速器配置卡」的遍历辅助。
 * <p>
 * 配置卡绑定数据的联动清理（设备/合成 CPU 组被移除 -> 清绑定、撤销注入）需要在多处遍历
 * 在线玩家背包中的配置卡，这一「玩家集合 × 背包槽位 × 判定配置卡」三层循环在多处重复。
 * 本类收敛该遍历，各调用方只需提供玩家集合与对每张卡的动作。
 */
public final class ConfigCardScanner {

    private ConfigCardScanner() {
    }

    /**
     * 遍历给定玩家集合（如全部在线玩家）的背包物件栏（主物品栏 + 装备 + 副手），
     * 对每张<b>加速器配置卡</b>执行 {@code action}。非配置卡的物品直接跳过。
     *
     * @param players 玩家的可迭代集合（{@code server.getPlayerList().getPlayers()} 或
     *                {@code level.players()} 均可）
     * @param action  对每张配置卡执行的动作（只读引用，调用方自行决定是否改写物品）
     */
    public static void forEachConfigCardInInventories(Iterable<? extends Player> players,
            Consumer<ItemStack> action) {
        for (Player player : players) {
            var inventory = player.getInventory();
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                ItemStack stack = inventory.getItem(i);
                if (ConfigCardData.isConfigCard(stack)) {
                    action.accept(stack);
                }
            }
        }
    }
}
