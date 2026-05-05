package de.jexcellence.multiverse.database.repository;

import de.jexcellence.jehibernate.repository.base.AbstractCrudRepository;
import de.jexcellence.multiverse.database.entity.PlotMember;
import jakarta.persistence.EntityManagerFactory;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * Repository for {@link PlotMember} entities.
 *
 * @author JExcellence
 * @since 3.2.0
 */
public class PlotMemberRepository extends AbstractCrudRepository<PlotMember, Long> {

    public PlotMemberRepository(@NotNull ExecutorService executor,
                                @NotNull EntityManagerFactory emf,
                                @NotNull Class<PlotMember> entityClass) {
        super(executor, emf, entityClass);
    }

    /**
     * Returns all members for a given plot asynchronously.
     *
     * @param plotId the plot's database id
     * @return a future containing the list of members
     */
    public @NotNull CompletableFuture<List<PlotMember>> findByPlotAsync(long plotId) {
        return query().and("plotId", plotId).listAsync();
    }

    /**
     * Finds a specific member entry by plot id and member UUID asynchronously.
     *
     * @param plotId the plot's database id
     * @param member the member's UUID
     * @return a future containing the matching entry, or empty if not found
     */
    public @NotNull CompletableFuture<Optional<PlotMember>> findByPlotAndMemberAsync(
            long plotId, @NotNull UUID member) {
        return query()
                .and("plotId", plotId)
                .and("memberUuid", member.toString())
                .firstAsync();
    }

    /**
     * Returns all plot members asynchronously.
     *
     * @return a future containing the list of all {@link PlotMember} entries
     */
    public @NotNull CompletableFuture<List<PlotMember>> findAllAsync() {
        return query().listAsync();
    }
}
