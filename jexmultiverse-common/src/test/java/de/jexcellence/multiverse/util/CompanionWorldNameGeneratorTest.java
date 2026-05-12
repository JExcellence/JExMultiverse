package de.jexcellence.multiverse.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link CompanionWorldNameGenerator}.
 * <p>
 * Tests validate the companion world naming logic with various parent and requested name
 * combinations, including edge cases and validation scenarios.
 */
@DisplayName("CompanionWorldNameGenerator Tests")
class CompanionWorldNameGeneratorTest {

    @Test
    @DisplayName("Should generate companion name with standard world names")
    void shouldGenerateCompanionNameWithStandardNames() {
        String result = CompanionWorldNameGenerator.generateCompanionName("world", "oneblock_overworld");
        assertEquals("world_oneblock_overworld", result);
    }

    @ParameterizedTest
    @DisplayName("Should generate companion name with various parent and requested name combinations")
    @CsvSource({
            "world, custom, world_custom",
            "world, oneblock_overworld, world_oneblock_overworld",
            "world, nether, world_nether",
            "world, the_end, world_the_end",
            "myworld, test, myworld_test",
            "lobby, arena_1, lobby_arena_1",
            "hub, minigame, hub_minigame",
            "world_nether, custom, world_nether_custom",
            "a, b, a_b",
            "parent123, child456, parent123_child456",
            "world-1, world-2, world-1_world-2",
            "world.backup, new, world.backup_new"
    })
    void shouldGenerateCompanionNameWithVariousCombinations(String parent, String requested, String expected) {
        String result = CompanionWorldNameGenerator.generateCompanionName(parent, requested);
        assertEquals(expected, result);
    }

    @Test
    @DisplayName("Should handle parent name with underscores")
    void shouldHandleParentNameWithUnderscores() {
        String result = CompanionWorldNameGenerator.generateCompanionName("world_nether", "custom");
        assertEquals("world_nether_custom", result);
    }

    @Test
    @DisplayName("Should handle requested name with underscores")
    void shouldHandleRequestedNameWithUnderscores() {
        String result = CompanionWorldNameGenerator.generateCompanionName("world", "my_custom_world");
        assertEquals("world_my_custom_world", result);
    }

    @Test
    @DisplayName("Should handle both names with underscores")
    void shouldHandleBothNamesWithUnderscores() {
        String result = CompanionWorldNameGenerator.generateCompanionName("world_nether", "my_custom_world");
        assertEquals("world_nether_my_custom_world", result);
    }

    @Test
    @DisplayName("Should handle single character names")
    void shouldHandleSingleCharacterNames() {
        String result = CompanionWorldNameGenerator.generateCompanionName("a", "b");
        assertEquals("a_b", result);
    }

    @Test
    @DisplayName("Should handle names with special characters")
    void shouldHandleNamesWithSpecialCharacters() {
        String result = CompanionWorldNameGenerator.generateCompanionName("world-1", "test.world");
        assertEquals("world-1_test.world", result);
    }

    @Test
    @DisplayName("Should handle names with numbers")
    void shouldHandleNamesWithNumbers() {
        String result = CompanionWorldNameGenerator.generateCompanionName("world123", "arena456");
        assertEquals("world123_arena456", result);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @DisplayName("Should throw IllegalArgumentException when parent name is null or empty")
    void shouldThrowExceptionWhenParentNameIsNullOrEmpty(String invalidParent) {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> CompanionWorldNameGenerator.generateCompanionName(invalidParent, "valid")
        );
        assertEquals("Parent name cannot be null or empty", exception.getMessage());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @DisplayName("Should throw IllegalArgumentException when requested name is null or empty")
    void shouldThrowExceptionWhenRequestedNameIsNullOrEmpty(String invalidRequested) {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> CompanionWorldNameGenerator.generateCompanionName("valid", invalidRequested)
        );
        assertEquals("Requested name cannot be null or empty", exception.getMessage());
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException when both names are null")
    void shouldThrowExceptionWhenBothNamesAreNull() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> CompanionWorldNameGenerator.generateCompanionName(null, null)
        );
        // Should fail on parent name first
        assertEquals("Parent name cannot be null or empty", exception.getMessage());
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException when both names are empty")
    void shouldThrowExceptionWhenBothNamesAreEmpty() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> CompanionWorldNameGenerator.generateCompanionName("", "")
        );
        // Should fail on parent name first
        assertEquals("Parent name cannot be null or empty", exception.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {" ", "  ", "\t", "\n", " \t\n"})
    @DisplayName("Should accept whitespace-only parent names (not validated as empty)")
    void shouldAcceptWhitespaceOnlyParentNames(String whitespaceParent) {
        // The current implementation only checks for null or isEmpty(), not blank
        // Whitespace-only strings are technically valid according to the current validation
        String result = CompanionWorldNameGenerator.generateCompanionName(whitespaceParent, "world");
        assertEquals(whitespaceParent + "_world", result);
    }

    @ParameterizedTest
    @ValueSource(strings = {" ", "  ", "\t", "\n", " \t\n"})
    @DisplayName("Should accept whitespace-only requested names (not validated as empty)")
    void shouldAcceptWhitespaceOnlyRequestedNames(String whitespaceRequested) {
        // The current implementation only checks for null or isEmpty(), not blank
        // Whitespace-only strings are technically valid according to the current validation
        String result = CompanionWorldNameGenerator.generateCompanionName("world", whitespaceRequested);
        assertEquals("world_" + whitespaceRequested, result);
    }

    @Test
    @DisplayName("Should not be instantiable")
    void shouldNotBeInstantiable() {
        assertThrows(UnsupportedOperationException.class, () -> {
            var constructor = CompanionWorldNameGenerator.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            constructor.newInstance();
        });
    }

    @Test
    @DisplayName("Should generate consistent results for same inputs")
    void shouldGenerateConsistentResults() {
        String result1 = CompanionWorldNameGenerator.generateCompanionName("world", "custom");
        String result2 = CompanionWorldNameGenerator.generateCompanionName("world", "custom");
        assertEquals(result1, result2);
    }

    @Test
    @DisplayName("Should generate different results for different inputs")
    void shouldGenerateDifferentResultsForDifferentInputs() {
        String result1 = CompanionWorldNameGenerator.generateCompanionName("world", "custom1");
        String result2 = CompanionWorldNameGenerator.generateCompanionName("world", "custom2");
        assertNotEquals(result1, result2);
    }
}
