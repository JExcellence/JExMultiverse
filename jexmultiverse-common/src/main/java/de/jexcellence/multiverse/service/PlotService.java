package de.jexcellence.multiverse.service;

import de.jexcellence.jexplatform.logging.JExLogger;
import de.jexcellence.multiverse.api.PlotCoord;
import de.jexcellence.multiverse.database.entity.MemberRole;
import de.jexcellence.multiverse.database.entity.Plot;
import de.jexcellence.multiverse.database.entity.PlotMember;
import de.jexcellence.multiverse.database.entity.StoredPlotFlag;
import de.jexcellence.multiverse.database.repository.PlotFlagRepository;
import de.jexcellence.multiverse.database.repository.PlotMemberRepository;
import de.jexcellence.multiverse.database.repository.PlotRepository;
import de.jexcellence.multiverse.factory.WorldFactory;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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
    private final WorldFactory worldFactory;
    private final PlotRepository plots;
    private final PlotMemberRepository members;
    private final PlotFlagRepository flags;
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
    /** Flag overrides per plot id (key → string value). Default flags aren't stored here. */
    private final ConcurrentMap<Long, Map<String, Boolean>> flagsByPlot = new ConcurrentHashMap<>();

    public PlotService(@NotNull MultiverseService multiverseService,
                       @NotNull WorldFactory worldFactory,
                       @NotNull PlotRepository plots,
                       @NotNull PlotMemberRepository members,
                       @NotNull PlotFlagRepository flags,
                       @NotNull JExLogger logger,
                       @NotNull JavaPlugin plugin) {
        this.multiverseService = multiverseService;
        this.worldFactory = worldFactory;
        this.plots = plots;
        this.members = members;
        this.flags = flags;
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
            return members.findAllAsync().thenCompose(allMembers -> {
                membersByPlot.clear();
                for (var m : allMembers) {
                    membersByPlot.computeIfAbsent(m.getPlotId(),
                                    k -> new ConcurrentHashMap<>())
                            .put(m.getMemberUuid(), m.getRole());
                }
                return flags.findAllAsync().thenAccept(allFlags -> {
                    flagsByPlot.clear();
                    for (var f : allFlags) {
                        flagsByPlot.computeIfAbsent(f.getPlotId(), k -> new ConcurrentHashMap<>())
                                .put(f.getFlagKey(), Boolean.parseBoolean(f.getFlagValue()));
                    }
                    logger.info("Plot cache loaded — {} plots, {} member entries, {} flag overrides",
                            allPlots.size(), allMembers.size(), allFlags.size());
                });
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
        flagsByPlot.remove(plot.getId());
    }

    // ── Flag accessors (listener-safe) ──────────────────────────────────────────

    /** Returns the effective boolean value of a flag — override if set, else default. */
    public boolean getFlag(@NotNull Plot plot, @NotNull PlotFlag flag) {
        var stored = flagsByPlot.get(plot.getId());
        if (stored != null) {
            var v = stored.get(flag.key());
            if (v != null) return v;
        }
        return flag.defaultValue();
    }

    /** Returns whether a flag has an explicit override (vs. taking the default). */
    public boolean hasFlagOverride(@NotNull Plot plot, @NotNull PlotFlag flag) {
        var stored = flagsByPlot.get(plot.getId());
        return stored != null && stored.containsKey(flag.key());
    }

    /** Returns a snapshot of explicit flag overrides for a plot. */
    public @NotNull Map<String, Boolean> getFlagOverrides(@NotNull Plot plot) {
        return new HashMap<>(flagsByPlot.getOrDefault(plot.getId(), Collections.emptyMap()));
    }

    // ── Flag mutations ──────────────────────────────────────────────────────────

    /**
     * Persists a flag override. Returns true on success.
     *
     * <p>For an existing override we delete the old row and create a new one
     * (rather than {@code updateAsync} on a detached entity) — fewer paths
     * for "session is closed" / stale-entity errors to surface.
     */
    public @NotNull CompletableFuture<Boolean> setFlag(@NotNull Plot plot,
                                                        @NotNull PlotFlag flag,
                                                        boolean value) {
        var key = flag.key();
        var raw = Boolean.toString(value);
        return flags.findByPlotAndKeyAsync(plot.getId(), key).thenCompose(existing -> {
            CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
            if (existing.isPresent()) {
                chain = chain.thenCompose(v -> flags.deleteAsync(existing.get().getId()).thenApply(x -> null));
            }
            return chain.thenCompose(v -> flags.createAsync(new StoredPlotFlag(plot.getId(), key, raw)));
        }).thenApply(saved -> {
            flagsByPlot.computeIfAbsent(plot.getId(), k -> new ConcurrentHashMap<>())
                    .put(key, value);
            return true;
        }).exceptionally(ex -> {
            logger.error("Failed to set flag {} on plot {}", flag.key(), plot.getId(), ex);
            return false;
        });
    }

    /** Removes a flag override (resets to default). Returns true on success. */
    public @NotNull CompletableFuture<Boolean> removeFlag(@NotNull Plot plot,
                                                          @NotNull PlotFlag flag) {
        return flags.findByPlotAndKeyAsync(plot.getId(), flag.key()).thenCompose(opt -> {
            if (opt.isEmpty()) return CompletableFuture.completedFuture(true);
            return flags.deleteAsync(opt.get().getId()).thenApply(v -> {
                var pf = flagsByPlot.get(plot.getId());
                if (pf != null) pf.remove(flag.key());
                return true;
            });
        }).exceptionally(ex -> {
            logger.error("Failed to remove flag {} on plot {}", flag.key(), plot.getId(), ex);
            return false;
        });
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
            applyClaimedWalls(saved);
            logger.info("Player {} claimed plot {}/{},{}", player.getName(),
                    coord.world(), coord.gridX(), coord.gridZ());
            return Optional.of(saved);
        }).exceptionally(ex -> {
            logger.error("Failed to claim plot for {}", player.getName(), ex);
            return Optional.empty();
        });
    }

    // ── Wall material helpers ───────────────────────────────────────────────────

    /** Returns the effective wall material for a claimed plot: override OR config default. */
    public @NotNull Material effectiveClaimedWall(@NotNull Plot plot) {
        var override = plot.getWallMaterialOverride();
        if (override != null) {
            var mat = Material.matchMaterial(override);
            if (mat != null) return mat;
        }
        return worldFactory.plotConfig().wallMaterialClaimed();
    }

    /** Schedules a perimeter-wall repaint with the plot's claimed material. */
    public void applyClaimedWalls(@NotNull Plot plot) {
        applyPerimeterWalls(plot, effectiveClaimedWall(plot));
    }

    /** Schedules a perimeter-wall repaint with the global unclaimed material. */
    public void applyUnclaimedWalls(@NotNull Plot plot) {
        applyPerimeterWalls(plot, worldFactory.plotConfig().wallMaterialUnclaimed());
    }

    private void applyPerimeterWalls(@NotNull Plot plot, @NotNull Material material) {
        var bukkit = Bukkit.getWorld(plot.getWorldName());
        var mvWorld = worldFactory.getCachedWorld(plot.getWorldName()).orElse(null);
        if (bukkit == null || mvWorld == null) return;
        int plotSize = worldFactory.effectivePlotSize(mvWorld);
        int roadWidth = worldFactory.effectiveRoadWidth(mvWorld);
        var config = worldFactory.plotConfig();
        var mergedIds = new java.util.HashSet<Long>();
        if (plot.getMergedGroupIdString() != null) {
            for (var p : getMergeGroup(plot)) {
                if (p.getId() != plot.getId()) mergedIds.add(p.getId());
            }
        }
        Bukkit.getScheduler().runTask(plugin, () ->
                PlotWallOps.applyWalls(bukkit, plot, plotSize, roadWidth, config, material,
                        mergedIds,
                        coords -> getPlot(plot.getWorldName(), coords[0], coords[1]).orElse(null)));
    }

    /**
     * Updates the owner-customised border material and repaints perimeter walls.
     *
     * <p>Re-fetches the entity by coordinates before mutation so we never hand
     * Hibernate a detached instance whose session was closed (which can surface
     * as "LogicalConnectionManagedImpl is closed" during transaction work).
     */
    public @NotNull CompletableFuture<Boolean> setBorder(@NotNull Plot plot, @Nullable Material material) {
        var newOverride = material == null ? null : material.name();
        return plots.findByCoordsAsync(plot.getWorldName(), plot.getGridX(), plot.getGridZ())
                .thenCompose(opt -> {
                    var target = opt.orElse(plot);
                    target.setWallMaterialOverride(newOverride);
                    return plots.updateAsync(target);
                })
                .thenApply(saved -> {
                    cachePlot(saved);
                    applyClaimedWalls(saved);
                    return true;
                })
                .exceptionally(ex -> {
                    logger.error("Failed to set border for plot {}", plot.getId(), ex);
                    return false;
                });
    }

    /**
     * Unclaims a plot. Removes member + flag records, repaints the perimeter
     * walls in the unclaimed material, drops the plot from the cache.
     * Returns true on success.
     */
    public @NotNull CompletableFuture<Boolean> unclaim(@NotNull Plot plot) {
        return purgePlotRows(plot).thenApply(ok -> {
            if (ok) {
                // Snapshot the geometry before uncaching so wall ops still
                // know which cells to touch.
                applyUnclaimedWalls(plot);
                uncachePlot(plot);
            }
            return ok;
        });
    }

    /**
     * Deletes every DB row associated with a plot — members, flag overrides,
     * the plot itself — and removes the plot from the in-memory caches.
     * Does NOT touch the world (no wall repaint).
     *
     * <p>Used as the shared core for {@link #unclaim(Plot)} (which adds wall
     * ops) and {@link #deletePlotsInWorld(String)} (which runs while the
     * world is in the process of being deleted, so wall ops would target a
     * world about to vanish).
     */
    private @NotNull CompletableFuture<Boolean> purgePlotRows(@NotNull Plot plot) {
        return members.findByPlotAsync(plot.getId()).thenCompose(memberList -> {
            CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
            for (var m : memberList) {
                chain = chain.thenCompose(v -> members.deleteAsync(m.getId()).thenApply(x -> null));
            }
            return chain;
        }).thenCompose(v -> flags.query()
                .and("plotId", plot.getId())
                .listAsync()
        ).thenCompose(flagList -> {
            CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
            for (var f : flagList) {
                chain = chain.thenCompose(v -> flags.deleteAsync(f.getId()).thenApply(x -> null));
            }
            return chain;
        }).thenCompose(v -> plots.deleteAsync(plot.getId()))
          .thenApply(v -> true)
          .exceptionally(ex -> {
              logger.error("Failed to purge plot rows for id={}", plot.getId(), ex);
              return false;
          });
    }

    /**
     * Adds (or updates) a member with the given role. Returns true on success.
     */
    public @NotNull CompletableFuture<Boolean> setMember(@NotNull Plot plot,
                                                         @NotNull OfflinePlayer target,
                                                         @NotNull MemberRole role) {
        var uuid = target.getUniqueId();
        var name = target.getName() != null ? target.getName() : uuid.toString().substring(0, 8);
        // Same pattern as setFlag — delete-then-create instead of update on a
        // detached entity, avoiding a class of Hibernate session lifecycle
        // errors that surface as "LogicalConnectionManagedImpl is closed".
        return members.findByPlotAndMemberAsync(plot.getId(), uuid).thenCompose(existing -> {
            CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
            if (existing.isPresent()) {
                chain = chain.thenCompose(v -> members.deleteAsync(existing.get().getId()).thenApply(x -> null));
            }
            return chain.thenCompose(v -> members.createAsync(new PlotMember(plot.getId(), uuid, name, role)));
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

    /**
     * Cascade-deletes every plot row for a given world (members + flags
     * included). Used by {@link MultiverseService#deleteWorld(String)} so
     * deleting a world doesn't leave orphan plot rows pointing at it.
     */
    public @NotNull CompletableFuture<Void> deletePlotsInWorld(@NotNull String worldName) {
        var doomed = new ArrayList<Plot>();
        for (var p : byId.values()) {
            if (p.getWorldName().equals(worldName)) doomed.add(p);
        }
        if (doomed.isEmpty()) return CompletableFuture.completedFuture(null);

        logger.info("Cascade-deleting {} plot row(s) for world '{}'", doomed.size(), worldName);
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (var plot : doomed) {
            // purgePlotRows skips wall ops — the world is about to be deleted
            // so there's no point trying to repaint anything in it.
            chain = chain.thenCompose(v -> purgePlotRows(plot).thenApply(ok -> null));
            uncachePlot(plot);
        }
        return chain;
    }

    // ── Merging ─────────────────────────────────────────────────────────────────

    /**
     * Returns the maximum merged-group size the player is allowed.
     * Reads {@code jexplots.merge.<n>} (highest matching) and
     * {@code jexplots.merge.unlimited}. Default is 1 (no merging).
     */
    public int getMergeLimit(@NotNull Player player) {
        if (player.hasPermission("jexplots.merge.unlimited")) return Integer.MAX_VALUE;
        int max = 1;
        for (var info : player.getEffectivePermissions()) {
            if (!info.getValue()) continue;
            var perm = info.getPermission();
            if (!perm.startsWith("jexplots.merge.")) continue;
            try {
                int n = Integer.parseInt(perm.substring("jexplots.merge.".length()));
                if (n > max) max = n;
            } catch (NumberFormatException ignored) {}
        }
        return max;
    }

    /** Returns all plots sharing the given plot's merge group, including itself. */
    public @NotNull List<Plot> getMergeGroup(@NotNull Plot plot) {
        var group = plot.getMergedGroupIdString();
        if (group == null) return List.of(plot);
        var list = new ArrayList<Plot>();
        for (var p : byId.values()) {
            if (group.equals(p.getMergedGroupIdString())) list.add(p);
        }
        return list;
    }

    /**
     * Returns the plot adjacent to {@code plot} in the given facing direction,
     * or empty if no plot is claimed in that cell.
     */
    public @NotNull Optional<Plot> getNeighbor(@NotNull Plot plot, @NotNull org.bukkit.block.BlockFace facing) {
        int dx = 0, dz = 0;
        switch (facing) {
            case NORTH -> dz = -1;
            case SOUTH -> dz = 1;
            case EAST  -> dx = 1;
            case WEST  -> dx = -1;
            default -> { return Optional.empty(); }
        }
        return getPlot(plot.getWorldName(), plot.getGridX() + dx, plot.getGridZ() + dz);
    }

    /**
     * Result of a merge attempt — either {@code OK} or one of the typed
     * failure reasons so the handler can render a specific error message.
     */
    public enum MergeResult { OK, NO_NEIGHBOR, DIFFERENT_OWNER, LIMIT_REACHED, NOT_ADJACENT, FAILED }

    /**
     * Merges {@code plot} with the plot adjacent in {@code facing}. Both plots
     * must be owned by {@code actor} (or actor must hold bypass) and the
     * combined merge group size must be within actor's permission cap.
     */
    public @NotNull CompletableFuture<MergeResult> merge(@NotNull Player actor,
                                                          @NotNull Plot plot,
                                                          @NotNull org.bukkit.block.BlockFace facing) {
        var neighbor = getNeighbor(plot, facing).orElse(null);
        if (neighbor == null) return CompletableFuture.completedFuture(MergeResult.NO_NEIGHBOR);
        if (!PlotMergeOps.areAdjacent(plot, neighbor)) {
            return CompletableFuture.completedFuture(MergeResult.NOT_ADJACENT);
        }
        var bypass = actor.hasPermission("jexplots.bypass.protect");
        if (!bypass && (!plot.isOwner(actor.getUniqueId()) || !neighbor.isOwner(actor.getUniqueId()))) {
            return CompletableFuture.completedFuture(MergeResult.DIFFERENT_OWNER);
        }
        var groupA = getMergeGroup(plot);
        var groupB = getMergeGroup(neighbor);
        var alreadyMerged = plot.getMergedGroupIdString() != null
                && plot.getMergedGroupIdString().equals(neighbor.getMergedGroupIdString());
        var combined = alreadyMerged ? groupA.size() : groupA.size() + groupB.size();
        if (combined > getMergeLimit(actor)) {
            return CompletableFuture.completedFuture(MergeResult.LIMIT_REACHED);
        }

        // Determine the final group id — reuse if either side already has one.
        var groupId = plot.getMergedGroupIdString() != null ? plot.getMergedGroupId()
                : neighbor.getMergedGroupIdString() != null ? neighbor.getMergedGroupId()
                : UUID.randomUUID();

        // Update DB: every plot in both groups gets the unified group id.
        var toUpdate = new ArrayList<Plot>();
        for (var p : groupA) if (!groupId.toString().equals(p.getMergedGroupIdString())) toUpdate.add(p);
        for (var p : groupB) if (!groupId.toString().equals(p.getMergedGroupIdString())) toUpdate.add(p);

        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (var p : toUpdate) {
            p.setMergedGroupId(groupId);
            chain = chain.thenCompose(v -> plots.updateAsync(p).thenApply(x -> null));
        }

        return chain.thenApply(v -> {
            // Visual merge runs on main thread.
            var bukkitWorld = Bukkit.getWorld(plot.getWorldName());
            var mvWorld = worldFactory.getCachedWorld(plot.getWorldName()).orElse(null);
            if (bukkitWorld != null && mvWorld != null) {
                int plotSize = worldFactory.effectivePlotSize(mvWorld);
                int roadWidth = worldFactory.effectiveRoadWidth(mvWorld);
                Bukkit.getScheduler().runTask(plugin, () ->
                        PlotMergeOps.applyMerge(bukkitWorld, plot, neighbor, plotSize, roadWidth,
                                worldFactory.plotConfig()));
            }
            logger.info("Player {} merged plots {} ↔ {}", actor.getName(),
                    coordOf(plot), coordOf(neighbor));
            return MergeResult.OK;
        }).exceptionally(ex -> {
            logger.error("Merge failed for {}", actor.getName(), ex);
            return MergeResult.FAILED;
        });
    }

    /**
     * Removes {@code plot} from its merge group. Restores the road slice +
     * walls between {@code plot} and each adjacent plot that was in the
     * same group. No-op if the plot wasn't merged.
     */
    public @NotNull CompletableFuture<Boolean> unmerge(@NotNull Plot plot) {
        var groupId = plot.getMergedGroupIdString();
        if (groupId == null) return CompletableFuture.completedFuture(true);

        // Every plot in the group that's adjacent to `plot` needs its road
        // slice + walls restored.
        var others = new ArrayList<Plot>();
        for (var p : byId.values()) {
            if (p == plot) continue;
            if (!groupId.equals(p.getMergedGroupIdString())) continue;
            if (PlotMergeOps.areAdjacent(plot, p)) others.add(p);
        }

        plot.setMergedGroupId(null);
        return plots.updateAsync(plot).thenApply(v -> {
            // Did removing this plot leave only one plot in the group? If so,
            // also clear that lone plot's group id (a group of one is just a
            // standalone plot).
            var remaining = new ArrayList<Plot>();
            for (var p : byId.values()) {
                if (groupId.equals(p.getMergedGroupIdString())) remaining.add(p);
            }
            if (remaining.size() == 1) {
                var solo = remaining.get(0);
                solo.setMergedGroupId(null);
                plots.updateAsync(solo);
            }

            var bukkitWorld = Bukkit.getWorld(plot.getWorldName());
            var mvWorld = worldFactory.getCachedWorld(plot.getWorldName()).orElse(null);
            if (bukkitWorld != null && mvWorld != null) {
                int plotSize = worldFactory.effectivePlotSize(mvWorld);
                int roadWidth = worldFactory.effectiveRoadWidth(mvWorld);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    for (var other : others) {
                        // Use the claimed material — both plots in this unmerge
                        // are still claimed, so the restored wall stripe should
                        // wear the claimed colour, not the unclaimed default.
                        PlotMergeOps.applyUnmerge(bukkitWorld, plot, other,
                                plotSize, roadWidth, worldFactory.plotConfig(),
                                effectiveClaimedWall(other));
                    }
                });
            }
            return true;
        }).exceptionally(ex -> {
            logger.error("Unmerge failed for plot {}", plot.getId(), ex);
            return false;
        });
    }

    private static @NotNull String coordOf(@NotNull Plot p) {
        return p.getWorldName() + ":" + p.getGridX() + "," + p.getGridZ();
    }
}
