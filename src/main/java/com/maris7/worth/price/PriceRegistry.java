package com.maris7.worth.price;

import com.maris7.worth.util.ItemUtil;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

public final class PriceRegistry {
    private static final Pattern COMPONENT_ENCHANT_PATTERN = Pattern.compile("\"([^\"]+)\":(\\d+)");
    private final JavaPlugin plugin;
    private final Map<Material, Double> materialPrices = new EnumMap<>(Material.class);
    private final Map<String, Double> enchantmentPrices = new HashMap<>();
    private final List<PriceEntry> entries = new ArrayList<>();

    public PriceRegistry(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        materialPrices.clear();
        enchantmentPrices.clear();
        entries.clear();
        File pricesFile = plugin.getDataFolder().toPath().resolve("prices.yml").toFile();
        YamlConfiguration configuration = YamlConfiguration.loadConfiguration(pricesFile);
        importMissingVanillaItems(configuration, pricesFile);
        for (String key : configuration.getStringList("prices")) {
            ConfigurationSection section = configuration.getConfigurationSection(key);
            if (section == null) {
                plugin.getLogger().warning("Skipping invalid price entry section: " + key);
                continue;
            }
            ConfigurationSection itemSection = section.getConfigurationSection("item");
            if (itemSection == null) {
                continue;
            }
            Material material = materialFromItemSection(itemSection);
            double price = section.getDouble("price", 0D);
            if (material == null || price <= 0D) {
                continue;
            }
            if (materialPrices.putIfAbsent(material, price) == null) {
                String itemName = ItemUtil.displayName(new ItemStack(material));
                MultiplierCategory category = MultiplierCategory.detect(material);
                entries.add(new PriceEntry(material, itemName, price, category));
            }
            ConfigurationSection metaSection = itemSection.getConfigurationSection("meta");
            if (material == Material.ENCHANTED_BOOK && metaSection != null) {
                ConfigurationSection enchants = metaSection.getConfigurationSection("stored-enchants");
                if (enchants != null) {
                    for (String enchantmentKey : enchants.getKeys(false)) {
                        int level = enchants.getInt(enchantmentKey, 1);
                        enchantmentPrices.put(normalizeEnchantmentKey(enchantmentKey, level), price);
                    }
                }
            }
            if (material == Material.ENCHANTED_BOOK) {
                readComponentStoredEnchantments(itemSection, price);
            }
        }
        entries.sort(Comparator.comparing(PriceEntry::displayName, String.CASE_INSENSITIVE_ORDER));
    }


    private void importMissingVanillaItems(YamlConfiguration configuration, File pricesFile) {
        if (!plugin.getConfig().getBoolean("prices.auto-import-missing-vanilla-items", true)) {
            return;
        }
        double defaultPrice = plugin.getConfig().getDouble("prices.default-import-price", 1.0D);
        if (defaultPrice <= 0D) {
            plugin.getLogger().warning("prices.default-import-price must be greater than 0; missing vanilla item import skipped.");
            return;
        }

        List<String> priceKeys = new ArrayList<>(configuration.getStringList("prices"));
        Set<String> usedKeys = new HashSet<>(priceKeys);
        Set<Material> configuredMaterials = EnumSet.noneOf(Material.class);
        int nextItemId = 0;

        for (String key : priceKeys) {
            if (key != null && key.startsWith("item_")) {
                try {
                    nextItemId = Math.max(nextItemId, Integer.parseInt(key.substring("item_".length())) + 1);
                } catch (NumberFormatException ignored) {
                }
            }

            ConfigurationSection section = configuration.getConfigurationSection(key);
            if (section == null) {
                continue;
            }
            ConfigurationSection itemSection = section.getConfigurationSection("item");
            Material material = materialFromItemSection(itemSection);
            if (material != null) {
                configuredMaterials.add(material);
            }
        }

        int imported = 0;
        for (Material material : Material.values()) {
            if (!material.isItem() || material.isAir() || configuredMaterials.contains(material)) {
                continue;
            }
            String key;
            do {
                key = "item_" + nextItemId++;
            } while (usedKeys.contains(key));

            priceKeys.add(key);
            usedKeys.add(key);
            configuredMaterials.add(material);
            configuration.set(key + ".item.type", material.name());
            configuration.set(key + ".price", defaultPrice);
            configuration.set(key + ".aliases", new ArrayList<String>());
            configuration.set(key + ".category", "UNSET");
            imported++;
        }

        if (imported == 0) {
            return;
        }

        configuration.set("prices", priceKeys);
        try {
            configuration.save(pricesFile);
            plugin.getLogger().info("Imported " + imported + " missing vanilla item price entries into prices.yml.");
        } catch (IOException exception) {
            plugin.getLogger().warning("Failed to save imported vanilla item prices: " + exception.getMessage());
        }
    }

    private Material materialFromItemSection(ConfigurationSection itemSection) {
        if (itemSection == null) {
            return null;
        }
        String typeName = itemSection.getString("type");
        if ((typeName == null || typeName.isBlank()) && itemSection.isString("id")) {
            typeName = itemSection.getString("id");
            if (typeName != null && typeName.startsWith("minecraft:")) {
                typeName = typeName.substring("minecraft:".length());
            }
        }
        return typeName == null ? null : Material.matchMaterial(typeName);
    }

    public double getBasePrice(Material material) {
        return materialPrices.getOrDefault(material, 0D);
    }

    public double getEnchantmentPrice(Enchantment enchantment, int level) {
        String fullKey = normalizeEnchantmentKey(enchantment.getKey().toString(), level);
        String shortKey = normalizeEnchantmentKey(enchantment.getKey().getKey(), level);
        return enchantmentPrices.containsKey(fullKey) ? enchantmentPrices.get(fullKey) : enchantmentPrices.getOrDefault(shortKey, 0D);
    }

    public Optional<PriceEntry> byMaterialName(String input) {
        String normalized = input.toUpperCase(Locale.ROOT).replace(' ', '_');
        Material material = Material.matchMaterial(normalized);
        if (material == null) {
            return Optional.empty();
        }
        return entries.stream().filter(entry -> entry.material() == material).findFirst();
    }

    public Collection<PriceEntry> allEntries() {
        return entries;
    }

    private String normalizeEnchantmentKey(String enchantmentKey, int level) {
        String normalized = enchantmentKey.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("minecraft:")) {
            normalized = normalized.substring("minecraft:".length());
        }
        return normalized + ':' + level;
    }

    private void readComponentStoredEnchantments(ConfigurationSection itemSection, double price) {
        ConfigurationSection components = itemSection.getConfigurationSection("components");
        if (components == null) {
            return;
        }
        String raw = components.getString("minecraft:stored_enchantments");
        if (raw == null || raw.isBlank()) {
            return;
        }
        Matcher matcher = COMPONENT_ENCHANT_PATTERN.matcher(raw);
        while (matcher.find()) {
            String enchantmentKey = matcher.group(1);
            int level = Integer.parseInt(matcher.group(2));
            enchantmentPrices.put(normalizeEnchantmentKey(enchantmentKey, level), price);
        }
    }

    public record PriceEntry(Material material, String displayName, double price, MultiplierCategory category) {
    }
}
