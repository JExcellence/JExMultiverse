package de.jexcellence.multiverse.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;

/**
 * Merges missing translation keys from JAR resources into existing on-disk
 * translation files.
 *
 * <p>The R18nManager extraction step only writes to disk when a translation
 * file does not yet exist, so admins who upgrade between versions never see
 * the new keys — old YAML stays put while the JAR ships additions. This
 * merger compares deep-leaf keys between the two and appends any missing
 * leaves into the disk file, preserving any customisations the admin has
 * already made.
 */
public final class TranslationKeyMerger {

    private TranslationKeyMerger() {}

    /**
     * For each given resource path (e.g. {@code "translations/en_US.yml"}),
     * extracts the JAR file if missing, then adds any leaf keys present in
     * the JAR but absent from the on-disk file.
     *
     * @param plugin        the owning plugin (used for {@code getResource} and
     *                      {@code getDataFolder})
     * @param resourcePaths paths relative to the JAR root and data folder
     */
    public static void mergeAll(@NotNull JavaPlugin plugin, @NotNull String... resourcePaths) {
        for (var path : resourcePaths) {
            mergeOne(plugin, path);
        }
    }

    private static void mergeOne(@NotNull JavaPlugin plugin, @NotNull String resourcePath) {
        var target = new File(plugin.getDataFolder(), resourcePath.replace('/', File.separatorChar));

        if (!target.exists()) {
            try {
                plugin.saveResource(resourcePath, false);
            } catch (IllegalArgumentException missing) {
                // Resource not in JAR — nothing to do.
            }
            return;
        }

        YamlConfiguration jarYaml;
        try (var in = plugin.getResource(resourcePath)) {
            if (in == null) return;
            jarYaml = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to read JAR resource " + resourcePath, e);
            return;
        }

        var diskYaml = YamlConfiguration.loadConfiguration(target);

        var added = 0;
        for (var key : jarYaml.getKeys(true)) {
            // Skip section nodes — only merge leaves so we don't clobber
            // partial customisations under a section the admin has edited.
            if (jarYaml.get(key) instanceof ConfigurationSection) continue;
            if (diskYaml.contains(key)) continue;

            diskYaml.set(key, jarYaml.get(key));
            added++;
        }

        if (added > 0) {
            try {
                diskYaml.save(target);
                plugin.getLogger().info("Merged " + added + " missing key(s) into " + resourcePath);
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to save merged " + resourcePath, e);
            }
        }
    }
}
