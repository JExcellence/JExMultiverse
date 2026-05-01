package de.jexcellence.multiverse.service;

import de.jexcellence.jexplatform.logging.JExLogger;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.structure.Structure;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Loads and caches Bukkit {@link Structure}s from
 * {@code plugins/JExMultiverse/schematics/<name>.nbt}.
 *
 * <p>Cached per name so populator hits don't re-read disk on every chunk.
 *
 * @author JExcellence
 * @since 3.1.0
 */
public final class SchematicService {

    private static final String DIR = "schematics";
    private static final String EXT = ".nbt";

    private final JavaPlugin plugin;
    private final JExLogger logger;
    private final ConcurrentMap<String, Structure> cache = new ConcurrentHashMap<>();

    public SchematicService(@NotNull JavaPlugin plugin, @NotNull JExLogger logger) {
        this.plugin = plugin;
        this.logger = logger;
        var dir = directory();
        if (!dir.exists() && dir.mkdirs()) {
            logger.info("Created schematics directory at {}", dir.getAbsolutePath());
        }
    }

    /**
     * Returns the schematics directory ({@code plugins/JExMultiverse/schematics/}).
     */
    public @NotNull File directory() {
        return new File(plugin.getDataFolder(), DIR);
    }

    /**
     * Returns the file path for a named schematic. The name is stored without
     * the {@code .nbt} extension; this method appends it.
     */
    public @NotNull File fileFor(@NotNull String name) {
        return new File(directory(), name + EXT);
    }

    /**
     * Loads a structure by name, caching the result. Returns empty if the
     * file is missing or fails to parse.
     */
    public @NotNull Optional<Structure> load(@NotNull String name) {
        var cached = cache.get(name);
        if (cached != null) return Optional.of(cached);

        var file = fileFor(name);
        if (!file.exists()) {
            logger.warn("Schematic '{}' not found at {}", name, file.getAbsolutePath());
            return Optional.empty();
        }
        try {
            var loaded = Bukkit.getStructureManager().loadStructure(file);
            cache.put(name, loaded);
            return Optional.of(loaded);
        } catch (IOException e) {
            logger.error("Failed to load schematic '{}'", name, e);
            return Optional.empty();
        }
    }

    /** Drops the cached entry for a name so the next lookup re-reads disk. */
    public void invalidate(@Nullable String name) {
        if (name == null) cache.clear();
        else cache.remove(name);
    }
}
