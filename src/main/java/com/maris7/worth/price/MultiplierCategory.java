package com.maris7.worth.price;

import org.bukkit.Material;
import org.bukkit.Tag;

public enum MultiplierCategory {
    CROPS("CROPS"),
    ORES("ORES"),
    MOB_DROPS("MOB_DROPS"),
    NATURAL_ITEMS("NATURAL_ITEMS"),
    ARMOR_AND_TOOLS("ARMOR_AND_TOOLS"),
    FISH("FISH"),
    ENCHANTED_BOOKS("ENCHANTED_BOOKS"),
    POTIONS("POTIONS"),
    BLOCKS("BLOCKS");

    private final String configKey;
    MultiplierCategory(String configKey) {
        this.configKey = configKey;
    }

    public String configKey() {
        return configKey;
    }

    public static MultiplierCategory detect(Material material) {
        if (material == Material.ENCHANTED_BOOK || material == Material.BOOK) {
            return ENCHANTED_BOOKS;
        }
        if (material.name().contains("POTION") || material == Material.BLAZE_POWDER || material == Material.NETHER_WART) {
            return POTIONS;
        }
        if (material.name().contains("FISH") || material == Material.SALMON || material == Material.COD || material == Material.TROPICAL_FISH || material == Material.PUFFERFISH) {
            return FISH;
        }
        if (material.name().contains("SWORD") || material.name().contains("PICKAXE") || material.name().contains("AXE") || material.name().contains("HOE") || material.name().contains("SHOVEL") || material.name().contains("HELMET") || material.name().contains("CHESTPLATE") || material.name().contains("LEGGINGS") || material.name().contains("BOOTS") || material == Material.SHIELD) {
            return ARMOR_AND_TOOLS;
        }
        if (material.name().contains("ORE") || material == Material.DIAMOND || material == Material.EMERALD || material == Material.COPPER_INGOT || material == Material.IRON_INGOT || material == Material.GOLD_INGOT || material == Material.NETHERITE_INGOT || material == Material.COAL || material == Material.REDSTONE || material == Material.LAPIS_LAZULI || material == Material.QUARTZ || material == Material.AMETHYST_SHARD) {
            return ORES;
        }
        if (material.name().contains("SEED") || material == Material.WHEAT || material == Material.CARROT || material == Material.POTATO || material == Material.BEETROOT || material == Material.PUMPKIN || material == Material.MELON || material == Material.SUGAR_CANE || material == Material.BAMBOO) {
            return CROPS;
        }
        if (material.name().contains("SPAWN_EGG") || material == Material.ROTTEN_FLESH || material == Material.BONE || material == Material.STRING || material == Material.GUNPOWDER || material == Material.ENDER_PEARL || material == Material.SPIDER_EYE || material == Material.BLAZE_ROD) {
            return MOB_DROPS;
        }
        if (Tag.LOGS.isTagged(material) || Tag.LEAVES.isTagged(material) || material == Material.DIRT || material == Material.GRASS_BLOCK || material == Material.SAND || material == Material.GRAVEL || material == Material.CACTUS || material == Material.KELP) {
            return NATURAL_ITEMS;
        }
        return BLOCKS;
    }
}
