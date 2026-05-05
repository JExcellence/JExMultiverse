package de.jexcellence.multiverse.service;

import de.jexcellence.jexplatform.logging.JExLogger;
import de.jexcellence.multiverse.config.PlotWorldConfig;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Loads and caches schematics from
 * {@code plugins/JExMultiverse/schematics/<name>.<ext>}.
 *
 * <p>Extension dispatch:
 * <ul>
 *   <li>{@code .nbt} — Bukkit native {@link org.bukkit.structure.Structure},
 *       no external dependencies required.</li>
 *   <li>{@code .schem} / {@code .schematic} — WorldEdit / FAWE clipboard
 *       formats, available only when WorldEdit is installed on the server.</li>
 * </ul>
 *
 * <p>Cached per name so populator hits don't re-read disk on every chunk.
 *
 * @author JExcellence
 * @since 3.1.0
 */
public final class SchematicService {

    private static final String DIR = "schematics";

    private final JavaPlugin plugin;
    private final JExLogger logger;
    private final PlotWorldConfig plotConfig;
    private final boolean worldeditAvailable;
    private final ConcurrentMap<String, PlacedSchematic> cache = new ConcurrentHashMap<>();

    public SchematicService(@NotNull JavaPlugin plugin,
                            @NotNull JExLogger logger,
                            @NotNull PlotWorldConfig plotConfig) {
        this.plugin = plugin;
        this.logger = logger;
        this.plotConfig = plotConfig;
        var dir = directory();
        if (!dir.exists() && dir.mkdirs()) {
            logger.info("Created schematics directory at {}", dir.getAbsolutePath());
        }
        var pm = Bukkit.getPluginManager();
        this.worldeditAvailable = pm.getPlugin("WorldEdit") != null
                || pm.getPlugin("FastAsyncWorldEdit") != null;
        if (worldeditAvailable) {
            logger.info("WorldEdit/FAWE detected — .schem and .schematic schematics enabled");
        } else {
            logger.info("WorldEdit/FAWE not present — only Bukkit .nbt schematics will load");
        }
    }

    /** Returns the schematics directory ({@code plugins/JExMultiverse/schematics/}). */
    public @NotNull File directory() {
        return new File(plugin.getDataFolder(), DIR);
    }

    /** X offset applied to schematic pastes (relative to plot NW corner). */
    public int offsetX() { return plotConfig.schematicOffsetX(); }

    /** Y offset applied to schematic pastes (relative to plotHeight). */
    public int offsetY() { return plotConfig.schematicOffsetY(); }

    /** Z offset applied to schematic pastes (relative to plot NW corner). */
    public int offsetZ() { return plotConfig.schematicOffsetZ(); }

    /** Returns whether a WorldEdit-compatible plugin is installed. */
    public boolean isWorldEditAvailable() {
        return worldeditAvailable;
    }

    /**
     * Resolves a schematic by name. Tries {@code .nbt} first (Bukkit native),
     * then {@code .schem} and {@code .schematic} (WorldEdit, if available).
     * Returns empty if no matching file exists or all readers fail.
     */
    public @NotNull Optional<PlacedSchematic> load(@NotNull String name) {
        var cached = cache.get(name);
        if (cached != null) return Optional.of(cached);

        var dir = directory();

        // Bukkit native — always attempted first.
        var nbt = new File(dir, name + ".nbt");
        if (nbt.exists()) {
            try {
                var structure = Bukkit.getStructureManager().loadStructure(nbt);
                var placed = new BukkitStructureSchematic(structure);
                cache.put(name, placed);
                return Optional.of(placed);
            } catch (IOException e) {
                logger.error("Failed to load Bukkit structure '{}'", name, e);
            }
        }

        // WorldEdit / FAWE — only if the plugin is installed. The class
        // reference inside the branch only triggers loading when reached.
        if (worldeditAvailable) {
            for (var ext : new String[]{".schem", ".schematic"}) {
                var file = new File(dir, name + ext);
                if (file.exists()) {
                    var placed = WorldEditSchematic.tryLoad(file, logger);
                    if (placed.isPresent()) {
                        cache.put(name, placed.get());
                        return placed;
                    }
                }
            }
        }

        logger.warn("Schematic '{}' not found in {}", name, dir.getAbsolutePath());
        return Optional.empty();
    }

    /** Drops the cached entry for a name so the next lookup re-reads disk. */
    public void invalidate(@Nullable String name) {
        if (name == null) cache.clear();
        else cache.remove(name);
    }
}
