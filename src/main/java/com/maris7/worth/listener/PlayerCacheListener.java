package com.maris7.worth.listener;

import com.maris7.worth.MarisWorthPlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class PlayerCacheListener implements Listener {
    private final MarisWorthPlugin plugin;

    public PlayerCacheListener(MarisWorthPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        plugin.getSchedulerAdapter().runLater(event.getPlayer(), 1L, () -> {
            if (!event.getPlayer().isOnline()) {
                return;
            }
            plugin.preloadMultipliers(event.getPlayer().getUniqueId());
            plugin.updateInjectTopInventory(event.getPlayer());
            plugin.deliverPendingReturnedItems(event.getPlayer());
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.forgetPlayer(event.getPlayer().getUniqueId());
    }
}
