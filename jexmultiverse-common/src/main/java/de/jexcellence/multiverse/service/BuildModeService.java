package de.jexcellence.multiverse.service;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks which players currently have <b>build mode</b> enabled — a per-session,
 * in-memory toggle that lets staff bypass {@code WorldProtectionListener} inside
 * a build-locked world. Build mode is intentionally <em>not</em> persisted: it
 * resets on relog so a forgotten toggle can never leave a locked world editable.
 *
 * @author JExcellence
 * @since 3.4.0
 */
public final class BuildModeService implements Listener {

    private final Set<UUID> active = ConcurrentHashMap.newKeySet();

    /**
     * Flips build mode for the player.
     *
     * @param player the player
     * @return the new state ({@code true} = now enabled)
     */
    public boolean toggle(@NotNull Player player) {
        UUID id = player.getUniqueId();
        if (active.remove(id)) {
            return false;
        }
        active.add(id);
        return true;
    }

    /**
     * @param playerId the player's UUID
     * @return whether the player currently has build mode enabled
     */
    public boolean isEnabled(@NotNull UUID playerId) {
        return active.contains(playerId);
    }

    /** Clears the player's build-mode flag on quit so it never persists. */
    @EventHandler
    public void onQuit(@NotNull PlayerQuitEvent event) {
        active.remove(event.getPlayer().getUniqueId());
    }
}
