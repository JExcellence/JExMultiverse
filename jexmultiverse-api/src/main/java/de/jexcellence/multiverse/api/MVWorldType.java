package de.jexcellence.multiverse.api;

/**
 * Represents the generation type for a managed world.
 *
 * @author JExcellence
 * @since 3.0.0
 */
public enum MVWorldType {

    /** Vanilla terrain generation. */
    DEFAULT,

    /** Completely empty world with no terrain. */
    VOID,

    /** Grid-based plot world with roads and borders. */
    PLOT
}
