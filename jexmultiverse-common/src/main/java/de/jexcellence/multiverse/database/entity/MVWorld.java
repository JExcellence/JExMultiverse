package de.jexcellence.multiverse.database.entity;

import de.jexcellence.jehibernate.entity.base.LongIdEntity;
import de.jexcellence.multiverse.api.MVWorldSnapshot;
import de.jexcellence.multiverse.api.MVWorldType;
import de.jexcellence.multiverse.database.converter.LocationConverter;
import de.jexcellence.multiverse.api.BuildLockInteractionMode;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.bukkit.Location;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * Persistent entity representing a managed multiverse world.
 *
 * @author JExcellence
 * @since 3.0.0
 */
@Entity
@Table(name = "mv_world", indexes = {
        @Index(name = "idx_mv_world_identifier", columnList = "world_name"),
        @Index(name = "idx_mv_world_global_spawn", columnList = "is_globalized_spawn")
})
public class MVWorld extends LongIdEntity {

    @Column(name = "world_name", nullable = false, unique = true, length = 64)
    private String identifier;

    @Enumerated(EnumType.STRING)
    @Column(name = "world_type", nullable = false, length = 16)
    private MVWorldType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "world_environment", nullable = false, length = 16)
    private World.Environment environment;

    @Convert(converter = LocationConverter.class)
    @Column(name = "spawn_location", nullable = false, columnDefinition = "LONGTEXT")
    private Location spawnLocation;

    @Column(name = "is_globalized_spawn", nullable = false)
    private boolean globalizedSpawn;

    @Column(name = "is_pvp_enabled", nullable = false)
    private boolean pvpEnabled;

    @Column(name = "enter_permission", length = 128)
    private String enterPermission;

    /**
     * Per-world plot size override (PLOT type only). When non-null this
     * supersedes the global config.yml {@code plot-world.plot-size} for
     * generation and API queries against this world.
     */
    @Column(name = "plot_size_override")
    private Integer plotSizeOverride;

    /**
     * Per-world road width override (PLOT type only). When non-null this
     * supersedes the global config.yml {@code plot-world.road-width}.
     */
    @Column(name = "road_width_override")
    private Integer roadWidthOverride;

    /**
     * Per-world schematic file name (PLOT type only). When non-null, the
     * structure (a {@code .nbt} file in {@code plugins/JExMultiverse/schematics/})
     * is pasted at the NW corner of every plot at chunk-generation time.
     * Stored without the {@code .nbt} extension.
     */
    @Column(name = "schematic_name", length = 128)
    private String schematicName;

    /**
     * When {@code true} the world is build-locked: every player action (break,
     * place, interact, container access, entity damage, …) is cancelled for
     * everyone except operators and players in build mode (see
     * {@code WorldProtectionListener}). Use it for showcase / hub / lobby worlds.
     */
    @Column(name = "is_build_locked", nullable = false)
    private boolean buildLocked;

    @Enumerated(EnumType.STRING)
    @Column(name = "build_lock_interaction_mode", length = 16)
    private BuildLockInteractionMode buildLockInteractionMode = BuildLockInteractionMode.SAFE;

    // ── Per-world runtime settings ──────────────────────────────────────
    //
    // Every column below is nullable and uses a wrapper type. Hibernate's
    // ddl-auto=update cannot add a NOT NULL column to a table that already has
    // rows, so a primitive here would break every existing installation on
    // upgrade. Null uniformly means "leave vanilla alone" rather than a default
    // value we would then have to keep in sync.

    /**
     * Gamerules to apply on load, serialised as {@code KEY=VALUE} pairs joined by
     * {@code ;}. Null or blank means no gamerules are managed for this world.
     */
    @Column(name = "game_rules", columnDefinition = "LONGTEXT")
    private String gameRules;

    /**
     * Fixed time of day in ticks. When set, the world's time is held here and
     * {@code doDaylightCycle} is disabled on load. Null means time runs normally.
     */
    @Column(name = "fixed_time")
    private Long fixedTime;

    /**
     * Whether weather is pinned to {@link #weatherType}. Null is treated as false.
     */
    @Column(name = "weather_locked")
    private Boolean weatherLocked;

    /**
     * Pinned weather when {@link #weatherLocked} is set: {@code CLEAR}, {@code RAIN}
     * or {@code THUNDER}. Ignored while weather is unlocked.
     */
    @Column(name = "weather_type", length = 16)
    private String weatherType;

    /**
     * Per-world difficulty name. Null means the world keeps the server default.
     */
    @Column(name = "difficulty", length = 16)
    private String difficulty;

    /**
     * Whether the spawn chunks stay loaded. Null is treated as false, matching the
     * {@code setKeepSpawnInMemory(false)} that world creation has always applied.
     */
    @Column(name = "keep_spawn_loaded")
    private Boolean keepSpawnLoaded;

    // ── Constructors ────────────────────────────────────────────────────

    public MVWorld() {
        // JPA requires a no-arg constructor
    }

    private MVWorld(Builder builder) {
        this.identifier = builder.identifier;
        this.type = builder.type;
        this.environment = builder.environment;
        this.spawnLocation = builder.spawnLocation;
        this.globalizedSpawn = builder.globalizedSpawn;
        this.pvpEnabled = builder.pvpEnabled;
        this.enterPermission = builder.enterPermission;
        this.plotSizeOverride = builder.plotSizeOverride;
        this.roadWidthOverride = builder.roadWidthOverride;
        this.schematicName = builder.schematicName;
        this.buildLocked = builder.buildLocked;
        this.buildLockInteractionMode = builder.buildLockInteractionMode;
    }

    // ── Getters & Setters ───────────────────────────────────────────────

    public @NotNull String getIdentifier() {
        return identifier;
    }

    public void setIdentifier(@NotNull String identifier) {
        this.identifier = identifier;
    }

    public @NotNull MVWorldType getType() {
        return type;
    }

    public void setType(@NotNull MVWorldType type) {
        this.type = type;
    }

    public World.@NotNull Environment getEnvironment() {
        return environment;
    }

    public void setEnvironment(World.@NotNull Environment environment) {
        this.environment = environment;
    }

    public @Nullable Location getSpawnLocation() {
        return spawnLocation;
    }

    public void setSpawnLocation(@Nullable Location spawnLocation) {
        this.spawnLocation = spawnLocation;
    }

    public boolean isGlobalizedSpawn() {
        return globalizedSpawn;
    }

    public void setGlobalizedSpawn(boolean globalizedSpawn) {
        this.globalizedSpawn = globalizedSpawn;
    }

    public boolean isPvpEnabled() {
        return pvpEnabled;
    }

    public void setPvpEnabled(boolean pvpEnabled) {
        this.pvpEnabled = pvpEnabled;
    }

    public @Nullable String getEnterPermission() {
        return enterPermission;
    }

    public void setEnterPermission(@Nullable String enterPermission) {
        this.enterPermission = enterPermission;
    }

    public @Nullable Integer getPlotSizeOverride() {
        return plotSizeOverride;
    }

    public void setPlotSizeOverride(@Nullable Integer plotSizeOverride) {
        this.plotSizeOverride = plotSizeOverride;
    }

    public @Nullable Integer getRoadWidthOverride() {
        return roadWidthOverride;
    }

    public void setRoadWidthOverride(@Nullable Integer roadWidthOverride) {
        this.roadWidthOverride = roadWidthOverride;
    }

    public @Nullable String getSchematicName() {
        return schematicName;
    }

    public void setSchematicName(@Nullable String schematicName) {
        this.schematicName = schematicName;
    }

    public boolean isBuildLocked() {
        return buildLocked;
    }

    public void setBuildLocked(boolean buildLocked) {
        this.buildLocked = buildLocked;
    }

    public @NotNull BuildLockInteractionMode getBuildLockInteractionMode() {
        return buildLockInteractionMode == null ? BuildLockInteractionMode.SAFE : buildLockInteractionMode;
    }

    public void setBuildLockInteractionMode(@NotNull BuildLockInteractionMode buildLockInteractionMode) {
        this.buildLockInteractionMode = buildLockInteractionMode;
    }

    // ── Per-world runtime settings accessors ─────────────────────────────

    /**
     * Returns the managed gamerules for this world.
     *
     * @return an immutable map of gamerule name to value, empty when none are managed
     */
    public @NotNull Map<String, String> getGameRules() {
        if (gameRules == null || gameRules.isBlank()) {
            return Map.of();
        }
        var parsed = new LinkedHashMap<String, String>();
        for (var pair : gameRules.split(";")) {
            var eq = pair.indexOf('=');
            if (eq > 0) {
                parsed.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
            }
        }
        return Collections.unmodifiableMap(parsed);
    }

    /**
     * Replaces the managed gamerules for this world.
     *
     * @param rules gamerule name to value; an empty map clears them
     */
    public void setGameRules(@NotNull Map<String, String> rules) {
        if (rules.isEmpty()) {
            this.gameRules = null;
            return;
        }
        var joiner = new StringJoiner(";");
        rules.forEach((key, value) -> joiner.add(key + "=" + value));
        this.gameRules = joiner.toString();
    }

    /**
     * Returns the fixed time of day in ticks, if the world's clock is pinned.
     *
     * @return the pinned tick value, or {@code null} when time runs normally
     */
    public @Nullable Long getFixedTime() {
        return fixedTime;
    }

    /**
     * Pins or releases this world's time of day.
     *
     * @param fixedTime tick value to hold, or {@code null} to let time run
     */
    public void setFixedTime(@Nullable Long fixedTime) {
        this.fixedTime = fixedTime;
    }

    /**
     * Returns whether weather is pinned for this world.
     *
     * @return {@code true} if weather is locked
     */
    public boolean isWeatherLocked() {
        return Boolean.TRUE.equals(weatherLocked);
    }

    /**
     * Sets whether weather is pinned for this world.
     *
     * @param weatherLocked whether to pin the weather
     */
    public void setWeatherLocked(boolean weatherLocked) {
        this.weatherLocked = weatherLocked;
    }

    /**
     * Returns the pinned weather type.
     *
     * @return {@code CLEAR}, {@code RAIN}, {@code THUNDER}, or {@code null} if unset
     */
    public @Nullable String getWeatherType() {
        return weatherType;
    }

    /**
     * Sets the pinned weather type.
     *
     * @param weatherType {@code CLEAR}, {@code RAIN}, {@code THUNDER}, or {@code null}
     */
    public void setWeatherType(@Nullable String weatherType) {
        this.weatherType = weatherType;
    }

    /**
     * Returns the per-world difficulty name.
     *
     * @return the difficulty name, or {@code null} to use the server default
     */
    public @Nullable String getDifficulty() {
        return difficulty;
    }

    /**
     * Sets the per-world difficulty name.
     *
     * @param difficulty the difficulty name, or {@code null} for the server default
     */
    public void setDifficulty(@Nullable String difficulty) {
        this.difficulty = difficulty;
    }

    /**
     * Returns whether the spawn chunks stay loaded.
     *
     * @return {@code true} if spawn chunks are kept in memory
     */
    public boolean isKeepSpawnLoaded() {
        return Boolean.TRUE.equals(keepSpawnLoaded);
    }

    /**
     * Sets whether the spawn chunks stay loaded.
     *
     * @param keepSpawnLoaded whether to keep spawn chunks in memory
     */
    public void setKeepSpawnLoaded(boolean keepSpawnLoaded) {
        this.keepSpawnLoaded = keepSpawnLoaded;
    }

    // ── Snapshot ─────────────────────────────────────────────────────────

    /**
     * Creates an immutable API snapshot of this world entity.
     *
     * @return a new {@link MVWorldSnapshot}
     */
    public @NotNull MVWorldSnapshot toSnapshot() {
        return new MVWorldSnapshot(
                getId(),
                identifier,
                type,
                environment.name(),
                spawnLocation != null ? spawnLocation.getX() : 0,
                spawnLocation != null ? spawnLocation.getY() : 0,
                spawnLocation != null ? spawnLocation.getZ() : 0,
                spawnLocation != null ? spawnLocation.getYaw() : 0,
                spawnLocation != null ? spawnLocation.getPitch() : 0,
                globalizedSpawn,
                pvpEnabled,
                enterPermission,
                buildLocked,
                getBuildLockInteractionMode(),
                plotSizeOverride,
                roadWidthOverride,
                schematicName,
                getGameRules(),
                fixedTime,
                isWeatherLocked(),
                weatherType,
                difficulty,
                isKeepSpawnLoaded()
        );
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    /**
     * Returns a human-readable representation of the spawn location.
     *
     * @return formatted spawn string, or {@code "Not set"} if {@code null}
     */
    public @NotNull String getFormattedSpawnLocation() {
        if (spawnLocation == null) return "Not set";
        return String.format("%.1f, %.1f, %.1f (yaw=%.1f, pitch=%.1f)",
                spawnLocation.getX(),
                spawnLocation.getY(),
                spawnLocation.getZ(),
                spawnLocation.getYaw(),
                spawnLocation.getPitch());
    }

    // ── Object overrides ────────────────────────────────────────────────

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MVWorld mvWorld = (MVWorld) o;
        return Objects.equals(identifier, mvWorld.identifier);
    }

    @Override
    public int hashCode() {
        return Objects.hash(identifier);
    }

    @Override
    public String toString() {
        return "MVWorld{" +
                "identifier='" + identifier + '\'' +
                ", type=" + type +
                ", environment=" + environment +
                ", globalizedSpawn=" + globalizedSpawn +
                ", pvpEnabled=" + pvpEnabled +
                '}';
    }

    // ── Builder ─────────────────────────────────────────────────────────

    /**
     * Returns a new {@link Builder} for constructing {@link MVWorld} instances.
     *
     * @return a fresh builder
     */
    public static @NotNull Builder builder() {
        return new Builder();
    }

    /**
     * Fluent builder for {@link MVWorld}.
     */
    public static final class Builder {
        private String identifier;
        private MVWorldType type;
        private World.Environment environment;
        private Location spawnLocation;
        private boolean globalizedSpawn;
        private boolean pvpEnabled;
        private String enterPermission;
        private Integer plotSizeOverride;
        private Integer roadWidthOverride;
        private String schematicName;
        private boolean buildLocked;
        private BuildLockInteractionMode buildLockInteractionMode = BuildLockInteractionMode.SAFE;

        private Builder() {}

        /**
         * Sets the world identifier.
         *
         * @param identifier the unique world name
         * @return this builder
         */
        public @NotNull Builder identifier(@NotNull String identifier) {
            this.identifier = identifier;
            return this;
        }

        /**
         * Sets the world generation type.
         *
         * @param type the {@link MVWorldType}
         * @return this builder
         */
        public @NotNull Builder type(@NotNull MVWorldType type) {
            this.type = type;
            return this;
        }

        /**
         * Sets the world environment.
         *
         * @param environment the Bukkit {@link World.Environment}
         * @return this builder
         */
        public @NotNull Builder environment(World.@NotNull Environment environment) {
            this.environment = environment;
            return this;
        }

        /**
         * Sets the spawn location.
         *
         * @param spawnLocation the spawn {@link Location}, or {@code null} to leave unset
         * @return this builder
         */
        public @NotNull Builder spawnLocation(@Nullable Location spawnLocation) {
            this.spawnLocation = spawnLocation;
            return this;
        }

        /**
         * Sets whether this world is the global spawn.
         *
         * @param globalizedSpawn {@code true} to mark as global spawn
         * @return this builder
         */
        public @NotNull Builder globalizedSpawn(boolean globalizedSpawn) {
            this.globalizedSpawn = globalizedSpawn;
            return this;
        }

        /**
         * Sets whether PvP is enabled in this world.
         *
         * @param pvpEnabled {@code true} to enable PvP
         * @return this builder
         */
        public @NotNull Builder pvpEnabled(boolean pvpEnabled) {
            this.pvpEnabled = pvpEnabled;
            return this;
        }

        /**
         * Sets the permission required to enter this world.
         *
         * @param enterPermission the permission node, or {@code null} for unrestricted access
         * @return this builder
         */
        public @NotNull Builder enterPermission(@Nullable String enterPermission) {
            this.enterPermission = enterPermission;
            return this;
        }

        /**
         * Sets the per-world plot size override (PLOT type only).
         */
        public @NotNull Builder plotSizeOverride(@Nullable Integer plotSizeOverride) {
            this.plotSizeOverride = plotSizeOverride;
            return this;
        }

        /**
         * Sets the per-world road width override (PLOT type only).
         */
        public @NotNull Builder roadWidthOverride(@Nullable Integer roadWidthOverride) {
            this.roadWidthOverride = roadWidthOverride;
            return this;
        }

        /**
         * Sets the per-world schematic file name (PLOT type only). Stored
         * without the {@code .nbt} extension.
         */
        public @NotNull Builder schematicName(@Nullable String schematicName) {
            this.schematicName = schematicName;
            return this;
        }

        /**
         * Sets whether this world is build-locked (all actions blocked except
         * operators / build mode).
         */
        public @NotNull Builder buildLocked(boolean buildLocked) {
            this.buildLocked = buildLocked;
            return this;
        }

        /**
         * Sets the build-lock interaction mode used while build lock is active.
         *
         * @param mode the interaction mode
         * @return this builder
         */
        public @NotNull Builder buildLockInteractionMode(@NotNull BuildLockInteractionMode mode) {
            this.buildLockInteractionMode = mode;
            return this;
        }

        /**
         * Builds and returns the configured {@link MVWorld} instance.
         *
         * @return a new {@link MVWorld}
         */
        public @NotNull MVWorld build() {
            return new MVWorld(this);
        }
    }
}
