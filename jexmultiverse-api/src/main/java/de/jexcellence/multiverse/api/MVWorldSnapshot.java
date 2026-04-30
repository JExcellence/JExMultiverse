package de.jexcellence.multiverse.api;

import org.jetbrains.annotations.Nullable;

/**
 * Immutable snapshot of a managed world's state, safe to expose via the public API.
 *
 * @param id              the database identifier
 * @param identifier      the world name
 * @param type            the world generation type
 * @param environment     the world environment name (NORMAL, NETHER, THE_END)
 * @param spawnX          spawn X coordinate
 * @param spawnY          spawn Y coordinate
 * @param spawnZ          spawn Z coordinate
 * @param spawnYaw        spawn yaw rotation
 * @param spawnPitch      spawn pitch rotation
 * @param globalSpawn     whether this world is the global spawn
 * @param pvpEnabled      whether PvP is enabled
 * @param enterPermission the permission required to enter, or {@code null} if unrestricted
 * @author JExcellence
 * @since 3.0.0
 */
public record MVWorldSnapshot(
        long id,
        String identifier,
        MVWorldType type,
        String environment,
        double spawnX,
        double spawnY,
        double spawnZ,
        float spawnYaw,
        float spawnPitch,
        boolean globalSpawn,
        boolean pvpEnabled,
        @Nullable String enterPermission
) {
}
