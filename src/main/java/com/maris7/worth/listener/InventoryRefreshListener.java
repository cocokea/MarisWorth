package com.maris7.worth.listener;

import com.maris7.worth.MarisWorthPlugin;
import com.maris7.worth.gui.GuiController.MultiDetailHolder;
import com.maris7.worth.gui.GuiController.MultiHolder;
import com.maris7.worth.gui.GuiController.SellHistoryHolder;
import com.maris7.worth.gui.GuiController.WorthHolder;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;

public final class InventoryRefreshListener implements Listener {
    private final MarisWorthPlugin plugin;
    private final java.util.Set<java.util.UUID> pendingRefreshes = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public InventoryRefreshListener(MarisWorthPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            if (shouldSkipGuiRefresh(event.getView().getTopInventory().getHolder())) {
                return;
            }
            refresh(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            if (shouldSkipGuiRefresh(event.getView().getTopInventory().getHolder())) {
                return;
            }
            refresh(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player) {
            plugin.updateInjectTopInventory(player);
            plugin.captureStaticTopInventoryItems(player);
            plugin.getSchedulerAdapter().runLater(player, 1L, () -> {
                if (!player.isOnline()) {
                    return;
                }
                plugin.updateInjectTopInventory(player);
                plugin.captureStaticTopInventoryItems(player);
                player.updateInventory();
            });
            refresh(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) {
            plugin.clearTopInventoryCache(player.getUniqueId());
            refresh(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHeld(PlayerItemHeldEvent event) {
        refresh(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        refresh(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        refresh(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player) {
            refresh(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        refresh(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        refresh(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameMode(PlayerGameModeChangeEvent event) {
        refresh(event.getPlayer());
    }

    private void refresh(Player player) {
        java.util.UUID playerId = player.getUniqueId();
        if (!pendingRefreshes.add(playerId)) {
            return;
        }
        plugin.getSchedulerAdapter().runLater(player, 1L, () -> {
            pendingRefreshes.remove(playerId);
            if (!player.isOnline()) {
                return;
            }
            plugin.updateInjectTopInventory(player);
            player.updateInventory();
        }, () -> pendingRefreshes.remove(playerId));
    }

    private boolean shouldSkipGuiRefresh(InventoryHolder holder) {
        return holder instanceof WorthHolder
            || holder instanceof SellHistoryHolder
            || holder instanceof MultiHolder
            || holder instanceof MultiDetailHolder;
    }
}
