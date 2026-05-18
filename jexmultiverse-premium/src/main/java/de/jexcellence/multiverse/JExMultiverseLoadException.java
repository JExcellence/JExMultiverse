package de.jexcellence.multiverse;

/**
 * Thrown when the JExMultiverse plugin fails to load.
 *
 * @author JExcellence
 * @since 3.0.0
 */
public class JExMultiverseLoadException extends RuntimeException {

    public JExMultiverseLoadException(String message, Throwable cause) {
        super(message, cause);
    }
}
