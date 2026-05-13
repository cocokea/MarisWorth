package com.maris7.worth.platform;

import java.lang.reflect.Method;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

public final class SchedulerAdapter {
    private final Plugin plugin;
    private final boolean folia;

    public SchedulerAdapter(Plugin plugin) {
        this.plugin = plugin;
        this.folia = hasClass("io.papermc.paper.threadedregions.scheduler.EntityScheduler");
    }

    public void runLater(Player player, long delayTicks, Runnable runnable) {
        runLater(player, delayTicks, runnable, () -> {
        });
    }

    public void runLater(Player player, long delayTicks, Runnable runnable, Runnable retired) {
        if (!folia) {
            Bukkit.getScheduler().runTaskLater(plugin, runnable, delayTicks);
            return;
        }
        try {
            Object scheduler = player.getClass().getMethod("getScheduler").invoke(player);
            Method runDelayed = scheduler.getClass().getMethod("runDelayed", Plugin.class, Consumer.class, Runnable.class, long.class);
            Consumer<Object> consumer = task -> runnable.run();
            runDelayed.invoke(scheduler, plugin, consumer, retired, delayTicks);
        } catch (ReflectiveOperationException exception) {
            plugin.getLogger().warning("Failed to use Folia entity scheduler, falling back to Bukkit scheduler: " + exception.getClass().getSimpleName());
            Bukkit.getScheduler().runTaskLater(plugin, runnable, delayTicks);
        }
    }

    public void runGlobal(Runnable runnable) {
        if (!folia) {
            Bukkit.getScheduler().runTask(plugin, runnable);
            return;
        }
        try {
            Object scheduler = Bukkit.class.getMethod("getGlobalRegionScheduler").invoke(null);
            Method run = scheduler.getClass().getMethod("run", Plugin.class, Consumer.class);
            run.invoke(scheduler, plugin, (Consumer<Object>) task -> runnable.run());
        } catch (ReflectiveOperationException exception) {
            plugin.getLogger().warning("Failed to use Folia global scheduler, falling back to Bukkit scheduler: " + exception.getClass().getSimpleName());
            Bukkit.getScheduler().runTask(plugin, runnable);
        }
    }

    public void runAsync(Runnable runnable) {
        if (!folia) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, runnable);
            return;
        }
        try {
            Object scheduler = Bukkit.class.getMethod("getAsyncScheduler").invoke(null);
            Method runNow = scheduler.getClass().getMethod("runNow", Plugin.class, Consumer.class);
            runNow.invoke(scheduler, plugin, (Consumer<Object>) task -> runnable.run());
        } catch (ReflectiveOperationException exception) {
            plugin.getLogger().warning("Failed to use Folia async scheduler, falling back to Bukkit async scheduler: " + exception.getClass().getSimpleName());
            Bukkit.getScheduler().runTaskAsynchronously(plugin, runnable);
        }
    }

    public void runAtLocation(Location location, Runnable runnable) {
        if (location == null) {
            runGlobal(runnable);
            return;
        }
        if (!folia) {
            Bukkit.getScheduler().runTask(plugin, runnable);
            return;
        }
        try {
            Object scheduler = Bukkit.class.getMethod("getRegionScheduler").invoke(null);
            Method run = scheduler.getClass().getMethod("run", Plugin.class, Location.class, Consumer.class);
            run.invoke(scheduler, plugin, location, (Consumer<Object>) task -> runnable.run());
        } catch (ReflectiveOperationException exception) {
            plugin.getLogger().warning("Failed to use Folia region scheduler, falling back to Bukkit scheduler: " + exception.getClass().getSimpleName());
            Bukkit.getScheduler().runTask(plugin, runnable);
        }
    }

    public boolean isFolia() {
        return folia;
    }

    private static boolean hasClass(String name) {
        try {
            Class.forName(name);
            return true;
        } catch (ClassNotFoundException exception) {
            return false;
        }
    }
}
