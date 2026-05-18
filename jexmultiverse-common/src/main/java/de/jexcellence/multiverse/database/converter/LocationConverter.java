package de.jexcellence.multiverse.database.converter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * JPA {@link AttributeConverter} that serializes and deserializes a Bukkit {@link Location}
 * to and from a JSON string for database storage.
 *
 * @author JExcellence
 * @since 3.0.0
 */
@Converter
public class LocationConverter implements AttributeConverter<Location, String> {
    private static final Logger LOGGER = Logger.getLogger(LocationConverter.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String FIELD_WORLD_UUID = "worldUuid";
    private static final String FIELD_WORLD_NAME = "worldName";

    @Override
    public String convertToDatabaseColumn(@Nullable final Location location) {
        if (location == null) return null;
        final World world = location.getWorld();
        if (world == null) {
            LOGGER.warning("Cannot serialize location without world reference");
            return null;
        }
        try {
            final ObjectNode node = MAPPER.createObjectNode();
            node.put(FIELD_WORLD_UUID, world.getUID().toString());
            node.put(FIELD_WORLD_NAME, world.getName());
            node.put("x", location.getX());
            node.put("y", location.getY());
            node.put("z", location.getZ());
            node.put("yaw", location.getYaw());
            node.put("pitch", location.getPitch());
            return MAPPER.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            LOGGER.log(Level.SEVERE, "Failed to serialize location to JSON", e);
            return null;
        }
    }

    @Override
    public Location convertToEntityAttribute(@Nullable final String json) {
        if (json == null || json.isBlank()) return null;
        try {
            final JsonNode node = MAPPER.readTree(json);
            final String worldUuidStr = node.has(FIELD_WORLD_UUID) ? node.get(FIELD_WORLD_UUID).asText() : null;
            final String worldName = node.has(FIELD_WORLD_NAME) ? node.get(FIELD_WORLD_NAME).asText() : null;
            final World world = resolveWorld(worldUuidStr, worldName);
            if (world == null) {
                LOGGER.log(Level.WARNING, () ->
                        "World not found for location: UUID=" + worldUuidStr + ", Name=" + worldName);
                return null;
            }
            return new Location(world, node.get("x").asDouble(), node.get("y").asDouble(),
                    node.get("z").asDouble(), (float) node.get("yaw").asDouble(),
                    (float) node.get("pitch").asDouble());
        } catch (JsonProcessingException e) {
            LOGGER.log(Level.SEVERE, "Failed to deserialize location from JSON: " + json, e);
            return null;
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Unexpected error deserializing location: " + json, e);
            return null;
        }
    }

    private static @Nullable World resolveWorld(@Nullable String worldUuidStr, @Nullable String worldName) {
        World world = null;
        if (worldUuidStr != null && !worldUuidStr.isBlank()) {
            try {
                world = Bukkit.getWorld(UUID.fromString(worldUuidStr));
            } catch (IllegalArgumentException ignored) {
                // UUID string was malformed — fall through to name-based lookup
            }
        }
        if (world == null && worldName != null && !worldName.isBlank()) {
            world = Bukkit.getWorld(worldName);
        }
        return world;
    }
}
