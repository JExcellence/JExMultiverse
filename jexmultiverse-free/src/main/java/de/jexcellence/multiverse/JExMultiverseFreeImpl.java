package de.jexcellence.multiverse;

import de.jexcellence.dependency.delegate.AbstractPluginDelegate;
import de.jexcellence.multiverse.service.MultiverseEdition;
import org.jetbrains.annotations.NotNull;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Free edition delegate. Bootstraps and delegates lifecycle to {@link JExMultiverse}.
 *
 * @author JExcellence
 * @since 3.0.0
 */
public final class JExMultiverseFreeImpl extends AbstractPluginDelegate<JExMultiverseFree> {

    private static final Logger LOGGER = Logger.getLogger(JExMultiverseFreeImpl.class.getName());
    private static final String EDITION = "Free";

    private JExMultiverse multiverse;

    public JExMultiverseFreeImpl(@NotNull JExMultiverseFree plugin) {
        super(plugin);
    }

    @Override
    public void onLoad() {
        try {
            this.multiverse = new JExMultiverse(getPlugin(), EDITION) {
                @Override
                protected int metricsId() {
                    return 0;
                }

                @Override
                protected MultiverseEdition edition() {
                    return new MultiverseEdition.FreeEdition();
                }
            };
            this.multiverse.onLoad();
        } catch (Exception ex) {
            LOGGER.log(Level.SEVERE, "Failed to load JExMultiverse " + EDITION, ex);
            throw new RuntimeException(ex);
        }
    }

    @Override
    public void onEnable() {
        if (this.multiverse == null) {
            LOGGER.severe("Cannot enable — JExMultiverse Free failed during onLoad.");
            getPlugin().getServer().getPluginManager().disablePlugin(getPlugin());
            return;
        }
        this.multiverse.onEnable();
    }

    @Override
    public void onDisable() {
        try {
            if (this.multiverse != null) {
                this.multiverse.onDisable();
            }
        } catch (Exception ex) {
            LOGGER.log(Level.SEVERE, "Error during JExMultiverse " + EDITION + " shutdown", ex);
        }
    }
}
