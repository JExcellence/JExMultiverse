package de.jexcellence.multiverse;

import com.raindropcentral.commands.CommandFactory;
import com.raindropcentral.commands.v2.argument.ArgumentTypeRegistry;
import de.jexcellence.jehibernate.core.JEHibernate;
import de.jexcellence.jexplatform.JExPlatform;
import de.jexcellence.jexplatform.logging.JExLogger;
import de.jexcellence.jexplatform.logging.LogLevel;
import de.jexcellence.multiverse.api.MultiverseProvider;
import de.jexcellence.multiverse.command.EnvironmentArgumentType;
import de.jexcellence.multiverse.command.PlotArgumentType;
import de.jexcellence.multiverse.command.PlotFlagArgumentType;
import de.jexcellence.multiverse.command.PlotHandler;
import de.jexcellence.multiverse.command.R18nCommandMessages;
import de.jexcellence.multiverse.command.WorldArgumentType;
import de.jexcellence.multiverse.config.TranslationKeyMerger;
import de.jexcellence.multiverse.database.repository.PlotFlagRepository;
import de.jexcellence.multiverse.database.repository.PlotMemberRepository;
import de.jexcellence.multiverse.database.repository.PlotRepository;
import de.jexcellence.multiverse.listener.PlotFlagListener;
import de.jexcellence.multiverse.listener.PlotProtectionListener;
import de.jexcellence.multiverse.service.PlotService;
import de.jexcellence.multiverse.database.repository.MVWorldRepository;
import de.jexcellence.multiverse.factory.WorldFactory;
import de.jexcellence.multiverse.listener.SpawnListener;
import de.jexcellence.multiverse.service.MultiverseEdition;
import de.jexcellence.multiverse.service.MultiverseService;
import de.jexcellence.multiverse.view.MultiverseEditorView;
import de.jexcellence.multiverse.view.MultiverseListView;
import de.jexcellence.multiverse.view.PlotFlagsView;
import de.jexcellence.multiverse.view.PlotMembersView;
import de.jexcellence.multiverse.view.PlotMenuView;
import me.devnatan.inventoryframework.ViewFrame;
import org.bukkit.Bukkit;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;

/**
 * Core API entry point for JExMultiverse. Manages plugin lifecycle, database,
 * world management, and service registration across Free and Premium editions.
 *
 * <p>Each edition subclass provides {@link #metricsId()} and {@link #edition()}.
 * All wiring is handled here: JExPlatform for infrastructure, JEHibernate for the
 * data layer, and MultiverseService for the public API.
 *
 * @author JExcellence
 * @since 3.0.0
 */
public abstract class JExMultiverse {

    private final JavaPlugin plugin;
    private final String edition;

    private JExPlatform platform;
    private JEHibernate jeHibernate;
    private MultiverseService multiverseService;
    private WorldFactory worldFactory;
    private MVWorldRepository worldRepository;
    private PlotService plotService;
    private PlotRepository plotRepository;
    private PlotMemberRepository plotMemberRepository;
    private PlotFlagRepository plotFlagRepository;
    private ViewFrame viewFrame;
    private JExLogger logger;

    protected JExMultiverse(@NotNull JavaPlugin plugin, @NotNull String edition) {
        this.plugin = plugin;
        this.edition = edition;
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────────

    /**
     * Called during server load phase. Builds the platform (synchronous components
     * become available immediately).
     */
    public void onLoad() {
        platform = JExPlatform.builder(plugin)
                .withLogLevel(LogLevel.INFO)
                .enableTranslations("en_US", "de_DE")
                .enableMetrics(metricsId())
                .build();

        logger = platform.logger();
        logger.info("Loading JExMultiverse {} Edition", edition);
    }

    /**
     * Enables the multiverse plugin: initializes database, repositories, services,
     * views, and auto-discovers commands and listeners.
     */
    public void onEnable() {
        logger.info("Enabling JExMultiverse {} Edition...", edition);

        // Merge any new translation keys from the JAR into existing on-disk
        // YAML files BEFORE R18n loads. R18nManager only extracts when a
        // file is missing, so plugin upgrades that ship new keys never reach
        // disk otherwise — admins keep seeing "missing key" placeholders.
        TranslationKeyMerger.mergeAll(plugin,
                "translations/en_US.yml",
                "translations/de_DE.yml");

        // Capture the main-thread context classloader NOW.
        // JEDependency.initializeWithRemapping() sets the dependency classloader as the
        // context classloader of the main thread during onLoad(). ForkJoinPool worker
        // threads inherit the JVM default (system classloader) and cannot see injected
        // libraries. Propagating the captured loader fixes that.
        final ClassLoader dependencyClassLoader = Thread.currentThread().getContextClassLoader();

        platform.initialize()
                .thenRun(() -> {
                    Thread.currentThread().setContextClassLoader(dependencyClassLoader);
                    initializeDatabase();
                    initializeServices();
                    registerViews();
                    registerCommands();
                    registerListeners();
                    logger.info("JExMultiverse {} Edition enabled", edition);
                })
                .exceptionally(ex -> {
                    logger.error("Failed to enable JExMultiverse ({}): {}", edition, ex.getMessage());
                    plugin.getServer().getPluginManager().disablePlugin(plugin);
                    return null;
                });
    }

    /**
     * Disables the multiverse plugin, unregistering services and releasing resources.
     */
    public void onDisable() {
        if (multiverseService != null) {
            Bukkit.getServicesManager().unregister(MultiverseProvider.class, multiverseService);
        }
        if (jeHibernate != null) jeHibernate.close();
        if (platform != null) platform.shutdown();
        logger.info("JExMultiverse {} Edition disabled", edition);
    }

    /**
     * Returns the bStats service ID for this edition.
     *
     * @return the metrics service ID, or {@code 0} if metrics are disabled
     */
    protected abstract int metricsId();

    /**
     * Returns the edition descriptor for this build.
     *
     * @return the multiverse edition
     */
    protected abstract MultiverseEdition edition();

    // ── Initialization pipeline ─────────────────────────────────────────────────

    private void initializeDatabase() {
        // Copy default config files to the data folder if they don't already exist.
        // Translations and command YAMLs are extracted so server admins can edit
        // them without unpacking the JAR; commands are still loaded from the JAR
        // by JExCommand, but the on-disk copies serve as a reference for tweaks.
        saveDefaultResource("database/hibernate.properties");
        saveDefaultResource("database/log4j.properties");
        saveDefaultResource("config.yml");
        // Translation files are extracted earlier (in onEnable, before
        // platform.initialize) by TranslationKeyMerger so R18n sees the
        // merged keys on first load.
        saveDefaultResource("commands/multiverse.yml");
        saveDefaultResource("commands/spawn.yml");
        saveDefaultResource("commands/plot.yml");

        jeHibernate = JEHibernate.builder()
                .configuration(config -> config.fromProperties(
                        de.jexcellence.jehibernate.config.PropertyLoader.load(
                                plugin.getDataFolder(), "database", "hibernate.properties")))
                .scanPackages("de.jexcellence.multiverse.database")
                .build();

        logger.info("Database initialized");
    }

    /**
     * Saves a resource from the JAR to the plugin data folder if it does not already exist.
     *
     * @param resourcePath the resource path relative to the JAR root (use forward slashes)
     */
    private void saveDefaultResource(@NotNull String resourcePath) {
        var target = new java.io.File(plugin.getDataFolder(), resourcePath.replace('/', java.io.File.separatorChar));
        if (!target.exists()) {
            plugin.saveResource(resourcePath, false);
        }
    }

    private void initializeServices() {
        var repos = jeHibernate.repositories();
        worldRepository = repos.get(MVWorldRepository.class);
        plotRepository = repos.get(PlotRepository.class);
        plotMemberRepository = repos.get(PlotMemberRepository.class);
        plotFlagRepository = repos.get(PlotFlagRepository.class);

        worldFactory = new WorldFactory(plugin, worldRepository, logger);
        multiverseService = new MultiverseService(
                edition(), worldRepository, worldFactory, logger, plugin);
        plotService = new PlotService(multiverseService, worldFactory, plotRepository,
                plotMemberRepository, plotFlagRepository, logger, plugin);

        Bukkit.getServicesManager().register(
                MultiverseProvider.class, multiverseService, plugin, ServicePriority.Normal);

        worldFactory.loadAllWorlds().join();
        plotService.loadAll().join();
        logger.info("Multiverse + plot services ready");
    }

    @SuppressWarnings("UnstableApiUsage")
    private void registerViews() {
        viewFrame = ViewFrame
                .create(plugin)
                .with(new MultiverseEditorView())
                .with(new MultiverseListView())
                .with(new PlotMenuView())
                .with(new PlotMembersView())
                .with(new PlotFlagsView())
                .defaultConfig(config -> {
                    config.cancelOnClick();
                    config.cancelOnDrag();
                    config.cancelOnDrop();
                    config.cancelOnPickup();
                    config.interactionDelay(Duration.ofMillis(100));
                })
                .disableMetrics()
                .register();
        logger.info("ViewFrame registered");
    }

    private void registerCommands() {
        var factory = new CommandFactory(plugin, this);

        // Shared argument type registry — defaults + the plugin-specific types.
        var registry = ArgumentTypeRegistry.defaults()
                .register(WorldArgumentType.of(worldFactory))
                .register(EnvironmentArgumentType.create())
                .register(PlotArgumentType.of(plotService))
                .register(PlotFlagArgumentType.create());

        // Shared i18n bridge — all framework & plugin keys route through R18nManager.
        var messages = new R18nCommandMessages();

        // Register each YAML tree against its handler map.
        factory.registerTree("commands/multiverse.yml",
                new de.jexcellence.multiverse.command.MultiverseHandler(
                        multiverseService, worldFactory, viewFrame, plugin).handlerMap(),
                messages, registry);
        factory.registerTree("commands/spawn.yml",
                new de.jexcellence.multiverse.command.SpawnHandler(
                        multiverseService, plugin).handlerMap(),
                messages, registry);
        factory.registerTree("commands/plot.yml",
                new PlotHandler(plotService, multiverseService, worldFactory, viewFrame, plugin).handlerMap(),
                messages, registry);

        // Still let JExCommand auto-register any listener classes under the plugin package.
        factory.registerAllCommandsAndListeners();
    }

    private void registerListeners() {
        var pm = Bukkit.getPluginManager();
        pm.registerEvents(new SpawnListener(multiverseService, worldFactory, logger), plugin);
        pm.registerEvents(new PlotProtectionListener(plotService, multiverseService, plugin), plugin);
        pm.registerEvents(new PlotFlagListener(plotService), plugin);
    }

    // ── Accessors ────────────────────────────────────────────────────────────────

    /** Returns the owning Bukkit plugin. */
    public @NotNull JavaPlugin getPlugin() { return plugin; }

    /** Returns the edition name (Free or Premium). */
    public @NotNull String getEdition() { return edition; }

    /** Returns the platform instance for scheduling, logging, and translations. */
    public @NotNull JExPlatform platform() { return platform; }

    /** Returns the multiverse service. */
    public @NotNull MultiverseService multiverseService() { return multiverseService; }

    /** Returns the world factory. */
    public @NotNull WorldFactory worldFactory() { return worldFactory; }

    /** Returns the world repository. */
    public @NotNull MVWorldRepository worldRepository() { return worldRepository; }

    /** Returns the platform logger. */
    public @NotNull JExLogger logger() { return logger; }

    /** Returns the view frame for opening GUI views. */
    public @NotNull ViewFrame viewFrame() { return viewFrame; }
}
