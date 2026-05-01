package de.jexcellence.multiverse.database.entity;

/**
 * Membership status of a player on a plot.
 *
 * @author JExcellence
 * @since 3.2.0
 */
public enum MemberRole {

    /** Trusted: can build, break, interact, and use containers on the plot. */
    TRUSTED,

    /** Denied: blocked from entering the plot's interior. */
    DENIED
}
