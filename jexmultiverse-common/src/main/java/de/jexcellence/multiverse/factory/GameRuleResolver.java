package de.jexcellence.multiverse.factory;

import org.bukkit.GameRule;
import org.bukkit.Registry;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.Map.entry;

/**
 * Resolves gamerules by name across the 26.x rename.
 *
 * <p>Minecraft 26.x moved every gamerule to a snake_case key and renamed a number of
 * them outright ({@code doDaylightCycle} became {@code advance_time}, {@code disableRaids}
 * became the inverted {@code raids}). Worlds persisted before the rename still carry the
 * old camelCase names, so lookups try the name as given, then the known rename, then a
 * plain camelCase to snake_case conversion. Rules whose meaning changed rather than their
 * name ({@code doFireTick}, {@code spawnChunkRadius}) are deliberately not mapped and
 * resolve to nothing.
 *
 * @author JExcellence
 * @since 3.7.0
 */
public final class GameRuleResolver {

    /** Legacy camelCase names, lowercased, mapped to the 26.x key they were renamed to. */
    private static final Map<String, String> RENAMED = Map.ofEntries(
            entry("announceadvancements", "show_advancement_messages"),
            entry("disableplayermovementcheck", "player_movement_check"),
            entry("disableelytramovementcheck", "elytra_movement_check"),
            entry("dodaylightcycle", "advance_time"),
            entry("doentitydrops", "entity_drops"),
            entry("dolimitedcrafting", "limited_crafting"),
            entry("domobloot", "mob_drops"),
            entry("domobspawning", "spawn_mobs"),
            entry("dotiledrops", "block_drops"),
            entry("doweathercycle", "advance_weather"),
            entry("naturalregeneration", "natural_health_regeneration"),
            entry("disableraids", "raids"),
            entry("doinsomnia", "spawn_phantoms"),
            entry("doimmediaterespawn", "immediate_respawn"),
            entry("dopatrolspawning", "spawn_patrols"),
            entry("dotraderspawning", "spawn_wandering_traders"),
            entry("dowardenspawning", "spawn_wardens"),
            entry("dovinesspread", "spread_vines"),
            entry("commandblocksenabled", "command_blocks_work"),
            entry("spawnerblocksenabled", "spawner_blocks_work"),
            entry("spawnradius", "respawn_radius"),
            entry("maxcommandchainlength", "max_command_sequence_length"),
            entry("maxcommandforkcount", "max_command_forks"),
            entry("commandmodificationblocklimit", "max_block_modifications"),
            entry("snowaccumulationheight", "max_snow_accumulation_height"),
            entry("minecartmaxspeed", "max_minecart_speed"));

    /** Legacy names whose 26.x replacement means the opposite ({@code disableRaids} vs {@code raids}). */
    private static final Set<String> INVERTED = Set.of(
            "disableplayermovementcheck", "disableelytramovementcheck", "disableraids");

    private static final AtomicReference<Map<String, GameRule<?>>> INDEX = new AtomicReference<>();

    private GameRuleResolver() {
    }

    /**
     * A resolved gamerule.
     *
     * @param rule     the rule the running server knows
     * @param inverted whether a boolean value stored under the requested name has to be
     *                 flipped before it is applied to {@code rule}
     */
    public record Resolved(@NotNull GameRule<?> rule, boolean inverted) {}

    /**
     * Resolves a gamerule by any of its names, case-insensitively.
     *
     * @param name the stored or requested gamerule name, camelCase, snake_case or namespaced
     * @return the resolved rule, or {@code null} if the running server has no such rule
     */
    public static @Nullable Resolved resolve(@NotNull String name) {
        var index = index();
        var trimmed = stripNamespace(name.trim());
        var lower = trimmed.toLowerCase(Locale.ROOT);
        var direct = index.get(lower);
        if (direct != null) {
            return new Resolved(direct, false);
        }
        var renamed = RENAMED.get(lower);
        var viaRename = renamed == null ? null : index.get(renamed);
        if (viaRename != null) {
            return new Resolved(viaRename, INVERTED.contains(lower));
        }
        var viaSnakeCase = index.get(toSnakeCase(trimmed));
        return viaSnakeCase == null ? null : new Resolved(viaSnakeCase, false);
    }

    /**
     * Returns a gamerule's name as the server reports it.
     *
     * <p>This is the one place {@code GameRule#getName()} is called. It is deprecated for
     * removal in favour of the {@code Keyed} interface; stored names that predate the
     * switch keep resolving through {@link #resolve(String)}.
     *
     * @param rule the gamerule
     * @return the gamerule name
     */
    @SuppressWarnings("removal")
    public static @NotNull String name(@NotNull GameRule<?> rule) {
        return rule.getName();
    }

    /**
     * Converts {@code doDaylightCycle} style names to {@code do_daylight_cycle}.
     *
     * @param name the camelCase name
     * @return the lowercased snake_case form
     */
    static @NotNull String toSnakeCase(@NotNull String name) {
        var out = new StringBuilder(name.length() + 8);
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isUpperCase(c) && i > 0) {
                out.append('_');
            }
            out.append(Character.toLowerCase(c));
        }
        return out.toString();
    }

    private static @NotNull String stripNamespace(@NotNull String name) {
        int colon = name.indexOf(':');
        return colon < 0 ? name : name.substring(colon + 1);
    }

    /**
     * Lazily built index of every gamerule the running server knows, keyed by its
     * reported name and by its registry key, both lowercased. Built on first use rather
     * than in a static initialiser, because the registry is not usable until the server
     * is up.
     */
    private static @NotNull Map<String, GameRule<?>> index() {
        var index = INDEX.get();
        if (index != null) {
            return index;
        }
        var built = new HashMap<String, GameRule<?>>();
        for (var rule : Registry.GAME_RULE) {
            built.putIfAbsent(name(rule).toLowerCase(Locale.ROOT), rule);
            built.putIfAbsent(rule.getKey().getKey().toLowerCase(Locale.ROOT), rule);
        }
        var frozen = Map.copyOf(built);
        INDEX.compareAndSet(null, frozen);
        return INDEX.get();
    }
}
