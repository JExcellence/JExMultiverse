package de.jexcellence.multiverse.view;

import de.jexcellence.jexplatform.scheduler.PlatformScheduler;
import de.jexcellence.multiverse.database.entity.MemberRole;
import de.jexcellence.multiverse.database.entity.Plot;
import de.jexcellence.multiverse.service.MultiverseService;
import de.jexcellence.multiverse.service.PlotFlag;
import de.jexcellence.multiverse.service.PlotService;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The plot actions the chest views and the Bedrock forms share: teleport home, unclaim, toggle a flag and
 * remove a member. Each one sends the same chat feedback, so both clients behave the same.
 *
 * @param plugin  the owning plugin, for scheduling the feedback onto the main thread
 * @param plots   the plot service
 * @param worlds  the multiverse service
 * @author JExcellence
 * @since 3.8.0
 */
public record PlotActions(@NotNull JavaPlugin plugin, @NotNull PlotService plots, @NotNull MultiverseService worlds) {

    private static final String GRID_X = "grid_x";
    private static final String GRID_Z = "grid_z";
    private static final String TARGET_NAME = "target_name";

    /** Permission that lets staff manage any plot; the same node {@code PlotHandler} checks. */
    public static final String PERM_BYPASS = "jexplots.bypass.protect";

    /**
     * The centre of the plot one block above the surface, or {@code null} when its world is not loaded.
     *
     * @param plot the plot
     * @return the teleport target or {@code null}
     */
    public @Nullable Location homeOf(@NotNull Plot plot) {
        World world = Bukkit.getWorld(plot.getWorldName());
        if (world == null) {
            return null;
        }
        return worlds.plotBounds(plot.getWorldName(), plot.getGridX(), plot.getGridZ())
                .map(bounds -> new Location(world, bounds.centerX() + 0.5, bounds.surfaceY() + 1.0,
                        bounds.centerZ() + 0.5))
                .orElse(null);
    }

    /**
     * Teleports the player to the plot's centre and confirms it in chat.
     *
     * @param player the player
     * @param plot   the plot
     */
    public void teleportHome(@NotNull Player player, @NotNull Plot plot) {
        Location home = homeOf(plot);
        if (home == null) {
            MultiverseCards.msg("multiverse.world_not_loaded").prefix()
                    .with("world_name", plot.getWorldName()).send(player);
            return;
        }
        PlatformScheduler.of(plugin).runSync(() -> {
            player.teleportAsync(home);
            MultiverseCards.msg("plot.teleported").prefix()
                    .with(GRID_X, String.valueOf(plot.getGridX()))
                    .with(GRID_Z, String.valueOf(plot.getGridZ()))
                    .with("world_name", plot.getWorldName())
                    .send(player);
        });
    }

    /**
     * Whether the player may change the plot: its owner, or staff with {@link #PERM_BYPASS}.
     *
     * @param player the player
     * @param plot   the plot
     * @return {@code true} when flags, members and the claim may be changed
     */
    public static boolean canManage(@NotNull Player player, @NotNull Plot plot) {
        return plot.isOwner(player.getUniqueId()) || player.hasPermission(PERM_BYPASS);
    }

    private static boolean allowed(@NotNull Player player, @NotNull Plot plot) {
        if (canManage(player, plot)) {
            return true;
        }
        MultiverseCards.msg("plot.error.not_owner").prefix().with("owner_name", plot.getOwnerName()).send(player);
        return false;
    }

    /**
     * Releases the plot and reports the result.
     *
     * @param player the player who asked
     * @param plot   the plot
     */
    public void unclaim(@NotNull Player player, @NotNull Plot plot) {
        if (!allowed(player, plot)) {
            return;
        }
        plots.unclaim(plot).thenAccept(ok -> PlatformScheduler.of(plugin).runSync(() ->
                MultiverseCards.msg(Boolean.TRUE.equals(ok) ? "plot.unclaimed" : "plot.error.unclaim_failed")
                        .prefix()
                        .with(GRID_X, String.valueOf(plot.getGridX()))
                        .with(GRID_Z, String.valueOf(plot.getGridZ()))
                        .send(player)));
    }

    /**
     * Sets a flag, reports the result and runs {@code after} on the main thread when it was saved.
     *
     * @param player the player who asked
     * @param plot   the plot
     * @param flag   the flag
     * @param value  the new value
     * @param after  follow-up on success, e.g. a re-render
     */
    public void setFlag(@NotNull Player player, @NotNull Plot plot, @NotNull PlotFlag flag, boolean value,
                        @NotNull Runnable after) {
        if (!allowed(player, plot)) {
            return;
        }
        plots.setFlag(plot, flag, value).thenAccept(ok -> PlatformScheduler.of(plugin).runSync(() -> {
            boolean saved = Boolean.TRUE.equals(ok);
            MultiverseCards.msg(saved ? "plot.flag_set" : "plot.error.flag_failed").prefix()
                    .with("flag", flag.key())
                    .with("value", String.valueOf(value))
                    .send(player);
            if (saved) {
                after.run();
            }
        }));
    }

    /**
     * Removes a member's role, reports the result and runs {@code after} on the main thread.
     *
     * @param player the player who asked
     * @param plot   the plot
     * @param member the member
     * @param after  follow-up, e.g. re-opening the list
     */
    public void removeMember(@NotNull Player player, @NotNull Plot plot, @NotNull Member member,
                             @NotNull Runnable after) {
        if (!allowed(player, plot)) {
            return;
        }
        plots.removeMember(plot, member.uuid()).thenAccept(ok -> PlatformScheduler.of(plugin).runSync(() -> {
            String key;
            if (!Boolean.TRUE.equals(ok)) {
                key = "plot.error.member_failed";
            } else if (member.role() == MemberRole.TRUSTED) {
                key = "plot.untrusted";
            } else {
                key = "plot.undenied";
            }
            MultiverseCards.msg(key).prefix().with(TARGET_NAME, member.name()).send(player);
            after.run();
        }));
    }

    /**
     * The plot's members, trusted first, then by name.
     *
     * @param plot the plot
     * @return the members
     */
    public @NotNull List<Member> members(@NotNull Plot plot) {
        Map<String, Member> sorted = new TreeMap<>();
        plots.getMembers(plot).forEach((uuid, role) -> {
            String name = Bukkit.getOfflinePlayer(uuid).getName();
            String shown = name != null ? name : uuid.toString().substring(0, 8);
            sorted.put(role.ordinal() + ":" + shown.toLowerCase(Locale.ROOT) + ":" + uuid,
                    new Member(uuid, shown, role));
        });
        return List.copyOf(sorted.values());
    }

    /**
     * How many flags of the plot differ from the server default.
     *
     * @param plot the plot
     * @return the override count
     */
    public int overrides(@NotNull Plot plot) {
        int count = 0;
        for (PlotFlag flag : PlotFlag.values()) {
            if (plots.hasFlagOverride(plot, flag)) {
                count++;
            }
        }
        return count;
    }

    /**
     * A member of a plot.
     *
     * @param uuid the member's id
     * @param name the member's name, or a short id when the name is unknown
     * @param role the member's role
     */
    public record Member(@NotNull UUID uuid, @NotNull String name, @NotNull MemberRole role) {
    }
}
