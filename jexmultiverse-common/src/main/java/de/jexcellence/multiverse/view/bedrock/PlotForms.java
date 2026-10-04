package de.jexcellence.multiverse.view.bedrock;

import de.jexcellence.jexplatform.scheduler.PlatformScheduler;
import de.jexcellence.jextranslate.MessageBuilder;
import de.jexcellence.multiverse.database.entity.MemberRole;
import de.jexcellence.multiverse.database.entity.Plot;
import de.jexcellence.multiverse.service.PlotFlag;
import de.jexcellence.multiverse.view.MultiverseCards;
import de.jexcellence.multiverse.view.PlotActions;
import org.bukkit.entity.Player;
import org.geysermc.cumulus.form.CustomForm;
import org.geysermc.cumulus.form.Form;
import org.geysermc.cumulus.form.ModalForm;
import org.geysermc.cumulus.form.SimpleForm;
import org.geysermc.cumulus.response.CustomFormResponse;
import org.geysermc.floodgate.api.FloodgateApi;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Bedrock mirrors of the plot views, sent as Cumulus forms through Floodgate: the plot menu, the flags as
 * toggles and the members list. Form answers arrive on Floodgate's thread, so every follow-up is scheduled
 * onto the player's thread. Only touched for players Floodgate reports as Bedrock players.
 *
 * @author JExcellence
 * @since 3.8.0
 */
public final class PlotForms {

    private static final String KEY = MultiverseCards.ROOT + "bedrock.plot.";
    private static final String NEWLINE = "\n";
    private static final String GRID_X = "grid_x";
    private static final String GRID_Z = "grid_z";
    private static final String TITLE = "title";
    private static final String BACK = "back";

    private final PlotActions actions;

    /**
     * Creates the forms.
     *
     * @param actions the shared plot actions
     */
    public PlotForms(@NotNull PlotActions actions) {
        this.actions = actions;
    }

    /**
     * Opens the plot menu: the plot summary and one button per action.
     *
     * @param player the Bedrock player
     * @param plot   the plot
     */
    public void openMenu(@NotNull Player player, @NotNull Plot plot) {
        List<Runnable> buttons = new ArrayList<>();
        SimpleForm.Builder form = SimpleForm.builder()
                .title(gridText(player, KEY + "menu.title", plot))
                .content(summary(player, plot));
        form.button(text(player, KEY + "menu.members"));
        buttons.add(() -> openMembers(player, plot));
        form.button(text(player, KEY + "menu.flags"));
        buttons.add(() -> openFlags(player, plot));
        if (actions.homeOf(plot) != null) {
            form.button(text(player, KEY + "menu.home"));
            buttons.add(() -> actions.teleportHome(player, plot));
        }
        form.button(text(player, KEY + "menu.unclaim"));
        buttons.add(() -> confirmUnclaim(player, plot));
        form.validResultHandler(response -> onPlayer(player, buttons.get(response.clickedButtonId())));
        send(player, form.build());
    }

    /**
     * Opens the flags as one toggle each; submitting saves every changed flag.
     *
     * @param player the Bedrock player
     * @param plot   the plot
     */
    public void openFlags(@NotNull Player player, @NotNull Plot plot) {
        PlotFlag[] flags = PlotFlag.values();
        CustomForm.Builder form = CustomForm.builder()
                .title(gridText(player, KEY + "flags.title", plot))
                .label(text(player, KEY + "flags.intro"));
        for (PlotFlag flag : flags) {
            form.toggle(text(player, MultiverseCards.ROOT + "bedrock.flag." + flag.key()),
                    actions.plots().getFlag(plot, flag));
        }
        form.validResultHandler(response -> onPlayer(player, () -> applyFlags(player, plot, flags, response)));
        send(player, form.build());
    }

    private void applyFlags(@NotNull Player player, @NotNull Plot plot, @NotNull PlotFlag[] flags,
                            @NotNull CustomFormResponse response) {
        for (int i = 0; i < flags.length; i++) {
            boolean wanted = response.asToggle(i + 1);
            if (wanted != actions.plots().getFlag(plot, flags[i])) {
                actions.setFlag(player, plot, flags[i], wanted, PlotForms::noFollowUp);
            }
        }
    }

    /**
     * Opens the members list; a button removes that member's role after a confirmation.
     *
     * @param player the Bedrock player
     * @param plot   the plot
     */
    public void openMembers(@NotNull Player player, @NotNull Plot plot) {
        List<PlotActions.Member> members = actions.members(plot);
        SimpleForm.Builder form = SimpleForm.builder()
                .title(gridText(player, KEY + "members.title", plot))
                .content(text(player, KEY + (members.isEmpty() ? "members.empty" : "members.intro")));
        for (PlotActions.Member member : members) {
            String role = member.role() == MemberRole.TRUSTED ? "trusted" : "denied";
            form.button(msg(KEY + "members.entry").with("member_name", member.name())
                    .with("role", text(player, MultiverseCards.COMMON + "word." + role)).plain(player));
        }
        form.button(text(player, KEY + BACK));
        form.validResultHandler(response -> onPlayer(player, () -> {
            int index = response.clickedButtonId();
            if (index < members.size()) {
                confirmRemove(player, plot, members.get(index));
            } else {
                openMenu(player, plot);
            }
        }));
        send(player, form.build());
    }

    private void confirmRemove(@NotNull Player player, @NotNull Plot plot, @NotNull PlotActions.Member member) {
        String role = member.role() == MemberRole.TRUSTED ? "trusted" : "denied";
        ModalForm form = ModalForm.builder()
                .title(text(player, KEY + "remove." + TITLE))
                .content(msg(KEY + "remove." + role).with("member_name", member.name()).plain(player))
                .button1(text(player, KEY + "remove.confirm"))
                .button2(text(player, KEY + BACK))
                .validResultHandler(response -> onPlayer(player, () -> {
                    if (response.clickedFirst()) {
                        actions.removeMember(player, plot, member, () -> openMembers(player, plot));
                    } else {
                        openMembers(player, plot);
                    }
                }))
                .build();
        send(player, form);
    }

    private void confirmUnclaim(@NotNull Player player, @NotNull Plot plot) {
        ModalForm form = ModalForm.builder()
                .title(text(player, KEY + "unclaim." + TITLE))
                .content(gridText(player, KEY + "unclaim.content", plot))
                .button1(text(player, KEY + "unclaim.confirm"))
                .button2(text(player, KEY + BACK))
                .validResultHandler(response -> onPlayer(player, () -> {
                    if (response.clickedFirst()) {
                        actions.unclaim(player, plot);
                    } else {
                        openMenu(player, plot);
                    }
                }))
                .build();
        send(player, form);
    }

    private @NotNull String summary(@NotNull Player player, @NotNull Plot plot) {
        var members = actions.plots().getMembers(plot);
        long trusted = members.values().stream().filter(role -> role == MemberRole.TRUSTED).count();
        long denied = members.values().stream().filter(role -> role == MemberRole.DENIED).count();
        String merge = plot.getMergedGroupIdString() != null ? "merged" : "standalone";
        List<String> lines = List.of(
                row(player, "owner", plot.getOwnerName()),
                row(player, "world", plot.getWorldName()),
                row(player, "grid", plot.getGridX() + ", " + plot.getGridZ()),
                row(player, "merge", text(player, MultiverseCards.COMMON + "word." + merge)),
                row(player, "trusted", String.valueOf(trusted)),
                row(player, "denied", String.valueOf(denied)));
        return String.join(NEWLINE, lines);
    }

    private static @NotNull String row(@NotNull Player player, @NotNull String label, @NotNull String value) {
        return msg(MultiverseCards.ROOT + "bedrock.row")
                .with("label", text(player, MultiverseCards.COMMON + "label." + label))
                .with("value", value)
                .plain(player);
    }

    private static @NotNull String gridText(@NotNull Player player, @NotNull String key, @NotNull Plot plot) {
        return msg(key).with(GRID_X, plot.getGridX()).with(GRID_Z, plot.getGridZ()).plain(player);
    }

    private static @NotNull String text(@NotNull Player player, @NotNull String key) {
        return msg(key).plain(player);
    }

    private static @NotNull MessageBuilder msg(@NotNull String key) {
        return MultiverseCards.msg(key);
    }

    private static void noFollowUp() {
        // The form is already closed; the chat confirmation is the only feedback a flag change needs.
    }

    private void onPlayer(@NotNull Player player, @NotNull Runnable task) {
        PlatformScheduler.of(actions.plugin()).runAtEntity(player, task);
    }

    private static void send(@NotNull Player player, @NotNull Form form) {
        FloodgateApi.getInstance().sendForm(player.getUniqueId(), form);
    }
}
