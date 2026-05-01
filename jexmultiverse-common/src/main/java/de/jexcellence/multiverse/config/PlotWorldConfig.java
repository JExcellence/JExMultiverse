package de.jexcellence.multiverse.config;

import de.jexcellence.multiverse.generator.plot.PlotLayer;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Plot-world generation parameters loaded from {@code config.yml}.
 *
 * <p>Falls back to sensible defaults for any missing key so a corrupted or
 * partially-edited file still produces a valid generator.
 *
 * @param plotSize         edge length of each plot
 * @param roadWidth        width of road between plots
 * @param plotHeight       y-coordinate of the plot surface
 * @param roadMaterial     top-layer material of roads
 * @param wallMaterial     material edging each plot
 * @param layers           terrain layers within plots
 * @param schematicOffsetX X offset for schematic pastes, relative to plot NW corner
 * @param schematicOffsetY Y offset for schematic pastes, relative to plotHeight
 * @param schematicOffsetZ Z offset for schematic pastes, relative to plot NW corner
 *
 * @author JExcellence
 * @since 3.0.0
 */
public record PlotWorldConfig(
        int plotSize,
        int roadWidth,
        int plotHeight,
        @NotNull Material roadMaterial,
        @NotNull Material wallMaterial,
        @NotNull List<PlotLayer> layers,
        int schematicOffsetX,
        int schematicOffsetY,
        int schematicOffsetZ
) {

    private static final PlotWorldConfig DEFAULT = new PlotWorldConfig(
            16, 5, 64,
            Material.STONE_BRICKS, Material.STONE_BRICK_WALL,
            List.of(
                    new PlotLayer(Material.DIRT, 1, 62),
                    new PlotLayer(Material.GRASS_BLOCK, 63, 63)
            ),
            0, 1, 0
    );

    /**
     * Loads from {@code <dataFolder>/config.yml}. If the file is missing or
     * the {@code plot-world} section is absent, returns the built-in default.
     */
    public static @NotNull PlotWorldConfig load(@NotNull File dataFolder) {
        var file = new File(dataFolder, "config.yml");
        if (!file.exists()) return DEFAULT;

        var yaml = YamlConfiguration.loadConfiguration(file);
        var section = yaml.getConfigurationSection("plot-world");
        if (section == null) return DEFAULT;

        var plotSize  = section.getInt("plot-size", DEFAULT.plotSize);
        var roadWidth = section.getInt("road-width", DEFAULT.roadWidth);
        var plotHeight = section.getInt("plot-height", DEFAULT.plotHeight);
        var roadMat   = parseMaterial(section.getString("road-material"), DEFAULT.roadMaterial);
        var wallMat   = parseMaterial(section.getString("wall-material"), DEFAULT.wallMaterial);
        var offX      = section.getInt("schematic-offset-x", DEFAULT.schematicOffsetX);
        var offY      = section.getInt("schematic-offset-y", DEFAULT.schematicOffsetY);
        var offZ      = section.getInt("schematic-offset-z", DEFAULT.schematicOffsetZ);

        var rawLayers = section.getList("layers");
        List<PlotLayer> layers = new ArrayList<>();
        if (rawLayers != null) {
            for (var raw : rawLayers) {
                if (!(raw instanceof Map<?, ?> map)) continue;
                var mat   = parseMaterial(asString(map.get("material")), Material.DIRT);
                var minY  = asInt(map.get("min-y"), 0);
                var maxY  = asInt(map.get("max-y"), minY);
                layers.add(new PlotLayer(mat, minY, maxY));
            }
        }
        if (layers.isEmpty()) layers = DEFAULT.layers;

        return new PlotWorldConfig(plotSize, roadWidth, plotHeight, roadMat, wallMat, layers,
                offX, offY, offZ);
    }

    private static Material parseMaterial(String raw, Material fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        var mat = Material.matchMaterial(raw);
        return mat != null ? mat : fallback;
    }

    private static String asString(Object o) {
        return o == null ? null : o.toString();
    }

    private static int asInt(Object o, int fallback) {
        if (o instanceof Number n) return n.intValue();
        if (o instanceof String s) try { return Integer.parseInt(s.trim()); } catch (NumberFormatException ignored) {}
        return fallback;
    }
}
