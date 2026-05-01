package de.jexcellence.multiverse.database.entity;

import de.jexcellence.jehibernate.entity.base.LongIdEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.jetbrains.annotations.NotNull;

/**
 * Persistent override of a {@link de.jexcellence.multiverse.service.PlotFlag}
 * on a single {@link Plot}. The combination of {@code (plot_id, flag_key)} is
 * unique — at most one row per (plot, flag).
 *
 * <p>Rows are created lazily — flags whose value matches the default are not
 * persisted. Removing an override is equivalent to "reset to default".
 *
 * @author JExcellence
 * @since 3.2.0
 */
@Entity
@Table(
        name = "mv_plot_flag",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_mv_plot_flag", columnNames = {"plot_id", "flag_key"})
        },
        indexes = {
                @Index(name = "idx_mv_plot_flag_plot", columnList = "plot_id")
        }
)
public class StoredPlotFlag extends LongIdEntity {

    @Column(name = "plot_id", nullable = false)
    private long plotId;

    @Column(name = "flag_key", nullable = false, length = 32)
    private String flagKey;

    /** Stored as a string so future non-boolean flags don't require a schema change. */
    @Column(name = "flag_value", nullable = false, length = 64)
    private String flagValue;

    public StoredPlotFlag() {
        // JPA
    }

    public StoredPlotFlag(long plotId, @NotNull String flagKey, @NotNull String flagValue) {
        this.plotId = plotId;
        this.flagKey = flagKey;
        this.flagValue = flagValue;
    }

    public long getPlotId() { return plotId; }
    public void setPlotId(long plotId) { this.plotId = plotId; }

    public @NotNull String getFlagKey() { return flagKey; }
    public void setFlagKey(@NotNull String flagKey) { this.flagKey = flagKey; }

    public @NotNull String getFlagValue() { return flagValue; }
    public void setFlagValue(@NotNull String flagValue) { this.flagValue = flagValue; }
}
