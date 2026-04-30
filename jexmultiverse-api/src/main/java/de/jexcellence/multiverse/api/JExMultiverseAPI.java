package de.jexcellence.multiverse.api;

import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.jetbrains.annotations.NotNull;

/**
 * Static accessor for the {@link MultiverseProvider} API.
 *
 * <pre>{@code
 * MultiverseProvider provider = JExMultiverseAPI.get();
 * provider.getWorld("my_world").thenAccept(opt -> { ... });
 * }</pre>
 *
 * @author JExcellence
 * @since 3.0.0
 */
public final class JExMultiverseAPI {

    private JExMultiverseAPI() {
    }

    /**
     * Returns the registered {@link MultiverseProvider} instance.
     *
     * @return the provider
     * @throws IllegalStateException if JExMultiverse is not loaded
     */
    public static @NotNull MultiverseProvider get() {
        RegisteredServiceProvider<MultiverseProvider> registration =
                Bukkit.getServicesManager().getRegistration(MultiverseProvider.class);
        if (registration == null) {
            throw new IllegalStateException(
                    "JExMultiverse is not loaded — MultiverseProvider is not registered");
        }
        return registration.getProvider();
    }
}
