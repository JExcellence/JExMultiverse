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

    private static final String NMS_REGISTRIES  = "net.minecraft.core.registries.Registries";
    private static final String FIELD_LEVEL_STEM = "LEVEL_STEM";

    private FoliaNmsWorldFactory() {
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Entry point
    // ─────────────────────────────────────────────────────────────────────

    static @NotNull World registerWorld(@NotNull String worldName,
                                         World.@NotNull Environment environment) throws Exception {
        final NmsClasses nms = new NmsClasses();
        final Object craftServer = Bukkit.getServer();
        final Object console = nms.cCraftServer.getMethod("getServer").invoke(craftServer);

        verifyMainLevelExists(nms.cMinecraftServer, console);

        final Object stemRegistryKey = field(nms.cRegistries, FIELD_LEVEL_STEM);
        final Object templateStemKey = resolveStemKey(nms.cLevelStem, environment);
        final Object templateStem    = resolveTemplateStem(nms, console, stemRegistryKey, templateStemKey);

        final Object uniqueStemKey = buildUniqueStemKey(nms, stemRegistryKey, worldName);
        final Object access        = buildLevelStorageAccess(nms, craftServer, worldName, templateStemKey);
        final Object primaryData   = buildPrimaryLevelData(nms, access, console);

        checkNameIfSupported(nms.cPrimaryLevelData, primaryData, worldName);

        final Object loadingInfo = buildWorldLoadingInfo(nms, environment, worldName, uniqueStemKey);
        invokCreateLevel(nms, console, templateStem, loadingInfo, access, primaryData);

        final World bukkitWorld = Bukkit.getWorld(worldName);
        if (bukkitWorld == null) {
            throw new NmsWorldLoadException(
                    "createLevel completed but Bukkit.getWorld('" + worldName + "') is null");
        }
        tickEntityManagerIfFolia(nms.cFeatureHooks, bukkitWorld);
        return bukkitWorld;
    }

    // ── registerWorld helpers ─────────────────────────────────────────────

    private static void verifyMainLevelExists(@NotNull Class<?> cMinecraftServer,
                                               @NotNull Object console) throws Exception {
        if (!((Iterable<?>) cMinecraftServer.getMethod("getAllLevels").invoke(console)).iterator().hasNext()) {
            throw new NmsWorldLoadException("Cannot create worlds before main level is created");
        }
    }

    private static @NotNull Object resolveStemKey(@NotNull Class<?> cLevelStem,
                                                    World.@NotNull Environment environment) {
        return switch (environment) {
            case NETHER  -> field(cLevelStem, "NETHER");
            case THE_END -> field(cLevelStem, "END");
            default      -> field(cLevelStem, "OVERWORLD");
        };
    }

    private static @NotNull Object resolveTemplateStem(@NotNull NmsClasses nms,
                                                         @NotNull Object console,
                                                         @NotNull Object stemRegistryKey,
                                                         @NotNull Object templateStemKey) throws Exception {
        final Object registryAccess = nms.cMinecraftServer.getMethod("registryAccess").invoke(console);
        final Object stemRegistry   = invokeLookupOrThrow(registryAccess, stemRegistryKey);
        final Object templateStem   = registryLookup(stemRegistry, templateStemKey, nms.cResourceKey);
        if (templateStem == null) {
            throw new NmsWorldLoadException("Stem template not found in registry");
        }
        return templateStem;
    }

    private static @NotNull Object buildUniqueStemKey(@NotNull NmsClasses nms,
                                                        @NotNull Object stemRegistryKey,
                                                        @NotNull String worldName) throws Exception {
        final Object uniqueId = newIdentifier(nms.cIdentifier, "jexmultiverse", worldName);
        return nms.cResourceKey.getMethod("create", nms.cResourceKey, nms.cIdentifier)
                .invoke(null, stemRegistryKey, uniqueId);
    }

    private static @NotNull Object buildLevelStorageAccess(@NotNull NmsClasses nms,
                                                             @NotNull Object craftServer,
                                                             @NotNull String worldName,
                                                             @NotNull Object templateStemKey) throws Exception {
        final Path worldContainer = ((java.io.File) nms.cCraftServer
                .getMethod("getWorldContainer").invoke(craftServer)).toPath();
        final Object storageSource = nms.cLevelStorageSrc
                .getMethod("createDefault", Path.class).invoke(null, worldContainer);
        return nms.cLevelStorageSrc
                .getMethod("validateAndCreateAccess", String.class, nms.cResourceKey)
                .invoke(storageSource, worldName, templateStemKey);
    }

    private static @NotNull Object buildPrimaryLevelData(@NotNull NmsClasses nms,
                                                           @NotNull Object access,
                                                           @NotNull Object console) throws Exception {
        final Object levelDataResult = nms.cPaperWorldLoader
                .getMethod("getLevelData", nms.cLevelStorageAcc).invoke(null, access);
        final boolean fatalError = (boolean) nms.cLevelDataResult
                .getMethod("fatalError").invoke(levelDataResult);
        if (fatalError) {
            throw new NmsWorldLoadException("PaperWorldLoader.getLevelData reported fatalError");
        }
        final Object dataTag = nms.cLevelDataResult.getMethod("dataTag").invoke(levelDataResult);
        return (dataTag == null)
                ? createNewWorldData(nms.cMain, console, nms.cMinecraftServer)
                : loadExistingWorldData(nms.cLevelStorageSrc, dataTag, console, nms.cMinecraftServer);
    }

    private static void checkNameIfSupported(@NotNull Class<?> cPrimaryLevelData,
                                              @NotNull Object primaryData,
                                              @NotNull String worldName) {
        try {
            cPrimaryLevelData.getMethod("checkName", String.class).invoke(primaryData, worldName);
        } catch (final Exception ignored) {
            // Method may not exist on all Paper builds; non-fatal
        }
    }

    private static @NotNull Object buildWorldLoadingInfo(@NotNull NmsClasses nms,
                                                           World.@NotNull Environment environment,
                                                           @NotNull String worldName,
                                                           @NotNull Object uniqueStemKey) throws Exception {
        final int dimensionInt = switch (environment) {
            case NETHER  -> -1;
            case THE_END -> 1;
            default      -> 0;
        };
        return nms.cWorldLoadingInfo
                .getConstructor(int.class, String.class, String.class, nms.cResourceKey, boolean.class)
                .newInstance(dimensionInt, worldName,
                        environment.name().toLowerCase(Locale.ROOT), uniqueStemKey, true);
    }

    private static void invokCreateLevel(@NotNull NmsClasses nms, @NotNull Object console,
                                          @NotNull Object templateStem, @NotNull Object loadingInfo,
                                          @NotNull Object access, @NotNull Object primaryData) throws Exception {
        nms.cMinecraftServer.getMethod("createLevel",
                        nms.cLevelStem, nms.cWorldLoadingInfo, nms.cLevelStorageAcc, nms.cPrimaryLevelData)
                .invoke(console, templateStem, loadingInfo, access, primaryData);
    }

    private static void tickEntityManagerIfFolia(@NotNull Class<?> cFeatureHooks,
                                                   @NotNull World bukkitWorld) {
        try {
            final Method getHandleM = bukkitWorld.getClass().getMethod("getHandle");
            final Object serverLevel = getHandleM.invoke(bukkitWorld);
            final Class<?> cServerLevel = cls("net.minecraft.server.level.ServerLevel");
            cFeatureHooks.getMethod("tickEntityManager", cServerLevel).invoke(null, serverLevel);
        } catch (final NoSuchMethodException ignored) {
            // Paper without Folia patches — tickEntityManager doesn't exist, fine.
        } catch (final Throwable t) {
            java.util.logging.Logger.getLogger(FoliaNmsWorldFactory.class.getName())
                    .warning("[JExMultiverse] [folia-nms] FeatureHooks.tickEntityManager skipped: " + t.getMessage());
        }
    }

    /** Holds all NMS class references needed by registerWorld. */
    private static final class NmsClasses {
        final Class<?> cMinecraftServer  = cls("net.minecraft.server.MinecraftServer");
        final Class<?> cLevelStem        = cls("net.minecraft.world.level.dimension.LevelStem");
        final Class<?> cResourceKey      = cls("net.minecraft.resources.ResourceKey");
        final Class<?> cIdentifier       = clsAny("net.minecraft.resources.Identifier",
                                                    "net.minecraft.resources.ResourceLocation");
        final Class<?> cRegistries       = cls(NMS_REGISTRIES);
        final Class<?> cCraftServer      = cls("org.bukkit.craftbukkit.CraftServer");
        final Class<?> cPaperWorldLoader = cls("io.papermc.paper.world.PaperWorldLoader");
        final Class<?> cWorldLoadingInfo = cls("io.papermc.paper.world.PaperWorldLoader$WorldLoadingInfo");
        final Class<?> cLevelDataResult  = cls("io.papermc.paper.world.PaperWorldLoader$LevelDataResult");
        final Class<?> cLevelStorageSrc  = cls("net.minecraft.world.level.storage.LevelStorageSource");
        final Class<?> cLevelStorageAcc  = cls("net.minecraft.world.level.storage.LevelStorageSource$LevelStorageAccess");
        final Class<?> cPrimaryLevelData = cls("net.minecraft.world.level.storage.PrimaryLevelData");
        final Class<?> cMain             = cls("net.minecraft.server.Main");
        final Class<?> cFeatureHooks     = cls("io.papermc.paper.FeatureHooks");
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
        final Class<?> cRegistries = cls(NMS_REGISTRIES);
        final Object settings = readField(console, "settings");
        final Object context = readField(console, "worldLoaderContext");
        final Object datapackDimensions = context.getClass().getMethod("datapackDimensions").invoke(context);
        final Object stemRegistryKey = field(cRegistries, FIELD_LEVEL_STEM);
        final Object stemRegistry = invokeLookupOrThrow(datapackDimensions, stemRegistryKey);
        final boolean isDemo = (boolean) cMinecraftServer.getMethod("isDemo").invoke(console);

        // Find createNewWorldData(Settings, DataLoadContext, Registry<LevelStem>, boolean, boolean)
        Method createNewWorldDataM = null;
        for (final Method m : cMain.getDeclaredMethods()) {
            if (m.getName().equals("createNewWorldData") && m.getParameterCount() == 5) {
                createNewWorldDataM = m;
            }
        }
        if (createNewWorldDataM == null) {
            throw new NmsWorldLoadException("Main.createNewWorldData(5-arg) not found");
        }
        makeAccessible(createNewWorldDataM);
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
        final Class<?> cRegistries = cls(NMS_REGISTRIES);
        final Object context = readField(console, "worldLoaderContext");
        final Object dataConfig = context.getClass().getMethod("dataConfiguration").invoke(context);
        final Object datapackDimensions = context.getClass().getMethod("datapackDimensions").invoke(context);
        final Object stemRegistryKey = field(cRegistries, FIELD_LEVEL_STEM);
        final Object stemRegistry = invokeLookupOrThrow(datapackDimensions, stemRegistryKey);
        final Object datapackWorldgen = context.getClass().getMethod("datapackWorldgen").invoke(context);

        // Find getLevelDataAndDimensions(Dynamic, DataConfiguration, Registry, HolderLookup$Provider) → 4 args
        Method m4 = null;
        for (final Method m : cLevelStorageSource.getDeclaredMethods()) {
            if (m.getName().equals("getLevelDataAndDimensions") && m.getParameterCount() == 4) {
                m4 = m;
            }
        }
        if (m4 == null) {
            throw new NmsWorldLoadException("LevelStorageSource.getLevelDataAndDimensions(4-arg) not found");
        }
        makeAccessible(m4);
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
                throw new NmsWorldLoadException("NMS class not found: " + name, ex);
            }
        });
    }

    private static Class<?> clsAny(@NotNull String... candidates) {
        for (final String name : candidates) {
            try { return Class.forName(name); }
            catch (final ClassNotFoundException ignored) { /* try next */ }
        }
        throw new NmsWorldLoadException("None of these NMS classes resolved: " + String.join(", ", candidates));
    }

    private static Object field(@NotNull Class<?> owner, @NotNull String fieldName) {
        return BINDINGS.computeIfAbsent("sfield:" + owner.getName() + "#" + fieldName, k -> {
            try {
                final Field f = owner.getDeclaredField(fieldName);
                makeAccessible(f);
                return f.get(null);
            } catch (final ReflectiveOperationException ex) {
                throw new NmsWorldLoadException("NMS static field missing: " + owner.getSimpleName()
                        + "#" + fieldName, ex);
            }
        });
    }

    private static Object readField(@NotNull Object instance, @NotNull String fieldName) throws Exception {
        Class<?> c = instance.getClass();
        while (c != null) {
            try {
                final Field f = c.getDeclaredField(fieldName);
                makeAccessible(f);
                return f.get(instance);
            } catch (final NoSuchFieldException ignored) {
                c = c.getSuperclass();
            }
        }
        throw new NmsWorldLoadException("Field not found on " + instance.getClass() + ": " + fieldName);
    }

    /** Only calls {@code setAccessible(true)} when the member is not already accessible. */
    private static void makeAccessible(@NotNull java.lang.reflect.AccessibleObject member) {
        if (!java.lang.reflect.Modifier.isPublic(((java.lang.reflect.Member) member).getModifiers())) {
            member.setAccessible(true);
        }
    }

    /**
     * Looks up a value in a {@code Registry} by {@code ResourceKey}. Tries
     * the standard {@code getValue(ResourceKey)} first, then falls back to
     * walking declared methods looking for a single-arg method whose
     * parameter type is assignable from {@code ResourceKey} — this catches
     * Paper-internal renames (e.g. {@code getValueOrThrow}, {@code get},
     * {@code getOrThrow}) and erasure quirks where {@code getMethod} can't
     * resolve the generic-parameter override.
     */
    private static Object registryLookup(@NotNull Object registry,
                                          @NotNull Object resourceKey,
                                          @NotNull Class<?> cResourceKey) throws Exception {
        // Fast path
        try {
            final Method m = registry.getClass().getMethod("getValue", cResourceKey);
            return m.invoke(registry, resourceKey);
        } catch (final NoSuchMethodException ignored) { /* fall through */ }

        // Try common alternatives by name
        for (final String candidateName : new String[]{ "getValue", "getOrThrow", "get", "getValueOrThrow" }) {
            Class<?> c = registry.getClass();
            while (c != null) {
                for (final Method m : c.getDeclaredMethods()) {
                    if (!m.getName().equals(candidateName) || m.getParameterCount() != 1) continue;
                    final Class<?> paramType = m.getParameterTypes()[0];
                    if (!paramType.isAssignableFrom(cResourceKey)) continue;
                    makeAccessible(m);
                    final Object result = m.invoke(registry, resourceKey);
                    if (result instanceof java.util.Optional<?> opt) return opt.orElse(null);
                    return result;
                }
                c = c.getSuperclass();
            }
            // Also walk interfaces
            for (final Class<?> i : registry.getClass().getInterfaces()) {
                for (final Method m : i.getMethods()) {
                    if (!m.getName().equals(candidateName) || m.getParameterCount() != 1) continue;
                    final Class<?> paramType = m.getParameterTypes()[0];
                    if (!paramType.isAssignableFrom(cResourceKey)) continue;
                    makeAccessible(m);
                    final Object result = m.invoke(registry, resourceKey);
                    if (result instanceof java.util.Optional<?> opt) return opt.orElse(null);
                    return result;
                }
            }
        }
        throw new NmsWorldLoadException("No suitable get-by-ResourceKey method found on "
                + registry.getClass().getName());
    }

    /** RegistryAccess#lookupOrThrow(ResourceKey<Registry<X>>) — found by name+arity. */
    private static Object invokeLookupOrThrow(@NotNull Object registryAccess,
                                                @NotNull Object registryKey) throws Exception {
        for (final Method m : registryAccess.getClass().getMethods()) {
            if (m.getName().equals("lookupOrThrow") && m.getParameterCount() == 1) {
                return m.invoke(registryAccess, registryKey);
            }
        }
        throw new NmsWorldLoadException("lookupOrThrow(1-arg) not found on "
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
