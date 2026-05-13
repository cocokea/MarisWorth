package com.maris7.worth.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.md_5.bungee.api.ChatColor;
import org.bukkit.command.CommandSender;

public final class ColorUtil {
    private static final Pattern HEX_PATTERN = Pattern.compile("&#([A-Fa-f0-9]{6})");

    private ColorUtil() {
    }

    public static String color(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        Matcher matcher = HEX_PATTERN.matcher(input);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(buffer, ChatColor.of("#" + matcher.group(1)).toString());
        }
        matcher.appendTail(buffer);
        return ChatColor.translateAlternateColorCodes('&', buffer.toString());
    }

    public static String noItalic(String input) {
        return color("&r" + (input == null ? "" : input));
    }

    public static void send(CommandSender sender, String message) {
        sender.sendMessage(color(message));
    }

    public static String strip(String input) {
        return ChatColor.stripColor(color(input));
    }
}
