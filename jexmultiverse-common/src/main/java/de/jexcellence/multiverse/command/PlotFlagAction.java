package de.jexcellence.multiverse.command;

/**
 * Sub-actions for {@code /plot flag <action> ...}.
 *
 * @author JExcellence
 * @since 3.2.0
 */
public enum PlotFlagAction {
    /** Set a flag to a boolean value. */
    SET,
    /** Remove a flag override (resets it to default). */
    REMOVE,
    /** List the plot's flag overrides. */
    LIST
}
