package de.jexcellence.multiverse.command;

import com.raindropcentral.commands.v2.argument.ArgumentType;
import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Argument type accepting any block-form {@link Material}, with tab
 * completion biased toward typical wall / fence / decorative-block choices
 * so players don't scroll through 1300+ unrelated materials.
 *
 * <p>YAML schema id: {@code wall_material}.
 *
 * @author JExcellence
 * @since 3.2.0
 */
public final class WallMaterialArgumentType {

    /**
     * Curated tab-completion list — popular wall / fence / glass / stone
     * variants. The parser still accepts any valid block material.
     */
    private static final List<String> SUGGESTED = List.of(
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

    private WallMaterialArgumentType() {}

    public static @NotNull ArgumentType<Material> create() {
        return ArgumentType.custom(
                "wall_material",
                Material.class,
                (sender, raw) -> {
                    var mat = Material.matchMaterial(raw);
                    if (mat == null || !mat.isBlock()) {
                        return ArgumentType.ParseResult.err(
                                "plot.error.invalid_material", Map.of("material", raw));
                    }
                    return ArgumentType.ParseResult.ok(mat);
                },
                (sender, partial) -> {
                    var lower = partial.toLowerCase(Locale.ROOT);
                    return SUGGESTED.stream()
                            .filter(m -> m.startsWith(lower))
                            .toList();
                });
    }
}
