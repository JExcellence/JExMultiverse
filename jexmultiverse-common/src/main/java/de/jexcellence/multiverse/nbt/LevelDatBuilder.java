package de.jexcellence.multiverse.nbt;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Generates a minimal {@code level.dat} for a brand-new world.
 *
 * <p>The output lands on disk under
 * {@code <server-root>/<worldName>/level.dat} and is everything Folia
 * (and Paper/Spigot) needs to discover the world during its startup
 * scan and load it like any other world directory. Once loaded, the
 * world's chunk generator is overridden by
 * {@code JExMultiverse#getDefaultWorldGenerator} (driven from
 * {@code bukkit.yml}) — so the level.dat's own {@code generator}
 * config matters only enough to be syntactically valid; the runtime
 * generator is ours.
 *
 * <p>Generated NBT layout (top-level compound, gzipped):
 * <pre>{@code
 * Compound("") {
 *   Compound("Data") {
 *     Int    DataVersion          // current server's data version
 *     String LevelName            // matches the on-disk folder
 *     Int    SpawnX, SpawnY, SpawnZ
 *     Long   Time, DayTime, LastPlayed
 *     Int    GameType             // 0 = survival
 *     Byte   hardcore             // 0
 *     Byte   allowCommands        // 1
 *     Byte   initialized          // 0 — server initialises on first load
 *     Int    version              // 19133 (Anvil)
 *     Compound Version { Id, Name, Series, Snapshot }
 *     Compound WorldGenSettings {
 *       Long seed
 *       Byte generate_features
 *       Byte bonus_chest
 *       Compound dimensions {
 *         Compound minecraft:overworld { type, generator: minecraft:flat layers=[] }
 *         Compound minecraft:the_nether { type, generator: minecraft:flat layers=[] }
 *         Compound minecraft:the_end    { type, generator: minecraft:flat layers=[] }
 *       }
 *     }
 *   }
 * }
 * }</pre>
 *
 * <p>Every dimension goes through the {@code minecraft:flat} void
 * generator (zero layers) — the server still computes a valid empty
 * world even if our plugin's chunk generator override is somehow not
 * applied yet. The dimensions compound carries all three so Folia can
 * locate "the nether-side" / "the end-side" companion worlds it
 * expects per-world even though we only ship the one folder.
 *
 * @author JExcellence
 * @since 3.4.0
 */
public final class LevelDatBuilder {

    /** Anvil region-file format version. Stable since 1.13. */
    private static final int ANVIL_VERSION = 19133;

    private static final String DIM_OVERWORLD = "minecraft:overworld";
    private static final String DIM_NETHER    = "minecraft:the_nether";
    private static final String DIM_END       = "minecraft:the_end";

    private LevelDatBuilder() {
    }

    /**
     * Creates the world skeleton on disk: {@code <root>/<name>/level.dat}
     * plus an empty {@code session.lock}. Safe to call when the
     * directory exists — re-writes the level.dat (idempotent).
     *
     * @param worldName   the world identifier (also the folder name)
     * @param environment the world environment, drives which dimension
     *                    type the primary stem points at
     */
    public static void writeSkeleton(@NotNull String worldName,
                                      @NotNull World.Environment environment) throws IOException {
        // Bukkit.getWorldContainer() respects server.properties' world
        // container override (rare, but operators do remap it). Falls
        // back to JVM CWD when Bukkit hasn't initialised yet (e.g. in
        // tests) — matches the default container in practice.
        File container;
        try {
            container = Bukkit.getWorldContainer();
        } catch (final Throwable ignored) {
            container = new File(".");
        }
        writeSkeletonAt(new File(container, worldName).getAbsoluteFile(), worldName, environment);
    }

    private static void writeSkeletonAt(@NotNull File worldDir,
                                         @NotNull String worldName,
                                         @NotNull World.Environment environment) throws IOException {
        if (!worldDir.exists() && !worldDir.mkdirs()) {
            throw new IOException("Failed to create world directory " + worldDir);
        }
        // session.lock is required by Mojang's world-storage layer to
        // detect concurrent access. Empty file is fine — the lock
        // mechanism uses file-channel locking, not contents.
        final File sessionLock = new File(worldDir, "session.lock");
        if (!sessionLock.exists() && !sessionLock.createNewFile()) {
            throw new IOException("Failed to create session.lock for " + worldName);
        }

        // uid.dat stores the world's unique UUID. If a stale uid.dat
        // from a previous (failed or aborted) skeleton-write is still
        // on disk, CraftServer will detect a UUID collision with any
        // other world that was assigned the same UUID and refuse to
        // load this world with "duplicate world" in the log. Deleting
        // it here is safe: the server regenerates uid.dat on first load
        // from the world's level.dat seed, so we never lose data.
        final File uidDat = new File(worldDir, "uid.dat");
        if (uidDat.exists()) {
            try {
                java.nio.file.Files.delete(uidDat.toPath());
            } catch (IOException ex) {
                // Non-fatal: if deletion fails the server may log a UUID collision
                // warning, but world loading will still proceed.
            }
        }

        final File levelDat = new File(worldDir, "level.dat");
        try (var out = new FileOutputStream(levelDat)) {
            NbtWriter.writeLevelDat(out, buildRoot(worldName, environment));
        }
    }

    /**
     * Returns the canonical Minecraft dimension type id for a Bukkit
     * {@link World.Environment}.
     */
    public static @NotNull String dimensionTypeFor(@NotNull World.Environment env) {
        return switch (env) {
            case NETHER -> DIM_NETHER;
            case THE_END -> DIM_END;
            case CUSTOM, NORMAL -> DIM_OVERWORLD;
        };
    }

    /**
     * Builds the NBT compound that goes inside a level.dat. The map
     * preserves insertion order; the on-disk byte layout matches.
     */
    private static @NotNull Map<String, Object> buildRoot(@NotNull String worldName,
                                                           @NotNull World.Environment environment) {
        final int dataVersion = currentDataVersion();
        final String mcVersion = Bukkit.getMinecraftVersion();

        // The dimensions compound declares the dimension type AND the
        // chunk generator for each dim. The void/flat layer-less
        // generator is universally accepted; our plugin's override
        // takes over once the world is loaded into Bukkit.
        final Map<String, Object> dimensions = NbtWriter.compound();
        dimensions.put(DIM_OVERWORLD, buildDimensionStem(DIM_OVERWORLD));
        dimensions.put(DIM_NETHER,    buildDimensionStem(DIM_NETHER));
        dimensions.put(DIM_END,       buildDimensionStem(DIM_END));

        final Map<String, Object> worldGenSettings = NbtWriter.compound();
        worldGenSettings.put("seed", 0L);
        worldGenSettings.put("generate_features", (byte) 0);
        worldGenSettings.put("bonus_chest", (byte) 0);
        worldGenSettings.put("dimensions", dimensions);

        final Map<String, Object> versionTag = NbtWriter.compound();
        versionTag.put("Id", dataVersion);
        versionTag.put("Name", mcVersion);
        versionTag.put("Series", "main");
        versionTag.put("Snapshot", (byte) 0);

        final Map<String, Object> data = NbtWriter.compound();
        data.put("DataVersion", dataVersion);
        data.put("LevelName", worldName);
        data.put("SpawnX", 0);
        data.put("SpawnY", 100);
        data.put("SpawnZ", 0);
        data.put("Time", 0L);
        data.put("DayTime", 6000L);
        data.put("LastPlayed", System.currentTimeMillis());
        data.put("GameType", 0);
        data.put("hardcore", (byte) 0);
        data.put("allowCommands", (byte) 1);
        data.put("initialized", (byte) 0);
        data.put("version", ANVIL_VERSION);
        data.put("Version", versionTag);
        data.put("WorldGenSettings", worldGenSettings);
        // Note: the per-world environment isn't carried as a top-level
        // tag — Bukkit derives it from the dimension stem the world's
        // primary level uses. {@code environment} is currently unused
        // here, but kept in the signature so a future schema (e.g.
        // dimension overrides) can branch on it without breaking the
        // call site.

        final Map<String, Object> root = NbtWriter.compound();
        root.put("Data", data);
        return root;
    }

    /**
     * One dimension's {@code type} + {@code generator} compound. We
     * intentionally use the empty-layers flat generator everywhere —
     * the actual chunk generation at runtime is replaced by our
     * plugin's {@link org.bukkit.generator.ChunkGenerator}.
     */
    private static @NotNull Map<String, Object> buildDimensionStem(@NotNull String dimensionType) {
        final Map<String, Object> settings = NbtWriter.compound();
        settings.put("biome", "minecraft:plains");
        settings.put("features", (byte) 0);
        settings.put("lakes", (byte) 0);
        settings.put("layers", List.of());

        final Map<String, Object> generator = NbtWriter.compound();
        generator.put("type", "minecraft:flat");
        generator.put("settings", settings);

        final Map<String, Object> stem = NbtWriter.compound();
        stem.put("type", dimensionType);
        stem.put("generator", generator);
        return stem;
    }

    /**
     * Reads the current MC data version from {@link Bukkit#getUnsafe()}.
     * Falls back to 4438 (1.21.4) when the unsafe accessor isn't
     * available — extremely unlikely on Paper/Folia 1.21.x.
     */
    @SuppressWarnings("deprecation")
    private static int currentDataVersion() {
        try {
            return Bukkit.getUnsafe().getDataVersion();
        } catch (final Throwable ignored) {
            return 4438;
        }
    }
}
