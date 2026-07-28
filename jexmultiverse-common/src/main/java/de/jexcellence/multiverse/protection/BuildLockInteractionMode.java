package de.jexcellence.multiverse.protection;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * Per-world interaction profile used when a managed world is build-locked.
 */
public enum BuildLockInteractionMode {
    /** Legacy behaviour: build actions are denied, many genuine uses still pass. */
    OPEN,
    /** Denies signs, doors, buttons, plates, containers, spawn eggs, trample, and modifier items. */
    SAFE,
    /** Denies every block interaction in the locked world. */
    LOCKED;

    /**
     * Returns the next profile in GUI cycle order.
     *
     * @return the next interaction mode
     */
    public @NotNull BuildLockInteractionMode next() {
        BuildLockInteractionMode[] values = values();
        return values[(ordinal() + 1) % values.length];
    }

    /**
     * Parses a persisted interaction mode, defaulting to the safe build-lock profile.
     *
     * @param raw the persisted value
     * @return the parsed mode, or {@link #SAFE}
     */
    public static @NotNull BuildLockInteractionMode safeValueOf(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return SAFE;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return OPEN;
        }
    }
}
