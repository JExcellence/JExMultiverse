package de.jexcellence.multiverse.database.repository;

import de.jexcellence.jehibernate.repository.base.AbstractCrudRepository;
import de.jexcellence.multiverse.database.entity.Plot;
import jakarta.persistence.EntityManagerFactory;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * Repository for {@link Plot} entities.
 *
 * @author JExcellence
 * @since 3.2.0
 */
public class PlotRepository extends AbstractCrudRepository<Plot, Long> {

    public PlotRepository(@NotNull ExecutorService executor,
                          @NotNull EntityManagerFactory emf,
                          @NotNull Class<Plot> entityClass) {
        super(executor, emf, entityClass);
    }

    /**
     * Finds a plot by its world and grid coordinates synchronously.
     *
     * @param world the world name
     * @param gridX the grid X coordinate
     * @param gridZ the grid Z coordinate
     * @return the matching plot, or empty if not found
     */
    public @NotNull Optional<Plot> findByCoords(@NotNull String world, int gridX, int gridZ) {
        return query()
                .and("worldName", world)
                .and("gridX", gridX)
                .and("gridZ", gridZ)
                .first();
    }

    /**
     * Finds a plot by its world and grid coordinates asynchronously.
     *
     * @param world the world name
     * @param gridX the grid X coordinate
     * @param gridZ the grid Z coordinate
     * @return a future containing the matching plot, or empty if not found
     */
    public @NotNull CompletableFuture<Optional<Plot>> findByCoordsAsync(
            @NotNull String world, int gridX, int gridZ) {
        return query()
                .and("worldName", world)
                .and("gridX", gridX)
                .and("gridZ", gridZ)
                .firstAsync();
    }

    /**
     * Returns all plots owned by the given player asynchronously.
     *
     * @param owner the owner's UUID
     * @return a future containing the list of owned plots
     */
    public @NotNull CompletableFuture<List<Plot>> findByOwnerAsync(@NotNull UUID owner) {
        return query()
                .and("ownerUuid", owner.toString())
                .listAsync();
    }

    /**
     * Returns all plots asynchronously.
     *
     * @return a future containing the list of all {@link Plot} entries
     */
    public @NotNull CompletableFuture<List<Plot>> findAllAsync() {
        return query().listAsync();
    }
}
