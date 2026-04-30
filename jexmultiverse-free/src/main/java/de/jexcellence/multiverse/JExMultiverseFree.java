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
}
