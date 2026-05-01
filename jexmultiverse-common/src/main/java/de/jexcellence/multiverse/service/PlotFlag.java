package de.jexcellence.multiverse.service;

import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Per-plot boolean flags. Each flag has a stable key (used in storage and on
 * the command line), a default value, and a short description.
 *
 * <p>To register a new flag: add an enum entry, wire any required listener
 * logic, and add a translation under {@code plot.flag.<key>.name/description}.
 *
 * @author JExcellence
 * @since 3.2.0
 */
public enum PlotFlag {

    /** Allow PvP between players standing in the plot. Default: false. */
    PVP("pvp", false),

    /** Allow hostile-mob natural spawning inside the plot. Default: true. */
    MOB_SPAWNING("mob-spawning", true),

    /**
     * Allow explosions (creeper / TNT / wither / end-crystal) to damage
     * blocks inside the plot. Default: false.
     */
    EXPLOSION("explosion", false),

    /** Allow fire to spread inside the plot. Default: false. */
    FIRE_SPREAD("fire-spread", false),

    /** Keep player inventory on death inside the plot. Default: false. */
    KEEP_INVENTORY("keep-inventory", false),

    /**
     * Allow non-trusted players to enter the plot. Default: true. When
     * {@code false}, only the owner and trusted members can enter; everyone
     * else is teleported back when they cross the boundary.
     */
    ENTRY("entry", true),

    /** Allow water / lava to flow inside the plot. Default: false. */
    LIQUID_FLOW("liquid-flow", false),

    /**
     * Allow ice / snow to form or melt inside the plot. Default: false (so
     * ice and snow stay where the owner placed them regardless of biome).
     */
    ICE_FORM_MELT("ice-form-melt", false);

    private final String key;
    private final boolean defaultValue;

    PlotFlag(@NotNull String key, boolean defaultValue) {
        this.key = key;
        this.defaultValue = defaultValue;
    }

    public @NotNull String key() {
        return key;
    }

    public boolean defaultValue() {
        return defaultValue;
    }

    /**
     * Resolves a flag by its YAML/CLI key (case-insensitive, dashes preserved).
     */
    public static @NotNull Optional<PlotFlag> byKey(@NotNull String raw) {
        var lower = raw.toLowerCase(Locale.ROOT);
        for (var f : values()) {
            if (f.key.equals(lower)) return Optional.of(f);
        }
        return Optional.empty();
    }

    /** Lower-case keys, sorted alphabetically — handy for tab completion. */
    public static @NotNull List<String> allKeys() {
        return Arrays.stream(values()).map(PlotFlag::key).sorted().toList();
    }
}
