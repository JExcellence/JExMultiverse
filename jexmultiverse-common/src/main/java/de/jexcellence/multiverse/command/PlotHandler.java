package de.jexcellence.multiverse.command;

import com.raindropcentral.commands.v2.CommandContext;
import com.raindropcentral.commands.v2.CommandHandler;
import de.jexcellence.jextranslate.R18nManager;
import de.jexcellence.multiverse.api.MVWorldType;
import de.jexcellence.multiverse.database.entity.MemberRole;
import de.jexcellence.multiverse.database.entity.Plot;
import de.jexcellence.multiverse.factory.WorldFactory;
import de.jexcellence.multiverse.service.MultiverseService;
import de.jexcellence.multiverse.service.PlotFlag;
import de.jexcellence.multiverse.service.PlotService;
import de.jexcellence.multiverse.view.PlotMenuView;
import me.devnatan.inventoryframework.ViewFrame;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

/**
 * /plot command tree handler — claim, unclaim, info, trust, untrust, deny,
 * undeny, home, list. Phase 2A scope.
 *
 * <p>Flags + merging arrive in Phase 2B/2C and add their own handler entries.
 *
 * @author JExcellence
 * @since 3.2.0
 */
public final class PlotHandler {

    private final PlotService plots;
    private final MultiverseService mv;
    private final WorldFactory worldFactory;
    private final ViewFrame viewFrame;
    private final JavaPlugin plugin;

    public PlotHandler(@NotNull PlotService plots,
                       @NotNull MultiverseService mv,
                       @NotNull WorldFactory worldFactory,
                       @NotNull ViewFrame viewFrame,
                       @NotNull JavaPlugin plugin) {
        this.plots = plots;
        this.mv = mv;
        this.worldFactory = worldFactory;
        this.viewFrame = viewFrame;
        this.plugin = plugin;
    }

    /**
     * Returns the full command handler map keyed by command path (e.g. {@code "plot.claim"}).
     *
     * @return an immutable map of command paths to their handlers
     */
    public @NotNull Map<String, CommandHandler> handlerMap() {
        return Map.ofEntries(
                Map.entry("plot",          this::onRoot),
                Map.entry("plot.claim",    this::onClaim),
                Map.entry("plot.unclaim",  this::onUnclaim),
                Map.entry("plot.info",     this::onInfo),
                Map.entry("plot.trust",    this::onTrust),
                Map.entry("plot.untrust",  this::onUntrust),
                Map.entry("plot.deny",     this::onDeny),
                Map.entry("plot.undeny",   this::onUndeny),
                Map.entry("plot.home",     this::onHome),
                Map.entry("plot.list",     this::onList),
                Map.entry("plot.flag",     this::onFlag),
                Map.entry("plot.merge",    this::onMerge),
                Map.entry("plot.unmerge",  this::onUnmerge),
                Map.entry("plot.menu",     this::onMenu),
                Map.entry("plot.border",   this::onBorder),
                Map.entry("plot.help",     this::onHelp)
        );
    }

    // ── Root → help ─────────────────────────────────────────────────────────────

    private void onRoot(@NotNull CommandContext ctx) { onHelp(ctx); }

    // ── Claim ───────────────────────────────────────────────────────────────────

    private void onClaim(@NotNull CommandContext ctx) {
        var player = ctx.asPlayer().orElse(null);
        if (player == null) return;

        var coord = mv.plotAt(player.getLocation()).orElse(null);
        if (coord == null) {
            r18n().msg("plot.error.not_on_plot").prefix().send(player);
            return;
        }

        var existing = plots.getPlot(coord.world(), coord.gridX(), coord.gridZ()).orElse(null);
        if (existing != null) {
            r18n().msg("plot.error.already_claimed").prefix()
                    .with("owner_name", existing.getOwnerName())
                    .send(player);
            return;
        }

        var owned = plots.getOwnedPlots(player.getUniqueId()).size();
        var limit = plots.getClaimLimit(player);
        if (owned >= limit) {
            r18n().msg("plot.error.claim_limit").prefix()
                    .with("owned", String.valueOf(owned))
                    .with("max", limit == Integer.MAX_VALUE ? "unlimited" : String.valueOf(limit))
                    .send(player);
            return;
        }

        plots.claim(player, player.getLocation()).thenAccept(opt -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (opt.isPresent()) {
                r18n().msg("plot.claimed").prefix()
                        .with("grid_x", String.valueOf(coord.gridX()))
                        .with("grid_z", String.valueOf(coord.gridZ()))
                        .with("world_name", coord.world())
                        .send(player);
            } else {
                r18n().msg("plot.error.claim_failed").prefix().send(player);
            }
        }));
    }

    // ── Unclaim ─────────────────────────────────────────────────────────────────

    private void onUnclaim(@NotNull CommandContext ctx) {
        var player = ctx.asPlayer().orElse(null);
        if (player == null) return;

        var plot = plots.getPlotAt(player.getLocation()).orElse(null);
        if (plot == null) {
            r18n().msg("plot.error.not_on_plot").prefix().send(player);
            return;
        }
        if (!plot.isOwner(player.getUniqueId()) && !player.hasPermission("jexplots.bypass.protect")) {
            r18n().msg("plot.error.not_owner").prefix()
                    .with("owner_name", plot.getOwnerName()).send(player);
            return;
        }

        plots.unclaim(plot).thenAccept(success -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (success) {
                r18n().msg("plot.unclaimed").prefix()
                        .with("grid_x", String.valueOf(plot.getGridX()))
                        .with("grid_z", String.valueOf(plot.getGridZ()))
                        .send(player);
            } else {
                r18n().msg("plot.error.unclaim_failed").prefix().send(player);
            }
        }));
    }

    // ── Info ────────────────────────────────────────────────────────────────────

    private void onInfo(@NotNull CommandContext ctx) {
        var player = ctx.asPlayer().orElse(null);
        if (player == null) return;

        var plot = plots.getPlotAt(player.getLocation()).orElse(null);
        if (plot == null) {
            r18n().msg("plot.error.not_on_plot").prefix().send(player);
            return;
        }

        var members = plots.getMembers(plot);
        var trustedCount = members.values().stream().filter(r -> r == MemberRole.TRUSTED).count();
        var deniedCount = members.values().stream().filter(r -> r == MemberRole.DENIED).count();

        r18n().msg("plot.info_header").prefix()
                .with("grid_x", String.valueOf(plot.getGridX()))
                .with("grid_z", String.valueOf(plot.getGridZ()))
                .with("world_name", plot.getWorldName())
                .send(player);
        r18n().msg("plot.info_owner")
                .with("owner_name", plot.getOwnerName())
                .send(player);
        r18n().msg("plot.info_members")
                .with("trusted", String.valueOf(trustedCount))
                .with("denied", String.valueOf(deniedCount))
                .send(player);
    }

    // ── Trust / untrust / deny / undeny ─────────────────────────────────────────

    private void onTrust(@NotNull CommandContext ctx)   { setRoleHere(ctx, MemberRole.TRUSTED); }
    private void onDeny(@NotNull CommandContext ctx)    { setRoleHere(ctx, MemberRole.DENIED); }
    private void onUntrust(@NotNull CommandContext ctx) { removeRoleHere(ctx, MemberRole.TRUSTED); }
    private void onUndeny(@NotNull CommandContext ctx)  { removeRoleHere(ctx, MemberRole.DENIED); }

    private void setRoleHere(@NotNull CommandContext ctx, @NotNull MemberRole role) {
        var player = ctx.asPlayer().orElse(null);
        if (player == null) return;
        var plot = plots.getPlotAt(player.getLocation()).orElse(null);
        if (plot == null) {
            r18n().msg("plot.error.not_on_plot").prefix().send(player);
            return;
        }
        if (!plot.isOwner(player.getUniqueId()) && !player.hasPermission("jexplots.bypass.protect")) {
            r18n().msg("plot.error.not_owner").prefix().with("owner_name", plot.getOwnerName()).send(player);
            return;
        }

        var target = ctx.require("target", OfflinePlayer.class);
        if (target.getUniqueId().equals(plot.getOwnerUuid())) {
            r18n().msg("plot.error.target_is_owner").prefix().send(player);
            return;
        }

        plots.setMember(plot, target, role).thenAccept(ok -> Bukkit.getScheduler().runTask(plugin, () -> {
            var key = role == MemberRole.TRUSTED ? "plot.trusted" : "plot.denied";
            r18n().msg(ok ? key : "plot.error.member_failed").prefix()
                    .with("target_name", String.valueOf(target.getName()))
                    .send(player);
        }));
    }

    private void removeRoleHere(@NotNull CommandContext ctx, @NotNull MemberRole role) {
        var player = ctx.asPlayer().orElse(null);
        if (player == null) return;
        var plot = plots.getPlotAt(player.getLocation()).orElse(null);
        if (plot == null) {
            r18n().msg("plot.error.not_on_plot").prefix().send(player);
            return;
        }
        if (!plot.isOwner(player.getUniqueId()) && !player.hasPermission("jexplots.bypass.protect")) {
            r18n().msg("plot.error.not_owner").prefix().with("owner_name", plot.getOwnerName()).send(player);
            return;
        }

        var target = ctx.require("target", OfflinePlayer.class);
        var current = plots.roleOf(plot, target.getUniqueId()).orElse(null);
        if (current != role) {
            r18n().msg("plot.error.member_not_set").prefix()
                    .with("target_name", String.valueOf(target.getName())).send(player);
            return;
        }

        plots.removeMember(plot, target.getUniqueId()).thenAccept(ok -> Bukkit.getScheduler().runTask(plugin, () -> {
            var key = role == MemberRole.TRUSTED ? "plot.untrusted" : "plot.undenied";
            r18n().msg(ok ? key : "plot.error.member_failed").prefix()
                    .with("target_name", String.valueOf(target.getName()))
                    .send(player);
        }));
    }

    // ── Home ────────────────────────────────────────────────────────────────────

    private void onHome(@NotNull CommandContext ctx) {
        var player = ctx.asPlayer().orElse(null);
        if (player == null) return;

        var owned = plots.getOwnedPlots(player.getUniqueId());
        if (owned.isEmpty()) {
            r18n().msg("plot.error.no_plots").prefix().send(player);
            return;
        }

        int idx = ctx.get("n", Long.class).map(Long::intValue).orElse(1) - 1;
        if (idx < 0 || idx >= owned.size()) {
            r18n().msg("plot.error.no_such_home").prefix()
                    .with("n", String.valueOf(idx + 1))
                    .with("count", String.valueOf(owned.size()))
                    .send(player);
            return;
        }

        var plot = owned.get(idx);
        var bukkit = Bukkit.getWorld(plot.getWorldName());
        if (bukkit == null) {
            r18n().msg("multiverse.world_not_loaded").prefix()
                    .with("world_name", plot.getWorldName()).send(player);
            return;
        }
        var bounds = mv.plotBounds(plot.getWorldName(), plot.getGridX(), plot.getGridZ()).orElse(null);
        if (bounds == null) return;
        var loc = new org.bukkit.Location(bukkit, bounds.centerX() + 0.5,
                bounds.surfaceY() + 1, bounds.centerZ() + 0.5);
        Bukkit.getScheduler().runTask(plugin, () -> {
            player.teleport(loc);
            r18n().msg("plot.teleported").prefix()
                    .with("grid_x", String.valueOf(plot.getGridX()))
                    .with("grid_z", String.valueOf(plot.getGridZ()))
                    .with("world_name", plot.getWorldName())
                    .send(player);
        });
    }

    // ── List ────────────────────────────────────────────────────────────────────

    private void onList(@NotNull CommandContext ctx) {
        var sender = ctx.sender();
        var player = ctx.asPlayer().orElse(null);
        var ownerUuid = player != null ? player.getUniqueId() : null;
        if (ownerUuid == null) {
            r18n().msg("plot.error.console_no_owner").prefix().send(sender);
            return;
        }

        var owned = plots.getOwnedPlots(ownerUuid);
        if (owned.isEmpty()) {
            r18n().msg("plot.list_empty").prefix().send(sender);
            return;
        }

        var limit = plots.getClaimLimit(player);
        r18n().msg("plot.list_header").prefix()
                .with("count", String.valueOf(owned.size()))
                .with("max", limit == Integer.MAX_VALUE ? "unlimited" : String.valueOf(limit))
                .send(sender);
        for (int i = 0; i < owned.size(); i++) {
            var p = owned.get(i);
            r18n().msg("plot.list_entry")
                    .with("n", String.valueOf(i + 1))
                    .with("world_name", p.getWorldName())
                    .with("grid_x", String.valueOf(p.getGridX()))
                    .with("grid_z", String.valueOf(p.getGridZ()))
                    .send(sender);
        }
    }

    // ── Flag ────────────────────────────────────────────────────────────────────

    private void onFlag(@NotNull CommandContext ctx) {
        var player = ctx.asPlayer().orElse(null);
        if (player == null) return;

        var plot = plots.getPlotAt(player.getLocation()).orElse(null);
        if (plot == null) {
            r18n().msg("plot.error.not_on_plot").prefix().send(player);
            return;
        }
        if (!plot.isOwner(player.getUniqueId()) && !player.hasPermission("jexplots.bypass.protect")) {
            r18n().msg("plot.error.not_owner").prefix().with("owner_name", plot.getOwnerName()).send(player);
            return;
        }

        var action = ctx.require("action", PlotFlagAction.class);
        switch (action) {
            case LIST -> {
                r18n().msg("plot.flag_list_header").prefix()
                        .with("grid_x", String.valueOf(plot.getGridX()))
                        .with("grid_z", String.valueOf(plot.getGridZ()))
                        .send(player);
                for (var f : PlotFlag.values()) {
                    var effective = plots.getFlag(plot, f);
                    var override = plots.hasFlagOverride(plot, f);
                    r18n().msg("plot.flag_list_entry")
                            .with("flag", f.key())
                            .with("value", String.valueOf(effective))
                            .with("source", override ? "override" : "default")
                            .send(player);
                }
            }
            case SET -> {
                var flag = ctx.get("flag", PlotFlag.class).orElse(null);
                if (flag == null) {
                    r18n().msg("plot.error.flag_set_usage").prefix().send(player);
                    return;
                }
                var raw = ctx.get("value", String.class).orElse(null);
                Boolean value = parseBoolean(raw);
                if (value == null) {
                    r18n().msg("plot.error.flag_set_usage").prefix().send(player);
                    return;
                }
                plots.setFlag(plot, flag, value).thenAccept(ok -> Bukkit.getScheduler().runTask(plugin, () ->
                        r18n().msg(ok ? "plot.flag_set" : "plot.error.flag_failed").prefix()
                                .with("flag", flag.key())
                                .with("value", String.valueOf(value))
                                .send(player)));
            }
            case REMOVE -> {
                var flag = ctx.get("flag", PlotFlag.class).orElse(null);
                if (flag == null) {
                    r18n().msg("plot.error.flag_remove_usage").prefix().send(player);
                    return;
                }
                plots.removeFlag(plot, flag).thenAccept(ok -> Bukkit.getScheduler().runTask(plugin, () ->
                        r18n().msg(ok ? "plot.flag_removed" : "plot.error.flag_failed").prefix()
                                .with("flag", flag.key())
                                .send(player)));
            }
        }
    }

    private static @org.jetbrains.annotations.Nullable Boolean parseBoolean(
            @org.jetbrains.annotations.Nullable String raw) {
        if (raw == null) return null;
        return switch (raw.toLowerCase(java.util.Locale.ROOT)) {
            case "true", "yes", "on", "1", "enable", "enabled" -> Boolean.TRUE;
            case "false", "no", "off", "0", "disable", "disabled" -> Boolean.FALSE;
            default -> null;
        };
    }

    // ── Border ──────────────────────────────────────────────────────────────────

    private void onBorder(@NotNull CommandContext ctx) {
        var player = ctx.asPlayer().orElse(null);
        if (player == null) return;
        var plot = plots.getPlotAt(player.getLocation()).orElse(null);
        if (plot == null) {
            r18n().msg("plot.error.not_on_plot").prefix().send(player);
            return;
        }
        if (!plot.isOwner(player.getUniqueId()) && !player.hasPermission("jexplots.bypass.protect")) {
            r18n().msg("plot.error.not_owner").prefix().with("owner_name", plot.getOwnerName()).send(player);
            return;
        }

        var material = ctx.get("material", org.bukkit.Material.class).orElse(null);
        plots.setBorder(plot, material).thenAccept(ok -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!ok) {
                r18n().msg("plot.error.border_failed").prefix().send(player);
                return;
            }
            if (material == null) {
                r18n().msg("plot.border_reset").prefix().send(player);
            } else {
                r18n().msg("plot.border_set").prefix()
                        .with("material", material.name().toLowerCase()).send(player);
            }
        }));
    }

    // ── Menu ────────────────────────────────────────────────────────────────────

    private void onMenu(@NotNull CommandContext ctx) {
        var player = ctx.asPlayer().orElse(null);
        if (player == null) return;
        var plot = plots.getPlotAt(player.getLocation()).orElse(null);
        if (plot == null) {
            r18n().msg("plot.error.not_on_plot").prefix().send(player);
            return;
        }
        viewFrame.open(PlotMenuView.class, player,
                PlotMenuView.dataMap(plot, plugin, plots, mv));
    }

    // ── Merge / Unmerge ─────────────────────────────────────────────────────────

    private void onMerge(@NotNull CommandContext ctx) {
        var player = ctx.asPlayer().orElse(null);
        if (player == null) return;

        var plot = plots.getPlotAt(player.getLocation()).orElse(null);
        if (plot == null) {
            r18n().msg("plot.error.not_on_plot").prefix().send(player);
            return;
        }
        if (!plot.isOwner(player.getUniqueId()) && !player.hasPermission("jexplots.bypass.protect")) {
            r18n().msg("plot.error.not_owner").prefix().with("owner_name", plot.getOwnerName()).send(player);
            return;
        }

        var facing = player.getFacing(); // already horizontal-flattened
        plots.merge(player, plot, facing).thenAccept(result -> Bukkit.getScheduler().runTask(plugin, () -> {
            switch (result) {
                case OK -> r18n().msg("plot.merged").prefix()
                        .with("direction", facing.name().toLowerCase()).send(player);
                case NO_NEIGHBOR -> r18n().msg("plot.error.merge_no_neighbor").prefix()
                        .with("direction", facing.name().toLowerCase()).send(player);
                case DIFFERENT_OWNER -> r18n().msg("plot.error.merge_different_owner").prefix().send(player);
                case LIMIT_REACHED -> r18n().msg("plot.error.merge_limit").prefix()
                        .with("max", String.valueOf(plots.getMergeLimit(player))).send(player);
                case NOT_ADJACENT -> r18n().msg("plot.error.merge_not_adjacent").prefix().send(player);
                case FAILED -> r18n().msg("plot.error.merge_failed").prefix().send(player);
            }
        }));
    }

    private void onUnmerge(@NotNull CommandContext ctx) {
        var player = ctx.asPlayer().orElse(null);
        if (player == null) return;

        var plot = plots.getPlotAt(player.getLocation()).orElse(null);
        if (plot == null) {
            r18n().msg("plot.error.not_on_plot").prefix().send(player);
            return;
        }
        if (!plot.isOwner(player.getUniqueId()) && !player.hasPermission("jexplots.bypass.protect")) {
            r18n().msg("plot.error.not_owner").prefix().with("owner_name", plot.getOwnerName()).send(player);
            return;
        }
        if (plot.getMergedGroupIdString() == null) {
            r18n().msg("plot.error.unmerge_not_merged").prefix().send(player);
            return;
        }

        plots.unmerge(plot).thenAccept(ok -> Bukkit.getScheduler().runTask(plugin, () ->
                r18n().msg(ok ? "plot.unmerged" : "plot.error.unmerge_failed").prefix().send(player)));
    }

    // ── Help ────────────────────────────────────────────────────────────────────

    private void onHelp(@NotNull CommandContext ctx) {

        var sender = ctx.sender();
        var alias = ctx.alias();
        r18n().msg("plot.help_header").send(sender);
        if (hasPerm(sender, "jexplots.command.claim"))   r18n().msg("plot.help_claim").with("alias", alias).send(sender);
        if (hasPerm(sender, "jexplots.command.unclaim")) r18n().msg("plot.help_unclaim").with("alias", alias).send(sender);
        if (hasPerm(sender, "jexplots.command.info"))    r18n().msg("plot.help_info").with("alias", alias).send(sender);
        if (hasPerm(sender, "jexplots.command.trust"))   r18n().msg("plot.help_trust").with("alias", alias).send(sender);
        if (hasPerm(sender, "jexplots.command.untrust")) r18n().msg("plot.help_untrust").with("alias", alias).send(sender);
        if (hasPerm(sender, "jexplots.command.deny"))    r18n().msg("plot.help_deny").with("alias", alias).send(sender);
        if (hasPerm(sender, "jexplots.command.home"))    r18n().msg("plot.help_home").with("alias", alias).send(sender);
        if (hasPerm(sender, "jexplots.command.list"))    r18n().msg("plot.help_list").with("alias", alias).send(sender);
        if (hasPerm(sender, "jexplots.command.flag"))    r18n().msg("plot.help_flag").with("alias", alias).send(sender);
        if (hasPerm(sender, "jexplots.command.merge"))   r18n().msg("plot.help_merge").with("alias", alias).send(sender);
        if (hasPerm(sender, "jexplots.command.unmerge")) r18n().msg("plot.help_unmerge").with("alias", alias).send(sender);
        if (hasPerm(sender, "jexplots.command.menu"))    r18n().msg("plot.help_menu").with("alias", alias).send(sender);
        if (hasPerm(sender, "jexplots.command.border"))  r18n().msg("plot.help_border").with("alias", alias).send(sender);
        r18n().msg("plot.help_footer").send(sender);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private static boolean hasPerm(@NotNull CommandSender sender, @NotNull String node) {
        return sender instanceof Player p && (p.isOp() || p.hasPermission(node));
    }

    private static R18nManager r18n() {
        return R18nManager.getInstance();
    }
}
