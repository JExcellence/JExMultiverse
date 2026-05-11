package de.jexcellence.multiverse.nbt;

import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/**
 * Minimal NBT writer covering the subset of tag types Minecraft's
 * {@code level.dat} uses. Built from the wire-format spec at
 * <a href="https://minecraft.wiki/w/NBT_format">minecraft.wiki/NBT</a>.
 *
 * <p>Why we ship our own: writing a brand-new world on Folia requires
 * dropping a valid {@code level.dat} on disk so Folia's startup scan
 * picks the world up. Folia's runtime {@code Bukkit.createWorld()} is
 * patched off (UOE) and no public Paper API plays the same role. The
 * existing NBT libraries (Adventure, Mojang, Querz) are either not on
 * the plugin classpath or too heavy for the three tag types we need.
 *
 * <p>Supported tag types:
 * <ul>
 *   <li>TAG_End (0)</li>
 *   <li>TAG_Byte (1)</li>
 *   <li>TAG_Int (3)</li>
 *   <li>TAG_Long (4)</li>
 *   <li>TAG_String (8)</li>
 *   <li>TAG_List (9)</li>
 *   <li>TAG_Compound (10)</li>
 * </ul>
 *
 * <p>All values are big-endian as the spec requires. The root compound
 * is written with the empty name {@code ""} and the whole stream is
 * gzip-compressed — that's the {@code .dat} convention Mojang adopted
 * in Alpha 1.0.6.
 *
 * @author JExcellence
 * @since 3.4.0
 */
public final class NbtWriter {

    public static final byte TAG_END = 0;
    public static final byte TAG_BYTE = 1;
    public static final byte TAG_INT = 3;
    public static final byte TAG_LONG = 4;
    public static final byte TAG_STRING = 8;
    public static final byte TAG_LIST = 9;
    public static final byte TAG_COMPOUND = 10;

    private NbtWriter() {
    }

    /**
     * Writes a compound as the root of a gzipped NBT stream — the
     * {@code level.dat} convention.
     *
     * @param out  the destination stream. Caller is responsible for closing.
     * @param root the root compound's children, keyed by tag name
     */
    public static void writeLevelDat(@NotNull OutputStream out,
                                      @NotNull Map<String, Object> root) throws IOException {
        try (var gz = new GZIPOutputStream(out);
             var dos = new DataOutputStream(gz)) {
            // Root: TAG_Compound with empty name.
            dos.writeByte(TAG_COMPOUND);
            dos.writeUTF("");
            writeCompoundPayload(dos, root);
        }
    }

    /**
     * Writes a compound's payload — every child tag in insertion order,
     * terminated by TAG_End. Values are dispatched by their Java type
     * to the matching NBT tag.
     */
    private static void writeCompoundPayload(@NotNull DataOutputStream dos,
                                              @NotNull Map<String, Object> entries) throws IOException {
        for (final var entry : entries.entrySet()) {
            writeNamedTag(dos, entry.getKey(), entry.getValue());
        }
        dos.writeByte(TAG_END);
    }

    @SuppressWarnings("unchecked")
    private static void writeNamedTag(@NotNull DataOutputStream dos,
                                       @NotNull String name,
                                       @NotNull Object value) throws IOException {
        if (value instanceof Byte b) {
            dos.writeByte(TAG_BYTE);
            dos.writeUTF(name);
            dos.writeByte(b);
        } else if (value instanceof Integer i) {
            dos.writeByte(TAG_INT);
            dos.writeUTF(name);
            dos.writeInt(i);
        } else if (value instanceof Long l) {
            dos.writeByte(TAG_LONG);
            dos.writeUTF(name);
            dos.writeLong(l);
        } else if (value instanceof String s) {
            dos.writeByte(TAG_STRING);
            dos.writeUTF(name);
            writeString(dos, s);
        } else if (value instanceof Map<?, ?> m) {
            dos.writeByte(TAG_COMPOUND);
            dos.writeUTF(name);
            writeCompoundPayload(dos, (Map<String, Object>) m);
        } else if (value instanceof java.util.List<?> list) {
            dos.writeByte(TAG_LIST);
            dos.writeUTF(name);
            writeListPayload(dos, list);
        } else {
            throw new IOException("Unsupported NBT value type for '" + name + "': "
                    + value.getClass().getName());
        }
    }

    private static void writeListPayload(@NotNull DataOutputStream dos,
                                          @NotNull java.util.List<?> list) throws IOException {
        if (list.isEmpty()) {
            // Spec: empty list has type TAG_End and length 0.
            dos.writeByte(TAG_END);
            dos.writeInt(0);
            return;
        }
        // All items must be the same type. Pick from the first.
        final Object first = list.get(0);
        if (first instanceof Map) {
            dos.writeByte(TAG_COMPOUND);
            dos.writeInt(list.size());
            for (final Object item : list) {
                @SuppressWarnings("unchecked")
                final Map<String, Object> m = (Map<String, Object>) item;
                writeCompoundPayload(dos, m);
            }
        } else if (first instanceof String) {
            dos.writeByte(TAG_STRING);
            dos.writeInt(list.size());
            for (final Object item : list) writeString(dos, (String) item);
        } else {
            throw new IOException("Unsupported NBT list element type: "
                    + first.getClass().getName());
        }
    }

    /**
     * Writes an NBT string — {@code DataOutputStream#writeUTF} uses
     * modified UTF-8 which matches NBT's string encoding.
     */
    private static void writeString(@NotNull DataOutputStream dos, @NotNull String s) throws IOException {
        final byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 0xFFFF) {
            throw new IOException("NBT string exceeds 65535 bytes: " + s.length() + " chars");
        }
        dos.writeShort(bytes.length);
        dos.write(bytes);
    }

    /**
     * Convenience: builds an ordered map for compound construction.
     * Insertion order is preserved when written.
     */
    public static @NotNull Map<String, Object> compound() {
        return new LinkedHashMap<>();
    }
}
