package de.jexcellence.multiverse.command;

import com.raindropcentral.commands.v2.argument.ArgumentType;
import de.jexcellence.multiverse.database.entity.MVWorld;
import de.jexcellence.multiverse.factory.WorldFactory;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;
import java.util.Map;

/**
 * JExCommand 2.0 argument type resolving raw tokens into {@link MVWorld} entities
 * via the in-memory {@link WorldFactory} cache.
 *
 * <p>YAML schema uses the id {@code world}:
 * <pre>
 * argumentSchema:
 *   - { name: world, type: world, required: true }
 * </pre>
 *
 * <p>On failure, emits the i18n key {@code multiverse.world_does_not_exist} with
 * the placeholder {@code {world_name}} equal to the raw token.
 *
 * @author JExcellence
 * @since 3.0.0
 */
public final class WorldArgumentType {

    private WorldArgumentType() {}

    /**
     * Builds an {@link ArgumentType ArgumentType&lt;MVWorld&gt;} backed by the given factory.
     */
    public static @NotNull ArgumentType<MVWorld> of(@NotNull WorldFactory worldFactory) {
        return ArgumentType.custom(
                "world",
                MVWorld.class,
                (sender, raw) -> worldFactory.getCachedWorld(raw)
                        .map(ArgumentType.ParseResult::ok)
                        .orElseGet(() -> ArgumentType.ParseResult.err(
                                "multiverse.world_does_not_exist",
                                Map.of("world_name", raw))),
                (sender, partial) -> {
                    var lower = partial.toLowerCase(Locale.ROOT);
                    return worldFactory.getAllCachedWorlds().stream()
                            .map(MVWorld::getIdentifier)
                            .filter(id -> id.toLowerCase(Locale.ROOT).startsWith(lower))
                            .sorted()
                            .toList();
                }
        );
    }
}
