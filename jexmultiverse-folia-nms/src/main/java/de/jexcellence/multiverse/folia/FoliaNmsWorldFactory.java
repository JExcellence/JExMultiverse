package de.jexcellence.multiverse.folia;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reflective driver for Paper/Folia 1.21.11's new world-creation API.
 *
 * <p>The Paper 1.21.11 refactor replaced the manual {@code ServerLevel}
 * constructor invocation (16+ params) with a single
 * {@code MinecraftServer#createLevel(LevelStem, WorldLoadingInfo,
 * LevelStorageAccess, PrimaryLevelData)} call that does all the heavy
 * lifting internally — including reading
 * {@code CraftServer#getGenerator(name)} for the chunk generator override.
 *
 * <p>Our job here is:
 * <ol>
 *   <li>Open a {@code LevelStorageAccess} for the world directory;</li>
 *   <li>Call {@code PaperWorldLoader.getLevelData(access)} to read or
 *       initialise the data tag;</li>
 *   <li>Build a {@code PrimaryLevelData} (new world → {@code Main
 *       .createNewWorldData}; existing → {@code LevelStorageSource
 *       .getLevelDataAndDimensions});</li>
 *   <li>Pick a {@code LevelStem} template (OVERWORLD/NETHER/END) from
 *       the registry;</li>
 *   <li>Construct a {@code WorldLoadingInfo} with a <em>unique</em>
 *       {@code stemKey} (so the dimensionKey doesn't collide with the
 *       default world);</li>
 *   <li>Invoke {@code createLevel(...)} which registers the level with
 *       {@code MinecraftServer.levels} and Bukkit;</li>
 *   <li>Return {@code Bukkit.getWorld(name)}.</li>
 * </ol>
 *
 * <p>All NMS access is reflective and cached. Missing bindings throw
 * {@link IllegalStateException} with the bind name so failures point
 * directly at the moved/renamed symbol.
 *
 * @author JExcellence
 * @since 3.5.0
 */
final class FoliaNmsWorldFactory {

    /** Cached reflection bindings — populated lazily, then immutable. */
    private static final ConcurrentHashMap<String, Object> BINDINGS = new ConcurrentHashMap<>();

    private FoliaNmsWorldFactory() {
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Entry point
    // ─────────────────────────────────────────────────────────────────────

    static @NotNull World registerWorld(@NotNull String worldName,
                                         World.@NotNull Environment environment) throws Exception {

        // ── Class lookups ──────────────────────────────────────────────
        final Class<?> cMinecraftServer  = cls("net.minecraft.server.MinecraftServer");
        final Class<?> cLevelStem        = cls("net.minecraft.world.level.dimension.LevelStem");
        final Class<?> cResourceKey      = cls("net.minecraft.resources.ResourceKey");
        final Class<?> cIdentifier       = clsAny(
                "net.minecraft.resources.Identifier",
                "net.minecraft.resources.ResourceLocation");
        final Class<?> cRegistries       = cls("net.minecraft.core.registries.Registries");
        final Class<?> cRegistry         = cls("net.minecraft.core.Registry");
        final Class<?> cCraftServer      = cls("org.bukkit.craftbukkit.CraftServer");
        final Class<?> cPaperWorldLoader = cls("io.papermc.paper.world.PaperWorldLoader");
        final Class<?> cWorldLoadingInfo = cls("io.papermc.paper.world.PaperWorldLoader$WorldLoadingInfo");
        final Class<?> cLevelDataResult  = cls("io.papermc.paper.world.PaperWorldLoader$LevelDataResult");
        final Class<?> cLevelStorageSrc  = cls("net.minecraft.world.level.storage.LevelStorageSource");
        final Class<?> cLevelStorageAcc  = cls("net.minecraft.world.level.storage.LevelStorageSource$LevelStorageAccess");
        final Class<?> cPrimaryLevelData = cls("net.minecraft.world.level.storage.PrimaryLevelData");
        final Class<?> cMain             = cls("net.minecraft.server.Main");
        final Class<?> cFeatureHooks     = cls("io.papermc.paper.FeatureHooks");

        // ── CraftServer + MinecraftServer ──────────────────────────────
        final Object craftServer = Bukkit.getServer();
        final Object console = cCraftServer.getMethod("getServer").invoke(craftServer);

        // Preflight: main level must already exist
        if (!((Iterable<?>) cMinecraftServer.getMethod("getAllLevels").invoke(console)).iterator().hasNext()) {
            throw new IllegalStateException("Cannot create worlds before main level is created");
        }

        // ── Resolve the stem template + a UNIQUE stemKey for this world ─
        // The stem template provides the dimension type and the vanilla
        // generator default; our ChunkGenerator override applies on top
        // via CraftServer.getGenerator(worldName) inside createLevel.
        //
        // The stemKey identifier must be UNIQUE per world (it builds the
        // dimensionKey for the levels map). Using LevelStem.OVERWORLD
        // verbatim would collide with the default world.
        final Object stemRegistryKey = field(cRegistries, "LEVEL_STEM");
        final Object templateStemKey = switch (environment) {
            case NETHER  -> field(cLevelStem, "NETHER");
            case THE_END -> field(cLevelStem, "END");
            default      -> field(cLevelStem, "OVERWORLD");
        };
        final Object registryAccess = cMinecraftServer.getMethod("registryAccess").invoke(console);
        final Object stemRegistry = invokeLookupOrThrow(registryAccess, stemRegistryKey);
        final Object templateStem = cRegistry.getMethod("getValue", cResourceKey)
                .invoke(stemRegistry, templateStemKey);
        if (templateStem == null) {
            throw new IllegalStateException("Stem template not found in registry for env " + environment);
        }

        // Unique stem key for this world: jexmultiverse:<worldName>
        // (different from the template stemKey, so the OVERWORLD-special-case
        // inside MinecraftServer#createLevel doesn't fire for our worlds)
        final Object uniqueIdentifier = newIdentifier(cIdentifier, "jexmultiverse", worldName);
        final Object uniqueStemKey = cResourceKey.getMethod("create", cResourceKey, cIdentifier)
                .invoke(null, stemRegistryKey, uniqueIdentifier);

        // ── Build LevelStorageAccess ───────────────────────────────────
        final Path worldContainer = ((java.io.File) cCraftServer.getMethod("getWorldContainer").invoke(craftServer)).toPath();
        final Object storageSource = cLevelStorageSrc.getMethod("createDefault", Path.class)
                .invoke(null, worldContainer);
        // validateAndCreateAccess(String, ResourceKey<LevelStem>)
        final Object access = cLevelStorageSrc.getMethod("validateAndCreateAccess", String.class, cResourceKey)
                .invoke(storageSource, worldName, templateStemKey);

        // ── Read level data (or detect "new world") via PaperWorldLoader ─
        final Object levelDataResult = cPaperWorldLoader.getMethod("getLevelData", cLevelStorageAcc)
                .invoke(null, access);
        final boolean fatalError = (boolean) cLevelDataResult.getMethod("fatalError").invoke(levelDataResult);
        if (fatalError) {
            throw new IllegalStateException("PaperWorldLoader.getLevelData reported fatalError for '" + worldName + "'");
        }
        final Object dataTag = cLevelDataResult.getMethod("dataTag").invoke(levelDataResult);

        // ── Build/load PrimaryLevelData ────────────────────────────────
        final Object primaryLevelData = (dataTag == null)
                ? createNewWorldData(cMain, console, cMinecraftServer)
                : loadExistingWorldData(cLevelStorageSrc, dataTag, console, cMinecraftServer);

        // primaryLevelData.checkName(name)
        try {
            cPrimaryLevelData.getMethod("checkName", String.class).invoke(primaryLevelData, worldName);
        } catch (final NoSuchMethodException ignored) {
            // Method may not exist on all Paper builds; non-fatal
        }

        // ── Build WorldLoadingInfo ─────────────────────────────────────
        // WorldLoadingInfo(int dimension, String name, String worldType,
        //                  ResourceKey<LevelStem> stemKey, boolean enabled)
        final int dimensionInt = switch (environment) {
            case NETHER  -> -1;
            case THE_END -> 1;
            default      -> 0;
        };
        final String worldTypeStr = environment.name().toLowerCase(Locale.ROOT);
        final Object loadingInfo = cWorldLoadingInfo
                .getConstructor(int.class, String.class, String.class, cResourceKey, boolean.class)
                .newInstance(dimensionInt, worldName, worldTypeStr, uniqueStemKey, true);

        // ── createLevel(stem, info, access, primaryLevelData) ──────────
        cMinecraftServer.getMethod("createLevel",
                        cLevelStem, cWorldLoadingInfo, cLevelStorageAcc, cPrimaryLevelData)
                .invoke(console, templateStem, loadingInfo, access, primaryLevelData);

        // ── Post-init: tickEntityManager on Folia (no-op on Paper) ─────
        // After createLevel, the new ServerLevel is registered. We need
        // to fetch it to call FeatureHooks.tickEntityManager (Folia
        // start-region-tick signal). Get via reflection from the levels
        // map.
        final World bukkitWorld = Bukkit.getWorld(worldName);
        if (bukkitWorld == null) {
            throw new IllegalStateException(
                    "createLevel completed but Bukkit.getWorld('" + worldName + "') is null — registration didn't propagate");
        }

        // Get the underlying ServerLevel via CraftWorld#getHandle (reflect)
        try {
            final Object craftWorld = bukkitWorld;
            final Method getHandleM = bukkitWorld.getClass().getMethod("getHandle");
            final Object serverLevel = getHandleM.invoke(craftWorld);
            final Class<?> cServerLevel = serverLevel.getClass();
            // FeatureHooks.tickEntityManager(ServerLevel)
            try {
                cFeatureHooks.getMethod("tickEntityManager", cServerLevel.getSuperclass() != null ? cls("net.minecraft.server.level.ServerLevel") : cServerLevel)
                        .invoke(null, serverLevel);
            } catch (final NoSuchMethodException notFolia) {
                // Paper without Folia patches: no tickEntityManager — fine.
            }
        } catch (final Throwable t) {
            // Non-fatal: world is registered, FeatureHooks polish failed.
            System.err.println("[JExMultiverse] [folia-nms] FeatureHooks.tickEntityManager skipped: " + t.getMessage());
        }

        return bukkitWorld;
    }

    // ─────────────────────────────────────────────────────────────────────
    //  PrimaryLevelData helpers
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Equivalent of:
     * <pre>{@code
     * Main.createNewWorldData(
     *     ((DedicatedServer) server).settings,
     *     server.worldLoaderContext,
     *     server.worldLoaderContext.datapackDimensions().lookupOrThrow(Registries.LEVEL_STEM),
     *     server.isDemo(),
     *     server.options.has("bonusChest")
     * ).cookie()
     * }</pre>
     */
    private static Object createNewWorldData(@NotNull Class<?> cMain,
                                              @NotNull Object console,
                                              @NotNull Class<?> cMinecraftServer) throws Exception {
        final Class<?> cDedicatedServer = cls("net.minecraft.server.dedicated.DedicatedServer");
        final Class<?> cRegistries = cls("net.minecraft.core.registries.Registries");
        final Object settings = readField(console, "settings");
        final Object context = readField(console, "worldLoaderContext");
        final Object datapackDimensions = context.getClass().getMethod("datapackDimensions").invoke(context);
        final Object stemRegistryKey = field(cRegistries, "LEVEL_STEM");
        final Object stemRegistry = invokeLookupOrThrow(datapackDimensions, stemRegistryKey);
        final boolean isDemo = (boolean) cMinecraftServer.getMethod("isDemo").invoke(console);

        // Find createNewWorldData(Settings, DataLoadContext, Registry<LevelStem>, boolean, boolean)
        Method createNewWorldDataM = null;
        for (final Method m : cMain.getDeclaredMethods()) {
            if (m.getName().equals("createNewWorldData") && m.getParameterCount() == 5) {
                createNewWorldDataM = m;
                break;
            }
        }
        if (createNewWorldDataM == null) {
            throw new IllegalStateException("Main.createNewWorldData(5-arg) not found");
        }
        createNewWorldDataM.setAccessible(true);
        final Object cookieHolder = createNewWorldDataM.invoke(null,
                settings, context, stemRegistry, isDemo, /*bonusChest*/ false);
        // .cookie() returns the WorldData (== PrimaryLevelData)
        final Method cookieM = cookieHolder.getClass().getMethod("cookie");
        return cookieM.invoke(cookieHolder);
    }

    /**
     * Equivalent of:
     * <pre>{@code
     * (PrimaryLevelData) LevelStorageSource.getLevelDataAndDimensions(
     *     dataTag,
     *     server.worldLoaderContext.dataConfiguration(),
     *     server.worldLoaderContext.datapackDimensions().lookupOrThrow(Registries.LEVEL_STEM),
     *     server.worldLoaderContext.datapackWorldgen()
     * ).worldData()
     * }</pre>
     */
    private static Object loadExistingWorldData(@NotNull Class<?> cLevelStorageSource,
                                                  @NotNull Object dataTag,
                                                  @NotNull Object console,
                                                  @NotNull Class<?> cMinecraftServer) throws Exception {
        final Class<?> cRegistries = cls("net.minecraft.core.registries.Registries");
        final Object context = readField(console, "worldLoaderContext");
        final Object dataConfig = context.getClass().getMethod("dataConfiguration").invoke(context);
        final Object datapackDimensions = context.getClass().getMethod("datapackDimensions").invoke(context);
        final Object stemRegistryKey = field(cRegistries, "LEVEL_STEM");
        final Object stemRegistry = invokeLookupOrThrow(datapackDimensions, stemRegistryKey);
        final Object datapackWorldgen = context.getClass().getMethod("datapackWorldgen").invoke(context);

        // Find getLevelDataAndDimensions(Dynamic, DataConfiguration, Registry, HolderLookup$Provider) → 4 args
        Method m4 = null;
        for (final Method m : cLevelStorageSource.getDeclaredMethods()) {
            if (m.getName().equals("getLevelDataAndDimensions") && m.getParameterCount() == 4) {
                m4 = m;
                break;
            }
        }
        if (m4 == null) {
            throw new IllegalStateException("LevelStorageSource.getLevelDataAndDimensions(4-arg) not found");
        }
        m4.setAccessible(true);
        final Object levelDataAndDimensions = m4.invoke(null, dataTag, dataConfig, stemRegistry, datapackWorldgen);
        return levelDataAndDimensions.getClass().getMethod("worldData").invoke(levelDataAndDimensions);
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Reflection helpers
    // ─────────────────────────────────────────────────────────────────────

    private static Class<?> cls(@NotNull String name) {
        return (Class<?>) BINDINGS.computeIfAbsent("cls:" + name, k -> {
            try { return Class.forName(name); }
            catch (final ClassNotFoundException ex) {
                throw new IllegalStateException("NMS class not found: " + name, ex);
            }
        });
    }

    private static Class<?> clsAny(@NotNull String... candidates) {
        for (final String name : candidates) {
            try { return Class.forName(name); }
            catch (final ClassNotFoundException ignored) { /* try next */ }
        }
        throw new IllegalStateException("None of these NMS classes resolved: " + String.join(", ", candidates));
    }

    private static Object field(@NotNull Class<?> owner, @NotNull String fieldName) {
        return BINDINGS.computeIfAbsent("sfield:" + owner.getName() + "#" + fieldName, k -> {
            try {
                final Field f = owner.getDeclaredField(fieldName);
                f.setAccessible(true);
                return f.get(null);
            } catch (final ReflectiveOperationException ex) {
                throw new IllegalStateException("NMS static field missing: " + owner.getSimpleName()
                        + "#" + fieldName, ex);
            }
        });
    }

    private static Object readField(@NotNull Object instance, @NotNull String fieldName) throws Exception {
        Class<?> c = instance.getClass();
        while (c != null) {
            try {
                final Field f = c.getDeclaredField(fieldName);
                f.setAccessible(true);
                return f.get(instance);
            } catch (final NoSuchFieldException ignored) {
                c = c.getSuperclass();
            }
        }
        throw new IllegalStateException("Field not found on " + instance.getClass() + ": " + fieldName);
    }

    /** RegistryAccess#lookupOrThrow(ResourceKey<Registry<X>>) — found by name+arity. */
    private static Object invokeLookupOrThrow(@NotNull Object registryAccess,
                                                @NotNull Object registryKey) throws Exception {
        for (final Method m : registryAccess.getClass().getMethods()) {
            if (m.getName().equals("lookupOrThrow") && m.getParameterCount() == 1) {
                return m.invoke(registryAccess, registryKey);
            }
        }
        throw new IllegalStateException("lookupOrThrow(1-arg) not found on "
                + registryAccess.getClass().getName());
    }

    /** Build a Mojang {@code Identifier} / {@code ResourceLocation}. */
    private static Object newIdentifier(@NotNull Class<?> cIdentifier,
                                          @NotNull String namespace,
                                          @NotNull String path) throws Exception {
        // Try static factories first, fall back to ctor
        try {
            return cIdentifier.getMethod("fromNamespaceAndPath", String.class, String.class)
                    .invoke(null, namespace, path);
        } catch (final NoSuchMethodException ignored) {
            try {
                return cIdentifier.getConstructor(String.class, String.class)
                        .newInstance(namespace, path);
            } catch (final NoSuchMethodException ignored2) {
                return cIdentifier.getMethod("tryBuild", String.class, String.class)
                        .invoke(null, namespace, path);
            }
        }
    }
}
