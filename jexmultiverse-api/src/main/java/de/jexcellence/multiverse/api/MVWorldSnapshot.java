package de.jexcellence.multiverse.api;

import org.jetbrains.annotations.Nullable;

/**
 * Immutable snapshot of a managed world's state, safe to expose via the public API.
 *
 * <p>New components are appended at the end so existing positional construction
 * keeps compiling across versions.
 *
 * @param id                       the database identifier
 * @param identifier               the world name
 * @param type                     the world generation type
 * @param environment              the world environment name (NORMAL, NETHER, THE_END)
 * @param spawnX                   spawn X coordinate
 * @param spawnY                   spawn Y coordinate
 * @param spawnZ                   spawn Z coordinate
 * @param spawnYaw                 spawn yaw rotation
 * @param spawnPitch               spawn pitch rotation
 * @param globalSpawn              whether this world is the global spawn
 * @param pvpEnabled               whether PvP is enabled
 * @param enterPermission          the permission required to enter, or {@code null} if unrestricted
 * @param buildLocked              whether the world is currently build-locked
 * @param buildLockInteractionMode the interaction profile applied while build-locked
 * @param plotSizeOverride         per-world plot size (PLOT only), or {@code null} for the config default
 * @param roadWidthOverride        per-world road width (PLOT only), or {@code null} for the config default
 * @param schematicName            per-world plot schematic name without extension, or {@code null}
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
        @Nullable String enterPermission,
        boolean buildLocked,
        BuildLockInteractionMode buildLockInteractionMode,
        @Nullable Integer plotSizeOverride,
        @Nullable Integer roadWidthOverride,
        @Nullable String schematicName
) {
}
