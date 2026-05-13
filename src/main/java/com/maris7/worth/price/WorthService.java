package com.maris7.worth.price;

import java.util.Map;
import org.bukkit.Material;
import org.bukkit.block.BlockState;
import org.bukkit.block.ShulkerBox;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;

public final class WorthService {
    private final PriceRegistry priceRegistry;
    private java.util.Set<Material> sellBlacklist = java.util.Set.of();

    public WorthService(PriceRegistry priceRegistry) {
        this.priceRegistry = priceRegistry;
    }

    public double calculate(ItemStack item, double multiplier) {
        if (item == null || item.getType() == Material.AIR) {
            return 0D;
        }
        return calculateUnit(item, multiplier) * item.getAmount();
    }

    public double calculateUnit(ItemStack item, double multiplier) {
        if (item == null || item.getType() == Material.AIR) {
            return 0D;
        }
        if (isBlacklisted(item.getType())) {
            return 0D;
        }
        double base = priceRegistry.getBasePrice(item.getType());
        double enchantmentWorth = enchantmentWorthPerItem(item);
        double containerWorth = containerWorthPerItem(item, multiplier);
        if (base <= 0D && enchantmentWorth <= 0D && containerWorth <= 0D) {
            return 0D;
        }
        double total = Math.max(0D, base) + enchantmentWorth + containerWorth;
        return total * multiplier;
    }

    public void setSellBlacklist(java.util.Set<Material> sellBlacklist) {
        this.sellBlacklist = sellBlacklist == null ? java.util.Set.of() : java.util.Set.copyOf(sellBlacklist);
    }

    public boolean isBlacklisted(Material material) {
        return material != null && sellBlacklist.contains(material);
    }

    private double enchantmentWorthPerItem(ItemStack item) {
        double total = 0D;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return 0D;
        }
        for (Map.Entry<Enchantment, Integer> entry : meta.getEnchants().entrySet()) {
            total += priceRegistry.getEnchantmentPrice(entry.getKey(), entry.getValue());
        }
        if (meta instanceof EnchantmentStorageMeta storedMeta) {
            for (Map.Entry<Enchantment, Integer> entry : storedMeta.getStoredEnchants().entrySet()) {
                total += priceRegistry.getEnchantmentPrice(entry.getKey(), entry.getValue());
            }
        }
        return total;
    }

    private double containerWorthPerItem(ItemStack item, double multiplier) {
        ItemMeta meta = item.getItemMeta();
        if (!(meta instanceof BlockStateMeta blockStateMeta)) {
            return 0D;
        }
        BlockState state = blockStateMeta.getBlockState();
        if (!(state instanceof ShulkerBox shulkerBox)) {
            return 0D;
        }
        Inventory inventory = shulkerBox.getInventory();
        double total = 0D;
        for (ItemStack inside : inventory.getContents()) {
            total += calculate(inside, multiplier);
        }
        return total;
    }
}
