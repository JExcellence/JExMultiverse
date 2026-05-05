package de.jexcellence.multiverse.database.repository;

import de.jexcellence.jehibernate.repository.base.AbstractCrudRepository;
import de.jexcellence.multiverse.database.entity.MVWorld;
import jakarta.persistence.EntityManagerFactory;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * Repository for {@link MVWorld} entities, providing synchronous and asynchronous CRUD operations.
 *
 * @author JExcellence
 * @since 3.0.0
 */
public class MVWorldRepository extends AbstractCrudRepository<MVWorld, Long> {

    /**
     * Constructs a new {@code MVWorldRepository}.
     *
     * @param asyncExecutorService   executor used for async operations
     * @param jpaEntityManagerFactory the JPA entity manager factory
     * @param entityClass            the entity class ({@link MVWorld})
     */
    public MVWorldRepository(
            @NotNull ExecutorService asyncExecutorService,
            @NotNull EntityManagerFactory jpaEntityManagerFactory,
            @NotNull Class<MVWorld> entityClass
    ) {
        super(asyncExecutorService, jpaEntityManagerFactory, entityClass);
    }

    /**
     * Finds a world by its unique identifier synchronously.
     *
     * @param identifier the world identifier
     * @return an {@link Optional} containing the world, or empty if not found
     */
    public @NotNull Optional<MVWorld> findByIdentifier(@NotNull String identifier) {
        return query().and("identifier", identifier).first();
    }

    /**
     * Finds the world currently set as the global spawn synchronously.
     *
     * @return an {@link Optional} containing the global spawn world, or empty if none is set
     */
    public @NotNull Optional<MVWorld> findByGlobalSpawn() {
        return query().and("globalizedSpawn", true).first();
    }

    /**
     * Finds a world by its unique identifier asynchronously.
     *
     * @param identifier the world identifier
     * @return a {@link CompletableFuture} resolving to an {@link Optional} of the world
     */
    public @NotNull CompletableFuture<Optional<MVWorld>> findByIdentifierAsync(@NotNull String identifier) {
        return query().and("identifier", identifier).firstAsync();
    }

    /**
     * Finds the world currently set as the global spawn asynchronously.
     *
     * @return a {@link CompletableFuture} resolving to an {@link Optional} of the global spawn world
     */
    public @NotNull CompletableFuture<Optional<MVWorld>> findByGlobalSpawnAsync() {
        return query().and("globalizedSpawn", true).firstAsync();
    }

    /**
     * Retrieves all persisted worlds asynchronously.
     *
     * @return a {@link CompletableFuture} resolving to a list of all {@link MVWorld} entities
     */
    public @NotNull CompletableFuture<List<MVWorld>> findAllAsync() {
        return query().listAsync();
    }

    /**
     * Persists a world asynchronously, creating it if new or updating it if it already has an ID.
     *
     * @param world the {@link MVWorld} to save
     * @return a {@link CompletableFuture} resolving to the saved entity
     */
    public @NotNull CompletableFuture<MVWorld> saveWorld(@NotNull MVWorld world) {
        if (world.getId() == null) {
            return createAsync(world);
        }
        return updateAsync(world);
    }

    /**
     * Deletes a world by its identifier asynchronously.
     *
     * @param identifier the unique identifier of the world to delete
     * @return a {@link CompletableFuture} resolving to {@code true} if deleted, {@code false} if not found
     */
    public @NotNull CompletableFuture<Boolean> deleteByIdentifier(@NotNull String identifier) {
        return findByIdentifierAsync(identifier)
                .thenCompose(opt -> opt
                        .map(world -> deleteAsync(world.getId()).thenApply(v -> true))
                        .orElseGet(() -> CompletableFuture.completedFuture(false)));
    }

    /**
     * Clears the global spawn flag on all worlds except the one with the given identifier.
     *
     * @param excludeIdentifier the identifier of the world to keep as global spawn
     * @return a {@link CompletableFuture} that completes when all updates are done
     */
    public @NotNull CompletableFuture<Void> clearGlobalSpawnExcept(@NotNull String excludeIdentifier) {
        return findAllAsync()
                .thenCompose(worlds -> {
                    List<CompletableFuture<MVWorld>> updates = worlds.stream()
                            .filter(w -> w.isGlobalizedSpawn() && !w.getIdentifier().equals(excludeIdentifier))
                            .map(w -> { w.setGlobalizedSpawn(false); return updateAsync(w); })
                            .toList();
                    return CompletableFuture.allOf(updates.toArray(new CompletableFuture[0]));
                });
    }
}
