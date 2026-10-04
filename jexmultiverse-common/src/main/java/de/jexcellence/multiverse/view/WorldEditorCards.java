package de.jexcellence.multiverse.view;

import de.jexcellence.jexplatform.gui.component.CardLore;
import de.jexcellence.jexplatform.gui.style.LockedIcon;
import de.jexcellence.multiverse.api.BuildLockInteractionMode;
import de.jexcellence.multiverse.database.entity.MVWorld;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;

/**
 * The cards of the world editor: the world header and one card per setting, each with what it does, its
 * current value and what a click does. Settings that need the live world show the locked icon while the world
 * is not loaded.
 *
 * @author JExcellence
 * @since 3.8.0
 */
final class WorldEditorCards {

    static final String KEY = MultiverseCards.ROOT + "world_editor.";
    static final String WORD = MultiverseCards.COMMON + "word.";
    private static final String PLAIN = "plain";
    private static final String MUTED = "muted";
    private static final String DISABLED = "disabled";
    private static final String NOT_LOADED = "not-loaded";

    private WorldEditorCards() {
    }

    static @NotNull ItemStack header(@NotNull Player player, @NotNull MVWorld world) {
        boolean loaded = Bukkit.getWorld(world.getIdentifier()) != null;
        List<Component> rows = List.of(
                MultiverseCards.row(player, "type", MultiverseCards.word(player, PLAIN, typeWord(world))),
                MultiverseCards.row(player, "environment",
                        MultiverseCards.word(player, PLAIN, environmentWord(world.getEnvironment()))),
                MultiverseCards.row(player, "loaded",
                        loaded ? MultiverseCards.word(player, "ok", "loaded") : MultiverseCards.word(player, "bad", "unloaded")),
                MultiverseCards.row(player, "gamerules",
                        MultiverseCards.value(player, String.valueOf(world.getGameRules().size()))));
        return MultiverseCards.card(Material.FILLED_MAP,
                MultiverseCards.ic(MultiverseCards.msg(KEY + "header.name")
                        .with("world_name", MultiverseCards.escape(world.getIdentifier())), player),
                CardLore.create()
                        .block(MultiverseCards.paragraphOf(player, KEY + "header.description"))
                        .section(MultiverseCards.section(player, "world"), rows)
                        .build());
    }

    static @NotNull ItemStack spawn(@NotNull Player player, @NotNull MVWorld world) {
        return setting(player, new ItemStack(Material.COMPASS), "spawn", "spawn",
                MultiverseCards.value(player, world.getFormattedSpawnLocation()));
    }

    static @NotNull ItemStack globalSpawn(@NotNull Player player, @NotNull MVWorld world) {
        boolean on = world.isGlobalizedSpawn();
        return toggle(player, "global-spawn", on, on ? Material.NETHER_STAR : Material.ENDER_PEARL);
    }

    static @NotNull ItemStack pvp(@NotNull Player player, @NotNull MVWorld world) {
        boolean on = world.isPvpEnabled();
        return toggle(player, "pvp", on, on ? Material.DIAMOND_SWORD : Material.WOODEN_SWORD);
    }

    static @NotNull ItemStack keepSpawn(@NotNull Player player, @NotNull MVWorld world) {
        boolean on = world.isKeepSpawnLoaded();
        return toggle(player, "keep-spawn", on, on ? Material.BEACON : Material.GLASS);
    }

    static @NotNull ItemStack buildLock(@NotNull Player player, @NotNull MVWorld world) {
        boolean on = world.isBuildLocked();
        return toggle(player, "build-lock", on, on ? Material.IRON_DOOR : Material.OAK_DOOR);
    }

    static @NotNull ItemStack time(@NotNull Player player, @NotNull MVWorld world) {
        World live = Bukkit.getWorld(world.getIdentifier());
        if (live == null) {
            return unavailable(player, "time");
        }
        return setting(player, new ItemStack(Material.CLOCK), "time", "now",
                MultiverseCards.word(player, PLAIN, timePhase(live.getTime())));
    }

    static @NotNull ItemStack timeLock(@NotNull Player player, @NotNull MVWorld world) {
        Long fixed = world.getFixedTime();
        String value = fixed != null
                ? MultiverseCards.word(player, "accent", timePhase(fixed))
                : MultiverseCards.word(player, MUTED, DISABLED);
        return setting(player, new ItemStack(fixed != null ? Material.AMETHYST_SHARD : Material.GLASS_BOTTLE),
                "time-lock", "pinned", value);
    }

    static @NotNull ItemStack weather(@NotNull Player player, @NotNull MVWorld world) {
        World live = Bukkit.getWorld(world.getIdentifier());
        if (live == null) {
            return unavailable(player, "weather");
        }
        Material stormIcon = live.isThundering() ? Material.LIGHTNING_ROD : Material.WATER_BUCKET;
        Material icon = live.hasStorm() ? stormIcon : Material.SUNFLOWER;
        return setting(player, new ItemStack(icon), "weather", "now",
                MultiverseCards.word(player, PLAIN, weatherPhase(live)));
    }

    static @NotNull ItemStack weatherLock(@NotNull Player player, @NotNull MVWorld world) {
        boolean locked = world.isWeatherLocked();
        String value = locked
                ? MultiverseCards.word(player, "accent", weatherWord(world.getWeatherType()))
                : MultiverseCards.word(player, MUTED, DISABLED);
        return setting(player, new ItemStack(locked ? Material.AMETHYST_SHARD : Material.GLASS_BOTTLE),
                "weather-lock", "pinned", value);
    }

    static @NotNull ItemStack difficulty(@NotNull Player player, @NotNull MVWorld world) {
        String configured = world.getDifficulty();
        String value = configured == null
                ? MultiverseCards.word(player, MUTED, "unmanaged")
                : MultiverseCards.word(player, PLAIN, configured.toLowerCase(Locale.ROOT));
        return setting(player, new ItemStack(configured == null ? Material.STRUCTURE_VOID : Material.CREEPER_HEAD),
                "difficulty", "difficulty", value);
    }

    static @NotNull ItemStack interactions(@NotNull Player player, @NotNull MVWorld world) {
        BuildLockInteractionMode mode = world.getBuildLockInteractionMode();
        Material icon = switch (mode) {
            case OPEN -> Material.OAK_BUTTON;
            case SAFE -> Material.LEVER;
            case LOCKED -> Material.IRON_BARS;
            default -> Material.STONE_BUTTON;
        };
        return setting(player, new ItemStack(icon), "interactions", "mode",
                MultiverseCards.word(player, PLAIN, interactionWord(mode)));
    }

    static @NotNull ItemStack gameRules(@NotNull Player player, @NotNull MVWorld world) {
        return setting(player, new ItemStack(Material.COMMAND_BLOCK), "gamerules", "managed",
                MultiverseCards.value(player, String.valueOf(world.getGameRules().size())));
    }

    static @NotNull ItemStack save(@NotNull Player player) {
        return MultiverseCards.card(Material.EMERALD, MultiverseCards.ic(player, KEY + "save.name"),
                CardLore.create()
                        .block(MultiverseCards.paragraphOf(player, KEY + "save.description"))
                        .block(List.of(MultiverseCards.action(player, KEY + "save.action")))
                        .build());
    }

    private static @NotNull ItemStack toggle(@NotNull Player player, @NotNull String id, boolean enabled,
                                             @NotNull Material icon) {
        return setting(player, new ItemStack(icon), id, "status", MultiverseCards.state(player, enabled));
    }

    private static @NotNull ItemStack setting(@NotNull Player player, @NotNull ItemStack icon, @NotNull String id,
                                              @NotNull String label, @NotNull String valueMini) {
        String base = KEY + id;
        return MultiverseCards.card(icon, MultiverseCards.ic(player, base + ".name"),
                CardLore.create()
                        .block(MultiverseCards.paragraphOf(player, base + ".description"))
                        .block(List.of(MultiverseCards.row(player, label, valueMini)))
                        .block(List.of(MultiverseCards.action(player, base + ".action")))
                        .build());
    }

    private static @NotNull ItemStack unavailable(@NotNull Player player, @NotNull String id) {
        String base = KEY + id;
        return MultiverseCards.card(LockedIcon.item(player), MultiverseCards.ic(player, base + ".name"),
                CardLore.create()
                        .block(MultiverseCards.paragraphOf(player, base + ".description"))
                        .block(List.of(MultiverseCards.row(player, "now",
                                MultiverseCards.word(player, "bad", NOT_LOADED))))
                        .build());
    }

    /** @return the translated, plain word for {@code word}. */
    static @NotNull String plainWord(@Nullable Player player, @NotNull String word) {
        return MultiverseCards.text(player, WORD + word);
    }

    static @NotNull String timePhase(long ticks) {
        if (ticks < 6000) {
            return "morning";
        }
        if (ticks < 12000) {
            return "noon";
        }
        if (ticks < 18000) {
            return "evening";
        }
        return "night";
    }

    static @NotNull String weatherPhase(@NotNull World world) {
        if (!world.hasStorm()) {
            return "clear";
        }
        return world.isThundering() ? "storm" : "rain";
    }

    static @NotNull String weatherWord(@Nullable String stored) {
        return stored == null ? "clear" : stored.toLowerCase(Locale.ROOT);
    }

    static @NotNull String interactionWord(@NotNull BuildLockInteractionMode mode) {
        return "mode-" + mode.name().toLowerCase(Locale.ROOT);
    }

    private static @NotNull String typeWord(@NotNull MVWorld world) {
        return "type-" + world.getType().name().toLowerCase(Locale.ROOT);
    }

    static @NotNull String environmentWord(@NotNull World.Environment environment) {
        return "env-" + environment.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /**
     * Cycles peaceful, easy, normal, hard, then unmanaged ({@code null}: the server default applies).
     *
     * @param current the current stored difficulty name, or {@code null}
     * @return the next difficulty name, or {@code null} for unmanaged
     */
    static @Nullable String nextDifficulty(@Nullable String current) {
        if (current == null) {
            return Difficulty.PEACEFUL.name();
        }
        return switch (current.toUpperCase(Locale.ROOT)) {
            case "PEACEFUL" -> Difficulty.EASY.name();
            case "EASY" -> Difficulty.NORMAL.name();
            case "NORMAL" -> Difficulty.HARD.name();
            default -> null;
        };
    }
}
