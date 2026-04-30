package de.jexcellence.multiverse;

import de.jexcellence.dependency.delegate.AbstractPluginDelegate;
import de.jexcellence.multiverse.service.MultiverseEdition;
import org.jetbrains.annotations.NotNull;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Premium edition delegate. Bootstraps and delegates lifecycle to {@link JExMultiverse}.
 *
 * @author JExcellence
 * @since 3.0.0
 */
public final class JExMultiversePremiumImpl extends AbstractPluginDelegate<JExMultiversePremium> {

    private static final Logger LOGGER = Logger.getLogger(JExMultiversePremiumImpl.class.getName());
    private static final String EDITION = "Premium";

    private JExMultiverse multiverse;

    public JExMultiversePremiumImpl(@NotNull JExMultiversePremium plugin) {
        super(plugin);
    }

    @Override
    public void onLoad() {
        try {
            this.multiverse = new JExMultiverse(getPlugin(), EDITION) {
                @Override
                protected int metricsId() {
                    return 24681;
                }

                @Override
                protected MultiverseEdition edition() {
                    return new MultiverseEdition.PremiumEdition();
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
            LOGGER.severe("Cannot enable — JExMultiverse Premium failed during onLoad.");
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
