package dev.broje;

import dev.broje.config.BrojeConfig;
import dev.broje.config.BrojeSettings;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.vulkanmod.config.gui.ModSettingsRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class BrojeClient implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("Broje");
    public static BrojeConfig CONFIG;

    @Override
    public void onInitializeClient() {
        CONFIG = BrojeConfig.load(FabricLoader.getInstance().getConfigDir().resolve("broje.json"));
        ModSettingsRegistry.INSTANCE.addModEntry(BrojeSettings.create(CONFIG));
        LOGGER.info("Broje initialized (enabled={}, fog={}, volumetric={}).", CONFIG.isEnabled(), CONFIG.isFogEnabled(), CONFIG.isVolumetricFog());
    }
}
