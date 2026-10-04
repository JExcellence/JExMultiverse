package de.jexcellence.multiverse.view;

import de.jexcellence.jexplatform.view.BaseView;
import me.devnatan.inventoryframework.context.Context;
import me.devnatan.inventoryframework.context.RenderContext;
import me.devnatan.inventoryframework.context.SlotClickContext;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Base for every JExMultiverse chest view (6 rows): fills the background, places close at 45 and, for child
 * views, the back button at 0, then lets the view render its header, filter and body. Replaces the arrow back
 * button {@link BaseView} would put into the bottom-left corner.
 *
 * @author JExcellence
 * @since 3.8.0
 */
public abstract class MultiverseBaseView extends BaseView {

    /** Initial-data key of the zero-based page of a list view. */
    public static final String DATA_PAGE = "page";

    @Override
    protected int size() {
        return MultiverseLayout.ROWS;
    }

    @Override
    public void onFirstRender(@NotNull RenderContext render) {
        Player player = render.getPlayer();
        ItemStack filler = MultiverseCards.filler();
        for (int slot = 0; slot < MultiverseLayout.ROWS * 9; slot++) {
            render.slot(slot, filler);
        }
        render.slot(MultiverseLayout.SLOT_CLOSE, MultiverseCards.close(player)).onClick(SlotClickContext::closeForPlayer);
        String destination = backDestination();
        if (destination != null) {
            render.slot(MultiverseLayout.SLOT_BACK, MultiverseCards.back(player, destination)).onClick(this::onBack);
        }
        onRender(render, player);
    }

    /**
     * The back button's destination key ({@code mv_gui.common.back.<destination>}), or {@code null} for a root
     * view without a back button.
     *
     * @return the destination key or {@code null}
     */
    protected @Nullable String backDestination() {
        return null;
    }

    /**
     * Runs when the back button is clicked; the default closes the view.
     *
     * @param click the click
     */
    protected void onBack(@NotNull SlotClickContext click) {
        click.closeForPlayer();
    }

    /**
     * Places the previous / next page arrows at 48 / 50 when there is a page in that direction. Clicking one
     * re-opens {@code view} with the new page.
     *
     * @param render the render context
     * @param player the viewer
     * @param page   zero-based current page
     * @param pages  page count
     */
    protected void pagination(@NotNull RenderContext render, @NotNull Player player, int page, int pages) {
        if (page > 0) {
            render.slot(MultiverseLayout.SLOT_PAGE_PREV, MultiverseCards.page(player, false, page, pages))
                    .onClick(click -> reopen(click, page - 1));
        }
        if (page + 1 < pages) {
            render.slot(MultiverseLayout.SLOT_PAGE_NEXT, MultiverseCards.page(player, true, page + 2, pages))
                    .onClick(click -> reopen(click, page + 1));
        }
    }

    /**
     * Re-opens this view with the same data and a new page.
     *
     * @param click the click
     * @param page  the zero-based page
     */
    protected void reopen(@NotNull SlotClickContext click, int page) {
        Map<String, Object> data = copyData(click);
        data.put(DATA_PAGE, page);
        click.openForPlayer(getClass(), data);
    }

    /**
     * The zero-based page stored in the initial data, {@code 0} when none is set.
     *
     * @param context the context
     * @return the page
     */
    protected static int requestedPage(@NotNull Context context) {
        return context.getInitialData() instanceof Map<?, ?> map && map.get(DATA_PAGE) instanceof Integer page
                ? page : 0;
    }

    /**
     * A mutable copy of the context's initial data.
     *
     * @param context the context
     * @return the copy
     */
    public static @NotNull Map<String, Object> copyData(@NotNull Context context) {
        Map<String, Object> copy = new LinkedHashMap<>();
        if (context.getInitialData() instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() instanceof String key && entry.getValue() != null) {
                    copy.put(key, entry.getValue());
                }
            }
        }
        return copy;
    }
}
