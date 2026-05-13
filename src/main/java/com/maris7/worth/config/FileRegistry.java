package com.maris7.worth.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public final class FileRegistry {
    private final JavaPlugin plugin;

    public FileRegistry(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void ensureAndMerge(String relativePath) {
        Path file = plugin.getDataFolder().toPath().resolve(relativePath);
        try {
            Files.createDirectories(file.getParent());
            if (Files.notExists(file)) {
                plugin.saveResource(relativePath, false);
                return;
            }
            YamlConfiguration current = YamlConfiguration.loadConfiguration(file.toFile());
            InputStream resource = plugin.getResource(relativePath);
            if (resource == null) {
                return;
            }
            try (InputStreamReader reader = new InputStreamReader(resource, StandardCharsets.UTF_8)) {
                YamlConfiguration defaults = YamlConfiguration.loadConfiguration(reader);
                if (mergeSection(defaults, current)) {
                    current.save(file.toFile());
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to manage file " + relativePath, exception);
        }
    }

    private boolean mergeSection(ConfigurationSection source, ConfigurationSection target) {
        boolean changed = false;
        for (String key : source.getKeys(false)) {
            Object sourceValue = source.get(key);
            if (sourceValue instanceof ConfigurationSection nestedSource) {
                ConfigurationSection nestedTarget = target.getConfigurationSection(key);
                if (nestedTarget == null) {
                    target.createSection(key, flatten(nestedSource));
                    changed = true;
                    continue;
                }
                changed |= mergeSection(nestedSource, nestedTarget);
                continue;
            }
            if (!target.contains(key)) {
                target.set(key, sourceValue);
                changed = true;
            }
        }
        return changed;
    }

    private Map<String, Object> flatten(ConfigurationSection section) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            Object value = section.get(key);
            if (value instanceof ConfigurationSection nested) {
                values.put(key, flatten(nested));
            } else {
                values.put(key, value);
            }
        }
        return values;
    }
}
