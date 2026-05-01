package de.jexcellence.multiverse.command;

import com.raindropcentral.commands.v2.argument.ArgumentType;
import de.jexcellence.multiverse.service.PlotFlag;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;
import java.util.Map;

/**
 * Argument type that resolves a flag key (e.g. {@code pvp}) to a
 * {@link PlotFlag} enum constant. Tab-completes the registered flag keys.
 *
 * <p>YAML schema id: {@code plot_flag}.
 *
 * @author JExcellence
 * @since 3.2.0
 */
public final class PlotFlagArgumentType {

    private PlotFlagArgumentType() {}

    public static @NotNull ArgumentType<PlotFlag> create() {
        return ArgumentType.custom(
                "plot_flag",
                PlotFlag.class,
                (sender, raw) -> PlotFlag.byKey(raw)
                        .map(ArgumentType.ParseResult::ok)
                        .orElseGet(() -> ArgumentType.ParseResult.err(
                                "plot.error.unknown_flag",
                                Map.of("flag", raw, "options", String.join(", ", PlotFlag.allKeys())))),
                (sender, partial) -> {
                    var lower = partial.toLowerCase(Locale.ROOT);
                    return PlotFlag.allKeys().stream()
                            .filter(k -> k.startsWith(lower))
                            .toList();
                });
    }
}
