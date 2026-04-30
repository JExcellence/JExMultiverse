package de.jexcellence.multiverse.service;

import de.jexcellence.multiverse.api.MVWorldType;

import java.util.List;

/**
 * Sealed edition hierarchy controlling world limits and available generation types.
 *
 * @author JExcellence
 * @since 3.0.0
 */
public sealed interface MultiverseEdition permits MultiverseEdition.FreeEdition, MultiverseEdition.PremiumEdition {

    /**
     * Returns the maximum number of worlds allowed for this edition, or {@code -1} for unlimited.
     *
     * @return the world limit
     */
    int maxWorlds();

    /**
     * Returns the list of world types available in this edition.
     *
     * @return available {@link MVWorldType} values
     */
    List<MVWorldType> availableTypes();

    /**
     * Returns whether this edition is the premium edition.
     *
     * @return {@code true} if premium, {@code false} otherwise
     */
    boolean isPremium();

    /**
     * The free edition, limited to {@value FreeEdition#MAX_WORLDS} worlds and basic generation types.
     */
    record FreeEdition() implements MultiverseEdition {
        private static final int MAX_WORLDS = 3;
        private static final List<MVWorldType> TYPES = List.of(MVWorldType.DEFAULT, MVWorldType.VOID);

        @Override public int maxWorlds() { return MAX_WORLDS; }
        @Override public List<MVWorldType> availableTypes() { return TYPES; }
        @Override public boolean isPremium() { return false; }
    }

    /**
     * The premium edition, with unlimited worlds and all generation types available.
     */
    record PremiumEdition() implements MultiverseEdition {
        private static final List<MVWorldType> TYPES = List.of(MVWorldType.values());

        @Override public int maxWorlds() { return -1; }
        @Override public List<MVWorldType> availableTypes() { return TYPES; }
        @Override public boolean isPremium() { return true; }
    }
}
