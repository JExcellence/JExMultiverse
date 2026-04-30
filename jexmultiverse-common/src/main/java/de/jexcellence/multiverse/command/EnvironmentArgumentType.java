package de.jexcellence.multiverse.command;

import com.raindropcentral.commands.v2.argument.ArgumentType;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Argument type resolving raw tokens into {@link World.Environment} values, with
 * {@link World.Environment#CUSTOM} filtered out.
 *
 * <p>{@code CUSTOM} represents datapack-defined dimensions that cannot be created
 * via the {@link org.bukkit.WorldCreator} API — Paper throws
 * {@code IllegalArgumentException("Illegal dimension (CUSTOM)")} from
 * {@code CraftServer#createWorld}. Filtering it from tab completion and parse
 * acceptance keeps the create flow clean.
 *
 * <p>YAML schema id: {@code environment}.
 */
public final class EnvironmentArgumentType {

    private static final List<World.Environment> CREATABLE = Arrays.stream(World.Environment.values())
            .filter(env -> env != World.Environment.CUSTOM)
            .toList();

    private EnvironmentArgumentType() {}

    public static @NotNull ArgumentType<World.Environment> create() {
        return ArgumentType.custom(
                "environment",
                World.Environment.class,
                (sender, raw) -> {
                    for (var env : CREATABLE) {
                        if (env.name().equalsIgnoreCase(raw)) {
                            return ArgumentType.ParseResult.ok(env);
                        }
                    }
                    var options = String.join(", ",
                            CREATABLE.stream()
                                    .map(e -> e.name().toLowerCase(Locale.ROOT))
                                    .toArray(String[]::new));
                    return ArgumentType.ParseResult.err(
                            "multiverse.invalid_environment",
                            Map.of("environment", raw, "options", options));
                },
                (sender, partial) -> {
                    var lower = partial.toLowerCase(Locale.ROOT);
                    return CREATABLE.stream()
                            .map(e -> e.name().toLowerCase(Locale.ROOT))
                            .filter(name -> name.startsWith(lower))
                            .sorted()
                            .toList();
                });
    }
}
