package de.jexcellence.multiverse.database.repository;

import de.jexcellence.jehibernate.repository.base.AbstractCrudRepository;
import de.jexcellence.multiverse.database.entity.MVWorld;
import jakarta.persistence.EntityManagerFactory;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

public class MVWorldRepository extends AbstractCrudRepository<MVWorld, Long> {

    public MVWorldRepository(
            @NotNull ExecutorService asyncExecutorService,
            @NotNull EntityManagerFactory jpaEntityManagerFactory,
            @NotNull Class<MVWorld> entityClass
    ) {
        super(asyncExecutorService, jpaEntityManagerFactory, entityClass);
    }

    public @NotNull Optional<MVWorld> findByIdentifier(@NotNull String identifier) {
        return query().and("identifier", identifier).first();
    }

    public @NotNull Optional<MVWorld> findByGlobalSpawn() {
        return query().and("globalizedSpawn", true).first();
    }

    public @NotNull CompletableFuture<Optional<MVWorld>> findByIdentifierAsync(@NotNull String identifier) {
        return query().and("identifier", identifier).firstAsync();
    }

    public @NotNull CompletableFuture<Optional<MVWorld>> findByGlobalSpawnAsync() {
        return query().and("globalizedSpawn", true).firstAsync();
    }

    public @NotNull CompletableFuture<List<MVWorld>> findAllAsync() {
        return query().listAsync();
    }

    public @NotNull CompletableFuture<MVWorld> saveWorld(@NotNull MVWorld world) {
        if (world.getId() == null) {
            return createAsync(world);
        }
        return updateAsync(world);
    }

    public @NotNull CompletableFuture<Boolean> deleteByIdentifier(@NotNull String identifier) {
        return findByIdentifierAsync(identifier)
                .thenCompose(opt -> opt
                        .map(world -> deleteAsync(world.getId()).thenApply(v -> true))
                        .orElseGet(() -> CompletableFuture.completedFuture(false)));
    }

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
