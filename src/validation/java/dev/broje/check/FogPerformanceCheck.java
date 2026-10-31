package dev.broje.check;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import dev.broje.BrojeClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

/** Uncapped frame-time comparison in the isolated fog world; no release instrumentation. */
public final class FogPerformanceCheck {
    private static final int[] RESOLUTIONS = {100, 50, 75, 100, 50, 100, 50};
    private static final long[] samples = new long[30000];
    private static final double[] gpuSamples = new double[30000];
    private static final List<String> results = new ArrayList<>(List.of("mode,resolution,frames,mean_ms,median_ms,p95_ms,gpu_mean_ms,gpu_median_ms"));
    private static long start, phaseStart, previous;
    private static int phase = -1, count, gpuCount;
    private static double x, z;

    public static void frame() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.getSingleplayerServer() == null) return;
        long now = System.nanoTime();
        var config = BrojeClient.CONFIG;
        if (start == 0) {
            start = now;
            x = mc.player.getX(); z = mc.player.getZ();
            config.setEnabled(true); config.setFogEnabled(true); config.setVolumetricFog(true);
            config.setShadowsEnabled(false); config.setLightingEnabled(false); config.setWaterEnabled(false);
            config.setFogResolution(100); config.setFogQuality(32); config.setFogDensity(45);
            config.setFogHeight(96); config.setFogStart(4); config.setFogFalloff(32);
            config.setFogVariation(55); config.setFogWind(0); config.setFogDistance(256);
            config.setLightShafts(true); config.setSunBloomStrength(0);
            mc.options.hideGui = true;
            commands("gamemode spectator @a", "tp @a " + x + " 88 " + z + " 0 15",
                    "gamerule minecraft:advance_time false", "time set 3000", "weather clear");
        }
        mc.player.setPos(x, 88, z); mc.player.setYRot(0); mc.player.setXRot(15);
        mc.player.setDeltaMovement(0, 0, 0); mc.player.setOldPosAndRot();
        if (phase < 0) {
            if (now - start < 15_000_000_000L) return;
            phase = 0; phaseStart = now;
        }
        long elapsed = now - phaseStart;
        // Warm up each pipeline/target, then collect real presented frame intervals.
        if (elapsed > 2_000_000_000L && previous != 0 && count < samples.length) samples[count++] = now - previous;
        previous = now;
        if (elapsed < 6_000_000_000L) return;
        long[] sorted = Arrays.copyOf(samples, count);
        Arrays.sort(sorted);
        double[] gpuSorted = Arrays.copyOf(gpuSamples, gpuCount);
        Arrays.sort(gpuSorted);
        if (gpuCount == 0) throw new AssertionError("No fog GPU timestamps");
        double mean = Arrays.stream(sorted).average().orElseThrow() / 1e6;
        String mode = phase < 5 ? "volume" : "shafts";
        String row = String.format(java.util.Locale.ROOT, "%s,%d,%d,%.4f,%.4f,%.4f,%.4f,%.4f", mode,
                RESOLUTIONS[phase], count, mean, sorted[count / 2] / 1e6, sorted[(int)(count * 0.95)] / 1e6,
                Arrays.stream(gpuSorted).average().orElseThrow(), gpuSorted[gpuCount / 2]);
        results.add(row);
        BrojeClient.LOGGER.info("FOG PERFORMANCE: {}", row);
        String capture = "perf-" + phase + "-" + mode + "-" + RESOLUTIONS[phase] + ".png";
        Screenshot.grab(mc.gameDirectory, capture, mc.getMainRenderTarget(), 1, message -> {});
        if (++phase == RESOLUTIONS.length) {
            try { Files.write(mc.gameDirectory.toPath().resolve("fog-performance.csv"), results); }
            catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
            mc.stop();
            return;
        }
        config.setFogResolution(RESOLUTIONS[phase]);
        config.setShadowsEnabled(phase >= 5);
        config.setEntityShadows(false);
        count = 0; gpuCount = 0; previous = 0; phaseStart = now;
    }

    public static void recordGpu(double milliseconds) {
        if (phase >= 0 && System.nanoTime() - phaseStart > 2_000_000_000L && gpuCount < gpuSamples.length)
            gpuSamples[gpuCount++] = milliseconds;
    }

    private static void commands(String... commands) {
        var server = Minecraft.getInstance().getSingleplayerServer();
        server.execute(() -> {
            var source = server.createCommandSourceStack();
            for (String command : commands) server.getCommands().performPrefixedCommand(source, command);
        });
    }
}
