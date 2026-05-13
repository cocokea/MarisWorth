package com.maris7.worth.gui;

import com.maris7.worth.MarisWorthPlugin;
import com.maris7.worth.MarisWorthPlugin.SellMultiTier;
import com.maris7.worth.price.MultiplierCategory;
import com.maris7.worth.price.PriceRegistry.PriceEntry;
import com.maris7.worth.storage.DatabaseManager.MultiplierState;
import com.maris7.worth.storage.DatabaseManager.SellHistoryEntry;
import com.maris7.worth.util.ColorUtil;
import com.maris7.worth.util.ItemUtil;
import com.maris7.worth.util.NumberFormatUtil;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public final class GuiController implements Listener {
    private static final int[] MULTI_SLOTS = {9, 10, 11, 12, 13, 14, 15, 16, 17};
    private static final int[] MULTI_DETAIL_SLOTS = {
        10, 11, 12, 13, 14, 15, 16,
        19, 20, 21, 22, 23, 24, 25,
        28, 29, 30, 31, 32, 33, 34,
        37, 38, 39, 40, 41, 42, 43
    };
    private static final String MULTI_PROGRESS_BAR_FILLED = "&f&m  ";
    private static final String MULTI_PROGRESS_BAR_EMPTY = "&8&m  ";
    private static final int[] MULTI_DETAIL_BACKGROUND_GRAY = {
        0, 2, 3, 4, 5, 6, 8,
        9, 11, 15, 17,
        18, 20, 22, 24, 26,
        27, 29, 31, 33, 35,
        36, 38, 40, 42, 44,
        46, 47, 48, 49, 50, 51, 52, 53
    };
    private static final int[] MULTI_DETAIL_BACKGROUND_WHITE = {
        7,
        10,
        12, 13, 14, 16,
        19, 21, 23, 25,
        28, 30, 32, 34,
        37, 39, 41, 43
    };
    private final MarisWorthPlugin plugin;

    public GuiController(MarisWorthPlugin plugin) {
        this.plugin = plugin;
    }

    public void openSell(Player player) {
        YamlConfiguration gui = guiConfig("sell.yml");
        player.openInventory(Bukkit.createInventory(new SellHolder(), 36, color(guiString(gui, "title", "&8ᴘʟᴀᴄᴇ ɪᴛᴇᴍs ɪɴ ʜᴇʀᴇ ᴛᴏ sᴇʟʟ"))));
    }

    public void openWorth(Player player, int page) {
        openWorth(player, new WorthHolder(page, WorthFilter.ALL, WorthSort.NAME));
    }

    public void openWorth(Player player, WorthHolder state) {
        YamlConfiguration gui = guiConfig("worth.yml");
        List<PriceEntry> entries = new ArrayList<>(plugin.getPriceRegistry().allEntries());
        if (state.filter() != WorthFilter.ALL) {
            entries = entries.stream()
                .filter(entry -> state.filter().matches(entry.material()))
                .collect(Collectors.toCollection(ArrayList::new));
        }
        entries.sort(switch (state.sort()) {
            case HIGHEST_PRICE -> Comparator.comparingDouble(PriceEntry::price).reversed().thenComparing(PriceEntry::displayName, String.CASE_INSENSITIVE_ORDER);
            case LOWEST_PRICE -> Comparator.comparingDouble(PriceEntry::price).thenComparing(PriceEntry::displayName, String.CASE_INSENSITIVE_ORDER);
            case NAME -> Comparator.comparing(PriceEntry::displayName, String.CASE_INSENSITIVE_ORDER);
        });
        int maxPages = Math.max(1, (int) Math.ceil(entries.size() / 45.0D));
        int page = Math.max(0, Math.min(state.page(), maxPages - 1));
        WorthHolder normalized = new WorthHolder(page, state.filter(), state.sort());
        String title = guiString(gui, "title", "&8ɪᴛᴇᴍ ᴘʀɪᴄᴇs &7(Page %page%)")
            .replace("%page%", String.valueOf(page + 1))
            .replace("%pages%", String.valueOf(maxPages));
        Inventory inventory = Bukkit.createInventory(normalized, 54, color(title));
        int start = page * 45;
        for (int slot = 0; slot < 45 && start + slot < entries.size(); slot++) {
            PriceEntry entry = entries.get(start + slot);
            ItemStack item = new ItemStack(entry.material());
            double displayWorth = plugin.getWorthService().calculateUnit(item, 1.0D);
            String lore = entry.material() == Material.ENCHANTED_BOOK
                ? guiString(gui, "item-lore.enchanted", "&fPrice: &#00FFA1$%price% &7(enchanted)")
                : guiString(gui, "item-lore.default", "&fPrice: &#00FFA1$%price%");
            lore = lore.replace("%price%", NumberFormatUtil.format(displayWorth));
            inventory.setItem(slot, buildItem(item, entry.displayName(), lore));
        }

        inventory.setItem(45, buildConfiguredItem(gui, "back", Material.ARROW, "&#00FFA1ʙᴀᴄᴋ", Map.of()));
        inventory.setItem(49, buildConfiguredItem(gui, "refresh", Material.ANVIL, "&#00FFA1ɪᴛᴇᴍ ᴘʀɪᴄᴇs", Map.of()));
        inventory.setItem(50, buildSortItem(gui, normalized.sort()));
        inventory.setItem(48, buildFilterItem(gui, normalized.filter()));
        inventory.setItem(53, buildConfiguredItem(gui, "next", Material.ARROW, "&#00FFA1ɴᴇxᴛ", Map.of()));
        player.openInventory(inventory);
    }

    public void openSellHistory(Player player, int page, boolean sortByAmount) {
        plugin.getDatabaseManager().loadHistoryAsync(player.getUniqueId()).thenAccept(historyEntries -> {
            YamlConfiguration gui = guiConfig("sellhistory.yml");
            List<SellHistoryEntry> history = new ArrayList<>(historyEntries);
            history.sort(sortByAmount
                ? Comparator.comparingLong(SellHistoryEntry::amount).reversed().thenComparing(SellHistoryEntry::name, String.CASE_INSENSITIVE_ORDER)
                : Comparator.comparing(SellHistoryEntry::name, String.CASE_INSENSITIVE_ORDER));
            plugin.getSchedulerAdapter().runLater(player, 1L, () -> {
                if (!player.isOnline()) {
                    return;
                }
                int maxPages = Math.max(1, (int) Math.ceil(history.size() / 45.0D));
                int normalizedPage = Math.max(0, Math.min(page, maxPages - 1));
                String title = guiString(gui, "title", "&8sᴇʟʟ ʜɪsᴛᴏʀʏ &7(Page %page%)")
                    .replace("%page%", String.valueOf(normalizedPage + 1))
                    .replace("%pages%", String.valueOf(maxPages));
                Inventory inventory = Bukkit.createInventory(new SellHistoryHolder(normalizedPage, sortByAmount), 54, color(title));
                int start = normalizedPage * 45;
                for (int slot = 0; slot < 45 && start + slot < history.size(); slot++) {
                    SellHistoryEntry entry = history.get(start + slot);
                    ItemStack item = entry.item().clone();
                    item.setAmount(1);
                    inventory.setItem(slot, buildItem(item, ItemUtil.displayName(item),
                        guiString(gui, "entry.total-price", "&fTotal price: &#00FFA1$%price%").replace("%price%", NumberFormatUtil.format(entry.totalPrice())),
                        guiString(gui, "entry.total-amount", "&fTotal Amount: %amount%").replace("%amount%", String.valueOf(entry.amount()))));
                }
                inventory.setItem(45, buildConfiguredItem(gui, "back", Material.ARROW, "&#00FFA1ʙᴀᴄᴋ", Map.of()));
                String currentSort = sortByAmount
                    ? guiString(gui, "labels.sort-by-amount", "By amount")
                    : guiString(gui, "labels.sort-by-name", "By name");
                inventory.setItem(49, buildConfiguredItem(gui, "sort", Material.ANVIL, "&#00FFA1sᴏʀᴛ",
                    Map.of("%current%", currentSort)));
                inventory.setItem(53, buildConfiguredItem(gui, "next", Material.ARROW, "&#00FFA1ɴᴇxᴛ", Map.of()));
                player.openInventory(inventory);
            });
        }).exceptionally(exception -> {
            plugin.getLogger().warning("Failed to load sell history for " + player.getUniqueId() + ": " + exception.getMessage());
            return null;
        });
    }

    public void openSellMulti(Player player) {
        YamlConfiguration gui = guiConfig("sellmulti.yml");
        Map<MultiplierCategory, MultiplierState> states = plugin.getMultipliers(player);
        Inventory inventory = Bukkit.createInventory(new MultiHolder(), 27, color(guiString(gui, "title", "&8ꜱᴇʟʟ ᴍᴜʟᴛɪᴘʟɪᴇʀ")));
        for (int i = 0; i < MULTI_SLOTS.length; i++) {
            MultiplierCategory category = MultiplierCategory.values()[i];
            MultiplierState state = states.get(category);
            inventory.setItem(MULTI_SLOTS[i], buildCategoryItem(gui, category, state));
        }
        player.openInventory(inventory);
    }

    private void openSellMultiDetail(Player player, MultiplierCategory category) {
        YamlConfiguration gui = guiConfig("sellmulti.yml");
        MultiplierState state = plugin.getMultiplierState(player.getUniqueId(), category);
        List<SellMultiTier> tiers = plugin.getSellMultiTiers(category);
        String categoryPath = "categories." + category.configKey().toLowerCase(Locale.ROOT);
        String title = guiString(gui, "detail.title", "&8%category% ᴘʀᴏɢʀᴇꜱꜱ")
            .replace("%category%", guiString(gui, categoryPath + ".short-name", defaultCategoryShortName(category)));
        Inventory inventory = Bukkit.createInventory(new MultiDetailHolder(category), 54, color(title));
        fillMultiDetailBackground(gui, inventory);
        Material iconMaterial = guiMaterial(gui, categoryPath + ".material", defaultCategoryMaterial(category));
        inventory.setItem(1, buildItem(new ItemStack(iconMaterial),
            guiString(gui, categoryPath + ".name", defaultCategoryName(category)),
            guiString(gui, categoryPath + ".lore.0", defaultCategoryLineOne(category)),
            guiString(gui, categoryPath + ".lore.1", "&7upgrade your sell multiplier!")));
        inventory.setItem(45, buildConfiguredItem(gui, "detail.back", Material.ARROW, "&#00FFA1ʙᴀᴄᴋ", Map.of()));

        double soldTotal = Math.max(0D, state.soldTotal());
        int firstIncomplete = tiers.size();
        for (int i = 0; i < tiers.size(); i++) {
            if (soldTotal < tiers.get(i).required()) {
                firstIncomplete = i;
                break;
            }
        }

        for (int i = 0; i < tiers.size() && i < MULTI_DETAIL_SLOTS.length; i++) {
            SellMultiTier tier = tiers.get(i);
            String status;
            Material paneMaterial;
            double progressPercent;
            if (soldTotal >= tier.required()) {
                status = guiString(gui, "detail.status.complete.name", "&aCOMPLETE");
                paneMaterial = guiMaterial(gui, "detail.status.complete.material", Material.LIME_STAINED_GLASS_PANE);
                progressPercent = 100D;
            } else if (i == firstIncomplete) {
                status = guiString(gui, "detail.status.working.name", "&eWORKING");
                paneMaterial = guiMaterial(gui, "detail.status.working.material", Material.YELLOW_STAINED_GLASS_PANE);
                progressPercent = tier.required() <= 0D ? 0D : Math.max(0D, Math.min(100D, (soldTotal / tier.required()) * 100D));
            } else {
                status = guiString(gui, "detail.status.incomplete.name", "&fINCOMPLETE");
                paneMaterial = guiMaterial(gui, "detail.status.incomplete.material", Material.RED_STAINED_GLASS_PANE);
                progressPercent = 0D;
            }
            List<String> detailLore = new ArrayList<>();
            detailLore.add(guiString(gui, "detail.entry.multiplier-line", "&f%bar% &f%multiplier% %progress%%")
                .replace("%bar%", buildProgressBar(progressPercent))
                .replace("%multiplier%", String.format(Locale.US, "%.1fx", tier.multiplier()))
                .replace("%progress%", String.format(Locale.US, "%.1f", progressPercent)));
            detailLore.add(guiString(gui, "detail.entry.amount-line", "&f$%current%/$%required%")
                .replace("%current%", NumberFormatUtil.format(Math.min(soldTotal, tier.required())))
                .replace("%required%", NumberFormatUtil.format(tier.required())));
            inventory.setItem(MULTI_DETAIL_SLOTS[i], buildItem(new ItemStack(paneMaterial), status, detailLore.toArray(new String[0])));
        }
        player.openInventory(inventory);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!plugin.isOperational()) {
            event.setCancelled(true);
            player.closeInventory();
            ColorUtil.send(player, plugin.unavailableMessage());
            return;
        }
        Inventory top = event.getView().getTopInventory();
        InventoryHolder holder = top.getHolder();
        int topSize = top.getSize();
        boolean touchesTop = event.getRawSlot() >= 0 && event.getRawSlot() < topSize;

        if (holder instanceof WorthHolder worthHolder) {
            event.setCancelled(true);
            if (touchesTop) {
                plugin.getSchedulerAdapter().runLater(player, 3L, () -> handleWorthClick(player, worthHolder, event.getRawSlot()));
            }
            return;
        }
        if (holder instanceof SellHistoryHolder historyHolder) {
            event.setCancelled(true);
            if (touchesTop) {
                plugin.getSchedulerAdapter().runLater(player, 3L, () -> handleHistoryClick(player, historyHolder, event.getRawSlot()));
            }
            return;
        }
        if (holder instanceof MultiDetailHolder) {
            event.setCancelled(true);
            if (touchesTop && event.getRawSlot() == 45) {
                plugin.getSchedulerAdapter().runLater(player, 3L, () -> {
                    playButtonClick(player);
                    openSellMulti(player);
                });
            }
            return;
        }
        if (holder instanceof MultiHolder) {
            event.setCancelled(true);
            if (touchesTop) {
                for (int i = 0; i < MULTI_SLOTS.length; i++) {
                    if (event.getRawSlot() == MULTI_SLOTS[i]) {
                        MultiplierCategory category = MultiplierCategory.values()[i];
                        plugin.getSchedulerAdapter().runLater(player, 3L, () -> {
                            playButtonClick(player);
                            openSellMultiDetail(player, category);
                        });
                        return;
                    }
                }
            }
            return;
        }
        if (holder instanceof SellHolder) {
            handleSellClick(event, player, top);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        InventoryHolder holder = top.getHolder();
        boolean touchesTop = event.getRawSlots().stream().anyMatch(slot -> slot < top.getSize());
        if (!touchesTop) {
            return;
        }
        if (holder instanceof WorthHolder || holder instanceof SellHistoryHolder || holder instanceof MultiHolder || holder instanceof MultiDetailHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        if (!(event.getInventory().getHolder() instanceof SellHolder)) {
            return;
        }
        Inventory inventory = event.getInventory();
        plugin.getSchedulerAdapter().runLater(player, 3L, () -> {
            if (!plugin.isOperational()) {
                plugin.queuePendingReturnedItems(player.getUniqueId(), collectInventoryContents(inventory));
                inventory.clear();
                plugin.deliverPendingReturnedItems(player);
                ColorUtil.send(player, plugin.unavailableMessage());
                return;
            }
            MarisWorthPlugin.SellResult result = plugin.sellInventory(player, inventory);
            plugin.finalizeSell(player, result);
            if (result.total() <= 0D) {
                playSound(player, plugin.getSounds().getString("sell-empty-close", "ENTITY_VILLAGER_NO"));
            }
        }, () -> {
            plugin.queuePendingReturnedItems(player.getUniqueId(), collectInventoryContents(inventory));
            inventory.clear();
            plugin.getLogger().warning("Deferred sell task retired before execution for player " + player.getUniqueId() + ". Items were queued for return on next login.");
        });
    }

    private List<ItemStack> collectInventoryContents(Inventory inventory) {
        List<ItemStack> items = new ArrayList<>();
        for (ItemStack item : inventory.getContents()) {
            if (item == null || item.getType().isAir()) {
                continue;
            }
            items.add(item.clone());
        }
        return items;
    }

    private void handleSellClick(InventoryClickEvent event, Player player, Inventory top) {
        if (event.getClickedInventory() == null) {
            return;
        }
        if (plugin.isCreativeMode(player.getUniqueId())) {
            event.setCancelled(true);
            player.updateInventory();
            return;
        }
        if (event.getClickedInventory().equals(player.getInventory()) && event.isShiftClick()) {
            event.setCancelled(true);
            ItemStack clicked = event.getCurrentItem();
            if (clicked == null || clicked.getType().isAir()) {
                return;
            }
            ItemStack copy = clicked.clone();
            Map<Integer, ItemStack> leftover = top.addItem(copy);
            int moved = copy.getAmount() - leftover.values().stream().mapToInt(ItemStack::getAmount).sum();
            if (moved <= 0) {
                player.updateInventory();
                return;
            }
            if (clicked.getAmount() <= moved) {
                event.getClickedInventory().setItem(event.getSlot(), null);
            } else {
                clicked.setAmount(clicked.getAmount() - moved);
                event.getClickedInventory().setItem(event.getSlot(), clicked);
            }
            player.updateInventory();
            return;
        }
        if (event.getClickedInventory().equals(top)) {
            if (event.getAction() == InventoryAction.COLLECT_TO_CURSOR
                || event.getAction() == InventoryAction.HOTBAR_SWAP
                || isHotbarMoveAndReadd(event.getAction())) {
                event.setCancelled(true);
            }
        }
    }

    private void handleWorthClick(Player player, WorthHolder state, int rawSlot) {
        if (rawSlot == 45 && state.page() > 0) {
            playButtonClick(player);
            openWorth(player, new WorthHolder(state.page() - 1, state.filter(), state.sort()));
        } else if (rawSlot == 53) {
            playButtonClick(player);
            openWorth(player, new WorthHolder(state.page() + 1, state.filter(), state.sort()));
        } else if (rawSlot == 48) {
            playButtonClick(player);
            openWorth(player, new WorthHolder(0, state.filter().next(), state.sort()));
        } else if (rawSlot == 49) {
            playButtonClick(player);
            openWorth(player, new WorthHolder(state.page(), state.filter(), state.sort()));
        } else if (rawSlot == 50) {
            playButtonClick(player);
            openWorth(player, new WorthHolder(0, state.filter(), state.sort().next()));
        }
    }

    private void handleHistoryClick(Player player, SellHistoryHolder state, int rawSlot) {
        if (rawSlot == 45 && state.page() > 0) {
            playButtonClick(player);
            openSellHistory(player, state.page() - 1, state.sortByAmount());
        } else if (rawSlot == 53) {
            playButtonClick(player);
            openSellHistory(player, state.page() + 1, state.sortByAmount());
        } else if (rawSlot == 49) {
            playButtonClick(player);
            openSellHistory(player, state.page(), !state.sortByAmount());
        }
    }

    private ItemStack buildCategoryItem(YamlConfiguration gui, MultiplierCategory category, MultiplierState state) {
        double soldTotal = Math.max(0D, state.soldTotal());
        String categoryKey = "categories." + category.configKey().toLowerCase(Locale.ROOT);
        SellMultiTier nextTier = plugin.getNextSellMultiTier(category, soldTotal);
        String nextProgress = nextTier == null
            ? guiString(gui, "labels.max", "MAX")
            : String.format(Locale.US, "%.1fx", nextTier.multiplier());
        double progress = nextTier == null || nextTier.required() <= 0D
            ? 100D
            : Math.max(0D, Math.min(100D, (soldTotal / nextTier.required()) * 100D));
        Material material = guiMaterial(gui, categoryKey + ".material", defaultCategoryMaterial(category));
        String title = guiString(gui, categoryKey + ".name", defaultCategoryName(category));
        String lineOne = guiString(gui, categoryKey + ".lore.0", defaultCategoryLineOne(category));
        String lineTwo = guiString(gui, categoryKey + ".lore.1", "&7upgrade your sell multiplier!");
        String progressLine = guiString(gui, categoryKey + ".progress-line", "&7Progress to &f%next_progress%")
            .replace("%next_progress%", nextProgress);
        String percentLine = guiString(gui, categoryKey + ".percent-line", "&f%bar% &f%progress%%")
            .replace("%bar%", buildProgressBar(progress))
            .replace("%progress%", String.format(Locale.US, "%.1f", progress));
        return buildItem(new ItemStack(material), title, lineOne, lineTwo, "", progressLine, percentLine);
    }

    private ItemStack buildFilterItem(YamlConfiguration gui, WorthFilter current) {
        List<String> lore = new ArrayList<>();
        String header = guiString(gui, "filter.header", "");
        if (!header.isBlank()) {
            lore.add(header);
        }
        for (WorthFilter filter : WorthFilter.values()) {
            String line = guiString(gui, filter == current ? "filter.active-line" : "filter.inactive-line", filter == current ? "&a• %name%" : "&7• %name%")
                .replace("%name%", filter.displayName(gui));
            lore.add(line);
        }
        return buildItem(new ItemStack(guiMaterial(gui, "filter.material", Material.CAULDRON)), guiString(gui, "filter.name", "&#00FFA1ꜰɪʟᴛᴇʀ"), lore.toArray(new String[0]));
    }

    private ItemStack buildSortItem(YamlConfiguration gui, WorthSort current) {
        List<String> lore = new ArrayList<>();
        for (WorthSort sort : WorthSort.values()) {
            String line = guiString(gui, sort == current ? "sort.active-line" : "sort.inactive-line", sort == current ? "&a• %name%" : "&7• %name%")
                .replace("%name%", sort.displayName(gui));
            lore.add(line);
        }
        return buildItem(new ItemStack(guiMaterial(gui, "sort.material", Material.HOPPER)), guiString(gui, "sort.name", "&#00FFA1ꜱᴏʀᴛ"), lore.toArray(new String[0]));
    }

    private void fillMultiDetailBackground(YamlConfiguration gui, Inventory inventory) {
        ItemStack gray = buildItem(new ItemStack(guiMaterial(gui, "detail.background.gray-material", Material.GRAY_STAINED_GLASS_PANE)),
            guiString(gui, "detail.background.gray-name", " "));
        ItemStack white = buildItem(new ItemStack(guiMaterial(gui, "detail.background.white-material", Material.WHITE_STAINED_GLASS_PANE)),
            guiString(gui, "detail.background.white-name", " "));
        for (int slot : MULTI_DETAIL_BACKGROUND_GRAY) {
            inventory.setItem(slot, gray.clone());
        }
        for (int slot : MULTI_DETAIL_BACKGROUND_WHITE) {
            inventory.setItem(slot, white.clone());
        }
    }

    private String buildProgressBar(double progressPercent) {
        int totalBars = 10;
        int filled = (int) Math.round(Math.max(0D, Math.min(100D, progressPercent)) / 100.0D * totalBars);
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < totalBars; i++) {
            builder.append(i < filled ? MULTI_PROGRESS_BAR_FILLED : MULTI_PROGRESS_BAR_EMPTY);
        }
        builder.append("&r");
        return builder.toString();
    }

    private Material defaultCategoryMaterial(MultiplierCategory category) {
        return switch (category) {
            case CROPS -> Material.WHEAT;
            case ORES -> Material.DIAMOND;
            case MOB_DROPS -> Material.BONE;
            case NATURAL_ITEMS -> Material.OAK_LEAVES;
            case ARMOR_AND_TOOLS -> Material.NETHERITE_HELMET;
            case FISH -> Material.TROPICAL_FISH;
            case ENCHANTED_BOOKS -> Material.ENCHANTED_BOOK;
            case POTIONS -> Material.BREWING_STAND;
            case BLOCKS -> Material.BRICKS;
        };
    }

    private String defaultCategoryName(MultiplierCategory category) {
        return switch (category) {
            case CROPS -> "&#00F986ᴄʀᴏᴘꜱ";
            case ORES -> "&#00F986ᴏʀᴇꜱ";
            case MOB_DROPS -> "&#00F986ᴍᴏʙ ᴅʀᴏᴘꜱ";
            case NATURAL_ITEMS -> "&#00F986ɴᴀᴛᴜʀᴀʟ ɪᴛᴇᴍꜱ";
            case ARMOR_AND_TOOLS -> "&#00F986ᴀʀᴍᴏʀ ᴀɴᴅ ᴛᴏᴏʟꜱ";
            case FISH -> "&#00F986ꜰɪꜱʜ";
            case ENCHANTED_BOOKS -> "&#00F986ᴇɴᴄʜᴀɴᴛᴇᴅ ʙᴏᴏᴋꜱ";
            case POTIONS -> "&#00F986ᴘᴏᴛɪᴏɴꜱ";
            case BLOCKS -> "&#00F986ʙʟᴏᴄᴋꜱ";
        };
    }

    private String defaultCategoryShortName(MultiplierCategory category) {
        return switch (category) {
            case CROPS -> "ᴄʀᴏᴘꜱ";
            case ORES -> "ᴏʀᴇꜱ";
            case MOB_DROPS -> "ᴍᴏʙ ᴅʀᴏᴘꜱ";
            case NATURAL_ITEMS -> "ɴᴀᴛᴜʀᴀʟ ɪᴛᴇᴍꜱ";
            case ARMOR_AND_TOOLS -> "ᴀʀᴍᴏʀ ᴀɴᴅ ᴛᴏᴏʟꜱ";
            case FISH -> "ꜰɪꜱʜ";
            case ENCHANTED_BOOKS -> "ᴇɴᴄʜᴀɴᴛᴇᴅ ʙᴏᴏᴋꜱ";
            case POTIONS -> "ᴘᴏᴛɪᴏɴꜱ";
            case BLOCKS -> "ʙʟᴏᴄᴋꜱ";
        };
    }

    private String defaultCategoryLineOne(MultiplierCategory category) {
        return switch (category) {
            case CROPS -> "&7Sell crops and farming materials to";
            case ORES -> "&7Sell ores and mining materials to";
            case MOB_DROPS -> "&7Sell mob drops and loot to";
            case NATURAL_ITEMS -> "&7Sell natural materials and trees to";
            case ARMOR_AND_TOOLS -> "&7Sell armor and tools to";
            case FISH -> "&7Sell fish and other fishing loot to";
            case ENCHANTED_BOOKS -> "&7Sell books and enchanted books to";
            case POTIONS -> "&7Sell brewing and brewing materials to";
            case BLOCKS -> "&7Sell blocks and placeable items to";
        };
    }

    private ItemStack buildConfiguredItem(YamlConfiguration gui, String path, Material fallbackMaterial, String fallbackName, Map<String, String> placeholders) {
        Material material = guiMaterial(gui, path + ".material", fallbackMaterial);
        String name = applyPlaceholders(guiString(gui, path + ".name", fallbackName), placeholders);
        List<String> lore = guiStringList(gui, path + ".lore");
        List<String> resolved = lore.stream().map(line -> applyPlaceholders(line, placeholders)).toList();
        return buildItem(new ItemStack(material), name, resolved.toArray(new String[0]));
    }

    private String applyPlaceholders(String value, Map<String, String> placeholders) {
        String result = value;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            result = result.replace(entry.getKey(), entry.getValue());
        }
        return result;
    }

    private YamlConfiguration guiConfig(String fileName) {
        String language = plugin.getConfig().getString("language", "en");
        Path file = plugin.getDataFolder().toPath().resolve("guis").resolve(language).resolve(fileName);
        if (!file.toFile().exists()) {
            file = plugin.getDataFolder().toPath().resolve("guis").resolve("en").resolve(fileName);
        }
        return YamlConfiguration.loadConfiguration(file.toFile());
    }

    private String guiString(YamlConfiguration gui, String path, String fallback) {
        return gui.getString(path, fallback);
    }

    private List<String> guiStringList(YamlConfiguration gui, String path) {
        return gui.getStringList(path);
    }

    private Material guiMaterial(YamlConfiguration gui, String path, Material fallback) {
        Material result = Material.matchMaterial(gui.getString(path, fallback.name()));
        return result != null ? result : fallback;
    }

    private String color(String input) {
        return ColorUtil.color(input);
    }

    private ItemStack buildItem(ItemStack item, String displayName, String... loreLines) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        meta.setDisplayName(ColorUtil.noItalic(displayName));
        List<String> lore = new ArrayList<>();
        for (String line : loreLines) {
            lore.add(ColorUtil.noItalic(line));
        }
        meta.setLore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
    }

    private void playSound(Player player, String soundName) {
        Sound sound = soundByName(soundName);
        if (sound != null) {
            player.playSound(player.getLocation(), sound, 1.0F, 1.0F);
        }
    }

    private Sound soundByName(String soundName) {
        if (soundName == null || soundName.isBlank()) {
            return null;
        }
        String key = soundName.trim().toLowerCase(Locale.ROOT);
        if (key.startsWith("minecraft:")) {
            key = key.substring("minecraft:".length());
        }
        Sound sound = Registry.SOUNDS.get(NamespacedKey.minecraft(key));
        if (sound != null) {
            return sound;
        }
        return Registry.SOUNDS.get(NamespacedKey.minecraft(key.replace('_', '.')));
    }

    private boolean isHotbarMoveAndReadd(InventoryAction action) {
        return action != null && "HOTBAR_MOVE_AND_READD".equals(action.name());
    }

    private void playButtonClick(Player player) {
        playSound(player, plugin.getSounds().getString("button-click", "UI_BUTTON_CLICK"));
    }

    public enum WorthSort {
        HIGHEST_PRICE,
        LOWEST_PRICE,
        NAME;

        public WorthSort next() {
            return switch (this) {
                case HIGHEST_PRICE -> LOWEST_PRICE;
                case LOWEST_PRICE -> NAME;
                case NAME -> HIGHEST_PRICE;
            };
        }

        public String displayName(YamlConfiguration gui) {
            return switch (this) {
                case HIGHEST_PRICE -> gui.getString("labels.sort-highest", "Highest Price");
                case LOWEST_PRICE -> gui.getString("labels.sort-lowest", "Lowest Price");
                case NAME -> gui.getString("labels.sort-name", "By name");
            };
        }
    }

    public enum WorthFilter {
        ALL, BLOCKS, TOOLS, FOOD, COMBAT, POTIONS, BOOKS, INGREDIENTS, UTILITIES;

        public WorthFilter next() {
            WorthFilter[] values = values();
            return values[(ordinal() + 1) % values.length];
        }

        public String displayName(YamlConfiguration gui) {
            return gui.getString("labels.filter-" + name().toLowerCase(Locale.ROOT), switch (this) {
                case ALL -> "All";
                case BLOCKS -> "Blocks";
                case TOOLS -> "Tools";
                case FOOD -> "Food";
                case COMBAT -> "Combat";
                case POTIONS -> "Potions";
                case BOOKS -> "Books";
                case INGREDIENTS -> "Ingredients";
                case UTILITIES -> "Utilities";
            });
        }

        public boolean matches(Material material) {
            return switch (this) {
                case ALL -> true;
                case BLOCKS -> material.isBlock();
                case TOOLS -> nameContains(material, "PICKAXE", "AXE", "HOE", "SHOVEL", "SHEARS", "FISHING_ROD") || material == Material.FLINT_AND_STEEL;
                case FOOD -> material.isEdible() || nameContains(material, "APPLE", "BREAD", "POTATO", "CARROT", "BEEF", "PORK", "CHICKEN", "MUTTON", "RABBIT", "STEW", "COOKIE", "PIE", "MELON", "BERRIES", "KELP", "FISH");
                case COMBAT -> nameContains(material, "SWORD", "HELMET", "CHESTPLATE", "LEGGINGS", "BOOTS", "TRIDENT", "BOW", "CROSSBOW") || material == Material.SHIELD || material == Material.TOTEM_OF_UNDYING;
                case POTIONS -> nameContains(material, "POTION") || material == Material.BREWING_STAND || material == Material.BLAZE_POWDER || material == Material.NETHER_WART || material == Material.GLISTERING_MELON_SLICE;
                case BOOKS -> material == Material.BOOK || material == Material.ENCHANTED_BOOK || material == Material.WRITABLE_BOOK || material == Material.WRITTEN_BOOK || material == Material.KNOWLEDGE_BOOK;
                case INGREDIENTS -> !material.isBlock() && !TOOLS.matches(material) && !FOOD.matches(material) && !COMBAT.matches(material) && !POTIONS.matches(material) && !BOOKS.matches(material) && !UTILITIES.matches(material);
                case UTILITIES -> material == Material.ANVIL || material == Material.HOPPER || material == Material.BUCKET || material == Material.WATER_BUCKET || material == Material.LAVA_BUCKET || material == Material.CLOCK || material == Material.COMPASS || material == Material.ENCHANTING_TABLE || material == Material.CAULDRON || material == Material.CRAFTING_TABLE || material == Material.ENDER_CHEST;
            };
        }

        private static boolean nameContains(Material material, String... terms) {
            String name = material.name();
            for (String term : terms) {
                if (name.contains(term)) {
                    return true;
                }
            }
            return false;
        }
    }

    public record SellHolder() implements InventoryHolder {
        @Override public Inventory getInventory() { return Bukkit.createInventory(this, 36); }
    }

    public record WorthHolder(int page, WorthFilter filter, WorthSort sort) implements InventoryHolder {
        @Override public Inventory getInventory() { return Bukkit.createInventory(this, 27); }
    }

    public record SellHistoryHolder(int page, boolean sortByAmount) implements InventoryHolder {
        @Override public Inventory getInventory() { return Bukkit.createInventory(this, 54); }
    }

    public record MultiHolder() implements InventoryHolder {
        @Override public Inventory getInventory() { return Bukkit.createInventory(this, 27); }
    }

    public record MultiDetailHolder(MultiplierCategory category) implements InventoryHolder {
        @Override public Inventory getInventory() { return Bukkit.createInventory(this, 54); }
    }
}
