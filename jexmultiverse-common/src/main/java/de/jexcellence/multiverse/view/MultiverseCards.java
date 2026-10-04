package de.jexcellence.multiverse.view;

import de.jexcellence.jexplatform.gui.component.CardLore;
import de.jexcellence.jexplatform.gui.component.FilterHopperButton;
import de.jexcellence.jexplatform.utility.item.HeadBuilder;
import de.jexcellence.jexplatform.utility.item.ItemBuilder;
import de.jexcellence.jextranslate.MessageBuilder;
import de.jexcellence.jextranslate.R18nManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The building blocks every JExMultiverse card is made of: wrapped description paragraphs, {@code Label | value}
 * rows, value tints, the shared filter button, navigation buttons and item builders that hide vanilla tooltip
 * noise. All text and colour come from the {@code mv_gui.common.*} translation keys, so the Java side only
 * decides which block goes where.
 *
 * @author JExcellence
 * @since 3.8.0
 */
public final class MultiverseCards {

    /** Root of every GUI key. */
    public static final String ROOT = "mv_gui.";
    /** Root of the shared GUI vocabulary. */
    public static final String COMMON = ROOT + "common.";

    private static final int WRAP_WIDTH = 34;
    private static final String PARAM_VALUE = "value";
    private static final String PARAM_NAME = "name";
    private static final String NAME_SUFFIX = ".name";
    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private MultiverseCards() {
    }

    /** @return a message builder for {@code key}. */
    public static @NotNull MessageBuilder msg(@NotNull String key) {
        return R18nManager.getInstance().msg(key);
    }

    /** A non-italic item component for {@code key}. */
    public static @NotNull Component ic(@Nullable Player viewer, @NotNull String key) {
        return ic(msg(key), viewer);
    }

    /** A non-italic item component for a prepared builder (placeholders already set). */
    public static @NotNull Component ic(@NotNull MessageBuilder builder, @Nullable Player viewer) {
        return builder.itemComponent(viewer).decoration(TextDecoration.ITALIC, false);
    }

    /** The plain text of {@code key}, for labels and paragraph wrapping. */
    public static @NotNull String text(@Nullable Player viewer, @NotNull String key) {
        return msg(key).text(viewer);
    }

    /** Escapes MiniMessage tags in player or world names before they become placeholder values. */
    public static @NotNull String escape(@NotNull String raw) {
        return MINI.escapeTags(raw);
    }

    /** Word-wraps a plain sentence into muted lore lines. */
    public static @NotNull List<Component> paragraph(@Nullable Player viewer, @NotNull String plainText) {
        List<Component> lines = new ArrayList<>();
        for (String line : CardLore.wrap(plainText, WRAP_WIDTH)) {
            lines.add(ic(msg(COMMON + "card.line").with("text", escape(line)), viewer));
        }
        return lines;
    }

    /** Word-wraps the text of a translation key into muted lore lines. */
    public static @NotNull List<Component> paragraphOf(@Nullable Player viewer, @NotNull String key) {
        return paragraph(viewer, text(viewer, key));
    }

    /** A section title line, e.g. {@code Plot}. */
    public static @NotNull Component section(@Nullable Player viewer, @NotNull String section) {
        return ic(msg(COMMON + "card.section").with(PARAM_NAME, text(viewer, COMMON + "section." + section)), viewer);
    }

    /** A {@code Label | value} row whose label is {@code mv_gui.common.label.<label>}. */
    public static @NotNull Component row(@Nullable Player viewer, @NotNull String label, @NotNull String valueMini) {
        return ic(msg(COMMON + "card.row").with("label", text(viewer, COMMON + "label." + label))
                .with(PARAM_VALUE, valueMini), viewer);
    }

    /** An action hint line ({@code ▸ Click to ...}) from {@code key}. */
    public static @NotNull Component action(@Nullable Player viewer, @NotNull String key) {
        return ic(msg(COMMON + "card.action").with("text", text(viewer, key)), viewer);
    }

    /** The reason line of a card the viewer may not use: {@code [X] Owner only}. */
    public static @NotNull Component ownerOnly(@Nullable Player viewer) {
        return ic(msg(COMMON + "card.locked").with("text", text(viewer, COMMON + "locked.owner-only")), viewer);
    }

    /** A highlighted plain value. */
    public static @NotNull String value(@Nullable Player viewer, @NotNull String raw) {
        return tone(viewer, "plain", raw);
    }

    /** A value in a tone: {@code plain}, {@code accent}, {@code ok}, {@code bad}, {@code warn} or {@code muted}. */
    public static @NotNull String tone(@Nullable Player viewer, @NotNull String tone, @NotNull String raw) {
        return msg(COMMON + "value." + tone).with(PARAM_VALUE, escape(raw)).miniMessage(viewer);
    }

    /** A translated word ({@code mv_gui.common.word.<word>}) in a tone. */
    public static @NotNull String word(@Nullable Player viewer, @NotNull String tone, @NotNull String word) {
        return tone(viewer, tone, text(viewer, COMMON + "word." + word));
    }

    /** {@code Enabled} in green or {@code Disabled} in red. */
    public static @NotNull String state(@Nullable Player viewer, boolean enabled) {
        return enabled ? word(viewer, "ok", "enabled") : word(viewer, "bad", "disabled");
    }

    /** A card with name and lore; vanilla attribute, enchant and extra tooltip lines are hidden. */
    public static @NotNull ItemStack card(@NotNull ItemStack base, @NotNull Component name,
                                          @NotNull List<Component> lore) {
        return ItemBuilder.from(base).name(name).lore(lore)
                .flags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_UNBREAKABLE)
                .build();
    }

    /** A card from a material. */
    public static @NotNull ItemStack card(@NotNull Material icon, @NotNull Component name,
                                          @NotNull List<Component> lore) {
        return card(new ItemStack(icon), name, lore);
    }

    /** A player head with the real skin when it is known, without a Mojang lookup. */
    public static @NotNull ItemStack head(@NotNull UUID owner) {
        return HeadBuilder.fromPlayerCached(owner).build();
    }

    /** Adds the enchantment glint without an enchantment line. */
    public static @NotNull ItemStack glint(@NotNull ItemStack stack) {
        stack.editMeta(meta -> meta.setEnchantmentGlintOverride(true));
        return stack;
    }

    /** A card with a wrapped description and nothing else, for empty or unavailable states. */
    public static @NotNull ItemStack notice(@Nullable Player viewer, @NotNull ItemStack base, @NotNull String keyBase) {
        return card(base, ic(viewer, keyBase + NAME_SUFFIX),
                CardLore.create().block(paragraphOf(viewer, keyBase + ".description")).build());
    }

    /** The background pane with no name and no tooltip. */
    public static @NotNull ItemStack filler() {
        ItemStack pane = ItemBuilder.of(Material.BLACK_STAINED_GLASS_PANE).name(Component.empty()).build();
        pane.editMeta(meta -> meta.setHideTooltip(true));
        return pane;
    }

    /** The back button (slot 0): a dark oak door with a one-line destination ({@code back.<destination>}). */
    public static @NotNull ItemStack back(@Nullable Player viewer, @NotNull String destination) {
        return card(Material.DARK_OAK_DOOR, ic(viewer, COMMON + "back.name"),
                CardLore.create().block(paragraphOf(viewer, COMMON + "back." + destination)).build());
    }

    /** The close button (slot 45). */
    public static @NotNull ItemStack close(@Nullable Player viewer) {
        return card(Material.BARRIER, ic(viewer, COMMON + "close.name"),
                CardLore.create().block(paragraphOf(viewer, COMMON + "close.description")).build());
    }

    /**
     * A page arrow.
     *
     * @param viewer     the viewer
     * @param next       {@code true} for the next page, {@code false} for the previous one
     * @param targetPage the one-based page the arrow leads to
     * @param pages      the page count
     * @return the arrow
     */
    public static @NotNull ItemStack page(@Nullable Player viewer, boolean next, int targetPage, int pages) {
        String base = COMMON + "page." + (next ? "next" : "previous");
        return card(Material.ARROW, ic(viewer, base + NAME_SUFFIX), List.of(ic(msg(COMMON + "page.lore")
                .with("page", targetPage).with("pages", pages), viewer)));
    }

    /**
     * The shared filter button: a hopper minecart named "Filter" with a "Show" block that marks the active
     * option with a filled dot and the others with an empty one, and the left/right-click hint.
     *
     * @param viewer       the viewer
     * @param optionLabels already translated option labels, in cycle order
     * @param active       index of the active option
     * @return the filter item
     */
    public static @NotNull ItemStack filter(@Nullable Player viewer, @NotNull List<String> optionLabels, int active) {
        List<Component> options = new ArrayList<>(optionLabels.size());
        for (int i = 0; i < optionLabels.size(); i++) {
            String key = i == active ? COMMON + "filter.option-active" : COMMON + "filter.option";
            options.add(ic(msg(key).with(PARAM_NAME, optionLabels.get(i)), viewer));
        }
        return card(FilterHopperButton.ICON, ic(viewer, COMMON + "filter.name"),
                CardLore.create()
                        .section(ic(viewer, COMMON + "filter.title"), options)
                        .block(List.of(ic(viewer, COMMON + "filter.action")))
                        .build());
    }
}
