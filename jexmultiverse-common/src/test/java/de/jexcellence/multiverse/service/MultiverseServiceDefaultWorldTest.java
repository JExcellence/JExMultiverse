package de.jexcellence.multiverse.service;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.WorldMock;
import de.jexcellence.jexplatform.logging.JExLogger;
import de.jexcellence.jexplatform.scheduler.PlatformScheduler;
import de.jexcellence.multiverse.api.MVWorldType;
import de.jexcellence.multiverse.database.repository.MVWorldRepository;
import de.jexcellence.multiverse.factory.WorldFactory;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link MultiverseService#getDefaultWorld()} method.
 * <p>
 * Tests validate the parent world resolution logic with various scenarios including
 * normal cases, edge cases with empty world lists, and non-standard default world names.
 * <p>
 * <b>Requirements:</b> 2.2, 2.5
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MultiverseService - Default World Resolution Tests")
class MultiverseServiceDefaultWorldTest {

    private ServerMock server;
    private MultiverseService service;

    @Mock
    private MultiverseEdition edition;

    @Mock
    private MVWorldRepository repository;

    @Mock
    private WorldFactory worldFactory;

    @Mock
    private JExLogger logger;

    @Mock
    private JavaPlugin plugin;

    @Mock
    private PlatformScheduler scheduler;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        
        // Create service instance
        service = new MultiverseService(
                edition,
                repository,
                worldFactory,
                logger,
                plugin,
                scheduler
        );
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("Should return first world when default world is present")
    void shouldReturnFirstWorldWhenDefaultWorldIsPresent() throws Exception {
        // Arrange
        WorldMock defaultWorld = server.addSimpleWorld("world");
        
        // Act
        World result = invokeGetDefaultWorld();
        
        // Assert
        assertNotNull(result, "Default world should not be null");
        assertEquals("world", result.getName(), "Default world name should be 'world'");
        assertEquals(defaultWorld, result, "Should return the first world in Bukkit's world list");
        
        // Verify no error was logged
        verify(logger, never()).error(anyString());
        verify(logger, never()).error(anyString(), any());
    }

    @Test
    @DisplayName("Should return first world with non-standard name")
    void shouldReturnFirstWorldWithNonStandardName() throws Exception {
        // Arrange - Create world with non-standard name
        WorldMock customWorld = server.addSimpleWorld("custom_lobby");
        
        // Act
        World result = invokeGetDefaultWorld();
        
        // Assert
        assertNotNull(result, "Default world should not be null even with non-standard name");
        assertEquals("custom_lobby", result.getName(), "Should return world with non-standard name");
        assertEquals(customWorld, result, "Should return the first world regardless of name");
        
        // Verify no error was logged
        verify(logger, never()).error(anyString());
        verify(logger, never()).error(anyString(), any());
    }

    @Test
    @DisplayName("Should return first world when multiple worlds exist")
    void shouldReturnFirstWorldWhenMultipleWorldsExist() throws Exception {
        // Arrange - Create multiple worlds
        WorldMock world1 = server.addSimpleWorld("world");
        WorldMock world2 = server.addSimpleWorld("world_nether");
        WorldMock world3 = server.addSimpleWorld("world_the_end");
        
        // Act
        World result = invokeGetDefaultWorld();
        
        // Assert
        assertNotNull(result, "Default world should not be null");
        assertEquals("world", result.getName(), "Should return the first world");
        assertEquals(world1, result, "Should return the first world, not subsequent worlds");
        
        // Verify the world list has all worlds
        assertEquals(3, Bukkit.getWorlds().size(), "Should have 3 worlds loaded");
        
        // Verify no error was logged
        verify(logger, never()).error(anyString());
        verify(logger, never()).error(anyString(), any());
    }

    @Test
    @DisplayName("Should return null and log error when world list is empty")
    void shouldReturnNullAndLogErrorWhenWorldListIsEmpty() throws Exception {
        // Arrange - Ensure no worlds are loaded (MockBukkit starts with no worlds by default)
        // Clear any worlds that might have been added
        while (!Bukkit.getWorlds().isEmpty()) {
            server.unloadWorld(Bukkit.getWorlds().get(0));
        }
        
        // Act
        World result = invokeGetDefaultWorld();
        
        // Assert
        assertNull(result, "Should return null when no worlds are loaded");
        
        // Verify error was logged
        verify(logger, times(1)).error("Cannot resolve default world: server has no loaded worlds");
    }

    @Test
    @DisplayName("Should handle world with special characters in name")
    void shouldHandleWorldWithSpecialCharactersInName() throws Exception {
        // Arrange - Create world with special characters
        WorldMock specialWorld = server.addSimpleWorld("world-1.backup");
        
        // Act
        World result = invokeGetDefaultWorld();
        
        // Assert
        assertNotNull(result, "Should handle world names with special characters");
        assertEquals("world-1.backup", result.getName(), "Should preserve special characters in name");
        assertEquals(specialWorld, result, "Should return the world with special characters");
        
        // Verify no error was logged
        verify(logger, never()).error(anyString());
        verify(logger, never()).error(anyString(), any());
    }

    @Test
    @DisplayName("Should handle world with underscores in name")
    void shouldHandleWorldWithUnderscoresInName() throws Exception {
        // Arrange - Create world with underscores (common pattern)
        WorldMock underscoreWorld = server.addSimpleWorld("my_custom_world");
        
        // Act
        World result = invokeGetDefaultWorld();
        
        // Assert
        assertNotNull(result, "Should handle world names with underscores");
        assertEquals("my_custom_world", result.getName(), "Should preserve underscores in name");
        assertEquals(underscoreWorld, result, "Should return the world with underscores");
        
        // Verify no error was logged
        verify(logger, never()).error(anyString());
        verify(logger, never()).error(anyString(), any());
    }

    @Test
    @DisplayName("Should handle world with numbers in name")
    void shouldHandleWorldWithNumbersInName() throws Exception {
        // Arrange - Create world with numbers
        WorldMock numberedWorld = server.addSimpleWorld("world123");
        
        // Act
        World result = invokeGetDefaultWorld();
        
        // Assert
        assertNotNull(result, "Should handle world names with numbers");
        assertEquals("world123", result.getName(), "Should preserve numbers in name");
        assertEquals(numberedWorld, result, "Should return the world with numbers");
        
        // Verify no error was logged
        verify(logger, never()).error(anyString());
        verify(logger, never()).error(anyString(), any());
    }

    @Test
    @DisplayName("Should return consistent results for multiple calls")
    void shouldReturnConsistentResultsForMultipleCalls() throws Exception {
        // Arrange
        WorldMock defaultWorld = server.addSimpleWorld("world");
        
        // Act
        World result1 = invokeGetDefaultWorld();
        World result2 = invokeGetDefaultWorld();
        World result3 = invokeGetDefaultWorld();
        
        // Assert
        assertNotNull(result1, "First call should return non-null");
        assertNotNull(result2, "Second call should return non-null");
        assertNotNull(result3, "Third call should return non-null");
        assertEquals(result1, result2, "Multiple calls should return same world");
        assertEquals(result2, result3, "Multiple calls should return same world");
        assertEquals(defaultWorld, result1, "Should consistently return the default world");
        
        // Verify no error was logged
        verify(logger, never()).error(anyString());
        verify(logger, never()).error(anyString(), any());
    }

    @Test
    @DisplayName("Should return first world even after worlds are added")
    void shouldReturnFirstWorldEvenAfterWorldsAreAdded() throws Exception {
        // Arrange - Create initial world
        WorldMock firstWorld = server.addSimpleWorld("world");
        
        // Act - Get default world
        World result1 = invokeGetDefaultWorld();
        
        // Add more worlds
        server.addSimpleWorld("world2");
        server.addSimpleWorld("world3");
        
        // Act - Get default world again
        World result2 = invokeGetDefaultWorld();
        
        // Assert
        assertNotNull(result1, "First call should return non-null");
        assertNotNull(result2, "Second call should return non-null");
        assertEquals(firstWorld, result1, "First call should return first world");
        assertEquals(firstWorld, result2, "Second call should still return first world");
        assertEquals("world", result2.getName(), "Should always return the first world");
        
        // Verify no error was logged
        verify(logger, never()).error(anyString());
        verify(logger, never()).error(anyString(), any());
    }

    /**
     * Helper method to invoke the private getDefaultWorld() method using reflection.
     *
     * @return the result of getDefaultWorld()
     * @throws Exception if reflection fails
     */
    private World invokeGetDefaultWorld() throws Exception {
        Method method = MultiverseService.class.getDeclaredMethod("getDefaultWorld");
        method.setAccessible(true);
        return (World) method.invoke(service);
    }
}
