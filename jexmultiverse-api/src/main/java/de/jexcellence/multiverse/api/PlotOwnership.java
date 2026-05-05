package de.jexcellence.multiverse.api;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable snapshot of a claimed plot's ownership data, exposed via
 * {@link MultiverseProvider#plotOwnership} for external plugins.
 *
 * @param world           JExMultiverse world identifier
 * @param gridX           plot grid X
 * @param gridZ           plot grid Z
 * @param ownerUuid       owning player's UUID
 * @param ownerName       last-known owner name (best-effort, may be stale)
 * @param mergedGroupId   non-null if this plot belongs to a merged group
 * @param claimedAt       timestamp when the plot was claimed
 *
 * @author JExcellence
 * @since 3.2.0
 */
public record PlotOwnership(
        @NotNull String world,
        int gridX, int gridZ,
        @NotNull UUID ownerUuid,
        @NotNull String ownerName,
        @Nullable UUID mergedGroupId,
        @NotNull Instant claimedAt
) {
}
