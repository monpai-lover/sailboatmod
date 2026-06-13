package com.monpai.sailboatmod.roadplanner.map;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;

import java.util.HashMap;
import java.util.Map;

/**
 * 方块 → 真实 RGB 颜色表，向 Pl3xMap 看齐（中档：真方块色 + 草/叶/水固定美调色，不做生物群系温湿度插值）。
 *
 * <p>色值取自 Pl3xMap 的 {@code ColorsConfig.BLOCK_COLORS}（覆盖 1.20.1 全部方块 + 后续版本）。
 * 草地/树叶/水/雪等用本类的美调固定常量覆盖，避免 MapColor 的荧光绿质感。
 * 表中查不到、或被标记为不可见（0x000000）的方块回退到 vanilla {@link MapColor}，保证全覆盖无空洞。
 */
public final class MapBlockColors {
    // 草/叶/水/雪的美调固定色（替代 Pl3xMap 中靠生物群系插值的占位值）。
    private static final int GRASS = 0x7EA44D;       // 自然草绿，比 MapColor.GRASS 柔和
    private static final int FOLIAGE = 0x4A7A38;     // 树叶深绿
    private static final int SPRUCE_FOLIAGE = 0x4E7A4E;
    private static final int BIRCH_FOLIAGE = 0x668644;
    private static final int WATER = 0x3F76C4;       // 自然湖蓝（替代 0x0000FF 刺眼纯蓝）
    private static final int SNOW = 0xF4F8F8;

    private static final Map<String, Integer> COLORS = new HashMap<>(1100);

    static {
        // 草 / 树叶 / 水 / 雪：美调固定色
        put("minecraft:grass_block", GRASS);
        put("minecraft:moss_block", 0x596E2D);
        put("minecraft:moss_carpet", 0x596E2D);
        put("minecraft:oak_leaves", FOLIAGE);
        put("minecraft:dark_oak_leaves", 0x37510B);
        put("minecraft:jungle_leaves", 0x3C5E13);
        put("minecraft:acacia_leaves", 0x556B1B);
        put("minecraft:mangrove_leaves", FOLIAGE);
        put("minecraft:birch_leaves", BIRCH_FOLIAGE);
        put("minecraft:spruce_leaves", SPRUCE_FOLIAGE);
        put("minecraft:azalea_leaves", 0x647A30);
        put("minecraft:flowering_azalea_leaves", 0x6E8438);
        put("minecraft:water", WATER);
        put("minecraft:bubble_column", WATER);
        put("minecraft:snow", SNOW);
        put("minecraft:snow_block", SNOW);
        put("minecraft:powder_snow", SNOW);
        put("minecraft:lily_pad", 0x2A6B33);

        // —— 以下为 Pl3xMap 方块色表（已去除 0x000000 不可见项与上面已覆盖项） ——
        put("minecraft:acacia_door", 0xA85F3D);
        put("minecraft:acacia_fence", 0xA85A32);
        put("minecraft:acacia_fence_gate", 0xA85A32);
        put("minecraft:acacia_log", 0x676157);
        put("minecraft:acacia_planks", 0xA85A32);
        put("minecraft:acacia_slab", 0xA85A32);
        put("minecraft:acacia_stairs", 0xA85A32);
        put("minecraft:acacia_wood", 0x676157);
        put("minecraft:amethyst_block", 0x8662BF);
        put("minecraft:ancient_debris", 0x5E4139);
        put("minecraft:andesite", 0x888889);
        put("minecraft:andesite_slab", 0x888889);
        put("minecraft:andesite_stairs", 0x888889);
        put("minecraft:andesite_wall", 0x888889);
        put("minecraft:azalea", 0x667D30);
        put("minecraft:bamboo", 0x5D9013);
        put("minecraft:barrel", 0x87653B);
        put("minecraft:basalt", 0x515156);
        put("minecraft:bedrock", 0x565656);
        put("minecraft:bee_nest", 0xCEA44D);
        put("minecraft:beehive", 0xB4915A);
        put("minecraft:big_dripleaf", 0x729034);
        put("minecraft:birch_door", 0xE0D6B7);
        put("minecraft:birch_fence", 0xC0AF79);
        put("minecraft:birch_log", 0xDBDAD5);
        put("minecraft:birch_planks", 0xC0AF79);
        put("minecraft:birch_slab", 0xC0AF79);
        put("minecraft:birch_stairs", 0xC0AF79);
        put("minecraft:birch_wood", 0xDBDAD5);
        put("minecraft:black_concrete", 0x080A0F);
        put("minecraft:black_terracotta", 0x251710);
        put("minecraft:black_wool", 0x15151A);
        put("minecraft:blackstone", 0x2A242A);
        put("minecraft:blue_concrete", 0x2D2F8F);
        put("minecraft:blue_ice", 0x74A8FD);
        put("minecraft:blue_terracotta", 0x4A3C5B);
        put("minecraft:blue_wool", 0x35399D);
        put("minecraft:bone_block", 0xD0CCB1);
        put("minecraft:bookshelf", 0x735D3A);
        put("minecraft:bricks", 0x976153);
        put("minecraft:brick_slab", 0x976153);
        put("minecraft:brick_stairs", 0x976153);
        put("minecraft:brick_wall", 0x976153);
        put("minecraft:brown_concrete", 0x603C20);
        put("minecraft:brown_mushroom_block", 0x957051);
        put("minecraft:brown_terracotta", 0x4D3324);
        put("minecraft:brown_wool", 0x724829);
        put("minecraft:budding_amethyst", 0x8560BA);
        put("minecraft:cactus", 0x58822D);
        put("minecraft:calcite", 0xE0E1DD);
        put("minecraft:carved_pumpkin", 0x915111);
        put("minecraft:chiseled_deepslate", 0x373738);
        put("minecraft:chiseled_nether_bricks", 0x30181C);
        put("minecraft:chiseled_polished_blackstone", 0x363139);
        put("minecraft:chiseled_quartz_block", 0xE8E3DA);
        put("minecraft:chiseled_red_sandstone", 0xB7601B);
        put("minecraft:chiseled_sandstone", 0xD8CB9B);
        put("minecraft:chiseled_stone_bricks", 0x787778);
        put("minecraft:chorus_plant", 0x5D395D);
        put("minecraft:clay", 0xA1A6B3);
        put("minecraft:coal_block", 0x101010);
        put("minecraft:coal_ore", 0x686867);
        put("minecraft:coarse_dirt", 0x77553B);
        put("minecraft:cobbled_deepslate", 0x4D4D50);
        put("minecraft:cobbled_deepslate_slab", 0x4D4D50);
        put("minecraft:cobbled_deepslate_stairs", 0x4D4D50);
        put("minecraft:cobbled_deepslate_wall", 0x4D4D50);
        put("minecraft:cobblestone", 0x807F7F);
        put("minecraft:cobblestone_slab", 0x807F7F);
        put("minecraft:cobblestone_stairs", 0x807F7F);
        put("minecraft:cobblestone_wall", 0x807F7F);
        put("minecraft:copper_block", 0xC06C50);
        put("minecraft:copper_ore", 0x7D7D77);
        put("minecraft:cracked_deepslate_bricks", 0x414041);
        put("minecraft:cracked_deepslate_tiles", 0x353535);
        put("minecraft:cracked_nether_bricks", 0x281418);
        put("minecraft:cracked_stone_bricks", 0x767676);
        put("minecraft:crafting_table", 0x7B4B2B);
        put("minecraft:crimson_hyphae", 0x5D1A1E);
        put("minecraft:crimson_nylium", 0x832020);
        put("minecraft:crimson_planks", 0x653147);
        put("minecraft:crimson_stem", 0x5D1A1E);
        put("minecraft:crying_obsidian", 0x220A3F);
        put("minecraft:cut_copper", 0xBF6B51);
        put("minecraft:cut_red_sandstone", 0xBE6620);
        put("minecraft:cut_sandstone", 0xDACFA0);
        put("minecraft:cyan_concrete", 0x157788);
        put("minecraft:cyan_terracotta", 0x575B5B);
        put("minecraft:cyan_wool", 0x158A91);
        put("minecraft:dark_oak_door", 0x4C3319);
        put("minecraft:dark_oak_fence", 0x432B14);
        put("minecraft:dark_oak_log", 0x3C2F1A);
        put("minecraft:dark_oak_planks", 0x432B14);
        put("minecraft:dark_oak_slab", 0x432B14);
        put("minecraft:dark_oak_stairs", 0x432B14);
        put("minecraft:dark_oak_wood", 0x3C2F1A);
        put("minecraft:dark_prismarine", 0x345C4C);
        put("minecraft:dead_bush", 0x6D5029);
        put("minecraft:deepslate", 0x575759);
        put("minecraft:deepslate_bricks", 0x474747);
        put("minecraft:deepslate_brick_slab", 0x474747);
        put("minecraft:deepslate_brick_stairs", 0x474747);
        put("minecraft:deepslate_brick_wall", 0x474747);
        put("minecraft:deepslate_coal_ore", 0x49494B);
        put("minecraft:deepslate_copper_ore", 0x5D5E59);
        put("minecraft:deepslate_diamond_ore", 0x536D6E);
        put("minecraft:deepslate_emerald_ore", 0x4D6A57);
        put("minecraft:deepslate_gold_ore", 0x76684D);
        put("minecraft:deepslate_iron_ore", 0x6D6560);
        put("minecraft:deepslate_lapis_ore", 0x4F5B76);
        put("minecraft:deepslate_redstone_ore", 0x6B4849);
        put("minecraft:deepslate_tiles", 0x373738);
        put("minecraft:diamond_block", 0x65EFE5);
        put("minecraft:diamond_ore", 0x788F8F);
        put("minecraft:diorite", 0xBDBDBD);
        put("minecraft:diorite_slab", 0xBDBDBD);
        put("minecraft:diorite_stairs", 0xBDBDBD);
        put("minecraft:diorite_wall", 0xBDBDBD);
        put("minecraft:dirt", 0x866043);
        put("minecraft:dirt_path", 0x947A41);
        put("minecraft:dried_kelp_block", 0x343C28);
        put("minecraft:dripstone_block", 0x866B5C);
        put("minecraft:emerald_block", 0x2BCD5A);
        put("minecraft:emerald_ore", 0x6A8972);
        put("minecraft:end_stone", 0xDBDF9E);
        put("minecraft:end_stone_bricks", 0xDBE0A2);
        put("minecraft:exposed_copper", 0xA17E68);
        put("minecraft:exposed_cut_copper", 0x9B7A65);
        put("minecraft:farmland", 0x8E6646);
        put("minecraft:fletching_table", 0xC6B687);
        put("minecraft:furnace", 0x727171);
        put("minecraft:gilded_blackstone", 0x382B27);
        put("minecraft:glowstone", 0xAD8455);
        put("minecraft:gold_block", 0xF8D33E);
        put("minecraft:gold_ore", 0x938769);
        put("minecraft:granite", 0x956756);
        put("minecraft:granite_slab", 0x956756);
        put("minecraft:granite_stairs", 0x956756);
        put("minecraft:granite_wall", 0x956756);
        put("minecraft:gravel", 0x84807F);
        put("minecraft:gray_concrete", 0x373A3E);
        put("minecraft:gray_terracotta", 0x3A2A24);
        put("minecraft:gray_wool", 0x3F4448);
        put("minecraft:green_concrete", 0x495B24);
        put("minecraft:green_terracotta", 0x4C532A);
        put("minecraft:green_wool", 0x556E1B);
        put("minecraft:hay_block", 0xA68C0C);
        put("minecraft:honeycomb_block", 0xE5951E);
        put("minecraft:ice", 0x91B8FE);
        put("minecraft:iron_block", 0xDEDEDE);
        put("minecraft:iron_ore", 0x8A827B);
        put("minecraft:jungle_door", 0xA47854);
        put("minecraft:jungle_fence", 0xA17351);
        put("minecraft:jungle_log", 0x554419);
        put("minecraft:jungle_planks", 0xA17351);
        put("minecraft:jungle_slab", 0xA17351);
        put("minecraft:jungle_stairs", 0xA17351);
        put("minecraft:jungle_wood", 0x554419);
        put("minecraft:lapis_block", 0x1F438C);
        put("minecraft:lapis_ore", 0x68758F);
        put("minecraft:lava", 0xD45A12);
        put("minecraft:light_blue_concrete", 0x2489C7);
        put("minecraft:light_blue_terracotta", 0x716D8A);
        put("minecraft:light_blue_wool", 0x3AAFD9);
        put("minecraft:light_gray_concrete", 0x7D7D73);
        put("minecraft:light_gray_terracotta", 0x876B62);
        put("minecraft:light_gray_wool", 0x8E8E87);
        put("minecraft:lime_concrete", 0x5EA919);
        put("minecraft:lime_terracotta", 0x687635);
        put("minecraft:lime_wool", 0x70B91A);
        put("minecraft:magenta_concrete", 0xA9309F);
        put("minecraft:magenta_terracotta", 0x96586D);
        put("minecraft:magenta_wool", 0xBE45B4);
        put("minecraft:magma_block", 0x8E3F20);
        put("minecraft:mangrove_door", 0x70302E);
        put("minecraft:mangrove_fence", 0x763631);
        put("minecraft:mangrove_log", 0x544329);
        put("minecraft:mangrove_planks", 0x763631);
        put("minecraft:mangrove_roots", 0x4B3C27);
        put("minecraft:mangrove_slab", 0x763631);
        put("minecraft:mangrove_stairs", 0x763631);
        put("minecraft:mangrove_wood", 0x544329);
        put("minecraft:melon", 0x6D901E);
        put("minecraft:mossy_cobblestone", 0x6D765E);
        put("minecraft:mossy_cobblestone_slab", 0x6D765E);
        put("minecraft:mossy_cobblestone_stairs", 0x6D765E);
        put("minecraft:mossy_cobblestone_wall", 0x6D765E);
        put("minecraft:mossy_stone_bricks", 0x74796A);
        put("minecraft:mud", 0x3C3A3D);
        put("minecraft:mud_bricks", 0x89684F);
        put("minecraft:muddy_mangrove_roots", 0x463B2D);
        put("minecraft:mushroom_stem", 0xCBC4B9);
        put("minecraft:mycelium", 0x6F6365);
        put("minecraft:nether_bricks", 0x2C161A);
        put("minecraft:nether_gold_ore", 0x76392B);
        put("minecraft:nether_quartz_ore", 0x794642);
        put("minecraft:nether_wart_block", 0x730302);
        put("minecraft:netherite_block", 0x443F41);
        put("minecraft:netherrack", 0x622727);
        put("minecraft:oak_door", 0x8C6E41);
        put("minecraft:oak_fence", 0xA2834F);
        put("minecraft:oak_log", 0x6D5533);
        put("minecraft:oak_planks", 0xA2834F);
        put("minecraft:oak_slab", 0xA2834F);
        put("minecraft:oak_stairs", 0xA2834F);
        put("minecraft:oak_wood", 0x6D5533);
        put("minecraft:obsidian", 0x0F0B19);
        put("minecraft:orange_concrete", 0xE06101);
        put("minecraft:orange_terracotta", 0xA25426);
        put("minecraft:orange_wool", 0xF17613);
        put("minecraft:oxidized_copper", 0x53A486);
        put("minecraft:oxidized_cut_copper", 0x509A7F);
        put("minecraft:packed_ice", 0x8DB4FA);
        put("minecraft:packed_mud", 0x8F6B50);
        put("minecraft:pink_concrete", 0xD6658F);
        put("minecraft:pink_terracotta", 0xA24E4F);
        put("minecraft:pink_wool", 0xEE8DAC);
        put("minecraft:podzol", 0x5C3F18);
        put("minecraft:pointed_dripstone", 0x816659);
        put("minecraft:polished_andesite", 0x848786);
        put("minecraft:polished_basalt", 0x656466);
        put("minecraft:polished_blackstone", 0x353139);
        put("minecraft:polished_blackstone_bricks", 0x302B32);
        put("minecraft:polished_deepslate", 0x484849);
        put("minecraft:polished_diorite", 0xC3C3C5);
        put("minecraft:polished_granite", 0x9B6B59);
        put("minecraft:prismarine", 0x639C97);
        put("minecraft:prismarine_bricks", 0x63AC9F);
        put("minecraft:pumpkin", 0xC57618);
        put("minecraft:purple_concrete", 0x64209C);
        put("minecraft:purple_terracotta", 0x764656);
        put("minecraft:purple_wool", 0x7A2AAC);
        put("minecraft:purpur_block", 0xAA7EAA);
        put("minecraft:purpur_pillar", 0xAB7FAB);
        put("minecraft:quartz_block", 0xECE6DF);
        put("minecraft:quartz_bricks", 0xEBE5DE);
        put("minecraft:quartz_pillar", 0xECE6E0);
        put("minecraft:raw_copper_block", 0x9C6A4F);
        put("minecraft:raw_gold_block", 0xDDA92F);
        put("minecraft:raw_iron_block", 0xA6886B);
        put("minecraft:red_concrete", 0x8E2121);
        put("minecraft:red_mushroom_block", 0xC9302E);
        put("minecraft:red_nether_bricks", 0x460709);
        put("minecraft:red_sand", 0xBE6721);
        put("minecraft:red_sandstone", 0xB5621F);
        put("minecraft:red_sandstone_slab", 0xB5621F);
        put("minecraft:red_sandstone_stairs", 0xB5621F);
        put("minecraft:red_sandstone_wall", 0xB5621F);
        put("minecraft:red_terracotta", 0x8F3D2F);
        put("minecraft:red_wool", 0xA12723);
        put("minecraft:redstone_block", 0xA91705);
        put("minecraft:redstone_ore", 0x8E6C6C);
        put("minecraft:reinforced_deepslate", 0x4D4F4C);
        put("minecraft:rooted_dirt", 0x90674C);
        put("minecraft:sand", 0xDBCFA3);
        put("minecraft:sandstone", 0xE0D6AA);
        put("minecraft:sandstone_slab", 0xE0D6AA);
        put("minecraft:sandstone_stairs", 0xE0D6AA);
        put("minecraft:sandstone_wall", 0xE0D6AA);
        put("minecraft:sculk", 0x0D1E24);
        put("minecraft:sculk_catalyst", 0x0F2027);
        put("minecraft:sea_lantern", 0xB1CBC2);
        put("minecraft:shroomlight", 0xF2974C);
        put("minecraft:slime_block", 0x6FC05B);
        put("minecraft:smooth_basalt", 0x48484E);
        put("minecraft:smooth_quartz", 0xEDE6E0);
        put("minecraft:smooth_red_sandstone", 0xB5621F);
        put("minecraft:smooth_sandstone", 0xE0D6AA);
        put("minecraft:smooth_stone", 0xA1A1A1);
        put("minecraft:smooth_stone_slab", 0xA1A1A1);
        put("minecraft:soul_sand", 0x523E33);
        put("minecraft:soul_soil", 0x4C3A2F);
        put("minecraft:sponge", 0xC4C14B);
        put("minecraft:spruce_door", 0x6A5030);
        put("minecraft:spruce_fence", 0x735531);
        put("minecraft:spruce_log", 0x3B2611);
        put("minecraft:spruce_planks", 0x735531);
        put("minecraft:spruce_slab", 0x735531);
        put("minecraft:spruce_stairs", 0x735531);
        put("minecraft:spruce_wood", 0x3B2611);
        put("minecraft:stone", 0x7E7E7E);
        put("minecraft:stone_bricks", 0x7A7A7A);
        put("minecraft:stone_brick_slab", 0x7A7A7A);
        put("minecraft:stone_brick_stairs", 0x7A7A7A);
        put("minecraft:stone_brick_wall", 0x7A7A7A);
        put("minecraft:stone_slab", 0x7E7E7E);
        put("minecraft:stone_stairs", 0x7E7E7E);
        put("minecraft:stripped_acacia_log", 0xB05D3C);
        put("minecraft:stripped_acacia_wood", 0xB05D3C);
        put("minecraft:stripped_birch_log", 0xC6B177);
        put("minecraft:stripped_birch_wood", 0xC6B177);
        put("minecraft:stripped_crimson_stem", 0x8A3A5B);
        put("minecraft:stripped_dark_oak_log", 0x493924);
        put("minecraft:stripped_dark_oak_wood", 0x493924);
        put("minecraft:stripped_jungle_log", 0xAC8555);
        put("minecraft:stripped_jungle_wood", 0xAC8555);
        put("minecraft:stripped_mangrove_log", 0x783730);
        put("minecraft:stripped_mangrove_wood", 0x783730);
        put("minecraft:stripped_oak_log", 0xB39157);
        put("minecraft:stripped_oak_wood", 0xB39157);
        put("minecraft:stripped_spruce_log", 0x745A35);
        put("minecraft:stripped_spruce_wood", 0x745A35);
        put("minecraft:stripped_warped_stem", 0x3A9895);
        put("minecraft:sugar_cane", 0x95C165);
        put("minecraft:suspicious_gravel", 0x837F7E);
        put("minecraft:suspicious_sand", 0xD9CDA1);
        put("minecraft:sweet_berry_bush", 0x305E3A);
        put("minecraft:terracotta", 0x985E44);
        put("minecraft:tnt", 0x873D36);
        put("minecraft:tuff", 0x6C6D67);
        put("minecraft:warped_hyphae", 0x3A3B4E);
        put("minecraft:warped_nylium", 0x2B7365);
        put("minecraft:warped_planks", 0x2B6963);
        put("minecraft:warped_stem", 0x3A3B4E);
        put("minecraft:warped_wart_block", 0x177879);
        put("minecraft:waxed_copper_block", 0xC06C50);
        put("minecraft:waxed_oxidized_copper", 0x53A486);
        put("minecraft:weathered_copper", 0x6C9A6F);
        put("minecraft:weathered_cut_copper", 0x6D916B);
        put("minecraft:wet_sponge", 0xAAB446);
        put("minecraft:white_concrete", 0xCFD5D6);
        put("minecraft:white_terracotta", 0xD2B2A1);
        put("minecraft:white_wool", 0xEAECED);
        put("minecraft:yellow_concrete", 0xF1AF15);
        put("minecraft:yellow_terracotta", 0xBA8523);
        put("minecraft:yellow_wool", 0xF9C628);
        // 1.21 pale garden / tuff / copper 系
        put("minecraft:pale_moss_block", 0x6B7169);
        put("minecraft:pale_oak_log", 0x574D4B);
        put("minecraft:pale_oak_planks", 0xE4DAD8);
        put("minecraft:pale_oak_leaves", 0x757A73);
        put("minecraft:tuff_bricks", 0x62675F);
        put("minecraft:polished_tuff", 0x626864);
        put("minecraft:resin_block", 0xD96319);
        put("minecraft:resin_bricks", 0xCE5918);
    }

    private MapBlockColors() {
    }

    private static void put(String key, int rgb) {
        COLORS.put(key, 0xFF000000 | rgb);
    }

    /**
     * 把项目内部使用的 ARGB({@code 0xAARRGGBB})颜色转成 Minecraft {@code NativeImage} 期望的
     * native 字节序({@code 0xAABBGGRR}，即 setPixelRGBA/getPixelRGBA 的 int 格式)。
     *
     * <p>客户端小地图({@code RoadPlannerChunkImage})把像素写入 {@code NativeImage.setPixelRGBA} 前必须做此转换，
     * 否则红蓝(R↔B)通道颠倒会让蓝色水面渲染成橙色。服务端 PNG 路径用 {@code BufferedImage.TYPE_INT_ARGB}，
     * 直接接收 ARGB，不需要此转换。该操作对称(R↔B 互换)。
     */
    public static int argbToNativeAbgr(int argb) {
        return (argb & 0xFF000000)
                | ((argb & 0x000000FF) << 16)
                | (argb & 0x0000FF00)
                | ((argb & 0x00FF0000) >>> 16);
    }

    /**
     * 返回方块的不透明 ARGB 颜色。表中无此方块时回退到 vanilla {@link MapColor}。
     *
     * @param state    方块状态
     * @param fallback 该方块的 MapColor.calculateRGBColor(NORMAL) 结果（含或不含 alpha 皆可），表中查不到时使用
     * @return 不透明 ARGB（高字节 alpha=0xFF）
     */
    public static int colorFor(BlockState state, int fallback) {
        if (state != null) {
            String key = blockKey(state);
            if (key != null) {
                Integer mapped = COLORS.get(key);
                if (mapped != null) {
                    return mapped;
                }
            }
        }
        return 0xFF000000 | (fallback & 0x00FFFFFF);
    }

    /**
     * 仅供测试 / 直接按注册名查色。返回 null 表示表中无此方块。
     */
    public static Integer lookup(String registryKey) {
        return COLORS.get(registryKey);
    }

    private static String blockKey(BlockState state) {
        var registryName = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(state.getBlock());
        return registryName == null ? null : registryName.toString();
    }
}
