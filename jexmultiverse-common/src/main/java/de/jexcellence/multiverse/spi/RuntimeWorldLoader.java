package de.jexcellence.multiverse.spi;

import org.bukkit.World;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

/**
 * Service-provider interface for runtime world loading on platforms
 * where {@code Bukkit.createWorld()} is unavailable.
 *
 * <p>On Folia, {@code CraftServer.createWorld} is patched off
 * (unconditional {@link UnsupportedOperationException}), so the only
 * way to make a freshly-created world directory available without a
 * server restart is to construct {@code ServerLevel} via the NMS layer
 * and register it with {@code MinecraftServer.addLevel(...)} directly.
 *
 * <p>The Folia implementation lives in the {@code jexmultiverse-folia-nms}
 * module and is wired via {@link java.util.ServiceLoader}. Common code
 * never references the implementation type — it discovers an
 * implementation through this interface and degrades gracefully (pending
 * restart) when none is present.
 *
 * @author JExcellence
 * @since 3.5.0
 */
public interface RuntimeWorldLoader {

    /**
     * Loads a world that already has a valid {@code level.dat} on disk
     * (typically produced by
     * {@code LevelDatBuilder.writeSkeleton(name, env)}).
     *
     * <p>The returned future completes on the server's main / global
     * region thread once the world is registered. Implementations are
     * responsible for hopping to whatever thread the underlying server
     * requires for world registration.
     *
     * @param worldName   the on-disk world folder name (also the level name)
     * @param environment Bukkit environment driving the dimension type
     * @return a future that completes with the loaded {@link World}, or
     *         completes exceptionally with the underlying failure cause
     * @throws IOException when the world directory or level.dat is missing
     */
    @NotNull CompletableFuture<World> loadWorld(@NotNull String worldName,
                                                 World.@NotNull Environment environment)
            throws IOException;

    /**
     * Returns a backendId.
     *
     * @return a short identifier for logging — e.g. {@code "folia-nms"}
     */
    @NotNull String backendId();
}
