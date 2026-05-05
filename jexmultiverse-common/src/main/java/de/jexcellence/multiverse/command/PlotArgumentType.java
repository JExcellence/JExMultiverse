package de.jexcellence.multiverse.command;

import com.raindropcentral.commands.v2.argument.ArgumentType;
import de.jexcellence.multiverse.database.entity.Plot;
import de.jexcellence.multiverse.service.PlotService;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Argument type that resolves a token like {@code <world>:<x>,<z>} (or just
 * {@code <x>,<z>} when the sender is in a plot world) to a claimed
 * {@link Plot}. Tab completion offers the sender's owned plots.
 *
 * <p>YAML schema:
 * <pre>
 * argumentSchema:
 *   - { name: plot, type: plot, required: true }
 * </pre>
 *
 * <p>On parse failure emits {@code plot.error.unknown_plot} with placeholder
 * {@code plot}.
 *
 * @author JExcellence
 * @since 3.2.0
 */
public final class PlotArgumentType {

    private PlotArgumentType() {}

    /**
     * Creates an {@link ArgumentType} that resolves a plot coordinate token to a claimed {@link Plot}.
     *
     * @param service the plot service used for lookup
     * @return the configured argument type
     */
    public static @NotNull ArgumentType<Plot> of(@NotNull PlotService service) {
        return ArgumentType.custom(
                "plot",
                Plot.class,
                (sender, raw) -> {
                    var parsed = parse(sender, raw);
                    if (parsed == null) {
                        return ArgumentType.ParseResult.err(
                                "plot.error.unknown_plot", Map.of("plot", raw));
                    }
                    var plot = service.getPlot(parsed.world(), parsed.x(), parsed.z()).orElse(null);
                    if (plot == null) {
                        return ArgumentType.ParseResult.err(
                                "plot.error.unknown_plot", Map.of("plot", raw));
                    }
                    return ArgumentType.ParseResult.ok(plot);
                },
                (sender, partial) -> {
                    UUID uuid = sender instanceof Player p ? p.getUniqueId() : null;
                    if (uuid == null) return java.util.List.of();
                    var lower = partial.toLowerCase(Locale.ROOT);
                    return service.getOwnedPlots(uuid).stream()
                            .map(p -> p.getWorldName() + ":" + p.getGridX() + "," + p.getGridZ())
                            .filter(s -> s.toLowerCase(Locale.ROOT).startsWith(lower))
                            .sorted()
                            .toList();
                });
    }

    private static @org.jetbrains.annotations.Nullable Parsed parse(@NotNull CommandSender sender,
                                                                     @NotNull String raw) {
        String world;
        String coords;
        var colonAt = raw.indexOf(':');
        if (colonAt > 0) {
            world = raw.substring(0, colonAt);
            coords = raw.substring(colonAt + 1);
        } else if (sender instanceof Player p) {
            world = p.getWorld().getName();
            coords = raw;
        } else {
            return null;
        }
        var commaAt = coords.indexOf(',');
        if (commaAt <= 0) return null;
        try {
            int x = Integer.parseInt(coords.substring(0, commaAt).trim());
            int z = Integer.parseInt(coords.substring(commaAt + 1).trim());
            return new Parsed(world, x, z);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private record Parsed(@NotNull String world, int x, int z) {}
}
