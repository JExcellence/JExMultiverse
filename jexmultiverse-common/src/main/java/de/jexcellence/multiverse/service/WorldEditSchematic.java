package de.jexcellence.multiverse.service;

import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.session.ClipboardHolder;
import de.jexcellence.jexplatform.logging.JExLogger;
import org.bukkit.World;
import org.bukkit.generator.LimitedRegion;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Optional;

/**
 * {@link PlacedSchematic} backed by a WorldEdit / FAWE {@link Clipboard}.
 * Loaded from {@code .schem} or {@code .schematic} files via WorldEdit's
 * {@link ClipboardFormats}.
 *
 * <p>This class is loaded lazily — {@link SchematicService} only references
 * it when the WorldEdit plugin is detected at runtime, so servers without
 * WorldEdit don't trigger {@code NoClassDefFoundError}.
 *
 * <p>Does not support {@link LimitedRegion} writes during chunk population
 * — WorldEdit's edit session API requires a fully-loaded World. Use
 * {@code /mv applyschematic} for retroactive placement instead.
 */
public final class WorldEditSchematic implements PlacedSchematic {

    private final Clipboard clipboard;

    private WorldEditSchematic(@NotNull Clipboard clipboard) {
        this.clipboard = clipboard;
    }

    /**
     * Attempts to load a WorldEdit schematic from disk. Returns empty if the
     * format isn't recognised or the file fails to parse.
     */
    public static @NotNull Optional<PlacedSchematic> tryLoad(@NotNull File file, @NotNull JExLogger logger) {
        var format = ClipboardFormats.findByFile(file);
        if (format == null) {
            logger.warn("WorldEdit: no clipboard format matches {}", file.getName());
            return Optional.empty();
        }
        try (var stream = new FileInputStream(file);
             var reader = format.getReader(stream)) {
            var clipboard = reader.read();
            return Optional.of(new WorldEditSchematic(clipboard));
        } catch (IOException e) {
            logger.error("WorldEdit: failed to read {}", file.getName(), e);
            return Optional.empty();
        }
    }

    @Override
    public void place(@NotNull World world, int x, int y, int z) {
        var weWorld = BukkitAdapter.adapt(world);
        try (var editSession = WorldEdit.getInstance().newEditSessionBuilder()
                .world(weWorld)
                .build()) {
            var holder = new ClipboardHolder(clipboard);
            var operation = holder.createPaste(editSession)
                    .to(BlockVector3.at(x, y, z))
                    .ignoreAirBlocks(false)
                    .build();
            Operations.complete(operation);
        } catch (com.sk89q.worldedit.WorldEditException e) {
            throw new RuntimeException("WorldEdit paste failed at " + x + "," + y + "," + z, e);
        }
    }

    @Override
    public boolean tryPlace(@NotNull LimitedRegion region, int x, int y, int z) {
        // WorldEdit's edit session API is incompatible with LimitedRegion's
        // restricted access pattern. Schematic worlds with .schem files must
        // use /mv applyschematic for retroactive placement.
        return false;
    }
}
