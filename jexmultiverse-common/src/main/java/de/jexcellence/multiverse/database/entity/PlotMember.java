package de.jexcellence.multiverse.database.entity;

import de.jexcellence.jehibernate.entity.base.LongIdEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.UUID;

/**
 * Membership entry on a {@link Plot}. The combination of {@code (plot_id,
 * member_uuid)} is unique — a player has at most one role per plot.
 *
 * @author JExcellence
 * @since 3.2.0
 */
@Entity
@Table(
        name = "mv_plot_member",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_mv_plot_member", columnNames = {"plot_id", "member_uuid"})
        },
        indexes = {
                @Index(name = "idx_mv_plot_member_plot",   columnList = "plot_id"),
                @Index(name = "idx_mv_plot_member_player", columnList = "member_uuid")
        }
)
public class PlotMember extends LongIdEntity {

    @Column(name = "plot_id", nullable = false)
    private long plotId;

    @Column(name = "member_uuid", nullable = false, length = 36)
    private String memberUuid;

    @Column(name = "member_name", nullable = false, length = 16)
    private String memberName;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 16)
    private MemberRole role;

    @Column(name = "added_at", nullable = false)
    private Instant addedAt;

    public PlotMember() {
        // JPA
    }

    public PlotMember(long plotId, @NotNull UUID memberUuid, @NotNull String memberName,
                      @NotNull MemberRole role) {
        this.plotId = plotId;
        this.memberUuid = memberUuid.toString();
        this.memberName = memberName;
        this.role = role;
        this.addedAt = Instant.now();
    }

    public long getPlotId() { return plotId; }
    public void setPlotId(long plotId) { this.plotId = plotId; }

    public @NotNull UUID getMemberUuid() { return UUID.fromString(memberUuid); }
    public @NotNull String getMemberUuidString() { return memberUuid; }
    public void setMemberUuid(@NotNull UUID memberUuid) { this.memberUuid = memberUuid.toString(); }

    public @NotNull String getMemberName() { return memberName; }
    public void setMemberName(@NotNull String memberName) { this.memberName = memberName; }

    public @NotNull MemberRole getRole() { return role; }
    public void setRole(@NotNull MemberRole role) { this.role = role; }

    public @NotNull Instant getAddedAt() { return addedAt; }
    public void setAddedAt(@NotNull Instant addedAt) { this.addedAt = addedAt; }
}
