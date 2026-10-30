package dev.broje.config;

import java.util.List;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.vulkanmod.config.gui.ModSettingsEntry;
import net.vulkanmod.config.gui.OptionBlock;
import net.vulkanmod.config.option.Option;
import net.vulkanmod.config.option.OptionPage;
import net.vulkanmod.config.option.SwitchOption;
import net.vulkanmod.config.option.RangeOption;
import net.vulkanmod.config.option.CyclingOption;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class BrojeSettings {
    private BrojeSettings() {
    }

    public static ModSettingsEntry create(BrojeConfig config) {
        return new ModSettingsEntry(
                Component.literal("Broje"),
                () -> Identifier.fromNamespaceAndPath("broje", "icon.png"),
                () -> createPages(config),
                config::save
        );
    }

    private static List<OptionPage> createPages(BrojeConfig config) {
        Option<Boolean> enabled = toggle("enabled", config::setEnabled, config::isEnabled);
        Option<Boolean> fog = toggle("fog", config::setFogEnabled, config::isFogEnabled);
        Option<Boolean> volume = toggle("volumetric", config::setVolumetricFog, config::isVolumetricFog);
        Option<Integer> quality = new CyclingOption<>(Component.translatable("broje.options.quality"),
                new Integer[] {16, 32, 48, 64}, config::setFogQuality, config::getFogQuality)
                .setTranslator(value -> Component.translatable("broje.quality." + value))
                .setTooltip(value -> Component.translatable("broje.options.quality.tooltip"));
        List<OptionPage> pages = new ArrayList<>();
        ActionOption reset = new ActionOption(Component.translatable("broje.options.reset"),
                Component.translatable("broje.options.reset.tooltip"), () -> {
            config.resetToDefaults();
            reloadOptions(pages);
            config.save();
        });
        // The master switch leads the first page, in a block of its own.
        pages.addAll(List.of(new OptionPage(Component.translatable("broje.page.shadows").getString(), new OptionBlock[] {
                new OptionBlock("", new Option<?>[] { enabled, reset }),
                new OptionBlock("", new Option<?>[] {
                        toggle("shadows", config::setShadowsEnabled, config::isShadowsEnabled),
                        new RangeOption(Component.translatable("broje.options.sun_path_tilt"), -60, 60, 5,
                                value -> Component.literal(value + "°"), config::setSunPathTilt, config::getSunPathTilt)
                                .setTooltip(value -> Component.translatable("broje.options.sun_path_tilt.tooltip")),
                        new CyclingOption<>(Component.translatable("broje.options.shadow_resolution"),
                                new Integer[] {1024,1536,2048,3072,4096}, config::setShadowResolution, config::getShadowResolution)
                                .setTranslator(value -> Component.literal(value + " × " + value))
                                .setTooltip(value -> Component.translatable("broje.options.shadow_resolution.tooltip")),
                        new RangeOption(Component.translatable("broje.options.shadow_distance"), 32, 192, 16,
                                value -> Component.translatable("broje.chunks", value / 16, value),
                                config::setShadowDistance, config::getShadowDistance)
                                .setTooltip(value -> Component.translatable("broje.options.shadow_distance.tooltip")),
                        range("shadow_strength",0,100,1,true,config::setShadowStrength,config::getShadowStrength),
                        new CyclingOption<>(Component.translatable("broje.options.shadow_filter"),
                                new Integer[] {0,1,2,3,4},config::setShadowFilter,config::getShadowFilter)
                                .setTranslator(value -> Component.translatable("broje.shadow_filter." + value))
                                .setTooltip(value -> Component.translatable("broje.options.shadow_filter.tooltip")),
                        range("shadow_softness",50,200,5,true,config::setShadowSoftness,config::getShadowSoftness),
                        new CyclingOption<>(Component.translatable("broje.options.shadow_quality"),
                                new Integer[] {8,16,24},config::setShadowSamples,config::getShadowSamples)
                                .setTranslator(value -> Component.literal(value + " PCF"))
                                .setTooltip(value -> Component.translatable("broje.options.shadow_quality.tooltip")),
                        toggle("foliage_shadows", config::setFoliageShadows, config::isFoliageShadows),
                        toggle("entity_shadows", config::setEntityShadows, config::isEntityShadows)
                })
        }), new OptionPage(Component.translatable("broje.page.colored").getString(), new OptionBlock[] {
                new OptionBlock("", new Option<?>[] {
                        toggle("colored_shadows", config::setColoredShadows, config::isColoredShadows),
                        new CyclingOption<>(Component.translatable("broje.options.colored_blend"),
                                new Integer[] {0,1,2}, config::setColoredBlend, config::getColoredBlend)
                                .setTranslator(value -> Component.translatable("broje.colored_blend." + value))
                                .setTooltip(value -> Component.translatable("broje.options.colored_blend.tooltip")),
                        range("colored_brightness", 0, 200, 5, true, config::setColoredBrightness, config::getColoredBrightness),
                        range("colored_saturation", 0, 200, 5, true, config::setColoredSaturation, config::getColoredSaturation),
                        range("colored_overlap", 0, 200, 5, true, config::setColoredOverlap, config::getColoredOverlap),
                        range("colored_contrast", 0, 300, 10, true, config::setColoredContrast, config::getColoredContrast),
                        range("colored_mixing", 0, 100, 5, true, config::setColoredMixing, config::getColoredMixing)
                })
        }), new OptionPage(Component.translatable("broje.page.lighting").getString(), new OptionBlock[] {
                new OptionBlock("", new Option<?>[] {
                        toggle("lighting", config::setLightingEnabled, config::isLightingEnabled),
                        range("bounce", 0, 200, 5, true, config::setBounceStrength, config::getBounceStrength),
                        range("light_color", 0, 100, 5, true, config::setLightColor, config::getLightColor),
                        range("foliage_glow", 0, 100, 5, true, config::setFoliageGlow, config::getFoliageGlow),
                        new CyclingOption<>(Component.translatable("broje.options.lighting_quality"),
                                new Integer[] {8,16,24}, config::setLightingQuality, config::getLightingQuality)
                                .setTranslator(value -> Component.translatable("broje.lighting_quality." + value))
                                .setTooltip(value -> Component.translatable("broje.options.lighting_quality.tooltip"))
                })
        }), new OptionPage(Component.translatable("broje.page.fog").getString(), new OptionBlock[] {
                new OptionBlock("", new Option<?>[] { fog, volume,
                        range("density", 0, 100, 1, true, config::setFogDensity, config::getFogDensity),
                        range("start", 0, 64, 1, false, config::setFogStart, config::getFogStart),
                        range("height", 32, 256, 4, false, config::setFogHeight, config::getFogHeight),
                        range("falloff", 8, 128, 4, false, config::setFogFalloff, config::getFogFalloff),
                        range("variation", 0, 100, 1, true, config::setFogVariation, config::getFogVariation),
                        range("wind", 0, 100, 1, true, config::setFogWind, config::getFogWind),
                        toggle("light_shafts", config::setLightShafts, config::isLightShafts),
                        range("distance", 64, 512, 16, false, config::setFogDistance, config::getFogDistance), quality })
        })));
        return pages;
    }

    /** Pulls every option's value back from the config, the reset switch included. */
    @SuppressWarnings("unchecked")
    private static void reloadOptions(List<OptionPage> pages) {
        try {
            Field supplier = Option.class.getDeclaredField("valueSupplier");
            supplier.setAccessible(true);
            for (OptionPage page : pages) for (OptionBlock block : page.optionBlocks) for (Option<?> option : block.options()) {
                if (option instanceof ActionOption) continue;
                Option<Object> typed = (Option<Object>) option;
                typed.setNewValue(((Supplier<Object>) supplier.get(option)).get());
                typed.apply();
            }
        } catch (ReflectiveOperationException exception) {
            dev.broje.BrojeClient.LOGGER.warn("Broje settings were reset; reopen the menu to see the defaults.", exception);
        }
    }

    private static Option<Boolean> toggle(String key, Consumer<Boolean> setter, Supplier<Boolean> getter) {
        return new SwitchOption(Component.translatable("broje.options." + key), setter, getter)
                .setTranslator(value -> Component.translatable(value ? "options.on" : "options.off"))
                .setTooltip(value -> Component.translatable("broje.options." + key + ".tooltip"));
    }

    private static Option<Integer> range(String key, int min, int max, int step, boolean percent,
                                          Consumer<Integer> setter, Supplier<Integer> getter) {
        return new RangeOption(Component.translatable("broje.options." + key), min, max, step,
                value -> percent ? Component.literal(value + "%") : Component.translatable("broje.blocks", value), setter, getter)
                .setTooltip(value -> Component.translatable("broje.options." + key + ".tooltip"));
    }
}
