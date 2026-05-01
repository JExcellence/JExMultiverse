package de.jexcellence.multiverse.service;

import de.jexcellence.jexplatform.logging.JExLogger;
import de.jexcellence.multiverse.api.PlotCoord;
import de.jexcellence.multiverse.database.entity.MemberRole;
import de.jexcellence.multiverse.database.entity.Plot;
import de.jexcellence.multiverse.database.entity.PlotMember;
import de.jexcellence.multiverse.database.repository.PlotMemberRepository;
import de.jexcellence.multiverse.database.repository.PlotRepository;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Plot management service. Handles claim, unclaim, member management, and
 * cached lookups for the protection listener.
 *
 * <p>All claimed plots and their members are loaded into memory at startup
 * via {@link #loadAll()} so the listener can do synchronous ownership /
 * trust checks on every block event without waiting on the DB. Mutations
 * write through to the cache and the database atomically.
 *
 * @author JExcellence
 * @since 3.2.0
 */
public class PlotService {

    private final MultiverseService multiverseService;
    private final PlotRepository plots;
    private final PlotMemberRepository members;
    private final JExLogger logger;
    private final JavaPlugin plugin;

    /** Plot cache keyed by ({@code worldName}, {@code gridX}, {@code gridZ}). */
    private final ConcurrentMap<PlotCoord, Plot> byCoord = new ConcurrentHashMap<>();
    /** Plot cache keyed by entity id. */
    private final ConcurrentMap<Long, Plot> byId = new ConcurrentHashMap<>();
    /** Plots owned per uuid. */
    private final ConcurrentMap<UUID, Set<Long>> byOwner = new ConcurrentHashMap<>();
    /** Members per plot id. */
    private final ConcurrentMap<Long, Map<UUID, MemberRole>> membersByPlot = new ConcurrentHashMap<>();

    public PlotService(@NotNull MultiverseService multiverseService,
                       @NotNull PlotRepository plots,
                       @NotNull PlotMemberRepository members,
                       @NotNull JExLogger logger,
                       @NotNull JavaPlugin plugin) {
        this.multiverseService = multiverseService;
        this.plots = plots;
        this.members = members;
        this.logger = logger;
        this.plugin = plugin;
    }

    // ── Cache load ──────────────────────────────────────────────────────────────

    /**
     * Loads every plot + member from the DB into the in-memory cache. Call
     * once during plugin enable, after the world cache is populated.
     */
    public @NotNull CompletableFuture<Void> loadAll() {
        return plots.findAllAsync().thenCompose(allPlots -> {
            byCoord.clear();
            byId.clear();
            byOwner.clear();
            for (var plot : allPlots) {
                cachePlot(plot);
            }
            return members.findAllAsync().thenAccept(allMembers -> {
                membersByPlot.clear();
                for (var m : allMembers) {
                    membersByPlot.computeIfAbsent(m.getPlotId(),
                                    k -> new ConcurrentHashMap<>())
                            .put(m.getMemberUuid(), m.getRole());
                }
                logger.info("Plot cache loaded — {} plots, {} member entries",
                        allPlots.size(), allMembers.size());
            });
        }).exceptionally(ex -> {
            logger.error("Failed to load plot cache", ex);
            return null;
        });
    }

    private void cachePlot(@NotNull Plot plot) {
        byId.put(plot.getId(), plot);
        byCoord.put(new PlotCoord(plot.getWorldName(), plot.getGridX(), plot.getGridZ()), plot);
        byOwner.computeIfAbsent(plot.getOwnerUuid(), k -> ConcurrentHashMap.newKeySet()).add(plot.getId());
    }

    private void uncachePlot(@NotNull Plot plot) {
        byId.remove(plot.getId());
        byCoord.remove(new PlotCoord(plot.getWorldName(), plot.getGridX(), plot.getGridZ()));
        var owned = byOwner.get(plot.getOwnerUuid());
        if (owned != null) owned.remove(plot.getId());
        membersByPlot.remove(plot.getId());
    }

    // ── Synchronous queries (listener-safe) ─────────────────────────────────────

    /** Returns the plot at the given location, or empty if location is on a road or unclaimed. */
    public @NotNull Optional<Plot> getPlotAt(@NotNull Location location) {
        return multiverseService.plotAt(location)
                .map(coord -> byCoord.get(coord));
    }

    /** Returns the plot at the given grid coordinates, or empty if unclaimed. */
    public @NotNull Optional<Plot> getPlot(@NotNull String worldName, int gridX, int gridZ) {
        return Optional.ofNullable(byCoord.get(new PlotCoord(worldName, gridX, gridZ)));
    }

    /** Returns the role of a player on a plot, if any. */
    public @NotNull Optional<MemberRole> roleOf(@NotNull Plot plot, @NotNull UUID player) {
        var pm = membersByPlot.get(plot.getId());
        if (pm == null) return Optional.empty();
        return Optional.ofNullable(pm.get(player));
    }

    /**
     * Returns whether a player can build on the plot — owner, trusted, or
     * holds the {@code jexplots.bypass.protect} permission.
     */
    public boolean canBuild(@NotNull Player player, @NotNull Plot plot) {
        if (player.hasPermission("jexplots.bypass.protect")) return true;
        if (plot.isOwner(player.getUniqueId())) return true;
        return roleOf(plot, player.getUniqueId()).orElse(null) == MemberRole.TRUSTED;
    }

    /** Returns whether a player is denied from a plot. */
    public boolean isDenied(@NotNull Player player, @NotNull Plot plot) {
        if (player.hasPermission("jexplots.bypass.protect")) return false;
        if (plot.isOwner(player.getUniqueId())) return false;
        return roleOf(plot, player.getUniqueId()).orElse(null) == MemberRole.DENIED;
    }

    /** Returns plots owned by a player. */
    public @NotNull List<Plot> getOwnedPlots(@NotNull UUID owner) {
        var ids = byOwner.get(owner);
        if (ids == null) return Collections.emptyList();
        var list = new ArrayList<Plot>(ids.size());
        for (var id : ids) {
            var p = byId.get(id);
            if (p != null) list.add(p);
        }
        list.sort((a, b) -> {
            var c = a.getWorldName().compareToIgnoreCase(b.getWorldName());
            if (c != 0) return c;
            c = Integer.compare(a.getGridX(), b.getGridX());
            return c != 0 ? c : Integer.compare(a.getGridZ(), b.getGridZ());
        });
        return list;
    }

    /** Returns the cached member map for a plot (uuid → role). Empty if no members. */
    public @NotNull Map<UUID, MemberRole> getMembers(@NotNull Plot plot) {
        return new HashMap<>(membersByPlot.getOrDefault(plot.getId(), Collections.emptyMap()));
    }

    /** Returns the maximum number of plots a player is allowed to claim. */
    public int getClaimLimit(@NotNull Player player) {
        if (player.hasPermission("jexplots.claim.unlimited")) return Integer.MAX_VALUE;
        // Read jexplots.claim.<n> with the highest n the player has
        int max = 1;
        for (var info : player.getEffectivePermissions()) {
            var perm = info.getPermission();
            if (!info.getValue()) continue;
            if (!perm.startsWith("jexplots.claim.")) continue;
            var tail = perm.substring("jexplots.claim.".length());
            try {
                int n = Integer.parseInt(tail);
                if (n > max) max = n;
            } catch (NumberFormatException ignored) {}
        }
        return max;
    }

    // ── Mutations (async write-through) ─────────────────────────────────────────

    /**
     * Claims a plot. Returns the new {@link Plot}, or empty if the cell is
     * already owned, the location isn't on a plot, or the player has reached
     * their claim limit.
     */
    public @NotNull CompletableFuture<Optional<Plot>> claim(@NotNull Player player,
                                                             @NotNull Location location) {
        var coord = multiverseService.plotAt(location).orElse(null);
        if (coord == null) return CompletableFuture.completedFuture(Optional.empty());
        if (byCoord.containsKey(coord)) return CompletableFuture.completedFuture(Optional.empty());
        if (getOwnedPlots(player.getUniqueId()).size() >= getClaimLimit(player)) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        var plot = Plot.builder()
                .worldName(coord.world())
                .gridX(coord.gridX())
                .gridZ(coord.gridZ())
                .ownerUuid(player.getUniqueId())
                .ownerName(player.getName())
                .claimedAt(Instant.now())
                .build();
        return plots.createAsync(plot).thenApply(saved -> {
            cachePlot(saved);
            logger.info("Player {} claimed plot {}/{},{}", player.getName(),
                    coord.world(), coord.gridX(), coord.gridZ());
            return Optional.of(saved);
        }).exceptionally(ex -> {
            logger.error("Failed to claim plot for {}", player.getName(), ex);
            return Optional.empty();
        });
    }

    /**
     * Unclaims a plot. Removes member records too. Returns true on success.
     */
    public @NotNull CompletableFuture<Boolean> unclaim(@NotNull Plot plot) {
        var future = new CompletableFuture<Boolean>();
        members.findByPlotAsync(plot.getId()).thenCompose(memberList -> {
            CompletableFuture<Void> all = CompletableFuture.completedFuture(null);
            for (var m : memberList) {
                all = all.thenCompose(v -> members.deleteAsync(m.getId()).thenApply(x -> null));
            }
            return all.thenCompose(v -> plots.deleteAsync(plot.getId()));
        }).thenAccept(v -> {
            uncachePlot(plot);
            future.complete(true);
        }).exceptionally(ex -> {
            logger.error("Failed to unclaim plot id={}", plot.getId(), ex);
            future.complete(false);
            return null;
        });
        return future;
    }

    /**
     * Adds (or updates) a member with the given role. Returns true on success.
     */
    public @NotNull CompletableFuture<Boolean> setMember(@NotNull Plot plot,
                                                         @NotNull OfflinePlayer target,
                                                         @NotNull MemberRole role) {
        var uuid = target.getUniqueId();
        var name = target.getName() != null ? target.getName() : uuid.toString().substring(0, 8);
        return members.findByPlotAndMemberAsync(plot.getId(), uuid).thenCompose(existing -> {
            if (existing.isPresent()) {
                var m = existing.get();
                m.setRole(role);
                m.setMemberName(name);
                return members.updateAsync(m).thenApply(x -> m);
            }
            var m = new PlotMember(plot.getId(), uuid, name, role);
            return members.createAsync(m);
        }).thenApply(saved -> {
            membersByPlot.computeIfAbsent(plot.getId(), k -> new ConcurrentHashMap<>())
                    .put(uuid, role);
            return true;
        }).exceptionally(ex -> {
            logger.error("Failed to set member {} on plot {}", name, plot.getId(), ex);
            return false;
        });
    }

    /** Removes a player's role from the plot (whatever it was). Returns true on success. */
    public @NotNull CompletableFuture<Boolean> removeMember(@NotNull Plot plot, @NotNull UUID member) {
        return members.findByPlotAndMemberAsync(plot.getId(), member).thenCompose(opt -> {
            if (opt.isEmpty()) return CompletableFuture.completedFuture(true);
            return members.deleteAsync(opt.get().getId()).thenApply(v -> {
                var pm = membersByPlot.get(plot.getId());
                if (pm != null) pm.remove(member);
                return true;
            });
        }).exceptionally(ex -> {
            logger.error("Failed to remove member from plot {}", plot.getId(), ex);
            return false;
        });
    }

    /** Returns a snapshot of all cached plots (read-only). */
    public @NotNull Collection<Plot> getAllPlots() {
        return Collections.unmodifiableCollection(byId.values());
    }
}
