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

    int maxWorlds();
    List<MVWorldType> availableTypes();
    boolean isPremium();

    record FreeEdition() implements MultiverseEdition {
        private static final int MAX_WORLDS = 3;
        private static final List<MVWorldType> TYPES = List.of(MVWorldType.DEFAULT, MVWorldType.VOID);

        @Override public int maxWorlds() { return MAX_WORLDS; }
        @Override public List<MVWorldType> availableTypes() { return TYPES; }
        @Override public boolean isPremium() { return false; }
    }

    record PremiumEdition() implements MultiverseEdition {
        private static final List<MVWorldType> TYPES = List.of(MVWorldType.values());

        @Override public int maxWorlds() { return -1; }
        @Override public List<MVWorldType> availableTypes() { return TYPES; }
        @Override public boolean isPremium() { return true; }
    }
}
