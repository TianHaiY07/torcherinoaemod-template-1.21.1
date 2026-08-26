package com.tianhai.torcherino_ae;

import net.neoforged.neoforge.common.ModConfigSpec;

// 加速火把的通用配置，会在首次加载时生成配置文件。
public class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    // 服务器与客户端都应保持相同的配置，因为它们不会被自动同步。
    public static final ModConfigSpec.IntValue DEFAULT_RANGE_X = BUILDER
            .comment("Default horizontal range (X) of the newly placed acceleration torch, in blocks.")
            .defineInRange("defaultRangeX", 3, 0, 64);

    public static final ModConfigSpec.IntValue DEFAULT_RANGE_Z = BUILDER
            .comment("Default horizontal range (Z) of the newly placed acceleration torch, in blocks.")
            .defineInRange("defaultRangeZ", 3, 0, 64);

    public static final ModConfigSpec.IntValue DEFAULT_RANGE_Y = BUILDER
            .comment("Default vertical range (Y) of the newly placed acceleration torch, in blocks.")
            .defineInRange("defaultRangeY", 3, 0, 64);

    public static final ModConfigSpec.IntValue DEFAULT_SPEED = BUILDER
            .comment("Default acceleration multiplier: how many times per tick AE block entities are ticked.")
            .defineInRange("defaultSpeed", 4, 1, 64);

    public static final ModConfigSpec.BooleanValue DEFAULT_ACTIVE = BUILDER
            .comment("Whether a newly placed acceleration torch is active by default. "
                    + "Right-click a torch to toggle it, shift+right-click to cycle its speed.")
            .define("defaultActive", true);

    static final ModConfigSpec SPEC = BUILDER.build();
}