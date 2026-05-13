package com.maris7.worth.util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

public final class ItemUtil {
    private ItemUtil() {
    }

    public static String displayName(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) {
            return "Air";
        }
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            String displayName = meta.getDisplayName();
            String plain = ColorUtil.strip(displayName).trim();
            if (!plain.isEmpty()) {
                return plain;
            }
            return displayName;
        }
        String lower = item.getType().name().toLowerCase().replace('_', ' ');
        String[] parts = lower.split(" ");
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return builder.toString();
    }

    public static String serialize(ItemStack item) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (BukkitObjectOutputStream data = new BukkitObjectOutputStream(output)) {
                data.writeObject(item);
            }
            return Base64.getEncoder().encodeToString(output.toByteArray());
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to serialize item", exception);
        }
    }

    public static ItemStack deserialize(String data) {
        try {
            ByteArrayInputStream input = new ByteArrayInputStream(Base64.getDecoder().decode(data));
            try (BukkitObjectInputStream objectInput = new BukkitObjectInputStream(input)) {
                Object object = objectInput.readObject();
                return object instanceof ItemStack itemStack ? itemStack : new ItemStack(Material.STONE);
            }
        } catch (IOException | ClassNotFoundException exception) {
            return new ItemStack(Material.STONE);
        }
    }
}
