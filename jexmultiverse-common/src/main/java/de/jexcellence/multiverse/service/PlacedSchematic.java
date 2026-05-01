package de.jexcellence.multiverse.service;

import org.bukkit.World;
import org.bukkit.generator.LimitedRegion;
import org.jetbrains.annotations.NotNull;

/**
 * Format-agnostic schematic that can be pasted at a world location.
 *
 * <p>Two implementations ship:
 * <ul>
 *   <li>{@link BukkitStructureSchematic} — wraps the native Bukkit Structure
 *       API for {@code .nbt} files. No external dependency.</li>
 *   <li>{@code WorldEditSchematic} — wraps a WorldEdit/FAWE Clipboard for
 *       {@code .schem} / {@code .schematic} files. Loaded only when
 *       WorldEdit is installed; absent otherwise.</li>
 * </ul>
 *
 * @author JExcellence
 * @since 3.1.0
 */
public interface PlacedSchematic {

    /**
     * Places this schematic at the given world block coordinates. Must be
     * called on the main server thread.
     */
    void place(@NotNull World world, int x, int y, int z);

    /**
     * Attempts to place this schematic into a {@link LimitedRegion} during
     * chunk population. Returns {@code true} on success, {@code false} if the
     * implementation does not support direct LimitedRegion writes (in which
     * case callers should fall back to {@code /mv applyschematic} for
     * retroactive placement).
     */
    boolean tryPlace(@NotNull LimitedRegion region, int x, int y, int z);
}
