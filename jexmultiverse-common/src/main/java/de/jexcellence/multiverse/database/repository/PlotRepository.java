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

    public @NotNull Optional<Plot> findByCoords(@NotNull String world, int gridX, int gridZ) {
        return query()
                .and("worldName", world)
                .and("gridX", gridX)
                .and("gridZ", gridZ)
                .first();
    }

    public @NotNull CompletableFuture<Optional<Plot>> findByCoordsAsync(
            @NotNull String world, int gridX, int gridZ) {
        return query()
                .and("worldName", world)
                .and("gridX", gridX)
                .and("gridZ", gridZ)
                .firstAsync();
    }

    public @NotNull CompletableFuture<List<Plot>> findByOwnerAsync(@NotNull UUID owner) {
        return query()
                .and("ownerUuid", owner.toString())
                .listAsync();
    }

    public @NotNull CompletableFuture<List<Plot>> findAllAsync() {
        return query().listAsync();
    }
}
