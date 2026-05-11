package de.jexcellence.multiverse.generator;

import de.jexcellence.multiverse.generator.void_world.VoidChunkGenerator;
import org.bukkit.generator.ChunkGenerator;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * Stateless dispatch from a bukkit.yml generator id ({@code "void"},
 * {@code "plot"}, ...) to a concrete {@link ChunkGenerator} instance.
 *
 * <p>Used by {@code JExMultiverseFree#getDefaultWorldGenerator} and the
 * matching Premium hook. The plugin main classes can't carry state at
 * the moment of this call — Bukkit invokes {@code
 * getDefaultWorldGenerator} during world load (server startup), which
 * runs <em>before</em> {@code onEnable} — so resolution has to be
 * stateless and side-effect-free. Generators that need configuration
 * (PlotChunkGenerator) are handled separately via the runtime
 * {@code WorldFactory} path; only the void generator is registered here
 * since it has a true no-arg construction.
 *
 * @author JExcellence
 * @since 3.3.0
 */
public final class GeneratorRegistry {

    private GeneratorRegistry() {
    }

    /**
     * Resolves a generator id (the {@code <id>} in
     * {@code generator: "JExMultiverse:<id>"}) to a chunk generator.
     *
     * @param id the generator id, case-insensitive; {@code null} or
     *           unknown ids return {@code null} so Bukkit uses the
     *           server default.
     * @return the resolved generator, or {@code null}
     */
    public static @Nullable ChunkGenerator resolve(@Nullable String id) {
        if (id == null || id.isBlank()) return null;
        return switch (id.toLowerCase(Locale.ROOT)) {
            case "void" -> new VoidChunkGenerator();
            // "plot" is intentionally NOT registered here — PlotChunkGenerator
            // needs the loaded PlotWorldConfig, which isn't available during
            // bukkit.yml world loading. Plot worlds always go through the
            // runtime MultiverseService.createWorld path.
            default -> null;
        };
    }

    /** Build the bukkit.yml generator string for a given void-world. Used
     *  by BukkitYmlWriter so the generator id stays in sync with what
     *  {@link #resolve(String)} understands. */
    public static @NotNull String voidGeneratorRef() {
        return "JExMultiverse:void";
    }
}
