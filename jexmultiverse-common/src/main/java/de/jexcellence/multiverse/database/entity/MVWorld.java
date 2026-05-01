package de.jexcellence.multiverse.database.entity;

import de.jexcellence.jehibernate.entity.base.LongIdEntity;
import de.jexcellence.multiverse.api.MVWorldSnapshot;
import de.jexcellence.multiverse.api.MVWorldType;
import de.jexcellence.multiverse.database.converter.LocationConverter;
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

import java.util.Objects;

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
                enterPermission
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
         * Builds and returns the configured {@link MVWorld} instance.
         *
         * @return a new {@link MVWorld}
         */
        public @NotNull MVWorld build() {
            return new MVWorld(this);
        }
    }
}
