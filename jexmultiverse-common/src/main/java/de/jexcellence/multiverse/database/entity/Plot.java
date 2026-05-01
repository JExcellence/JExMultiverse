package de.jexcellence.multiverse.database.entity;

import de.jexcellence.jehibernate.entity.base.LongIdEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Persistent claim of a single plot grid cell in a JExMultiverse PLOT world.
 *
 * <p>Uniqueness is enforced over {@code (world_name, grid_x, grid_z)} so a
 * given grid coordinate can be claimed by at most one player at a time.
 *
 * @author JExcellence
 * @since 3.2.0
 */
@Entity
@Table(
        name = "mv_plot",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_mv_plot_coords", columnNames = {"world_name", "grid_x", "grid_z"})
        },
        indexes = {
                @Index(name = "idx_mv_plot_owner",  columnList = "owner_uuid"),
                @Index(name = "idx_mv_plot_merged", columnList = "merged_group_id")
        }
)
public class Plot extends LongIdEntity {

    @Column(name = "world_name", nullable = false, length = 64)
    private String worldName;

    @Column(name = "grid_x", nullable = false)
    private int gridX;

    @Column(name = "grid_z", nullable = false)
    private int gridZ;

    @Column(name = "owner_uuid", nullable = false, length = 36)
    private String ownerUuid;

    @Column(name = "owner_name", nullable = false, length = 16)
    private String ownerName;

    @Column(name = "claimed_at", nullable = false)
    private Instant claimedAt;

    /**
     * Reserved for Phase 2C plot merging. Plots sharing the same merge group
     * UUID act as one logical plot for ownership/protection purposes.
     */
    @Column(name = "merged_group_id", length = 36)
    private String mergedGroupId;

    public Plot() {
        // JPA
    }

    private Plot(Builder b) {
        this.worldName = b.worldName;
        this.gridX = b.gridX;
        this.gridZ = b.gridZ;
        this.ownerUuid = b.ownerUuid;
        this.ownerName = b.ownerName;
        this.claimedAt = b.claimedAt;
        this.mergedGroupId = b.mergedGroupId;
    }

    public @NotNull String getWorldName() { return worldName; }
    public void setWorldName(@NotNull String worldName) { this.worldName = worldName; }

    public int getGridX() { return gridX; }
    public void setGridX(int gridX) { this.gridX = gridX; }

    public int getGridZ() { return gridZ; }
    public void setGridZ(int gridZ) { this.gridZ = gridZ; }

    public @NotNull UUID getOwnerUuid() { return UUID.fromString(ownerUuid); }
    public @NotNull String getOwnerUuidString() { return ownerUuid; }
    public void setOwnerUuid(@NotNull UUID owner) { this.ownerUuid = owner.toString(); }

    public @NotNull String getOwnerName() { return ownerName; }
    public void setOwnerName(@NotNull String ownerName) { this.ownerName = ownerName; }

    public @NotNull Instant getClaimedAt() { return claimedAt; }
    public void setClaimedAt(@NotNull Instant claimedAt) { this.claimedAt = claimedAt; }

    public @Nullable UUID getMergedGroupId() {
        return mergedGroupId == null ? null : UUID.fromString(mergedGroupId);
    }
    public @Nullable String getMergedGroupIdString() { return mergedGroupId; }
    public void setMergedGroupId(@Nullable UUID id) {
        this.mergedGroupId = id == null ? null : id.toString();
    }

    /** Returns whether the given UUID is this plot's owner. */
    public boolean isOwner(@NotNull UUID uuid) {
        return ownerUuid.equalsIgnoreCase(uuid.toString());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Plot p)) return false;
        return gridX == p.gridX && gridZ == p.gridZ && Objects.equals(worldName, p.worldName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(worldName, gridX, gridZ);
    }

    public static @NotNull Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String worldName;
        private int gridX;
        private int gridZ;
        private String ownerUuid;
        private String ownerName;
        private Instant claimedAt = Instant.now();
        private String mergedGroupId;

        private Builder() {}

        public @NotNull Builder worldName(@NotNull String worldName) { this.worldName = worldName; return this; }
        public @NotNull Builder gridX(int gridX) { this.gridX = gridX; return this; }
        public @NotNull Builder gridZ(int gridZ) { this.gridZ = gridZ; return this; }
        public @NotNull Builder ownerUuid(@NotNull UUID owner) { this.ownerUuid = owner.toString(); return this; }
        public @NotNull Builder ownerName(@NotNull String ownerName) { this.ownerName = ownerName; return this; }
        public @NotNull Builder claimedAt(@NotNull Instant claimedAt) { this.claimedAt = claimedAt; return this; }
        public @NotNull Builder mergedGroupId(@Nullable UUID id) {
            this.mergedGroupId = id == null ? null : id.toString();
            return this;
        }

        public @NotNull Plot build() { return new Plot(this); }
    }
}
