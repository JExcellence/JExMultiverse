package de.jexcellence.multiverse.command;

import de.jexcellence.jexplatform.gui.chat.ChatPanel;
import de.jexcellence.jextranslate.MessageBuilder;
import de.jexcellence.multiverse.view.MultiverseCards;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Builds the multi-line chat panels of {@code /plot} and {@code /mv} (info, lists, help) on top of JExPlatform's
 * {@link ChatPanel}: header and context lines, {@code Label | value} rows with labels from
 * {@code mv_gui.common.label.*} and values in the shared tones. {@link ChatPanel#send} adds the blank lines
 * around the panel.
 *
 * @author JExcellence
 * @since 3.8.0
 */
final class ChatPanels {

    /** Root of the chat panel keys. */
    static final String ROOT = "mv_chat.";

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private ChatPanels() {
    }

    /** @return the player behind the sender, {@code null} for the console. */
    static @Nullable Player viewer(@NotNull CommandSender sender) {
        return sender instanceof Player player ? player : null;
    }

    /** A chat component for a prepared builder. */
    static @NotNull Component line(@NotNull CommandSender sender, @NotNull MessageBuilder builder) {
        return builder.component(viewer(sender));
    }

    /** A chat component for {@code key}. */
    static @NotNull Component line(@NotNull CommandSender sender, @NotNull String key) {
        return line(sender, MultiverseCards.msg(key));
    }

    /**
     * Adds a {@code Label | value} row.
     *
     * @param panel     the panel
     * @param sender    the reader
     * @param label     label id ({@code mv_gui.common.label.<label>})
     * @param valueMini the value as a MiniMessage fragment, e.g. from {@link MultiverseCards#tone}
     * @return the panel
     */
    static @NotNull ChatPanel row(@NotNull ChatPanel panel, @NotNull CommandSender sender, @NotNull String label,
                                  @NotNull String valueMini) {
        Player viewer = viewer(sender);
        return labelledRow(panel, sender, MultiverseCards.text(viewer, MultiverseCards.COMMON + "label." + label),
                valueMini);
    }

    /**
     * Adds a row with an already translated label.
     *
     * @param panel     the panel
     * @param sender    the reader
     * @param labelText the plain label
     * @param valueMini the value as a MiniMessage fragment
     * @return the panel
     */
    static @NotNull ChatPanel labelledRow(@NotNull ChatPanel panel, @NotNull CommandSender sender,
                                          @NotNull String labelText, @NotNull String valueMini) {
        return panel.row(line(sender, MultiverseCards.msg(ROOT + "label").with("text", MultiverseCards.escape(labelText))),
                MINI.deserialize(valueMini));
    }
}
