package de.jexcellence.multiverse.service;

import de.jexcellence.jexplatform.logging.JExLogger;
import de.jexcellence.jexplatform.schematic.PlacedSchematic;
import de.jexcellence.multiverse.config.PlotWorldConfig;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.Optional;

/**
 * Multiverse-side schematic adapter. Delegates loading/caching to the shared
 * {@link de.jexcellence.jexplatform.schematic.SchematicService} in JExPlatform
 * and layers on the plot-specific paste offsets read from {@link PlotWorldConfig}.
 *
 * <p>Schematics live in {@code plugins/JExMultiverse/schematics/}. Format
 * support (Bukkit {@code .nbt}, plus {@code .schem}/{@code .schematic} via the
 * WorldEdit-free reader or a WorldEdit clipboard) is provided entirely by the
 * shared service.
 *
 * @author JExcellence
 * @since 3.1.0
 */
public final class SchematicService {

    private static final String DIR = "schematics";

    private final de.jexcellence.jexplatform.schematic.SchematicService delegate;
    private final PlotWorldConfig plotConfig;

    public SchematicService(@NotNull JavaPlugin plugin,
                            @NotNull JExLogger logger,
                            @NotNull PlotWorldConfig plotConfig) {
        this.delegate = new de.jexcellence.jexplatform.schematic.SchematicService(plugin, logger, DIR);
        this.plotConfig = plotConfig;
    }

    /** The schematics directory ({@code plugins/JExMultiverse/schematics/}). */
    public @NotNull File directory() {
        return delegate.directory();
    }

    /** X offset applied to schematic pastes (relative to plot NW corner). */
    public int offsetX() { return plotConfig.schematicOffsetX(); }

    /** Y offset applied to schematic pastes (relative to plotHeight). */
    public int offsetY() { return plotConfig.schematicOffsetY(); }

    /** Z offset applied to schematic pastes (relative to plot NW corner). */
    public int offsetZ() { return plotConfig.schematicOffsetZ(); }

    /** Whether a WorldEdit-compatible plugin is installed. */
    public boolean isWorldEditAvailable() {
        return delegate.isWorldEditAvailable();
    }

    /** Resolves a schematic by name (tries {@code .nbt}, then {@code .schem}/{@code .schematic}). */
    public @NotNull Optional<PlacedSchematic> load(@NotNull String name) {
        return delegate.load(name);
    }

    /** Drops the cached entry for a name so the next lookup re-reads disk. */
    public void invalidate(@Nullable String name) {
        delegate.invalidate(name);
    }

    /** The shared platform service this adapter delegates to. */
    public de.jexcellence.jexplatform.schematic.@NotNull SchematicService platform() {
        return delegate;
    }
}
