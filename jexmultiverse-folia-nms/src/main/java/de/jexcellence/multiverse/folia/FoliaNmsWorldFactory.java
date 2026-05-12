package de.jexcellence.multiverse.folia;

import com.google.common.collect.ImmutableList;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.generator.ChunkGenerator;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Reflective recreation of {@code CraftServer#createWorld}'s pre-Folia-patch
 * body. Constructs a {@code ServerLevel} via NMS, registers it with
 * {@code MinecraftServer#addLevel}, and runs the post-registration init
 * steps so the world behaves identically to one loaded at server boot.
 *
 * <p>Mirrors the implementation in
 * <a href="https://github.com/TheNextLvl-net/worlds/blob/main/version-specifics/v26.1.2/src/main/java/net/thenextlvl/worlds/versions/v26_1_2/SimpleVersionHandler.java">TheNextLvl/worlds#SimpleVersionHandler.createAsync</a>
 * (GPL-3.0). This is a clean-room reimplementation against the same
 * public NMS surface (the Paper-internal classes are not GPL-protected,
 * only the original Java source is). Behaviour is intentionally identical.
 *
 * <p>All NMS lookups are cached in a {@link ConcurrentHashMap} keyed by
 * a simple identifier, populated lazily on first use. Failure to resolve
 * any binding throws {@link IllegalStateException} with the bind name —
 * the caller surfaces that to operators rather than silently failing.
 *
 * <p>Pinned to Paper 1.21.x internals. The following symbols must exist:
 * <ul>
 *   <li>{@code net.minecraft.server.MinecraftServer} — fields
 *       {@code executor}, {@code storageSource}, {@code worldLoaderContext};
 *       methods {@code addLevel}, {@code initWorld}, {@code prepareLevel},
 *       {@code registryAccess}, {@code getWorldData}</li>
 *   <li>{@code net.minecraft.server.level.ServerLevel} — 16-arg constructor</li>
 *   <li>{@code net.minecraft.world.level.dimension.LevelStem} — constants
 *       OVERWORLD/NETHER/END</li>
 *   <li>{@code net.minecraft.world.level.levelgen.WorldGenSettings},
 *       {@code WorldDimensions}, {@code WorldOptions}</li>
 *   <li>{@code io.papermc.paper.world.PaperWorldLoader} — static methods
 *       {@code dimensionKey}, {@code loadWorldData}; nested record
 *       {@code LoadedWorldData}</li>
 *   <li>{@code io.papermc.paper.FeatureHooks} — static
 *       {@code tickEntityManager} (no-op on Paper, required on Folia)</li>
 *   <li>{@code org.bukkit.craftbukkit.CraftServer},
 *       {@code org.bukkit.craftbukkit.generator.CraftWorldInfo}</li>
 * </ul>
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

    /**
     * Build and register a {@code ServerLevel} for the given world name,
     * then return its Bukkit {@link World} wrapper.
     *
     * <p>Must be invoked on the server's global region thread (Folia) or
     * main thread (Paper). The caller's
     * {@link FoliaRuntimeWorldLoader#loadWorld} handles the hop.
     */
    static @NotNull World registerWorld(@NotNull String worldName,
                                         World.@NotNull Environment environment) throws Exception {

        // Resolve classes once + cache
        final Class<?> cMinecraftServer  = cls("net.minecraft.server.MinecraftServer");
        final Class<?> cServerLevel      = cls("net.minecraft.server.level.ServerLevel");
        final Class<?> cLevelStem        = cls("net.minecraft.world.level.dimension.LevelStem");
        final Class<?> cResourceKey      = cls("net.minecraft.resources.ResourceKey");
        final Class<?> cRegistries       = cls("net.minecraft.core.registries.Registries");
        final Class<?> cBiomeManager     = cls("net.minecraft.world.level.biome.BiomeManager");
        final Class<?> cCraftServer      = cls("org.bukkit.craftbukkit.CraftServer");
        final Class<?> cPaperWorldLoader = cls("io.papermc.paper.world.PaperWorldLoader");
        final Class<?> cWorldGenSettings = cls("net.minecraft.world.level.levelgen.WorldGenSettings");
        final Class<?> cWorldOptions     = cls("net.minecraft.world.level.levelgen.WorldOptions");
        final Class<?> cWorldDimensions  = cls("net.minecraft.world.level.levelgen.WorldDimensions");
        final Class<?> cWorldDimComplete = cls("net.minecraft.world.level.levelgen.WorldDimensions$Complete");
        final Class<?> cDedSrvProps      = cls("net.minecraft.server.dedicated.DedicatedServerProperties");
        final Class<?> cWorldDimData     = cls("net.minecraft.server.dedicated.DedicatedServerProperties$WorldDimensionData");
        final Class<?> cIdentifier       = cls("net.minecraft.resources.Identifier"); // Paper-mapped (= ResourceLocation in vanilla)
        final Class<?> cRegistry         = cls("net.minecraft.core.Registry");
        final Class<?> cSavedDataStorage = cls("net.minecraft.world.level.storage.SavedDataStorage");
        final Class<?> cLevelResource    = cls("net.minecraft.world.level.storage.LevelResource");
        final Class<?> cCraftWorldInfo   = cls("org.bukkit.craftbukkit.generator.CraftWorldInfo");
        final Class<?> cFeatureHooks     = cls("io.papermc.paper.FeatureHooks");
        final Class<?> cPrimaryLevelData = cls("net.minecraft.world.level.storage.PrimaryLevelData");

        // CraftServer + MinecraftServer
        final Object craftServer = Bukkit.getServer();
        final Method getServer = cCraftServer.getMethod("getServer");
        final Object console = getServer.invoke(craftServer);

        // Preflight: main level must exist
        final Method getAllLevels = cMinecraftServer.getMethod("getAllLevels");
        if (!((Iterable<?>) getAllLevels.invoke(console)).iterator().hasNext()) {
            throw new IllegalStateException("Cannot create worlds before main level is created");
        }

        // Build identifier from the simple world name (namespace=minecraft, path=name)
        final Object worldIdentifier = newIdentifier(cIdentifier, "minecraft", worldName);
        // ResourceKey<LevelStem> resourceKey = ResourceKey.create(Registries.LEVEL_STEM, identifier)
        final Object levelStemRegistryKey = field(cRegistries, "LEVEL_STEM");
        final Object resourceKey = invokeStatic(cResourceKey, "create",
                new Class[]{ cResourceKey, cIdentifier },
                levelStemRegistryKey, worldIdentifier);

        // dimensionKey = PaperWorldLoader.dimensionKey(resourceKey)
        final Object dimensionKey = invokeStatic(cPaperWorldLoader, "dimensionKey",
                new Class[]{ cResourceKey }, resourceKey);

        // actualDimension = LevelStem.OVERWORLD/NETHER/END (these are ResourceKey<LevelStem> constants)
        final Object actualDimension = switch (environment) {
            case NETHER  -> field(cLevelStem, "NETHER");
            case THE_END -> field(cLevelStem, "END");
            default      -> field(cLevelStem, "OVERWORLD");
        };

        // context = console.worldLoaderContext (field), then datapackDimensions() + lookupOrThrow
        final Object context = readField(console, "worldLoaderContext");
        final Method datapackDimensions = context.getClass().getMethod("datapackDimensions");
        Object registryAccess = datapackDimensions.invoke(context);
        Object levelStemRegistry = invokeOnRegistryAccess(registryAccess, levelStemRegistryKey);

        // Build a fresh WorldGenSettings (we skip the "load existing" branch — new world)
        final long seed = ThreadLocalRandom.current().nextLong();
        final Object worldOptions = newWorldOptions(cWorldOptions, seed, /*generateStructures*/ false, /*bonusChest*/ false);

        // generatorSettings JSON (empty) + levelType ("normal") → WorldDimensionData
        final JsonObject genSettingsJson = new JsonObject();
        final String levelTypeName = "normal";
        final Object worldDimensionData = newInstance(cWorldDimData,
                new Class[]{ JsonObject.class, String.class },
                genSettingsJson, levelTypeName);

        // WorldDimensions worldDimensions = properties.create(context.datapackWorldgen())
        final Method datapackWorldgen = context.getClass().getMethod("datapackWorldgen");
        final Object datapackWg = datapackWorldgen.invoke(context);
        Object worldDimensions = worldDimensionData.getClass()
                .getMethod("create", datapackWg.getClass().getInterfaces().length > 0
                        ? findHolderLookupProviderInterface(datapackWg) : datapackWg.getClass())
                .invoke(worldDimensionData, datapackWg);

        // complete = worldDimensions.bake(contextLevelStemRegistry)
        final Method bake = cWorldDimensions.getMethod("bake", cRegistry);
        final Object complete = bake.invoke(worldDimensions, levelStemRegistry);

        // genSettings = new WorldGenSettings(worldOptions, worldDimensions)
        final Constructor<?> wgsCtor = cWorldGenSettings.getConstructor(cWorldOptions, cWorldDimensions);
        final Object genSettings = wgsCtor.newInstance(worldOptions, worldDimensions);

        // registryAccess from complete.dimensionsRegistryAccess()
        final Method dimRegAccess = cWorldDimComplete.getMethod("dimensionsRegistryAccess");
        registryAccess = dimRegAccess.invoke(complete);
        levelStemRegistry = invokeOnRegistryAccess(registryAccess, levelStemRegistryKey);

        // loadedWorldData = PaperWorldLoader.loadWorldData(console, dimensionKey, worldName)
        final Method loadWorldDataM = cPaperWorldLoader.getMethod("loadWorldData",
                cMinecraftServer, cResourceKey, String.class);
        final Object loadedWorldData = loadWorldDataM.invoke(null, console, dimensionKey, worldName);

        // primaryLevelData (for isDebugWorld + enabledFeatures)
        final Method getWorldData = cMinecraftServer.getMethod("getWorldData");
        final Object primaryLevelData = getWorldData.invoke(console);
        final boolean isDebugWorld = (boolean) cPrimaryLevelData.getMethod("isDebugWorld").invoke(primaryLevelData);
        final Object enabledFeatures = cPrimaryLevelData.getMethod("enabledFeatures").invoke(primaryLevelData);

        // biomeZoomSeed = BiomeManager.obfuscateSeed(genSettings.options().seed())
        final Method optionsM = cWorldGenSettings.getMethod("options");
        final Object effectiveOptions = optionsM.invoke(genSettings);
        final long effectiveSeed = (long) cWorldOptions.getMethod("seed").invoke(effectiveOptions);
        final long biomeZoomSeed = (long) cBiomeManager.getMethod("obfuscateSeed", long.class)
                .invoke(null, effectiveSeed);

        // customStem = genSettings.dimensions().get(actualDimension).orElseGet(registry.getValue(actualDimension))
        final Method dimensionsM = cWorldGenSettings.getMethod("dimensions");
        final Object dimensionsObj = dimensionsM.invoke(genSettings);
        Object customStem = findStem(dimensionsObj, actualDimension);
        if (customStem == null) {
            customStem = cRegistry.getMethod("getValue", cResourceKey).invoke(levelStemRegistry, actualDimension);
        }
        if (customStem == null) {
            throw new IllegalStateException("Missing LevelStem for " + actualDimension);
        }

        // Resolve dimension type + chunk generator (from the stem)
        final Method stemType = cLevelStem.getMethod("type");
        final Object dimTypeHolder = stemType.invoke(customStem);
        final Method holderValue = dimTypeHolder.getClass().getMethod("value");
        final Object dimensionType = holderValue.invoke(dimTypeHolder);
        final Method stemGenerator = cLevelStem.getMethod("generator");
        final Object vanillaGenerator = stemGenerator.invoke(customStem);

        // Build CraftWorldInfo (Bukkit-facing world metadata) for biomeProvider lookup
        // Signature (Paper 1.21.x):
        //   CraftWorldInfo(String name, NamespacedKey worldKey, long seed,
        //                  FeatureFlagSet enabledFeatures, World.Environment env,
        //                  DimensionType dimType, Object generator,
        //                  RegistryAccess registryAccess, UUID uuid)
        final String bukkitName  = (String) loadedWorldData.getClass().getMethod("bukkitName").invoke(loadedWorldData);
        final Object loadedUuid  = loadedWorldData.getClass().getMethod("uuid").invoke(loadedWorldData);

        final NamespacedKey nsKey = NamespacedKey.minecraft(worldName);
        final Object craftWorldInfo = newCraftWorldInfo(cCraftWorldInfo, bukkitName, nsKey, effectiveSeed,
                enabledFeatures, environment, dimensionType, vanillaGenerator,
                cMinecraftServer.getMethod("registryAccess").invoke(console), loadedUuid);

        // Resolve chunk generator + biome provider via CraftServer reflection
        // (CraftBukkit classes aren't on the paper-api compile classpath).
        ChunkGenerator chunkGenerator = (ChunkGenerator) cCraftServer
                .getMethod("getGenerator", String.class).invoke(craftServer, worldName);
        org.bukkit.generator.BiomeProvider biomeProvider = (org.bukkit.generator.BiomeProvider)
                cCraftServer.getMethod("getBiomeProvider", String.class).invoke(craftServer, worldName);
        if (biomeProvider == null && chunkGenerator != null) {
            biomeProvider = chunkGenerator.getDefaultBiomeProvider(
                    (org.bukkit.generator.WorldInfo) craftWorldInfo);
        }

        // SavedDataStorage(dimensionPath/data, dataFixer, registryAccess)
        final Object storageSource = readField(console, "storageSource");
        final Method getDimensionPath = storageSource.getClass().getMethod("getDimensionPath", cResourceKey);
        final Path dimDir = (Path) getDimensionPath.invoke(storageSource, dimensionKey);
        final Object dataLevelRes = field(cLevelResource, "DATA");
        final String dataResId = (String) cLevelResource.getMethod("id").invoke(dataLevelRes);
        final Path dataPath = dimDir.resolve(dataResId);
        final Object dataFixer = cMinecraftServer.getMethod("getFixerUpper").invoke(console);
        final Object consoleRegistryAccess = cMinecraftServer.getMethod("registryAccess").invoke(console);
        final Constructor<?> sdsCtor = findConstructor(cSavedDataStorage, 3); // (Path, DataFixer, RegistryAccess)
        final Object savedDataStorage = sdsCtor.newInstance(dataPath, dataFixer, consoleRegistryAccess);

        // Persist WorldGenSettings into the SavedDataStorage (TheNextLvl does this)
        final Object wgsType = field(cWorldGenSettings, "TYPE");
        cSavedDataStorage.getMethod("set", wgsType.getClass(), Object.class)
                .invoke(savedDataStorage, wgsType,
                        wgsCtor.newInstance(effectiveOptions, dimensionsM.invoke(genSettings)));

        // CustomSpawner list — empty for non-overworld
        // TheNextLvl uses a non-empty list for overworld (Phantom, Patrol, Cat, Siege, WanderingTrader);
        // we ship an empty list for now — those are nice-to-have, not blocking creation. JExMultiverse's
        // void worlds don't need vanilla mob-cap spawners.
        final List<?> customSpawners = ImmutableList.of();

        // ─── Construct ServerLevel ────────────────────────────────────────
        final Constructor<?> levelCtor = findServerLevelConstructor(cServerLevel);
        levelCtor.setAccessible(true);
        final Object serverLevel = levelCtor.newInstance(
                console,                                                            // MinecraftServer
                readField(console, "executor"),                                     // Executor
                storageSource,                                                      // LevelStorageAccess
                genSettings,                                                        // WorldGenSettings
                dimensionKey,                                                       // ResourceKey<Level>
                customStem,                                                         // LevelStem
                isDebugWorld,                                                       // boolean isDebug
                biomeZoomSeed,                                                      // long
                customSpawners,                                                     // List<CustomSpawner>
                true,                                                               // boolean tickTime
                actualDimension,                                                    // ResourceKey<LevelStem>
                environment,                                                        // World.Environment
                chunkGenerator,                                                     // ChunkGenerator
                biomeProvider,                                                      // BiomeProvider
                savedDataStorage,                                                   // SavedDataStorage
                loadedWorldData                                                     // LoadedWorldData
        );

        // ─── Register with MinecraftServer ────────────────────────────────
        cMinecraftServer.getMethod("addLevel", cServerLevel).invoke(console, serverLevel);

        // initWorld(serverLevel, @Nullable WorldCreator)
        try {
            cMinecraftServer.getMethod("initWorld", cServerLevel, org.bukkit.WorldCreator.class)
                    .invoke(console, serverLevel, (Object) null);
        } catch (final NoSuchMethodException ignored) {
            // Older Paper builds had a single-arg initWorld; try that
            cMinecraftServer.getMethod("initWorld", cServerLevel).invoke(console, serverLevel);
        }

        cServerLevel.getMethod("setSpawnSettings", boolean.class).invoke(serverLevel, true);

        // FeatureHooks.tickEntityManager — Folia-only (no-op on Paper)
        try {
            cFeatureHooks.getMethod("tickEntityManager", cServerLevel).invoke(null, serverLevel);
        } catch (final NoSuchMethodException notFolia) {
            // FeatureHooks exists on Paper but tickEntityManager is a Folia-specific
            // signal — fine to skip.
        }

        cMinecraftServer.getMethod("prepareLevel", cServerLevel).invoke(console, serverLevel);

        // Return Bukkit wrapper
        final Object bukkitWorld = cServerLevel.getMethod("getWorld").invoke(serverLevel);
        if (bukkitWorld == null) {
            throw new IllegalStateException("ServerLevel.getWorld() returned null after registration");
        }
        return (World) bukkitWorld;
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Reflection helpers (all binding-cached)
    // ─────────────────────────────────────────────────────────────────────

    private static Class<?> cls(@NotNull String name) {
        return (Class<?>) BINDINGS.computeIfAbsent("cls:" + name, k -> {
            try { return Class.forName(name); }
            catch (final ClassNotFoundException ex) {
                throw new IllegalStateException("NMS class not found: " + name, ex);
            }
        });
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

    private static Object invokeStatic(@NotNull Class<?> owner, @NotNull String method,
                                        @NotNull Class<?>[] paramTypes,
                                        @NotNull Object... args) throws Exception {
        final Method m = owner.getMethod(method, paramTypes);
        return m.invoke(null, args);
    }

    private static Object newInstance(@NotNull Class<?> owner,
                                       @NotNull Class<?>[] paramTypes,
                                       @NotNull Object... args) throws Exception {
        final Constructor<?> c = owner.getConstructor(paramTypes);
        c.setAccessible(true);
        return c.newInstance(args);
    }

    private static Constructor<?> findConstructor(@NotNull Class<?> owner, int paramCount) {
        for (final Constructor<?> c : owner.getDeclaredConstructors()) {
            if (c.getParameterCount() == paramCount) {
                c.setAccessible(true);
                return c;
            }
        }
        throw new IllegalStateException("No " + paramCount + "-arg constructor on " + owner.getName());
    }

    /**
     * Locate the 16-argument {@code ServerLevel} constructor by parameter
     * count. Pinning by exact signature would be brittle; arity + the call
     * site's type list is enough.
     */
    private static Constructor<?> findServerLevelConstructor(@NotNull Class<?> serverLevel) {
        for (final Constructor<?> c : serverLevel.getDeclaredConstructors()) {
            if (c.getParameterCount() == 16) return c;
        }
        throw new IllegalStateException("ServerLevel 16-arg constructor not found — Paper version mismatch?");
    }

    /** Builds a Mojang {@code Identifier} ({@code ResourceLocation}) via reflection. */
    private static Object newIdentifier(@NotNull Class<?> cIdentifier,
                                         @NotNull String namespace,
                                         @NotNull String path) throws Exception {
        // Identifier has a static factory `tryBuild` / `tryParse` / direct ctor depending on version.
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

    private static Object newWorldOptions(@NotNull Class<?> cWorldOptions,
                                           long seed, boolean structures, boolean bonusChest) throws Exception {
        // Paper 1.21.x: new WorldOptions(long seed, boolean generateStructures, boolean bonusChest)
        return cWorldOptions.getConstructor(long.class, boolean.class, boolean.class)
                .newInstance(seed, structures, bonusChest);
    }

    /** Walks RegistryAccess#lookupOrThrow(ResourceKey<Registry<X>>). */
    private static Object invokeOnRegistryAccess(@NotNull Object registryAccess,
                                                  @NotNull Object registryKey) throws Exception {
        // RegistryAccess interface declares lookupOrThrow(ResourceKey<Registry<E>>)
        for (final Method m : registryAccess.getClass().getMethods()) {
            if (m.getName().equals("lookupOrThrow") && m.getParameterCount() == 1) {
                return m.invoke(registryAccess, registryKey);
            }
        }
        throw new IllegalStateException("RegistryAccess.lookupOrThrow not found on "
                + registryAccess.getClass().getName());
    }

    /** Walks {@code dimensions.get(actualDimension)} returning the contained LevelStem or null. */
    @Nullable
    private static Object findStem(@NotNull Object dimensionsObj, @NotNull Object actualDimension) throws Exception {
        // dimensions.get(...) returns Optional<LevelStem>
        Method getM = null;
        for (final Method m : dimensionsObj.getClass().getMethods()) {
            if (m.getName().equals("get") && m.getParameterCount() == 1) {
                getM = m;
                break;
            }
        }
        if (getM == null) return null;
        final Object opt = getM.invoke(dimensionsObj, actualDimension);
        if (opt == null) return null;
        // Optional.orElse(null)
        return opt.getClass().getMethod("orElse", Object.class).invoke(opt, (Object) null);
    }

    /**
     * Find a {@code HolderLookup$Provider} super-interface on the
     * {@code datapackWorldgen()} return value. Needed because the
     * {@code WorldDimensionData.create(...)} signature is declared against
     * the interface, not the concrete impl class.
     */
    private static Class<?> findHolderLookupProviderInterface(@NotNull Object datapackWg) {
        for (final Class<?> i : datapackWg.getClass().getInterfaces()) {
            if (i.getName().equals("net.minecraft.core.HolderLookup$Provider")) return i;
        }
        // Walk superclasses
        Class<?> c = datapackWg.getClass().getSuperclass();
        while (c != null) {
            for (final Class<?> i : c.getInterfaces()) {
                if (i.getName().equals("net.minecraft.core.HolderLookup$Provider")) return i;
            }
            c = c.getSuperclass();
        }
        try {
            return Class.forName("net.minecraft.core.HolderLookup$Provider");
        } catch (final ClassNotFoundException ex) {
            throw new IllegalStateException("HolderLookup$Provider class missing", ex);
        }
    }

    /**
     * Build a {@code CraftWorldInfo} via its constructor. Signature has
     * shifted between Paper minor versions; we pick the 9-arg constructor
     * by parameter count to stay loose.
     */
    private static Object newCraftWorldInfo(@NotNull Class<?> cCraftWorldInfo,
                                             @NotNull String name,
                                             @NotNull NamespacedKey worldKey,
                                             long seed,
                                             @NotNull Object enabledFeatures,
                                             @NotNull World.Environment environment,
                                             @NotNull Object dimensionType,
                                             @Nullable Object generator,
                                             @NotNull Object registryAccess,
                                             @NotNull Object uuid) throws Exception {
        for (final Constructor<?> c : cCraftWorldInfo.getDeclaredConstructors()) {
            if (c.getParameterCount() == 9) {
                c.setAccessible(true);
                return c.newInstance(name, worldKey, seed, enabledFeatures, environment,
                        dimensionType, generator, registryAccess, uuid);
            }
        }
        throw new IllegalStateException("CraftWorldInfo 9-arg constructor not found");
    }
}
