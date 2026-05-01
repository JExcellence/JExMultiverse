package de.jexcellence.multiverse.command;

import com.raindropcentral.commands.v2.argument.ArgumentType;
import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Argument type accepting any Bukkit {@link Material} that's a real block
 * (excluding AIR / TECHNICAL legacy entries). Tab completion returns the
 * curated "popular wall material" list when the partial is empty, and the
 * full block-material catalog filtered by partial otherwise — so players
 * can type any block name.
 *
 * <p>YAML schema id: {@code wall_material}.
 *
 * @author JExcellence
 * @since 3.2.0
 */
public final class WallMaterialArgumentType {

    /**
     * Suggested suggestions when the player hasn't typed anything yet —
     * common wall / fence / decorative blocks.
     */
    private static final List<String> CURATED = List.of(
            "stone_brick_wall",
            "mossy_stone_brick_wall",
            "cobblestone_wall",
            "mossy_cobblestone_wall",
            "andesite_wall",
            "diorite_wall",
            "granite_wall",
            "deepslate_brick_wall",
            "polished_deepslate_wall",
            "blackstone_wall",
            "polished_blackstone_brick_wall",
            "red_sandstone_wall",
            "sandstone_wall",
            "brick_wall",
            "nether_brick_wall",
            "red_nether_brick_wall",
            "end_stone_brick_wall",
            "prismarine_wall",
            "oak_fence",
            "spruce_fence",
            "birch_fence",
            "dark_oak_fence",
            "iron_bars",
            "glass",
            "white_stained_glass",
            "black_stained_glass"
    );

    /**
     * Lazy-built lower-case list of every Bukkit block material name. Built
     * once at first access; ~600 entries. Filtered by partial string at tab
     * time — sub-millisecond on modern hardware.
     */
    private static volatile List<String> ALL_BLOCKS;

    private WallMaterialArgumentType() {}

    public static @NotNull ArgumentType<Material> create() {
        return ArgumentType.custom(
                "wall_material",
                Material.class,
                (sender, raw) -> {
                    var mat = Material.matchMaterial(raw);
                    if (mat == null || !mat.isBlock() || mat.isAir() || mat == Material.STRUCTURE_VOID) {
                        return ArgumentType.ParseResult.err(
                                "plot.error.invalid_material", Map.of("material", raw));
                    }
                    return ArgumentType.ParseResult.ok(mat);
                },
                (sender, partial) -> {
                    var lower = partial.toLowerCase(Locale.ROOT);
                    if (lower.isEmpty()) return CURATED;
                    return allBlocks().stream()
                            .filter(name -> name.startsWith(lower))
                            .limit(64) // brigadier hard-caps tab suggestions; keep below.
                            .toList();
                });
    }

    private static @NotNull List<String> allBlocks() {
        var cached = ALL_BLOCKS;
        if (cached != null) return cached;
        cached = Arrays.stream(Material.values())
                .filter(m -> m.isBlock() && !m.isAir() && m != Material.STRUCTURE_VOID)
                .filter(m -> !m.name().startsWith("LEGACY_"))
                .map(m -> m.name().toLowerCase(Locale.ROOT))
                .sorted()
                .toList();
        ALL_BLOCKS = cached;
        return cached;
    }
}
