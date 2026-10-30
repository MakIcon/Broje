package dev.broje.config;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import dev.broje.BrojeClient;

public final class BrojeConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;
    private boolean enabled;
    private boolean fogEnabled = true;
    private boolean volumetricFog = true;
    private int fogDensity = 45;
    private int fogStart = 4;
    private int fogHeight = 96;
    private int fogFalloff = 32;
    private int fogVariation = 55;
    private int fogWind = 15;
    private int fogDistance = 256;
    private int fogQuality = 32;
    private boolean shadowsEnabled = true;
    private int shadowResolution = 2048;
    private int shadowDistance = 64;
    private int shadowStrength = 80;
    private int shadowSoftness = 100;
    private int shadowSamples = 16;
    private int shadowFilter = 2;
    private int sunPathTilt;
    private boolean foliageShadows;
    private boolean coloredShadows = true;
    private int coloredContrast = 100;
    private int coloredMixing = 100;
    private int coloredBlend = 2;
    private int coloredBrightness = 100;
    private int coloredSaturation = 130;
    private int coloredOverlap = 100;
    private boolean entityShadows = true;
    private int foliageGlow = 60;
    private boolean lightShafts = true;
    private boolean lightingEnabled = true;
    private int bounceStrength = 100;
    private int lightColor = 60;
    private int lightingQuality = 16;

    private BrojeConfig(Path file) {
        this.file = file;
    }

    public static BrojeConfig load(Path file) {
        BrojeConfig config = new BrojeConfig(file);
        if (!Files.exists(file)) {
            config.save();
            return config;
        }

        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(reader);
            if (!root.isJsonObject()) {
                throw new JsonParseException("Expected a JSON object");
            }
            config.read(root.getAsJsonObject());
        } catch (IOException | JsonParseException exception) {
            BrojeClient.LOGGER.warn("Could not load {}. Using disabled defaults.", file, exception);
            return new BrojeConfig(file);
        }
        return config;
    }

    private void read(JsonObject json) {
        enabled = readBoolean(json, "enabled", false);
        fogEnabled = readBoolean(json, "fogEnabled", true);
        volumetricFog = readBoolean(json, "volumetricFog", true);
        setFogDensity(readInt(json, "fogDensity", 45));
        setFogStart(readInt(json, "fogStart", 4));
        setFogHeight(readInt(json, "fogHeight", 96));
        setFogFalloff(readInt(json, "fogFalloff", 32));
        setFogVariation(readInt(json, "fogVariation", 55));
        setFogWind(readInt(json, "fogWind", 15));
        setFogDistance(readInt(json, "fogDistance", 256));
        setFogQuality(readInt(json, "fogQuality", 32));
        shadowsEnabled = readBoolean(json, "shadowsEnabled", true);
        setShadowResolution(readInt(json, "shadowResolution", 2048));
        setShadowDistance(readInt(json, "shadowDistance", 64));
        setShadowStrength(readInt(json, "shadowStrength", 80));
        setShadowSoftness(readInt(json, "shadowSoftness", 100));
        setShadowSamples(readInt(json, "shadowSamples", 16));
        setShadowFilter(readInt(json, "shadowFilter", 2));
        setSunPathTilt(readInt(json, "sunPathTilt", 0));
        foliageShadows = readBoolean(json, "foliageShadows", false);
        coloredShadows = readBoolean(json, "coloredShadows", true);
        setColoredContrast(readInt(json, "coloredContrast", 100));
        setColoredMixing(readInt(json, "coloredMixing", 100));
        setColoredBlend(readInt(json, "coloredBlend", 2));
        setColoredBrightness(readInt(json, "coloredBrightness", 100));
        setColoredSaturation(readInt(json, "coloredSaturation", 130));
        setColoredOverlap(readInt(json, "coloredOverlap", 100));
        entityShadows = readBoolean(json, "entityShadows", true);
        setFoliageGlow(readInt(json, "foliageGlow", 60));
        lightShafts = readBoolean(json, "lightShafts", true);
        lightingEnabled = readBoolean(json, "lightingEnabled", true);
        setBounceStrength(readInt(json, "bounceStrength", 100));
        setLightColor(readInt(json, "lightColor", 60));
        setLightingQuality(readInt(json, "lightingQuality", 16));
    }

    /** Every setting back to its default; the master switch stays as it is. */
    public void resetToDefaults() {
        boolean wasEnabled = enabled;
        read(new BrojeConfig(file).toJson());
        enabled = wasEnabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    private static boolean readBoolean(JsonObject json, String name, boolean fallback) {
        JsonElement value = json.get(name);
        if (value == null) return fallback;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            throw new JsonParseException(name + " must be a boolean");
        }
        return value.getAsBoolean();
    }

    private static int readInt(JsonObject json, String name, int fallback) {
        JsonElement value = json.get(name);
        if (value == null) return fallback;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new JsonParseException(name + " must be a number");
        }
        double number = value.getAsDouble();
        if (!Double.isFinite(number)) throw new JsonParseException(name + " must be finite");
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, number));
    }

    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
    public boolean isFogEnabled() { return fogEnabled; }
    public void setFogEnabled(boolean value) { fogEnabled = value; }
    public boolean isVolumetricFog() { return volumetricFog; }
    public void setVolumetricFog(boolean value) { volumetricFog = value; }
    public int getFogDensity() { return fogDensity; }
    public void setFogDensity(int value) { fogDensity = clamp(value, 0, 100); }
    public int getFogStart() { return fogStart; }
    public void setFogStart(int value) { fogStart = clamp(value, 0, 64); }
    public int getFogHeight() { return fogHeight; }
    public void setFogHeight(int value) { fogHeight = clamp(value, 32, 256); }
    public int getFogFalloff() { return fogFalloff; }
    public void setFogFalloff(int value) { fogFalloff = clamp(value, 8, 128); }
    public int getFogVariation() { return fogVariation; }
    public void setFogVariation(int value) { fogVariation = clamp(value, 0, 100); }
    public int getFogWind() { return fogWind; }
    public void setFogWind(int value) { fogWind = clamp(value, 0, 100); }
    public int getFogDistance() { return fogDistance; }
    public void setFogDistance(int value) { fogDistance = clamp(value, 64, 512); }
    public int getFogQuality() { return fogQuality; }
    public void setFogQuality(int value) { fogQuality = value <= 16 ? 16 : value <= 32 ? 32 : value <= 48 ? 48 : 64; }
    public boolean isShadowsEnabled() { return shadowsEnabled; }
    public void setShadowsEnabled(boolean value) { shadowsEnabled = value; }
    public int getShadowResolution() { return shadowResolution; }
    public void setShadowResolution(int value) {
        shadowResolution = value <= 1024 ? 1024 : value <= 1536 ? 1536 : value <= 2048 ? 2048 : value <= 3072 ? 3072 : 4096;
    }
    public int getShadowDistance() { return shadowDistance; }
    public void setShadowDistance(int value) { shadowDistance = clamp(value, 32, 192); }
    public int getShadowStrength() { return shadowStrength; }
    public void setShadowStrength(int value) { shadowStrength = clamp(value, 0, 100); }
    public int getShadowSoftness() { return shadowSoftness; }
    public void setShadowSoftness(int value) { shadowSoftness = clamp(value, 50, 200); }
    /** 0 sharp, 1 crisp, 2 soft PCF, 3 smooth tent, 4 contact hardening. */
    public int getShadowFilter() { return shadowFilter; }
    public int getSunPathTilt() { return sunPathTilt; }
    public void setSunPathTilt(int value) { sunPathTilt = clamp(value, -60, 60); }
    public void setShadowFilter(int value) { shadowFilter = clamp(value, 0, 4); }
    public int getShadowSamples() { return shadowSamples; }
    public void setShadowSamples(int value) { shadowSamples = value <= 8 ? 8 : value <= 16 ? 16 : 24; }
    public boolean isFoliageShadows() { return foliageShadows; }
    public void setFoliageShadows(boolean value) { foliageShadows = value; }
    public boolean isColoredShadows() { return coloredShadows; }
    public void setColoredShadows(boolean value) { coloredShadows = value; }
    public int getColoredContrast() { return coloredContrast; }
    public void setColoredContrast(int value) { coloredContrast = clamp(value, 0, 300); }
    public int getColoredMixing() { return coloredMixing; }
    public void setColoredMixing(int value) { coloredMixing = clamp(value, 0, 100); }
    public boolean isEntityShadows() { return entityShadows; }
    public int getColoredBlend() { return coloredBlend; }
    public void setColoredBlend(int value) { coloredBlend = clamp(value, 0, 2); }
    public int getColoredBrightness() { return coloredBrightness; }
    public void setColoredBrightness(int value) { coloredBrightness = clamp(value, 0, 200); }
    public int getColoredSaturation() { return coloredSaturation; }
    public void setColoredSaturation(int value) { coloredSaturation = clamp(value, 0, 200); }
    public int getColoredOverlap() { return coloredOverlap; }
    public void setColoredOverlap(int value) { coloredOverlap = clamp(value, 0, 200); }
    public void setEntityShadows(boolean value) { entityShadows = value; }
    public int getFoliageGlow() { return foliageGlow; }
    public void setFoliageGlow(int value) { foliageGlow = clamp(value, 0, 100); }
    public boolean isLightShafts() { return lightShafts; }
    public void setLightShafts(boolean value) { lightShafts = value; }
    public boolean isLightingEnabled() { return lightingEnabled; }
    public void setLightingEnabled(boolean value) { lightingEnabled = value; }
    public int getBounceStrength() { return bounceStrength; }
    public void setBounceStrength(int value) { bounceStrength = clamp(value, 0, 200); }
    public int getLightColor() { return lightColor; }
    public void setLightColor(int value) { lightColor = clamp(value, 0, 100); }
    public int getLightingQuality() { return lightingQuality; }
    public void setLightingQuality(int value) { lightingQuality = value <= 8 ? 8 : value <= 16 ? 16 : 24; }

    private JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("enabled", enabled);
        json.addProperty("fogEnabled", fogEnabled);
        json.addProperty("volumetricFog", volumetricFog);
        json.addProperty("fogDensity", fogDensity);
        json.addProperty("fogStart", fogStart);
        json.addProperty("fogHeight", fogHeight);
        json.addProperty("fogFalloff", fogFalloff);
        json.addProperty("fogVariation", fogVariation);
        json.addProperty("fogWind", fogWind);
        json.addProperty("fogDistance", fogDistance);
        json.addProperty("fogQuality", fogQuality);
        json.addProperty("shadowsEnabled", shadowsEnabled);
        json.addProperty("shadowResolution", shadowResolution);
        json.addProperty("shadowDistance", shadowDistance);
        json.addProperty("shadowStrength", shadowStrength);
        json.addProperty("shadowSoftness", shadowSoftness);
        json.addProperty("shadowSamples", shadowSamples);
        json.addProperty("shadowFilter", shadowFilter);
        json.addProperty("sunPathTilt", sunPathTilt);
        json.addProperty("foliageShadows", foliageShadows);
        json.addProperty("coloredShadows", coloredShadows);
        json.addProperty("coloredContrast", coloredContrast);
        json.addProperty("coloredMixing", coloredMixing);
        json.addProperty("coloredBlend", coloredBlend);
        json.addProperty("coloredBrightness", coloredBrightness);
        json.addProperty("coloredSaturation", coloredSaturation);
        json.addProperty("coloredOverlap", coloredOverlap);
        json.addProperty("entityShadows", entityShadows);
        json.addProperty("foliageGlow", foliageGlow);
        json.addProperty("lightShafts", lightShafts);
        json.addProperty("lightingEnabled", lightingEnabled);
        json.addProperty("bounceStrength", bounceStrength);
        json.addProperty("lightColor", lightColor);
        json.addProperty("lightingQuality", lightingQuality);
        return json;
    }

    public void save() {
        Path temporaryFile = file.resolveSibling(file.getFileName() + ".tmp");
        JsonObject json = toJson();
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.writeString(temporaryFile, GSON.toJson(json) + System.lineSeparator(), StandardCharsets.UTF_8);
            try {
                Files.move(temporaryFile, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporaryFile, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            BrojeClient.LOGGER.error("Could not save Broje settings to {}", file, exception);
        }
    }
}
