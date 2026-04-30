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

@Converter
public class LocationConverter implements AttributeConverter<Location, String> {
    private static final Logger LOGGER = Logger.getLogger(LocationConverter.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(@Nullable final Location location) {
        if (location == null) return null;
        final World world = location.getWorld();
        if (world == null) {
            LOGGER.log(Level.WARNING, "Cannot serialize location without world reference");
            return null;
        }
        try {
            final ObjectNode node = MAPPER.createObjectNode();
            node.put("worldUuid", world.getUID().toString());
            node.put("worldName", world.getName());
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
            final String worldUuidStr = node.has("worldUuid") ? node.get("worldUuid").asText() : null;
            final String worldName = node.has("worldName") ? node.get("worldName").asText() : null;
            World world = null;
            if (worldUuidStr != null && !worldUuidStr.isBlank()) {
                try {
                    world = Bukkit.getWorld(UUID.fromString(worldUuidStr));
                } catch (IllegalArgumentException ignored) {}
            }
            if (world == null && worldName != null && !worldName.isBlank()) {
                world = Bukkit.getWorld(worldName);
            }
            if (world == null) {
                LOGGER.log(Level.WARNING, "World not found for location: UUID={0}, Name={1}", new Object[]{worldUuidStr, worldName});
                return null;
            }
            return new Location(world, node.get("x").asDouble(), node.get("y").asDouble(), node.get("z").asDouble(), (float) node.get("yaw").asDouble(), (float) node.get("pitch").asDouble());
        } catch (JsonProcessingException e) {
            LOGGER.log(Level.SEVERE, "Failed to deserialize location from JSON: " + json, e);
            return null;
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Unexpected error deserializing location: " + json, e);
            return null;
        }
    }
}
