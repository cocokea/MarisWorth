package com.maris7.worth.storage;

import com.maris7.worth.MarisWorthPlugin;
import com.maris7.worth.price.MultiplierCategory;
import com.maris7.worth.util.ItemUtil;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemStack;

public final class DatabaseManager {
    private final MarisWorthPlugin plugin;
    private HikariDataSource dataSource;
    private boolean mysql;
    private ExecutorService executor;
    private final AtomicBoolean available = new AtomicBoolean(true);
    private volatile int healthCheckTaskId = -1;
    private volatile Object foliaHealthTask;

    public DatabaseManager(MarisWorthPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        FileConfiguration configuration = plugin.getConfig();
        this.mysql = configuration.getBoolean("database.mysql.enabled", false);
        this.executor = Executors.newSingleThreadExecutor(new DbThreadFactory());
        HikariConfig hikari = new HikariConfig();
        if (mysql) {
            String host = configuration.getString("database.mysql.host", "localhost");
            int port = configuration.getInt("database.mysql.port", 3306);
            String database = configuration.getString("database.mysql.database", "marisworth");
            hikari.setJdbcUrl("jdbc:mysql://" + host + ':' + port + '/' + database + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC");
            hikari.setUsername(configuration.getString("database.mysql.username", "root"));
            hikari.setPassword(configuration.getString("database.mysql.password", ""));
            hikari.setDriverClassName("com.mysql.cj.jdbc.Driver");
        } else {
            File file = plugin.getDataFolder().toPath().resolve("data.db").toFile();
            hikari.setJdbcUrl("jdbc:sqlite:" + file.getAbsolutePath());
            hikari.setDriverClassName("org.sqlite.JDBC");
        }
        int poolSize = Math.max(1, configuration.getInt("database.pool-size", 1));
        hikari.setMaximumPoolSize(poolSize);
        hikari.setPoolName("MarisWorth-Hikari");
        hikari.setConnectionTimeout(10_000L);
        this.dataSource = new HikariDataSource(hikari);
        try {
            CompletableFuture.runAsync(this::bootstrap, executor).get(15L, TimeUnit.SECONDS);
            available.set(true);
        } catch (Exception exception) {
            if (!mysql) {
                throw new IllegalStateException("Failed to bootstrap database", exception);
            }
            available.set(false);
            plugin.getLogger().warning("&cMySQL timed out. Please reconnect!");
        }
        startHealthMonitor();
    }

    public void stop() {
        stopHealthMonitor();
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(10L, TimeUnit.SECONDS)) {
                    plugin.getLogger().warning("Timed out while waiting for pending database tasks to finish.");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                plugin.getLogger().warning("Interrupted while waiting for pending database tasks to finish.");
            }
            executor = null;
        }
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }

    public CompletableFuture<Void> recordSaleAsync(UUID playerId, ItemStack item, String itemName, long amount, double totalPrice) {
        ExecutorService currentExecutor = activeExecutor();
        if (currentExecutor == null) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.runAsync(() -> recordSale(playerId, item, itemName, amount, totalPrice), currentExecutor);
    }

    public CompletableFuture<Map<MultiplierCategory, MultiplierState>> loadMultipliersAsync(UUID playerId) {
        ExecutorService currentExecutor = activeExecutor();
        if (currentExecutor == null) {
            return CompletableFuture.completedFuture(new EnumMap<>(MultiplierCategory.class));
        }
        return CompletableFuture.supplyAsync(() -> loadMultipliers(playerId), currentExecutor);
    }

    public CompletableFuture<Void> saveMultipliersAsync(UUID playerId, Map<MultiplierCategory, MultiplierState> states) {
        ExecutorService currentExecutor = activeExecutor();
        if (currentExecutor == null) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.runAsync(() -> saveMultipliers(playerId, states), currentExecutor);
    }

    public CompletableFuture<List<SellHistoryEntry>> loadHistoryAsync(UUID playerId) {
        ExecutorService currentExecutor = activeExecutor();
        if (currentExecutor == null) {
            return CompletableFuture.completedFuture(List.of());
        }
        return CompletableFuture.supplyAsync(() -> loadHistory(playerId), currentExecutor);
    }

    public CompletableFuture<String> topNameAsync(int position) {
        ExecutorService currentExecutor = activeExecutor();
        if (currentExecutor == null) {
            return CompletableFuture.completedFuture("");
        }
        return CompletableFuture.supplyAsync(() -> topName(position), currentExecutor);
    }

    public CompletableFuture<Double> topValueAsync(int position) {
        ExecutorService currentExecutor = activeExecutor();
        if (currentExecutor == null) {
            return CompletableFuture.completedFuture(0D);
        }
        return CompletableFuture.supplyAsync(() -> topValue(position), currentExecutor);
    }

    public CompletableFuture<Integer> playerPositionAsync(UUID playerId) {
        ExecutorService currentExecutor = activeExecutor();
        if (currentExecutor == null) {
            return CompletableFuture.completedFuture(0);
        }
        return CompletableFuture.supplyAsync(() -> playerPosition(playerId), currentExecutor);
    }

    public CompletableFuture<Double> playerTotalAsync(UUID playerId) {
        ExecutorService currentExecutor = activeExecutor();
        if (currentExecutor == null) {
            return CompletableFuture.completedFuture(0D);
        }
        return CompletableFuture.supplyAsync(() -> playerTotal(playerId), currentExecutor);
    }


    private ExecutorService activeExecutor() {
        return executor;
    }

    public Executor getExecutor() {
        return executor;
    }

    public boolean isAvailable() {
        return available.get();
    }

    public void recordSale(UUID playerId, ItemStack item, String itemName, long amount, double totalPrice) {
        execute("record sale", () -> {
            try (Connection connection = dataSource.getConnection()) {
                try (PreparedStatement history = connection.prepareStatement("INSERT INTO marisworth_history (player_uuid, item_key, item_payload, item_name, total_amount, total_price) VALUES (?, ?, ?, ?, ?, ?)");
                     PreparedStatement stats = connection.prepareStatement(playerStatsUpsertSql())) {
                    history.setString(1, playerId.toString());
                    history.setString(2, item.getType().name());
                    history.setString(3, ItemUtil.serialize(item));
                    history.setString(4, itemName);
                    history.setLong(5, amount);
                    history.setDouble(6, totalPrice);
                    history.executeUpdate();

                    stats.setString(1, playerId.toString());
                    stats.setDouble(2, totalPrice);
                    stats.executeUpdate();
                }
            }
            return null;
        });
    }

    public List<SellHistoryEntry> loadHistory(UUID playerId) {
        return execute("load sell history", () -> {
            List<SellHistoryEntry> entries = new ArrayList<>();
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement("SELECT item_payload, item_name, total_amount, total_price FROM marisworth_history WHERE player_uuid = ? ORDER BY sold_at DESC, id DESC")) {
                statement.setString(1, playerId.toString());
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        entries.add(new SellHistoryEntry(
                            ItemUtil.deserialize(resultSet.getString("item_payload")),
                            resultSet.getString("item_name"),
                            resultSet.getLong("total_amount"),
                            resultSet.getDouble("total_price")
                        ));
                    }
                }
            }
            return entries;
        });
    }

    public Map<MultiplierCategory, MultiplierState> loadMultipliers(UUID playerId) {
        return execute("load multiplier data", () -> {
            Map<MultiplierCategory, MultiplierState> map = new EnumMap<>(MultiplierCategory.class);
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement("SELECT category, sold_total, multiplier, next_threshold FROM marisworth_multiplier WHERE player_uuid = ?")) {
                statement.setString(1, playerId.toString());
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        MultiplierCategory category = MultiplierCategory.valueOf(resultSet.getString("category"));
                        map.put(category, new MultiplierState(resultSet.getDouble("multiplier"), resultSet.getDouble("sold_total"), resultSet.getDouble("next_threshold")));
                    }
                }
            }
            return map;
        });
    }

    public void saveMultipliers(UUID playerId, Map<MultiplierCategory, MultiplierState> states) {
        execute("save multipliers", () -> {
            try (Connection connection = dataSource.getConnection()) {
                for (Map.Entry<MultiplierCategory, MultiplierState> entry : states.entrySet()) {
                    try (PreparedStatement statement = connection.prepareStatement(multiplierUpsertSql())) {
                        statement.setString(1, playerId.toString());
                        statement.setString(2, entry.getKey().name());
                        statement.setDouble(3, entry.getValue().soldTotal());
                        statement.setDouble(4, entry.getValue().multiplier());
                        statement.setDouble(5, entry.getValue().nextThreshold());
                        statement.executeUpdate();
                    }
                }
            }
            return null;
        });
    }

    public String topName(int position) {
        try {
            return execute("load top name", () -> {
                try (Connection connection = dataSource.getConnection();
                     PreparedStatement statement = connection.prepareStatement("SELECT player_uuid FROM marisworth_player_stats ORDER BY total_earned DESC LIMIT 1 OFFSET ?")) {
                    statement.setInt(1, Math.max(0, position - 1));
                    try (ResultSet resultSet = statement.executeQuery()) {
                        return resultSet.next() ? resultSet.getString(1) : "";
                    }
                }
            });
        } catch (IllegalStateException exception) {
            return "";
        }
    }

    public double topValue(int position) {
        try {
            return execute("load top value", () -> {
                try (Connection connection = dataSource.getConnection();
                     PreparedStatement statement = connection.prepareStatement("SELECT total_earned FROM marisworth_player_stats ORDER BY total_earned DESC LIMIT 1 OFFSET ?")) {
                    statement.setInt(1, Math.max(0, position - 1));
                    try (ResultSet resultSet = statement.executeQuery()) {
                        return resultSet.next() ? resultSet.getDouble(1) : 0D;
                    }
                }
            });
        } catch (IllegalStateException exception) {
            return 0D;
        }
    }

    public int playerPosition(UUID playerId) {
        if (playerId == null) {
            return 0;
        }
        try {
            return execute("load player position", () -> {
                try (Connection connection = dataSource.getConnection();
                     PreparedStatement statement = connection.prepareStatement(
                         "SELECT ranked.position FROM (" +
                             "SELECT player_uuid, ROW_NUMBER() OVER (ORDER BY total_earned DESC, player_uuid ASC) AS position " +
                             "FROM marisworth_player_stats" +
                         ") ranked WHERE ranked.player_uuid = ?")) {
                    statement.setString(1, playerId.toString());
                    try (ResultSet resultSet = statement.executeQuery()) {
                        return resultSet.next() ? resultSet.getInt(1) : 0;
                    }
                }
            });
        } catch (IllegalStateException exception) {
            return 0;
        }
    }

    public double playerTotal(UUID playerId) {
        if (playerId == null) {
            return 0D;
        }
        try {
            return execute("load player total", () -> {
                try (Connection connection = dataSource.getConnection();
                     PreparedStatement statement = connection.prepareStatement("SELECT total_earned FROM marisworth_player_stats WHERE player_uuid = ?")) {
                    statement.setString(1, playerId.toString());
                    try (ResultSet resultSet = statement.executeQuery()) {
                        return resultSet.next() ? resultSet.getDouble(1) : 0D;
                    }
                }
            });
        } catch (IllegalStateException exception) {
            return 0D;
        }
    }

    private void bootstrap() {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            if (mysql) {
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS marisworth_history (id BIGINT NOT NULL AUTO_INCREMENT, player_uuid VARCHAR(36) NOT NULL, item_key VARCHAR(64) NOT NULL, item_payload LONGTEXT NOT NULL, item_name TEXT NOT NULL, total_amount BIGINT NOT NULL, total_price DOUBLE NOT NULL, sold_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY (id), INDEX idx_marisworth_history_player_sold_at (player_uuid, sold_at))");
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS marisworth_player_stats (player_uuid VARCHAR(36) NOT NULL, total_earned DOUBLE NOT NULL, PRIMARY KEY (player_uuid))");
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS marisworth_multiplier (player_uuid VARCHAR(36) NOT NULL, category VARCHAR(64) NOT NULL, sold_total DOUBLE NOT NULL, multiplier DOUBLE NOT NULL, next_threshold DOUBLE NOT NULL, PRIMARY KEY (player_uuid, category))");
            } else {
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS marisworth_history (id INTEGER PRIMARY KEY AUTOINCREMENT, player_uuid VARCHAR(36) NOT NULL, item_key VARCHAR(64) NOT NULL, item_payload TEXT NOT NULL, item_name TEXT NOT NULL, total_amount BIGINT NOT NULL, total_price DOUBLE NOT NULL, sold_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)");
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_marisworth_history_player_sold_at ON marisworth_history (player_uuid, sold_at)");
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS marisworth_player_stats (player_uuid VARCHAR(36) PRIMARY KEY, total_earned DOUBLE NOT NULL)");
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS marisworth_multiplier (player_uuid VARCHAR(36) NOT NULL, category VARCHAR(64) NOT NULL, sold_total DOUBLE NOT NULL, multiplier DOUBLE NOT NULL, next_threshold DOUBLE NOT NULL, PRIMARY KEY (player_uuid, category))");
            }
        } catch (SQLException exception) {
            markUnavailable(exception);
            throw new IllegalStateException("Failed to bootstrap database", exception);
        }
    }



    private String playerStatsUpsertSql() {
        if (mysql) {
            return "INSERT INTO marisworth_player_stats (player_uuid, total_earned) VALUES (?, ?) ON DUPLICATE KEY UPDATE total_earned = total_earned + VALUES(total_earned)";
        }
        return "INSERT INTO marisworth_player_stats (player_uuid, total_earned) VALUES (?, ?) ON CONFLICT(player_uuid) DO UPDATE SET total_earned = total_earned + excluded.total_earned";
    }

    private String multiplierUpsertSql() {
        if (mysql) {
            return "INSERT INTO marisworth_multiplier (player_uuid, category, sold_total, multiplier, next_threshold) VALUES (?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE sold_total = VALUES(sold_total), multiplier = VALUES(multiplier), next_threshold = VALUES(next_threshold)";
        }
        return "INSERT INTO marisworth_multiplier (player_uuid, category, sold_total, multiplier, next_threshold) VALUES (?, ?, ?, ?, ?) ON CONFLICT(player_uuid, category) DO UPDATE SET sold_total = excluded.sold_total, multiplier = excluded.multiplier, next_threshold = excluded.next_threshold";
    }


    private void startHealthMonitor() {
        if (!mysql) {
            available.set(true);
            return;
        }
        long delay = 20L * 60L * 5L;
        if (plugin.getSchedulerAdapter().isFolia()) {
            try {
                Object scheduler = Bukkit.class.getMethod("getGlobalRegionScheduler").invoke(null);
                java.lang.reflect.Method runAtFixedRate = scheduler.getClass()
                    .getMethod("runAtFixedRate", org.bukkit.plugin.Plugin.class, Consumer.class, long.class, long.class);
                Consumer<Object> task = scheduledTask -> {
                    foliaHealthTask = scheduledTask;
                    if (!plugin.isEnabled()) {
                        return;
                    }
                    plugin.getSchedulerAdapter().runAsync(this::runHealthCheck);
                };
                foliaHealthTask = runAtFixedRate.invoke(scheduler, plugin, task, delay, delay);
            } catch (ReflectiveOperationException exception) {
                plugin.getLogger().warning("Failed to start Folia health monitor: " + exception.getMessage());
            }
            return;
        }
        healthCheckTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, () -> {
            if (!plugin.isEnabled()) {
                return;
            }
            plugin.getSchedulerAdapter().runAsync(this::runHealthCheck);
        }, delay, delay);
    }

    private void runHealthCheck() {
        if (checkConnection()) {
            if (!available.getAndSet(true)) {
                plugin.getLogger().info("MySQL connection restored. Re-enabling MarisWorth features.");
            }
        } else {
            available.set(false);
            plugin.getLogger().warning("&cMySQL timed out. Please reconnect!");
        }
    }

    private void stopHealthMonitor() {
        if (foliaHealthTask != null) {
            try {
                foliaHealthTask.getClass().getMethod("cancel").invoke(foliaHealthTask);
            } catch (ReflectiveOperationException ignored) {
            }
            foliaHealthTask = null;
        }
        if (healthCheckTaskId != -1) {
            Bukkit.getScheduler().cancelTask(healthCheckTaskId);
            healthCheckTaskId = -1;
        }
    }

    private boolean checkConnection() {
        if (!mysql || dataSource == null || dataSource.isClosed()) {
            return true;
        }
        try {
            bootstrap();
            return true;
        } catch (IllegalStateException exception) {
            return false;
        }
    }

    private void markUnavailable(SQLException exception) {
        if (mysql && available.getAndSet(false)) {
            plugin.getLogger().warning("&cMySQL timed out. Please reconnect!");
        }
    }

    private <T> T execute(String action, SqlSupplier<T> supplier) {
        if (mysql && !available.get()) {
            throw new IllegalStateException("Failed to " + action + ": MySQL unavailable");
        }
        try {
            T result = supplier.get();
            if (mysql) {
                available.set(true);
            }
            return result;
        } catch (SQLException exception) {
            markUnavailable(exception);
            throw new IllegalStateException("Failed to " + action, exception);
        }
    }

    @FunctionalInterface
    private interface SqlSupplier<T> {
        T get() throws SQLException;
    }

    public record SellHistoryEntry(ItemStack item, String name, long amount, double totalPrice) {
    }

    public record MultiplierState(double multiplier, double soldTotal, double nextThreshold) {
    }

    private static final class DbThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "MarisWorth-DB");
            thread.setDaemon(true);
            return thread;
        }
    }
}
