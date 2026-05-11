package de.jexcellence.multiverse;

import de.jexcellence.dependency.JEDependency;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

/**
 * Premium edition entry point for JExMultiverse.
 * Delegates lifecycle to {@link JExMultiversePremiumImpl}.
 *
 * @author JExcellence
 * @since 3.0.0
 */
public final class JExMultiversePremium extends JavaPlugin {

    private JExMultiversePremiumImpl implementation;

    @Override
    public void onLoad() {
        try {
            JEDependency.initializeWithRemapping(this, JExMultiversePremium.class);
            this.implementation = new JExMultiversePremiumImpl(this);
            this.implementation.onLoad();
        } catch (Exception exception) {
            this.getLogger().log(Level.SEVERE, "[JExMultiverse-Premium] Failed to load", exception);
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

    public JExMultiversePremiumImpl getImpl() {
        return this.implementation;
    }

    /**
     * Bukkit hook invoked at server startup when {@code bukkit.yml} declares
     * a world with {@code generator: "JExMultiverse[:id]"}. See
     * {@link JExMultiverseFree#getDefaultWorldGenerator} for the full
     * contract — this Premium variant defers to the same registry so
     * the two editions stay aligned.
     */
    @Override
    public org.bukkit.generator.ChunkGenerator getDefaultWorldGenerator(
            @org.jetbrains.annotations.NotNull String worldName,
            @org.jetbrains.annotations.Nullable String id) {
        return de.jexcellence.multiverse.generator.GeneratorRegistry.resolve(id);
    }
}
