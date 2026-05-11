package de.jexcellence.multiverse.factory;

import de.jexcellence.jexplatform.logging.JExLogger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.util.Map;

/**
 * Idempotent writer for {@code <server-root>/bukkit.yml} world entries.
 *
 * <p>On Folia, runtime world creation via {@code Bukkit.createWorld} is
 * patched off ({@link UnsupportedOperationException} unconditional) and
 * the maintainer-blessed alternative — per
 * <a href="https://github.com/PaperMC/Folia/issues/396">Folia issue
 * #396</a> — is to declare worlds in {@code bukkit.yml}, which Folia
 * loads at server startup using the same code path it uses for the
 * default world (NOT the Bukkit API call that throws).
 *
 * <p>This writer adds a section like:
 * <pre>{@code
 * worlds:
 *   oneblock_overworld:
 *     generator: JExMultiverse:void
 * }</pre>
 *
 * to the existing file, preserving any other worlds + top-level keys
 * already declared by the operator. Safe to call multiple times — if
 * the world is already declared with the same generator, this is a
 * no-op.
 *
 * @author JExcellence
 * @since 3.3.0
 */
public final class BukkitYmlWriter {

    /** Filename in the server root — same on Paper, Spigot, and Folia. */
    private static final String FILENAME = "bukkit.yml";

    private BukkitYmlWriter() {
    }

    /**
     * Result of a write attempt.
     *
     * @param added      true when this call mutated bukkit.yml
     * @param alreadyDeclared true when the world was already in
     *                        bukkit.yml with the expected generator
     */
    public record Result(boolean added, boolean alreadyDeclared) {
    }

    /**
     * Idempotently declares a world in bukkit.yml.
     *
     * <p>Writes {@code worlds.<name>.generator: <generator>} to the
     * server-root bukkit.yml, creating the file or section as needed.
     * If the entry already exists with the same generator, the call is
     * a no-op.
     *
     * @param worldName the world identifier (also the on-disk folder name)
     * @param generator the generator reference, e.g.
     *                  {@code "JExMultiverse:void"}
     * @param logger    used to log the action so operators understand
     *                  why a server restart is being requested
     * @return a {@link Result} describing what happened
     * @throws IOException when bukkit.yml can't be written to disk
     */
    public static @NotNull Result declare(@NotNull String worldName,
                                           @NotNull String generator,
                                           @NotNull JExLogger logger) throws IOException {
        // Server root = working directory. Paper/Spigot/Folia all start
        // the JVM there; bukkit.yml lives at this path.
        final File file = new File(FILENAME).getAbsoluteFile();
        final YamlConfiguration cfg = file.exists()
                ? YamlConfiguration.loadConfiguration(file)
                : new YamlConfiguration();

        final String generatorKey = "worlds." + worldName + ".generator";
        final String existing = cfg.getString(generatorKey);
        if (generator.equals(existing)) {
            return new Result(false, true);
        }

        cfg.set(generatorKey, generator);
        cfg.save(file);
        logger.info("[worlds] declared '{}' in bukkit.yml (generator={}) — will load on next server start",
                worldName, generator);
        return new Result(true, false);
    }

    /**
     * Returns true iff bukkit.yml already declares the given world (any
     * generator). Useful to short-circuit a write when the world is
     * pending its first restart but already on file.
     */
    public static boolean isDeclared(@NotNull String worldName) {
        final File file = new File(FILENAME).getAbsoluteFile();
        if (!file.exists()) return false;
        final YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        final var worldsSection = cfg.getConfigurationSection("worlds");
        if (worldsSection == null) return false;
        return worldsSection.contains(worldName);
    }
}
