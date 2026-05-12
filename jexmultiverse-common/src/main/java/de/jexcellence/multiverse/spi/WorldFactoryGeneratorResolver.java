package de.jexcellence.multiverse.spi;

import de.jexcellence.multiverse.database.entity.MVWorld;
import de.jexcellence.multiverse.database.repository.MVWorldRepository;
import de.jexcellence.multiverse.factory.WorldFactory;
import org.bukkit.generator.ChunkGenerator;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Generator resolver that integrates with WorldFactory to resolve chunk generators
 * based on MVWorld configuration.
 * <p>
 * This resolver looks up the MVWorld entity for a given world name and uses
 * WorldFactory to determine the appropriate chunk generator based on the world's
 * type and configuration (plot size overrides, road width overrides, schematic).
 *
 * @author JExcellence
 * @since 3.0.0
 */
public class WorldFactoryGeneratorResolver {

    private final MVWorldRepository repository;
    private final WorldFactory worldFactory;

    /**
     * Creates a new generator resolver.
     *
     * @param repository   the repository for looking up MVWorld entities
     * @param worldFactory the factory for resolving generators
     */
    public WorldFactoryGeneratorResolver(@NotNull MVWorldRepository repository,
                                          @NotNull WorldFactory worldFactory) {
        this.repository = repository;
        this.worldFactory = worldFactory;
    }

    /**
     * Resolves the chunk generator for the given world name.
     * <p>
     * Looks up the MVWorld entity in the database and uses WorldFactory to
     * determine the appropriate generator based on the world's type and overrides.
     *
     * @param worldName the name of the world
     * @return the chunk generator, or null for default generation
     */
    public @Nullable ChunkGenerator resolveGenerator(@NotNull String worldName) {
        try {
            // Look up the MVWorld entity
            final var mvWorldOpt = repository.findByIdentifier(worldName);
            if (mvWorldOpt.isEmpty()) {
                // World not in database yet — return null for default generation
                return null;
            }

            final MVWorld mvWorld = mvWorldOpt.get();
            
            // Use WorldFactory to get the generator for this world type
            return worldFactory.getGeneratorForType(
                    mvWorld.getType(),
                    mvWorld.getPlotSizeOverride(),
                    mvWorld.getRoadWidthOverride(),
                    mvWorld.getSchematicName()
            );
        } catch (final Exception ex) {
            // If lookup fails, return null for default generation
            return null;
        }
    }
}
