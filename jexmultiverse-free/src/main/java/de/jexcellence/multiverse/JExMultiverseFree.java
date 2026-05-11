package de.jexcellence.multiverse;

import de.jexcellence.dependency.JEDependency;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

/**
 * Free edition entry point for JExMultiverse.
 * Delegates lifecycle to {@link JExMultiverseFreeImpl}.
 *
 * @author JExcellence
 * @since 3.0.0
 */
public final class JExMultiverseFree extends JavaPlugin {

    private JExMultiverseFreeImpl implementation;

    @Override
    public void onLoad() {
        try {
            JEDependency.initializeWithRemapping(this, JExMultiverseFree.class);
            this.implementation = new JExMultiverseFreeImpl(this);
            this.implementation.onLoad();
        } catch (Exception exception) {
            this.getLogger().log(Level.SEVERE, "[JExMultiverse-Free] Failed to load", exception);
            this.implementation = null;
        }
    }

    @Override
    public void onEnable() {
        if (this.implementation != null) {
            this.implementation.onEnable();
        }
    }

    @Override
    public void onDisable() {
        if (this.implementation != null) {
            this.implementation.onDisable();
        }
    }

    public JExMultiverseFreeImpl getImpl() {
        return this.implementation;
    }

    /**
     * Bukkit hook invoked at server startup when {@code bukkit.yml} declares
     * a world with {@code generator: "JExMultiverse[:id]"}. The {@code id}
     * suffix (after the colon) selects which JExMultiverse generator to
     * use. Called once per declared world, BEFORE {@link #onEnable()} —
     * this is how Folia (and Paper/Spigot) creates worlds without going
     * through the runtime {@code Bukkit.createWorld} API.
     *
     * @param worldName the world being created (also the on-disk folder name)
     * @param id        the generator id from bukkit.yml — e.g. "void", "plot"
     * @return a chunk generator, or {@code null} to use the server default
     */
    @Override
    public org.bukkit.generator.ChunkGenerator getDefaultWorldGenerator(
            @org.jetbrains.annotations.NotNull String worldName,
            @org.jetbrains.annotations.Nullable String id) {
        return de.jexcellence.multiverse.generator.GeneratorRegistry.resolve(id);
    }
}
