package de.jexcellence.multiverse.util;

import org.jetbrains.annotations.NotNull;

/**
 * Utility class for generating companion world names following Folia's naming convention.
 * <p>
 * Companion worlds in Folia follow the pattern {@code <parent>_<requested>}, where the parent
 * is typically the server's default world (e.g., "world") and the requested name is the
 * custom world name provided by the plugin.
 * <p>
 * Example: For parent "world" and requested name "oneblock_overworld", the companion name
 * will be "world_oneblock_overworld".
 *
 * @since 3.0.0
 */
public final class CompanionWorldNameGenerator {

    private CompanionWorldNameGenerator() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }

    /**
     * Generates a companion world name following Folia's naming convention.
     * <p>
     * The generated name follows the pattern {@code <parentName>_<requestedName>}.
     *
     * @param parentName    the name of the parent world (typically the server's default world)
     * @param requestedName the requested custom world name
     * @return the generated companion world name in the format {@code parentName_requestedName}
     * @throws IllegalArgumentException if either parameter is null or empty
     */
    @NotNull
    public static String generateCompanionName(@NotNull String parentName, @NotNull String requestedName) {
        if (parentName == null || parentName.isEmpty()) {
            throw new IllegalArgumentException("Parent name cannot be null or empty");
        }
        if (requestedName == null || requestedName.isEmpty()) {
            throw new IllegalArgumentException("Requested name cannot be null or empty");
        }
        return parentName + "_" + requestedName;
    }
}
