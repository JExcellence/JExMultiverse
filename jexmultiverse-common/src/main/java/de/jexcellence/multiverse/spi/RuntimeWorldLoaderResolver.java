package de.jexcellence.multiverse.spi;

import de.jexcellence.jexplatform.logging.JExLogger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.ServiceLoader;

/**
 * Discovers a {@link RuntimeWorldLoader} via {@link ServiceLoader}.
 *
 * <p>The Folia-NMS module ships a
 * {@code META-INF/services/de.jexcellence.multiverse.spi.RuntimeWorldLoader}
 * descriptor pointing at its implementation; when the shaded plugin JAR
 * includes that module, this resolver returns the implementation and
 * runtime world loading becomes available. When no implementation is
 * present (e.g. Paper-only build), the resolver returns
 * {@link Optional#empty()} and the caller falls back to the "pending
 * restart" path.
 *
 * @author JExcellence
 * @since 3.5.0
 */
public final class RuntimeWorldLoaderResolver {

    private RuntimeWorldLoaderResolver() {
    }

    /**
     * Resolves a loader for the current platform. Logs at debug level
     * which backend was selected (or that none was found).
     *
     * <p>The first implementation registered in {@code META-INF/services}
     * wins. We don't expect multiple — Folia is the only platform that
     * needs an NMS loader today.
     *
     * @param logger structured logger for diagnostics; may be {@code null}
     * @return the resolved loader, or empty when none is on the classpath
     */
    public static @NotNull Optional<RuntimeWorldLoader> resolve(@Nullable JExLogger logger) {
        try {
            // Use this class's classloader explicitly — Bukkit's plugin
            // classloader is the one that holds the shaded folia-nms
            // implementation. The thread context classloader can be the
            // server's classloader in some lifecycle phases and would
            // miss our META-INF/services file.
            final var loader = ServiceLoader
                    .load(RuntimeWorldLoader.class,
                            RuntimeWorldLoaderResolver.class.getClassLoader())
                    .findFirst();
            if (logger != null) {
                loader.ifPresentOrElse(
                        impl -> logger.debug("[worlds] runtime loader backend: {}", impl.backendId()),
                        () -> logger.debug("[worlds] no runtime loader backend on classpath — restart-only mode"));
            }
            return loader;
        } catch (final Throwable ex) {
            // ServiceLoader can throw ServiceConfigurationError on malformed
            // descriptors or class-init failures. We swallow + log; a
            // missing backend is not a fatal error.
            if (logger != null) {
                logger.warn("[worlds] runtime loader discovery failed: {}", ex.getMessage());
            }
            return Optional.empty();
        }
    }
}
