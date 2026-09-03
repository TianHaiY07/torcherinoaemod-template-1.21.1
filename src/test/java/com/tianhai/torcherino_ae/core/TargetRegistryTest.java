package com.tianhai.torcherino_ae.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

import com.tianhai.torcherino_ae.api.AccelSource;
import com.tianhai.torcherino_ae.api.DeviceId;

import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

/**
 * 加速目标登记表 {@link TargetRegistry} 的纯逻辑单测。
 * <p>
 * 覆盖核心语义：按来源精确撤销（配置卡取出不误伤玩家手动设置）、来源覆盖优先级、
 * 倍数 <=1 视为取消、集合视图一致性，以及经 NBT 的存档往返（save/load）。
 * 存档往返使用 {@link RegistryAccess#EMPTY}（本模组 CODEC 不依赖具体注册表内容）。
 */
class TargetRegistryTest {

    private static final ResourceKey<Level> DIM = ResourceKey.create(Registries.DIMENSION,
            ResourceLocation.parse("minecraft:overworld"));

    private static DeviceId block(int x, int y, int z) {
        return DeviceId.ofBlock(DIM, new BlockPos(x, y, z));
    }

    @Test
    void 设置后查询与计数正确() {
        TargetRegistry registry = new TargetRegistry();
        DeviceId id = block(1, 2, 3);
        registry.set(id, 8, AccelSource.PLAYER);

        assertTrue(registry.isAccelerated(id));
        assertEquals(8, registry.multiplierFor(id, -1));
        assertEquals(1, registry.size());
        assertTrue(registry.ids().contains(id));
        assertEquals(Set.of(id), registry.idsOfSource(AccelSource.PLAYER));
    }

    @Test
    void 未登记设备返回fallback() {
        TargetRegistry registry = new TargetRegistry();
        assertEquals(-1, registry.multiplierFor(block(9, 9, 9), -1));
        assertEquals(0, registry.multiplierFor(block(9, 9, 9), 0));
        assertFalse(registry.isAccelerated(block(9, 9, 9)));
        assertTrue(registry.isEmpty());
    }

    @Test
    void 倍数小于等于1视为取消登记() {
        TargetRegistry registry = new TargetRegistry();
        DeviceId id = block(0, 0, 0);
        registry.set(id, 4, AccelSource.PLAYER);
        registry.set(id, 1, AccelSource.PLAYER);
        assertFalse(registry.isAccelerated(id));
        assertTrue(registry.isEmpty());
    }

    @Test
    void 按来源撤销只清理该来源() {
        TargetRegistry registry = new TargetRegistry();
        DeviceId playerDevice = block(1, 0, 0);
        DeviceId cardDevice = block(2, 0, 0);
        registry.set(playerDevice, 4, AccelSource.PLAYER);
        registry.set(cardDevice, 8, AccelSource.CONFIG_CARD);

        registry.clearBySource(AccelSource.CONFIG_CARD);

        // 玩家手动设置的设备保持原样（配置卡取出不误伤玩家）。
        assertTrue(registry.isAccelerated(playerDevice));
        assertFalse(registry.isAccelerated(cardDevice));
        assertEquals(1, registry.size());
        assertTrue(registry.idsOfSource(AccelSource.PLAYER).contains(playerDevice));
        assertTrue(registry.idsOfSource(AccelSource.CONFIG_CARD).isEmpty());
    }

    @Test
    void 玩家显式设置覆盖卡来源后卡撤销不影响() {
        // 契约：「玩家 GUI 显式设置优先」。卡注入之后玩家再次勾选同一设备，
        // 该记录来源被覆盖为 PLAYER；此时取出配置卡不应撤销它。
        TargetRegistry registry = new TargetRegistry();
        DeviceId id = block(3, 3, 3);
        registry.set(id, 8, AccelSource.CONFIG_CARD);
        registry.set(id, 4, AccelSource.PLAYER);

        registry.clearBySource(AccelSource.CONFIG_CARD);

        assertTrue(registry.isAccelerated(id));
        assertEquals(4, registry.multiplierFor(id, -1));
    }

    @Test
    void remove与clear按预期工作() {
        TargetRegistry registry = new TargetRegistry();
        DeviceId a = block(1, 0, 0);
        DeviceId b = block(2, 0, 0);
        registry.set(a, 4, AccelSource.PLAYER);
        registry.set(b, 4, AccelSource.CONFIG_CARD);

        registry.remove(a);
        assertFalse(registry.isAccelerated(a));
        assertEquals(1, registry.size());

        registry.clear();
        assertTrue(registry.isEmpty());
        assertFalse(registry.isAccelerated(b));
    }

    @Test
    void nbt存档往返保持设置不变() {
        TargetRegistry original = new TargetRegistry();
        original.set(block(1, 0, 0), 4, AccelSource.PLAYER);
        original.set(block(2, 0, 0), 16, AccelSource.CONFIG_CARD);
        original.set(block(3, 0, 0), 2, AccelSource.PLAYER);

        CompoundTag tag = new CompoundTag();
        original.save(tag, RegistryAccess.EMPTY);

        TargetRegistry loaded = new TargetRegistry();
        loaded.load(tag, RegistryAccess.EMPTY);

        assertEquals(original.size(), loaded.size());
        assertEquals(4, loaded.multiplierFor(block(1, 0, 0), -1));
        assertEquals(16, loaded.multiplierFor(block(2, 0, 0), -1));
        assertEquals(2, loaded.multiplierFor(block(3, 0, 0), -1));
        assertEquals(Set.of(block(2, 0, 0)), loaded.idsOfSource(AccelSource.CONFIG_CARD));
    }

    @Test
    void 无targets标签时加载为空() {
        TargetRegistry registry = new TargetRegistry();
        registry.load(new CompoundTag(), RegistryAccess.EMPTY);
        assertTrue(registry.isEmpty());
    }

    @Test
    void 变更操作使版本号单调递增() {
        // 版本号供菜单判断设备列表缓存是否失效：登记表任何变更都必须让版本前进。
        TargetRegistry registry = new TargetRegistry();
        int initial = registry.version();
        registry.set(block(1, 0, 0), 4, AccelSource.PLAYER);
        assertTrue(registry.version() > initial);

        int afterSet = registry.version();
        registry.remove(block(1, 0, 0));
        registry.clearBySource(AccelSource.PLAYER);
        registry.clear();
        assertTrue(registry.version() > afterSet);
    }
}
