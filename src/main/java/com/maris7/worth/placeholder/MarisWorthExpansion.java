package com.maris7.worth.placeholder;

import com.maris7.worth.MarisWorthPlugin;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

public final class MarisWorthExpansion extends PlaceholderExpansion {
    private final MarisWorthPlugin plugin;

    public MarisWorthExpansion(MarisWorthPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "marisworth";
    }

    @Override
    public @NotNull String getAuthor() {
        return "Maris";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        if (params.startsWith("top_name_")) {
            int position = parsePosition(params.substring("top_name_".length()));
            return plugin.topName(position);
        }
        if (params.startsWith("top_value_")) {
            int position = parsePosition(params.substring("top_value_".length()));
            return plugin.topValue(position);
        }
        if ("postion".equalsIgnoreCase(params)) {
            return player == null ? "0" : String.valueOf(plugin.playerPosition(player));
        }
        if ("total".equalsIgnoreCase(params)) {
            return plugin.playerTotal(player);
        }
        return null;
    }

    private int parsePosition(String input) {
        try {
            return Math.max(1, Integer.parseInt(input));
        } catch (NumberFormatException exception) {
            return 1;
        }
    }
}
