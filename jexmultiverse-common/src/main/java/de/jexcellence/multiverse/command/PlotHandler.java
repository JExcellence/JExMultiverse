package de.jexcellence.multiverse.command;

import com.raindropcentral.commands.v2.CommandContext;
import com.raindropcentral.commands.v2.CommandHandler;
import de.jexcellence.jexplatform.gui.chat.ChatPanel;
import de.jexcellence.jexplatform.gui.style.BedrockViewers;
import de.jexcellence.jexplatform.scheduler.PlatformScheduler;
import de.jexcellence.jextranslate.MessageBuilder;
import de.jexcellence.multiverse.database.entity.MemberRole;
import de.jexcellence.multiverse.database.entity.Plot;
import de.jexcellence.multiverse.service.MultiverseService;
import de.jexcellence.multiverse.service.PlotFlag;
import de.jexcellence.multiverse.service.PlotService;
import de.jexcellence.multiverse.service.PlotService.MergeResult;
import de.jexcellence.multiverse.view.MultiverseCards;
import de.jexcellence.multiverse.view.PlotActions;
import de.jexcellence.multiverse.view.PlotMenuView;
import de.jexcellence.multiverse.view.bedrock.PlotForms;
import me.devnatan.inventoryframework.ViewFrame;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.BlockFace;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@code /plot} command tree handler: claim, unclaim, info, trust, untrust, deny, undeny, home, list, flag,
 * merge, unmerge, border, menu and help. Single results are one prefixed chat line; info, list, flag list and
 * help are {@link ChatPanel} panels. Bedrock players get the plot menu as a Cumulus form.
 *
 * @author JExcellence
 * @since 3.2.0
 */
public final class PlotHandler {

    private static final String KEY_NOT_ON_PLOT = "plot.error.not_on_plot";
    private static final String KEY_NOT_OWNER = "plot.error.not_owner";
    private static final String PERM_BYPASS = "jexplots.bypass.protect";
    private static final String KEY_OWNER_NAME = "owner_name";
    private static final String KEY_GRID_X = "grid_x";
    private static final String KEY_GRID_Z = "grid_z";
    private static final String KEY_WORLD_NAME = "world_name";
    private static final String KEY_TARGET_NAME = "target_name";
    private static final String KEY_COUNT = "count";
    private static final String KEY_VALUE = "value";
    private static final String KEY_ALIAS = "alias";
    private static final String KEY_FLAG = "flag";
    private static final String KEY_MAX = "max";
    private static final String KEY_DIRECTION = "direction";
    private static final String PANEL = ChatPanels.ROOT + "plot.";
    private static final String MSG_FLAG_SET_USAGE = "plot.error.flag_set_usage";

    private static final List<String> HELP_COMMANDS = List.of(
            "claim", "unclaim", "info", "trust", "untrust", "deny", "home", "list", "flag", "merge", "unmerge",
            "menu", "border");

    private final PlotService plots;
    private final MultiverseService mv;
    private final ViewFrame viewFrame;
    private final JavaPlugin plugin;
    private final PlotActions actions;
    private final AtomicReference<PlotForms> forms = new AtomicReference<>();

    public PlotHandler(@NotNull PlotService plots,
                       @NotNull MultiverseService mv,
                       @NotNull ViewFrame viewFrame,
                       @NotNull JavaPlugin plugin) {
        this.plots = plots;
        this.mv = mv;
        this.viewFrame = viewFrame;
        this.plugin = plugin;
        this.actions = new PlotActions(plugin, plots, mv);
    }

    /**
     * Returns the full command handler map keyed by command path (e.g. {@code "plot.claim"}).
     *
     * @return an immutable map of command paths to their handlers
     */
    public @NotNull Map<String, CommandHandler> handlerMap() {
        return Map.ofEntries(
                Map.entry("plot", this::onHelp),
                Map.entry("plot.claim", this::onClaim),
                Map.entry("plot.unclaim", this::onUnclaim),
                Map.entry("plot.info", this::onInfo),
                Map.entry("plot.trust", ctx -> setRoleHere(ctx, MemberRole.TRUSTED)),
                Map.entry("plot.untrust", ctx -> removeRoleHere(ctx, MemberRole.TRUSTED)),
                Map.entry("plot.deny", ctx -> setRoleHere(ctx, MemberRole.DENIED)),
                Map.entry("plot.undeny", ctx -> removeRoleHere(ctx, MemberRole.DENIED)),
                Map.entry("plot.home", this::onHome),
                Map.entry("plot.list", this::onList),
                Map.entry("plot.flag", this::onFlag),
                Map.entry("plot.merge", this::onMerge),
                Map.entry("plot.unmerge", this::onUnmerge),
                Map.entry("plot.menu", this::onMenu),
                Map.entry("plot.border", this::onBorder),
                Map.entry("plot.help", this::onHelp)
        );
    }

    private void onClaim(@NotNull CommandContext ctx) {
        Player player = ctx.asPlayer().orElse(null);
        if (player == null) {
            return;
        }
        var coord = mv.plotAt(player.getLocation()).orElse(null);
        if (coord == null) {
            msg(KEY_NOT_ON_PLOT).prefix().send(player);
            return;
        }
        Plot existing = plots.getPlot(coord.world(), coord.gridX(), coord.gridZ()).orElse(null);
        if (existing != null) {
            msg("plot.error.already_claimed").prefix().with(KEY_OWNER_NAME, existing.getOwnerName()).send(player);
            return;
        }
        int owned = plots.getOwnedPlots(player.getUniqueId()).size();
        int limit = plots.getClaimLimit(player);
        if (owned >= limit) {
            msg("plot.error.claim_limit").prefix()
                    .with("owned", String.valueOf(owned))
                    .with(KEY_MAX, limitText(player, limit))
                    .send(player);
            return;
        }
        plots.claim(player, player.getLocation()).thenAccept(opt -> PlatformScheduler.of(plugin).runSync(() -> {
            if (opt.isPresent()) {
                msg("plot.claimed").prefix()
                        .with(KEY_GRID_X, String.valueOf(coord.gridX()))
                        .with(KEY_GRID_Z, String.valueOf(coord.gridZ()))
                        .with(KEY_WORLD_NAME, coord.world())
                        .send(player);
            } else {
                msg("plot.error.claim_failed").prefix().send(player);
            }
        }));
    }

    private void onUnclaim(@NotNull CommandContext ctx) {
        Player player = ctx.asPlayer().orElse(null);
        Plot plot = ownedPlotHere(player);
        if (player != null && plot != null) {
            actions.unclaim(player, plot);
        }
    }

    private void onInfo(@NotNull CommandContext ctx) {
        Player player = ctx.asPlayer().orElse(null);
        if (player == null) {
            return;
        }
        Plot plot = plots.getPlotAt(player.getLocation()).orElse(null);
        if (plot == null) {
            msg(KEY_NOT_ON_PLOT).prefix().send(player);
            return;
        }
        Map<?, MemberRole> members = plots.getMembers(plot);
        long trusted = members.values().stream().filter(role -> role == MemberRole.TRUSTED).count();
        long denied = members.values().stream().filter(role -> role == MemberRole.DENIED).count();
        String merge = plot.getMergedGroupIdString() != null ? "merged" : "standalone";
        ChatPanel panel = ChatPanel.create()
                .header(ChatPanels.line(player, gridMsg(PANEL + "info.header", plot)))
                .context(ChatPanels.line(player, msg(PANEL + "info.context")
                        .with(KEY_WORLD_NAME, MultiverseCards.escape(plot.getWorldName()))))
                .gap();
        ChatPanels.row(panel, player, "owner", MultiverseCards.tone(player, "accent", plot.getOwnerName()));
        ChatPanels.row(panel, player, "trusted", MultiverseCards.tone(player, "ok", String.valueOf(trusted)));
        ChatPanels.row(panel, player, "denied", MultiverseCards.tone(player, "bad", String.valueOf(denied)));
        ChatPanels.row(panel, player, "merge", MultiverseCards.word(player, "plain", merge));
        ChatPanels.row(panel, player, "changed", MultiverseCards.value(player,
                actions.overrides(plot) + " / " + PlotFlag.values().length));
        panel.send(player);
    }

    private void setRoleHere(@NotNull CommandContext ctx, @NotNull MemberRole role) {
        Player player = ctx.asPlayer().orElse(null);
        Plot plot = ownedPlotHere(player);
        if (player == null || plot == null) {
            return;
        }
        OfflinePlayer target = ctx.require("target", OfflinePlayer.class);
        if (target.getUniqueId().equals(plot.getOwnerUuid())) {
            msg("plot.error.target_is_owner").prefix().send(player);
            return;
        }
        plots.setMember(plot, target, role).thenAccept(ok -> PlatformScheduler.of(plugin).runSync(() -> {
            String key = role == MemberRole.TRUSTED ? "plot.trusted" : "plot.denied";
            msg(Boolean.TRUE.equals(ok) ? key : "plot.error.member_failed").prefix()
                    .with(KEY_TARGET_NAME, String.valueOf(target.getName()))
                    .send(player);
        }));
    }

    private void removeRoleHere(@NotNull CommandContext ctx, @NotNull MemberRole role) {
        Player player = ctx.asPlayer().orElse(null);
        Plot plot = ownedPlotHere(player);
        if (player == null || plot == null) {
            return;
        }
        OfflinePlayer target = ctx.require("target", OfflinePlayer.class);
        String name = String.valueOf(target.getName());
        MemberRole current = plots.roleOf(plot, target.getUniqueId()).orElse(null);
        if (current != role) {
            msg("plot.error.member_not_set").prefix().with(KEY_TARGET_NAME, name).send(player);
            return;
        }
        actions.removeMember(player, plot, new PlotActions.Member(target.getUniqueId(), name, role),
                PlotHandler::noFollowUp);
    }

    private void onHome(@NotNull CommandContext ctx) {
        Player player = ctx.asPlayer().orElse(null);
        if (player == null) {
            return;
        }
        List<Plot> owned = plots.getOwnedPlots(player.getUniqueId());
        if (owned.isEmpty()) {
            msg("plot.error.no_plots").prefix().send(player);
            return;
        }
        int index = ctx.get("n", Long.class).map(Long::intValue).orElse(1) - 1;
        if (index < 0 || index >= owned.size()) {
            msg("plot.error.no_such_home").prefix()
                    .with("n", String.valueOf(index + 1))
                    .with(KEY_COUNT, String.valueOf(owned.size()))
                    .send(player);
            return;
        }
        actions.teleportHome(player, owned.get(index));
    }

    private void onList(@NotNull CommandContext ctx) {
        CommandSender sender = ctx.sender();
        Player player = ctx.asPlayer().orElse(null);
        if (player == null) {
            msg("plot.error.console_no_owner").prefix().send(sender);
            return;
        }
        List<Plot> owned = plots.getOwnedPlots(player.getUniqueId());
        if (owned.isEmpty()) {
            msg("plot.list_empty").prefix().send(sender);
            return;
        }
        ChatPanel panel = ChatPanel.create()
                .header(ChatPanels.line(player, PANEL + "list.header"))
                .context(ChatPanels.line(player, msg(PANEL + "list.context")
                        .with(KEY_COUNT, owned.size())
                        .with(KEY_MAX, limitText(player, plots.getClaimLimit(player)))))
                .gap();
        for (int i = 0; i < owned.size(); i++) {
            Plot plot = owned.get(i);
            panel.line(ChatPanels.line(player, gridMsg(PANEL + "list.entry", plot)
                    .with("n", i + 1)
                    .with(KEY_WORLD_NAME, MultiverseCards.escape(plot.getWorldName()))));
        }
        panel.footer(ChatPanels.line(player, msg(PANEL + "list.footer").with(KEY_ALIAS, ctx.alias())));
        panel.send(player);
    }

    private void onFlag(@NotNull CommandContext ctx) {
        Player player = ctx.asPlayer().orElse(null);
        Plot plot = ownedPlotHere(player);
        if (player == null || plot == null) {
            return;
        }
        PlotFlagAction action = ctx.require("action", PlotFlagAction.class);
        switch (action) {
            case LIST -> sendFlagList(player, plot);
            case SET -> handleFlagSet(ctx, player, plot);
            case REMOVE -> handleFlagRemove(ctx, player, plot);
            default -> msg(MSG_FLAG_SET_USAGE).prefix().send(player);
        }
    }

    private void sendFlagList(@NotNull Player player, @NotNull Plot plot) {
        ChatPanel panel = ChatPanel.create()
                .header(ChatPanels.line(player, gridMsg(PANEL + "flags.header", plot)))
                .gap();
        for (PlotFlag flag : PlotFlag.values()) {
            String source = plots.hasFlagOverride(plot, flag) ? "override" : "default";
            String value = MultiverseCards.state(player, plots.getFlag(plot, flag)) + " "
                    + MultiverseCards.word(player, "muted", source);
            ChatPanels.labelledRow(panel, player,
                    MultiverseCards.text(player, MultiverseCards.ROOT + "bedrock.flag." + flag.key()), value);
        }
        panel.footer(ChatPanels.line(player, PANEL + "flags.footer"));
        panel.send(player);
    }

    private void handleFlagSet(@NotNull CommandContext ctx, @NotNull Player player, @NotNull Plot plot) {
        PlotFlag flag = ctx.get(KEY_FLAG, PlotFlag.class).orElse(null);
        Boolean value = parseBoolean(ctx.get(KEY_VALUE, String.class).orElse(null));
        if (flag == null || value == null) {
            msg(MSG_FLAG_SET_USAGE).prefix().send(player);
            return;
        }
        actions.setFlag(player, plot, flag, value, PlotHandler::noFollowUp);
    }

    private void handleFlagRemove(@NotNull CommandContext ctx, @NotNull Player player, @NotNull Plot plot) {
        PlotFlag flag = ctx.get(KEY_FLAG, PlotFlag.class).orElse(null);
        if (flag == null) {
            msg("plot.error.flag_remove_usage").prefix().send(player);
            return;
        }
        plots.removeFlag(plot, flag).thenAccept(ok -> PlatformScheduler.of(plugin).runSync(() ->
                msg(Boolean.TRUE.equals(ok) ? "plot.flag_removed" : "plot.error.flag_failed").prefix()
                        .with(KEY_FLAG, flag.key())
                        .send(player)));
    }

    private static @Nullable Boolean parseBoolean(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "true", "yes", "on", "1", "enable", "enabled" -> Boolean.TRUE;
            case "false", "no", "off", "0", "disable", "disabled" -> Boolean.FALSE;
            default -> null;
        };
    }

    private void onBorder(@NotNull CommandContext ctx) {
        Player player = ctx.asPlayer().orElse(null);
        Plot plot = ownedPlotHere(player);
        if (player == null || plot == null) {
            return;
        }
        Material material = ctx.get("material", Material.class).orElse(null);
        plots.setBorder(plot, material).thenAccept(ok -> PlatformScheduler.of(plugin).runSync(() -> {
            if (!Boolean.TRUE.equals(ok)) {
                msg("plot.error.border_failed").prefix().send(player);
            } else if (material == null) {
                msg("plot.border_reset").prefix().send(player);
            } else {
                msg("plot.border_set").prefix()
                        .with("material", material.name().toLowerCase(Locale.ROOT)).send(player);
            }
        }));
    }

    private void onMenu(@NotNull CommandContext ctx) {
        Player player = ctx.asPlayer().orElse(null);
        if (player == null) {
            return;
        }
        Plot plot = plots.getPlotAt(player.getLocation()).orElse(null);
        if (plot == null) {
            msg(KEY_NOT_ON_PLOT).prefix().send(player);
            return;
        }
        if (BedrockViewers.isBedrock(player)) {
            forms().openMenu(player, plot);
            return;
        }
        viewFrame.open(PlotMenuView.class, player, PlotMenuView.dataMap(plot, plugin, plots, mv));
    }

    private @NotNull PlotForms forms() {
        PlotForms current = forms.get();
        if (current != null) {
            return current;
        }
        forms.compareAndSet(null, new PlotForms(actions));
        return forms.get();
    }

    private void onMerge(@NotNull CommandContext ctx) {
        Player player = ctx.asPlayer().orElse(null);
        Plot plot = ownedPlotHere(player);
        if (player == null || plot == null) {
            return;
        }
        BlockFace facing = player.getFacing();
        plots.merge(player, plot, facing).thenAccept(result -> PlatformScheduler.of(plugin).runSync(() ->
                mergeMessage(player, result, facing).prefix().send(player)));
    }

    private @NotNull MessageBuilder mergeMessage(@NotNull Player player, @NotNull MergeResult result,
                                                 @NotNull BlockFace facing) {
        String direction = facing.name().toLowerCase(Locale.ROOT);
        return switch (result) {
            case OK -> msg("plot.merged").with(KEY_DIRECTION, direction);
            case NO_NEIGHBOR -> msg("plot.error.merge_no_neighbor").with(KEY_DIRECTION, direction);
            case DIFFERENT_OWNER -> msg("plot.error.merge_different_owner");
            case LIMIT_REACHED -> msg("plot.error.merge_limit").with(KEY_MAX, String.valueOf(plots.getMergeLimit(player)));
            case NOT_ADJACENT -> msg("plot.error.merge_not_adjacent");
            default -> msg("plot.error.merge_failed");
        };
    }

    private void onUnmerge(@NotNull CommandContext ctx) {
        Player player = ctx.asPlayer().orElse(null);
        Plot plot = ownedPlotHere(player);
        if (player == null || plot == null) {
            return;
        }
        if (plot.getMergedGroupIdString() == null) {
            msg("plot.error.unmerge_not_merged").prefix().send(player);
            return;
        }
        plots.unmerge(plot).thenAccept(ok -> PlatformScheduler.of(plugin).runSync(() ->
                msg(Boolean.TRUE.equals(ok) ? "plot.unmerged" : "plot.error.unmerge_failed").prefix().send(player)));
    }

    private void onHelp(@NotNull CommandContext ctx) {
        CommandSender sender = ctx.sender();
        ChatPanel panel = ChatPanel.create()
                .header(ChatPanels.line(sender, PANEL + "help.header"))
                .context(ChatPanels.line(sender, PANEL + "help.context"))
                .gap();
        for (String command : HELP_COMMANDS) {
            if (hasPerm(sender, "jexplots.command." + command)) {
                panel.line(ChatPanels.line(sender, msg(PANEL + "help." + command).with(KEY_ALIAS, ctx.alias())));
            }
        }
        panel.send(sender);
    }

    /**
     * The plot the player stands on when they own it or may bypass protection; sends the matching error and
     * returns {@code null} otherwise.
     */
    private @Nullable Plot ownedPlotHere(@Nullable Player player) {
        if (player == null) {
            return null;
        }
        Plot plot = plots.getPlotAt(player.getLocation()).orElse(null);
        if (plot == null) {
            msg(KEY_NOT_ON_PLOT).prefix().send(player);
            return null;
        }
        if (!plot.isOwner(player.getUniqueId()) && !player.hasPermission(PERM_BYPASS)) {
            msg(KEY_NOT_OWNER).prefix().with(KEY_OWNER_NAME, plot.getOwnerName()).send(player);
            return null;
        }
        return plot;
    }

    private static @NotNull MessageBuilder gridMsg(@NotNull String key, @NotNull Plot plot) {
        return msg(key).with(KEY_GRID_X, plot.getGridX()).with(KEY_GRID_Z, plot.getGridZ());
    }

    private static @NotNull String limitText(@Nullable Player player, int limit) {
        return limit == Integer.MAX_VALUE
                ? MultiverseCards.text(player, MultiverseCards.COMMON + "word.unlimited")
                : String.valueOf(limit);
    }

    private static void noFollowUp() {
        // The chat confirmation sent by PlotActions is the only feedback a command needs.
    }

    private static boolean hasPerm(@NotNull CommandSender sender, @NotNull String node) {
        return sender instanceof Player p && (p.isOp() || p.hasPermission(node));
    }

    private static @NotNull MessageBuilder msg(@NotNull String key) {
        return MultiverseCards.msg(key);
    }
}
