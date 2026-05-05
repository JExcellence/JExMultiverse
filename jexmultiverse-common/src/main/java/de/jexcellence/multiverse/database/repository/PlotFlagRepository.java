package de.jexcellence.multiverse.database.repository;

import de.jexcellence.jehibernate.repository.base.AbstractCrudRepository;
import de.jexcellence.multiverse.database.entity.StoredPlotFlag;
import jakarta.persistence.EntityManagerFactory;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * Repository for {@link StoredPlotFlag} entries.
 *
 * @author JExcellence
 * @since 3.2.0
 */
public class PlotFlagRepository extends AbstractCrudRepository<StoredPlotFlag, Long> {

    public PlotFlagRepository(@NotNull ExecutorService executor,
                              @NotNull EntityManagerFactory emf,
                              @NotNull Class<StoredPlotFlag> entityClass) {
        super(executor, emf, entityClass);
    }

    /**
     * Returns all stored plot flags asynchronously.
     *
     * @return a future containing the list of all {@link StoredPlotFlag} entries
     */
    public @NotNull CompletableFuture<List<StoredPlotFlag>> findAllAsync() {
        return query().listAsync();
    }

    /**
     * Finds a stored flag by plot id and flag key asynchronously.
     *
     * @param plotId  the plot's database id
     * @param flagKey the flag's stable key
     * @return a future containing the matching entry, or empty if not found
     */
    public @NotNull CompletableFuture<Optional<StoredPlotFlag>> findByPlotAndKeyAsync(
            long plotId, @NotNull String flagKey) {
        return query().and("plotId", plotId).and("flagKey", flagKey).firstAsync();
    }
}
