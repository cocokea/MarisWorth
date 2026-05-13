package com.maris7.worth.command;

import com.maris7.worth.MarisWorthPlugin;
import com.maris7.worth.price.PriceRegistry.PriceEntry;
import com.maris7.worth.util.ColorUtil;
import com.maris7.worth.util.NumberFormatUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

public final class MarisWorthCommand implements TabExecutor {
    private final MarisWorthPlugin plugin;

    public MarisWorthCommand(MarisWorthPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            ColorUtil.send(sender, plugin.message("player-only"));
            return true;
        }
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (!plugin.isOperational()) {
            ColorUtil.send(player, plugin.unavailableMessage());
            return true;
        }
        switch (name) {
            case "sell" -> plugin.getGuiController().openSell(player);
            case "worth" -> {
                if (args.length == 0) {
                    plugin.getGuiController().openWorth(player, 0);
                    return true;
                }
                Material requestedMaterial = Material.matchMaterial(String.join("_", args).toUpperCase(Locale.ROOT));
                if (requestedMaterial != null && plugin.isSellBlacklisted(requestedMaterial)) {
                    String itemName = requestedMaterial.name().replace('_', ' ');
                    String message = plugin.getConfig().getString("worth.command-message", "&#EAFF00%item% &7is worth &a$%prices%")
                        .replace("%item%", itemName)
                        .replace("%prices%", NumberFormatUtil.format(0D));
                    player.sendMessage(ColorUtil.color(message));
                    player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(ColorUtil.noItalic(message)));
                    return true;
                }
                plugin.getPriceRegistry().byMaterialName(String.join("_", args)).ifPresentOrElse(entry -> {
                    String message = plugin.getConfig().getString("worth.command-message", "&#EAFF00%item% &7is worth &a$%prices%")
                        .replace("%item%", entry.displayName())
                        .replace("%prices%", NumberFormatUtil.format(entry.price()));
                    player.sendMessage(ColorUtil.color(message));
                    player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(ColorUtil.noItalic(message)));
                }, () -> ColorUtil.send(player, plugin.message("no-price")));
            }
            case "sellhistory" -> plugin.getGuiController().openSellHistory(player, 0, false);
            case "sellmulti" -> plugin.getGuiController().openSellMulti(player);
            default -> { return false; }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!command.getName().equalsIgnoreCase("worth") || args.length != 1) {
            return List.of();
        }
        String prefix = args[0].toUpperCase(Locale.ROOT);
        List<String> values = new ArrayList<>();
        for (PriceEntry entry : plugin.getPriceRegistry().allEntries()) {
            String material = entry.material().name();
            if (material.startsWith(prefix)) {
                values.add(material);
            }
        }
        return values;
    }
}
