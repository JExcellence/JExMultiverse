package de.jexcellence.multiverse.folia;

/**
 * Thrown when NMS-based world loading or registration fails.
 *
 * @author JExcellence
 * @since 3.0.0
 */
public class NmsWorldLoadException extends RuntimeException {

    public NmsWorldLoadException(String message) {
        super(message);
    }

    public NmsWorldLoadException(String message, Throwable cause) {
        super(message, cause);
    }
}
