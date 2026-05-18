package de.jexcellence.multiverse.service;

/**
 * Thrown when a schematic paste operation fails.
 *
 * @author JExcellence
 * @since 3.0.0
 */
public class SchematicPasteException extends RuntimeException {

    public SchematicPasteException(String message, Throwable cause) {
        super(message, cause);
    }
}
