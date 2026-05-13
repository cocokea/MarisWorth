package com.maris7.worth;

import com.maris7.worth.command.MarisWorthCommand;
import com.maris7.worth.config.FileRegistry;
import com.maris7.worth.gui.GuiController;
import com.maris7.worth.listener.InventoryRefreshListener;
import com.maris7.worth.listener.PlayerCacheListener;
import com.maris7.worth.packet.NmsWorthPacketListener;
import com.maris7.worth.placeholder.MarisWorthExpansion;
import com.maris7.worth.platform.SchedulerAdapter;
import com.maris7.worth.price.MultiplierCategory;
import com.maris7.worth.price.PriceRegistry;
import com.maris7.worth.price.WorthService;
import com.maris7.worth.storage.DatabaseManager;
import com.maris7.worth.storage.DatabaseManager.MultiplierState;
import com.maris7.worth.util.ColorUtil;
import com.maris7.worth.util.ItemUtil;
import com.maris7.worth.util.NumberFormatUtil;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import net.milkbowl.vault.economy.Economy;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

public final class MarisWorthPlugin extends JavaPlugin {
    private final Map<UUID, Map<MultiplierCategory, MultiplierState>> multiplierCache = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> topInventoryInjectionCache = new ConcurrentHashMap<>();
    private final Map<UUID, String> topInventoryTypeCache = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> topInventorySizeCache = new ConcurrentHashMap<>();
    private final Map<UUID, Map<Integer, String>> topInventoryStaticItemsCache = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> creativeModeCache = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> loreEnabledCache = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, List<ItemStack>> pendingReturnedItems = new ConcurrentHashMap<>();
    private final Map<Integer, String> topNameCache = new ConcurrentHashMap<>();
    private final Map<Integer, String> topValueCache = new ConcurrentHashMap<>();
    private Economy economy;
    private FileConfiguration sounds;
    private FileConfiguration messages;
    private PriceRegistry priceRegistry;
    private WorthService worthService;
    private DatabaseManager databaseManager;
    private SchedulerAdapter schedulerAdapter;
    private GuiController guiController;
    private SettingsHook settingsHook;
    private NmsWorthPacketListener nmsWorthPacketListener;

    @Override
    public void onEnable() {
        ensureFiles();
        reloadConfig();
        this.sounds = YamlConfiguration.loadConfiguration(getDataFolder().toPath().resolve("sounds.yml").toFile());
        this.messages = loadMessages();
        this.schedulerAdapter = new SchedulerAdapter(this);
        this.settingsHook = new SettingsHook(this);
        this.priceRegistry = new PriceRegistry(this);
        this.priceRegistry.reload();
        this.worthService = new WorthService(priceRegistry);
        refreshSellBlacklist();
        this.databaseManager = new DatabaseManager(this);
        this.databaseManager.start();
        this.guiController = new GuiController(this);
        if (!setupEconomy()) {
            getLogger().severe("Vault economy provider was not found.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        Bukkit.getPluginManager().registerEvents(guiController, this);
        Bukkit.getPluginManager().registerEvents(new PlayerCacheListener(this), this);
        Bukkit.getPluginManager().registerEvents(new InventoryRefreshListener(this), this);
        MarisWorthCommand executor = new MarisWorthCommand(this);
        Objects.requireNonNull(getCommand("sell")).setExecutor(executor);
        Objects.requireNonNull(getCommand("worth")).setExecutor(executor);
        Objects.requireNonNull(getCommand("worth")).setTabCompleter(executor);
        Objects.requireNonNull(getCommand("sellhistory")).setExecutor(executor);
        Objects.requireNonNull(getCommand("sellmulti")).setExecutor(executor);
        try {
            this.nmsWorthPacketListener = new NmsWorthPacketListener(this);
            this.nmsWorthPacketListener.register();
        } catch (ReflectiveOperationException exception) {
            getLogger().severe("Failed to enable NMS worth lore packet hook: " + exception.getMessage());
        }
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            new MarisWorthExpansion(this).register();
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            preloadMultipliers(player.getUniqueId());
            schedulerAdapter.runLater(player, 1L, () -> updateInjectTopInventory(player));
        }
        refreshTopStatsAsync();
    }

    @Override
    public void onDisable() {
        if (nmsWorthPacketListener != null) {
            nmsWorthPacketListener.unregister();
        }
        if (databaseManager != null) {
            databaseManager.stop();
        }
        multiplierCache.clear();
        topInventoryInjectionCache.clear();
        topInventoryTypeCache.clear();
        topInventorySizeCache.clear();
        topInventoryStaticItemsCache.clear();
        creativeModeCache.clear();
        loreEnabledCache.clear();
        pendingReturnedItems.clear();
        topNameCache.clear();
        topValueCache.clear();
    }

    public SellResult sellInventory(Player player, Inventory inventory) {
        Map<MultiplierCategory, MultiplierState> states = new EnumMap<>(getMultipliers(player));
        double total = 0D;
        List<ItemStack> leftovers = new ArrayList<>();
        List<SaleRecord> sales = new ArrayList<>();
        boolean rejectedBlacklisted = false;
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || item.getType() == Material.AIR) {
                continue;
            }
            if (isSellBlacklisted(item)) {
                rejectedBlacklisted = true;
                leftovers.add(item.clone());
                inventory.setItem(slot, null);
                continue;
            }
            MultiplierCategory category = MultiplierCategory.detect(item.getType());
            MultiplierState state = states.getOrDefault(category, defaultMultiplierState(category));
            double worth = worthService.calculate(item, state.multiplier());
            if (worth <= 0D) {
                leftovers.add(item.clone());
                inventory.setItem(slot, null);
                continue;
            }
            total += worth;
            sales.add(new SaleRecord(item.clone(), ItemUtil.displayName(item), (long) item.getAmount(), worth));
            states.put(category, advanceState(category, state, worth));
            inventory.setItem(slot, null);
        }
        saveMultiplierState(player.getUniqueId(), states);
        flushSalesAsync(player.getUniqueId(), sales);
        refreshTopStatsAsync();
        returnLeftovers(player, leftovers);
        return new SellResult(total, rejectedBlacklisted);
    }

    public void finalizeSell(Player player, SellResult result) {
        if (result.rejectedBlacklisted()) {
            ColorUtil.send(player, message("sell-blacklisted"));
        }
        if (result.total() <= 0D) {
            return;
        }
        String formatted = NumberFormatUtil.format(result.total());
        String chat = getConfig().getString("sell.success-message", "&#95FF00+$%earn%").replace("%earn%", formatted);
        String actionbar = getConfig().getString("sell.success-actionbar", "&#95FF00+$%earn%").replace("%earn%", formatted);
        schedulerAdapter.runGlobal(() -> {
            if (!player.isOnline()) {
                return;
            }
            economy.depositPlayer(player, result.total());
            player.sendMessage(ColorUtil.color(chat));
            player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(ColorUtil.noItalic(actionbar)));
            playConfiguredSound(player, "sell-success", "ENTITY_EXPERIENCE_ORB_PICKUP");
        });
    }

    public void sellContainerContents(Player player, Inventory inventory, String sourceName) {
        if (player == null || inventory == null) {
            return;
        }
        SellResult result = sellInventory(player, inventory);
        finalizeSell(player, result);
    }

    public void preloadMultipliers(UUID playerId) {
        databaseManager.loadMultipliersAsync(playerId)
            .thenApply(this::normalizeStates)
            .thenAccept(states -> multiplierCache.put(playerId, states))
            .exceptionally(exception -> {
                getLogger().warning("Failed to preload multipliers for " + playerId + ": " + exception.getMessage());
                return null;
            });
    }

    public String currentWorth(Player player, ItemStack item) {
        MultiplierState state = getMultiplierState(player.getUniqueId(), MultiplierCategory.detect(item.getType()));
        return NumberFormatUtil.format(worthService.calculateUnit(item, state.multiplier()));
    }

    public boolean isSellBlacklisted(ItemStack item) {
        return item != null && isSellBlacklisted(item.getType());
    }

    public boolean isSellBlacklisted(Material material) {
        return worthService.isBlacklisted(material);
    }

    public Set<Material> getSellBlacklist() {
        EnumSet<Material> materials = EnumSet.noneOf(Material.class);
        for (String entry : getConfig().getStringList("sell.blacklist-sell")) {
            Material material = Material.matchMaterial(entry);
            if (material != null) {
                materials.add(material);
            }
        }
        return materials;
    }

    public boolean isSupportedInventory(InventoryType type) {
        return isSupportedInventory(type, cachedTopInventorySize(null));
    }

    public boolean isSupportedInventory(InventoryType type, int topSize) {
        List<String> supported = getConfig().getStringList("packet-lore.supported-inventories");
        if (type == InventoryType.CHEST && topSize >= 54 && supported.contains("LARGE_CHEST")) {
            return true;
        }
        return supported.contains(type.name());
    }

    public boolean isCreativeMode(UUID playerId) {
        Player player = Bukkit.getPlayer(playerId);
        if (player != null) {
            boolean creative = player.getGameMode() == GameMode.CREATIVE;
            creativeModeCache.put(playerId, creative);
            return creative;
        }
        return creativeModeCache.getOrDefault(playerId, false);
    }

    public InventoryType cachedTopInventoryType(UUID playerId) {
        String typeName = topInventoryTypeCache.get(playerId);
        if (typeName == null || typeName.isBlank()) {
            return InventoryType.CRAFTING;
        }
        try {
            return InventoryType.valueOf(typeName);
        } catch (IllegalArgumentException exception) {
            return InventoryType.CRAFTING;
        }
    }

    public int cachedTopInventorySize(UUID playerId) {
        return playerId == null ? 46 : topInventorySizeCache.getOrDefault(playerId, 46);
    }

    public boolean shouldInjectTopInventory(UUID playerId) {
        return topInventoryInjectionCache.getOrDefault(playerId, true) && isLoreEnabledCached(playerId) && isOperational();
    }

    public boolean shouldDisplayWorthPacket(UUID playerId, int rawSlot) {
        if (!isLoreEnabledCached(playerId) || !isOperational()) {
            return false;
        }
        if (getConfig().getBoolean("packet-lore.skip-creative", true) && isCreativeMode(playerId)) {
            return false;
        }
        if (!shouldInjectTopInventory(playerId)) {
            return false;
        }
        InventoryType type = cachedTopInventoryType(playerId);
        int topSize = cachedTopInventorySize(playerId);
        return isSupportedInventory(type, topSize);
    }

    public boolean isStaticTopInventoryItem(UUID playerId, int rawSlot, ItemStack item) {
        if (playerId == null || rawSlot < 0 || rawSlot >= cachedTopInventorySize(playerId) || item == null || item.getType().isAir()) {
            return false;
        }
        Map<Integer, String> staticItems = topInventoryStaticItemsCache.get(playerId);
        if (staticItems == null || staticItems.isEmpty()) {
            return false;
        }
        String expected = staticItems.get(rawSlot);
        return expected != null && expected.equals(itemFingerprint(item));
    }

    public void updateInjectTopInventory(Player player) {
        UUID playerId = player.getUniqueId();
        Inventory top = player.getOpenInventory().getTopInventory();
        InventoryType type = top.getType();
        boolean shouldInject = true;
        topInventoryInjectionCache.put(playerId, shouldInject);
        topInventoryTypeCache.put(playerId, type.name());
        topInventorySizeCache.put(playerId, top.getSize());
        boolean creative = player.getGameMode() == GameMode.CREATIVE;
        creativeModeCache.put(playerId, creative);
        loreEnabledCache.put(playerId, isLoreEnabled(playerId));
    }

    public void captureStaticTopInventoryItems(Player player) {
        UUID playerId = player.getUniqueId();
        Inventory top = player.getOpenInventory().getTopInventory();
        InventoryType type = top.getType();
        if (top.getLocation() != null || type == InventoryType.CRAFTING || type == InventoryType.PLAYER || type == InventoryType.ENDER_CHEST) {
            topInventoryStaticItemsCache.remove(playerId);
            return;
        }

        Map<Integer, String> staticItems = new HashMap<>();
        for (int slot = 0; slot < top.getSize(); slot++) {
            ItemStack item = top.getItem(slot);
            if (item != null && !item.getType().isAir()) {
                staticItems.put(slot, itemFingerprint(item));
            }
        }
        if (staticItems.isEmpty()) {
            topInventoryStaticItemsCache.remove(playerId);
        } else {
            topInventoryStaticItemsCache.put(playerId, staticItems);
        }
    }

    private String itemFingerprint(ItemStack item) {
        byte[] bytes = item.serializeAsBytes();
        return bytes.length + ":" + java.util.Arrays.hashCode(bytes);
    }

    public void clearTopInventoryCache(UUID playerId) {
        topInventoryInjectionCache.remove(playerId);
        topInventoryTypeCache.remove(playerId);
        topInventorySizeCache.remove(playerId);
        topInventoryStaticItemsCache.remove(playerId);
    }

    public String message(String key) {
        String prefix = messages.getString("prefix", "");
        return messages.getString(key, key).replace("%prefix%", prefix);
    }

    public boolean isOperational() {
        return databaseManager == null || databaseManager.isAvailable();
    }

    public String unavailableMessage() {
        return "&cMySQL timed out. Please reconnect!";
    }

    public boolean isLoreEnabled(UUID playerId) {
        if (!getConfig().getBoolean("packet-lore.enabled", true)) {
            return false;
        }
        return settingsHook == null || settingsHook.isEnabled(playerId, "WORTHT_TOGGLE", true);
    }

    public boolean isLoreEnabledCached(UUID playerId) {
        if (!getConfig().getBoolean("packet-lore.enabled", true)) {
            return false;
        }
        return loreEnabledCache.getOrDefault(playerId, true);
    }



    public PriceRegistry getPriceRegistry() {
        return priceRegistry;
    }

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    public WorthService getWorthService() {
        return worthService;
    }

    public MultiplierCategory detectCategory(ItemStack item) {
        return MultiplierCategory.detect(item.getType());
    }

    public SchedulerAdapter getSchedulerAdapter() {
        return schedulerAdapter;
    }

    public GuiController getGuiController() {
        return guiController;
    }

    public FileConfiguration getSounds() {
        return sounds;
    }

    public MultiplierState getMultiplierState(UUID playerId, MultiplierCategory category) {
        return getMultipliers(playerId).getOrDefault(category, defaultMultiplierState(category));
    }

    public Map<MultiplierCategory, MultiplierState> getMultipliers(Player player) {
        return getMultipliers(player.getUniqueId());
    }

    public Map<MultiplierCategory, MultiplierState> getMultipliers(UUID playerId) {
        return multiplierCache.computeIfAbsent(playerId, uuid -> normalizeStates(Map.of()));
    }

    public void saveMultiplierState(UUID playerId, Map<MultiplierCategory, MultiplierState> states) {
        Map<MultiplierCategory, MultiplierState> normalized = normalizeStates(states);
        multiplierCache.put(playerId, normalized);
        databaseManager.saveMultipliersAsync(playerId, normalized)
            .exceptionally(exception -> {
                getLogger().warning("Failed to save multipliers for " + playerId + ": " + exception.getMessage());
                return null;
            });
    }

    public void forgetPlayer(UUID playerId) {
        multiplierCache.remove(playerId);
        topInventoryInjectionCache.remove(playerId);
        topInventoryTypeCache.remove(playerId);
        topInventorySizeCache.remove(playerId);
        topInventoryStaticItemsCache.remove(playerId);
        creativeModeCache.remove(playerId);
        loreEnabledCache.remove(playerId);
    }

    public String topName(int position) {
        return topNameCache.getOrDefault(Math.max(1, position), "");
    }

    public String topValue(int position) {
        return topValueCache.getOrDefault(Math.max(1, position), NumberFormatUtil.format(0D));
    }

    public void reloadRuntime() {
        reloadConfig();
        this.sounds = YamlConfiguration.loadConfiguration(getDataFolder().toPath().resolve("sounds.yml").toFile());
        this.messages = loadMessages();
        this.priceRegistry.reload();
        refreshSellBlacklist();
        setupEconomy();
        multiplierCache.clear();
        topInventoryInjectionCache.clear();
        topInventoryTypeCache.clear();
        topInventorySizeCache.clear();
        topInventoryStaticItemsCache.clear();
        creativeModeCache.clear();
        loreEnabledCache.clear();
        pendingReturnedItems.clear();
        topNameCache.clear();
        topValueCache.clear();
        refreshTopStatsAsync();
    }

    private Map<MultiplierCategory, MultiplierState> normalizeStates(Map<MultiplierCategory, MultiplierState> source) {
        Map<MultiplierCategory, MultiplierState> map = new EnumMap<>(MultiplierCategory.class);
        for (MultiplierCategory category : MultiplierCategory.values()) {
            MultiplierState state = source.getOrDefault(category, defaultMultiplierState(category));
            map.put(category, normalizeState(category, state));
        }
        return map;
    }

    private MultiplierState defaultMultiplierState(MultiplierCategory category) {
        return normalizeState(category, new MultiplierState(1.0D, 0D, getSellMultiRequiredPerLevel(category)));
    }

    private MultiplierState normalizeState(MultiplierCategory category, MultiplierState state) {
        double soldTotal = Math.max(0D, state.soldTotal());
        double requiredPerLevel = Math.max(1D, getSellMultiRequiredPerLevel(category));
        double multiplierStep = Math.max(0D, getSellMultiMultiplierStep(category));
        int completedLevels = (int) Math.floor(soldTotal / requiredPerLevel);
        double multiplier = 1.0D + (completedLevels * multiplierStep);
        double nextThreshold = (completedLevels + 1D) * requiredPerLevel;
        return new MultiplierState(multiplier, soldTotal, nextThreshold);
    }

    private MultiplierState advanceState(MultiplierCategory category, MultiplierState state, double worth) {
        return normalizeState(category, new MultiplierState(state.multiplier(), state.soldTotal() + worth, state.nextThreshold()));
    }

    public double getSellMultiRequiredPerLevel(MultiplierCategory category) {
        String base = "sellmulti.categories." + category.configKey().toLowerCase(Locale.ROOT);
        return Math.max(1D, getConfig().getDouble(base + ".required-per-level", getConfig().getDouble("sellmulti.required-per-level", 50000D)));
    }

    public double getSellMultiMultiplierStep(MultiplierCategory category) {
        String base = "sellmulti.categories." + category.configKey().toLowerCase(Locale.ROOT);
        return Math.max(0D, getConfig().getDouble(base + ".multiplier-step", getConfig().getDouble("sellmulti.multiplier-step", 0.1D)));
    }

    public int getSellMultiDetailLevels(MultiplierCategory category) {
        String base = "sellmulti.categories." + category.configKey().toLowerCase(Locale.ROOT);
        return Math.max(1, getConfig().getInt(base + ".detail-levels", getConfig().getInt("sellmulti.detail-levels", 28)));
    }

    public List<SellMultiTier> getSellMultiTiers(MultiplierCategory category) {
        double requiredPerLevel = getSellMultiRequiredPerLevel(category);
        double multiplierStep = getSellMultiMultiplierStep(category);
        int detailLevels = getSellMultiDetailLevels(category);
        List<SellMultiTier> tiers = new ArrayList<>();
        for (int level = 1; level <= detailLevels; level++) {
            tiers.add(new SellMultiTier(1.0D + (level * multiplierStep), level * requiredPerLevel));
        }
        return tiers;
    }

    public SellMultiTier getNextSellMultiTier(MultiplierCategory category, double soldTotal) {
        double requiredPerLevel = getSellMultiRequiredPerLevel(category);
        double multiplierStep = getSellMultiMultiplierStep(category);
        int detailLevels = getSellMultiDetailLevels(category);
        if (Math.max(0D, soldTotal) >= (detailLevels * requiredPerLevel)) {
            return null;
        }
        int nextLevel = (int) Math.floor(Math.max(0D, soldTotal) / requiredPerLevel) + 1;
        return new SellMultiTier(1.0D + (nextLevel * multiplierStep), nextLevel * requiredPerLevel);
    }

    private void flushSalesAsync(UUID playerId, List<SaleRecord> sales) {
        for (SaleRecord sale : sales) {
            databaseManager.recordSaleAsync(playerId, sale.item(), sale.itemName(), sale.amount(), sale.totalPrice())
                .exceptionally(exception -> {
                    getLogger().warning("Failed to record sale for " + playerId + ": " + exception.getMessage());
                    return null;
                });
        }
    }

    private void returnLeftovers(Player player, List<ItemStack> leftovers) {
        for (ItemStack leftover : leftovers) {
            Map<Integer, ItemStack> failed = player.getInventory().addItem(leftover);
            if (!failed.isEmpty()) {
                Location dropLocation = player.getLocation();
                failed.values().forEach(item -> schedulerAdapter.runAtLocation(dropLocation, () -> {
                    if (dropLocation.getWorld() == null) {
                        queuePendingReturnedItems(player.getUniqueId(), List.of(item));
                        getLogger().warning("Failed to drop returned item for " + player.getUniqueId() + " because the world was unavailable. Item was queued for later delivery.");
                        return;
                    }
                    Item dropped = dropLocation.getWorld().dropItemNaturally(dropLocation, item);
                    dropped.setOwner(player.getUniqueId());
                }));
            }
        }
    }

    public ItemStack injectWorthForDisplay(UUID playerId, ItemStack item) {
        if (!isLoreEnabled(playerId) || !isOperational()) {
            return item;
        }
        return injectWorthLore(playerId, item);
    }

    public ItemStack injectWorthForPacket(UUID playerId, ItemStack item) {
        if (!isLoreEnabledCached(playerId) || !isOperational()) {
            return item;
        }
        if (getConfig().getBoolean("packet-lore.skip-creative", true) && isCreativeMode(playerId)) {
            return item;
        }
        return injectWorthLore(playerId, item);
    }

    private ItemStack injectWorthLore(UUID playerId, ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return item;
        }

        ItemStack clone = item.clone();
        org.bukkit.inventory.meta.ItemMeta meta = clone.getItemMeta();
        if (meta == null) {
            return clone;
        }

        List<String> lore = meta.hasLore() && meta.getLore() != null ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
        lore.removeIf(line -> ColorUtil.strip(line).startsWith("Worth:"));

        ItemStack pricingView = clone.clone();
        org.bukkit.inventory.meta.ItemMeta pricingMeta = pricingView.getItemMeta();
        if (pricingMeta != null) {
            if (pricingMeta.hasDisplayName()) {
                pricingMeta.setDisplayName(ColorUtil.noItalic(pricingMeta.getDisplayName()));
            }
            pricingMeta.setLore(lore.isEmpty() ? null : new ArrayList<>(lore));
            pricingView.setItemMeta(pricingMeta);
        }

        double worth = worthService.calculate(pricingView, getMultiplierState(playerId, detectCategory(pricingView)).multiplier());

        if (meta.hasDisplayName()) {
            meta.setDisplayName(ColorUtil.noItalic(meta.getDisplayName()));
        }
        String worthLine = getConfig().getString("sell.lore-format", "&7Worth: &a$%prices%")
            .replace("%prices%", NumberFormatUtil.format(worth));
        lore.add(ColorUtil.noItalic(worthLine));
        meta.setLore(lore);
        clone.setItemMeta(meta);
        return clone;
    }

    private void refreshTopStatsAsync() {
        for (int position = 1; position <= 10; position++) {
            final int currentPosition = position;
            databaseManager.topNameAsync(currentPosition)
                .thenApplyAsync(uuid -> resolveTopPlayerName(uuid), databaseManager.getExecutor())
                .thenAccept(name -> schedulerAdapter.runGlobal(() -> topNameCache.put(currentPosition, name)))
                .exceptionally(exception -> {
                getLogger().warning("Failed to refresh top name cache at position " + currentPosition + ": " + exception.getMessage());
                return null;
            });
            databaseManager.topValueAsync(currentPosition).thenAccept(value -> topValueCache.put(currentPosition, NumberFormatUtil.format(value)))
                .exceptionally(exception -> {
                    getLogger().warning("Failed to refresh top value cache at position " + currentPosition + ": " + exception.getMessage());
                    return null;
                });
        }
    }

    public void queuePendingReturnedItems(UUID playerId, List<ItemStack> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        pendingReturnedItems.merge(playerId, snapshotItems(items), (existing, incoming) -> {
            List<ItemStack> merged = new ArrayList<>(existing);
            merged.addAll(incoming);
            return merged;
        });
    }

    public void deliverPendingReturnedItems(Player player) {
        List<ItemStack> pending = pendingReturnedItems.remove(player.getUniqueId());
        if (pending == null || pending.isEmpty()) {
            return;
        }
        returnLeftovers(player, pending);
    }

    private List<ItemStack> snapshotItems(List<ItemStack> items) {
        List<ItemStack> snapshot = new ArrayList<>();
        for (ItemStack item : items) {
            if (item == null || item.getType().isAir()) {
                continue;
            }
            snapshot.add(item.clone());
        }
        return Collections.unmodifiableList(snapshot);
    }

    private void playConfiguredSound(Player player, String path, String fallback) {
        Sound sound = soundByName(sounds.getString(path, fallback));
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

    private boolean setupEconomy() {
        RegisteredServiceProvider<Economy> provider = getServer().getServicesManager().getRegistration(Economy.class);
        if (provider == null) {
            return false;
        }
        this.economy = provider.getProvider();
        return economy != null;
    }

    private FileConfiguration loadMessages() {
        return YamlConfiguration.loadConfiguration(getDataFolder().toPath().resolve("message").resolve("message_" + getConfig().getString("language", "en") + ".yml").toFile());
    }

    private String resolveTopPlayerName(String uuid) {
        if (uuid == null || uuid.isBlank()) {
            return "";
        }
        try {
            OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(UUID.fromString(uuid));
            return offlinePlayer.getName() == null ? uuid : offlinePlayer.getName();
        } catch (IllegalArgumentException exception) {
            return uuid;
        }
    }

    private void refreshSellBlacklist() {
        worthService.setSellBlacklist(getSellBlacklist());
    }

    private void ensureFiles() {
        FileRegistry registry = new FileRegistry(this);
        for (String path : List.of(
            "config.yml",
            "prices.yml",
            "sounds.yml",
            "message/message_en.yml",
            "message/message_vi.yml",
            "guis/en/sell.yml",
            "guis/vi/sell.yml",
            "guis/en/worth.yml",
            "guis/vi/worth.yml",
            "guis/en/sellhistory.yml",
            "guis/vi/sellhistory.yml",
            "guis/en/sellmulti.yml",
            "guis/vi/sellmulti.yml"
        )) {
            registry.ensureAndMerge(path);
        }
    }

    private record SaleRecord(ItemStack item, String itemName, long amount, double totalPrice) {
    }

    public record SellResult(double total, boolean rejectedBlacklisted) {
    }

    public record SellMultiTier(double multiplier, double required) {
    }



}
