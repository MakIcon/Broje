package dev.broje.check;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import dev.broje.BrojeClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

/** Uncapped 1920x1080 frame and cloud-pass GPU times per cloud setting, in the isolated fog world. */
public final class CloudPerformanceCheck {
    // clouds on, resolution %, steps
    private static final int[][] MODES = {{0, 50, 32}, {1, 50, 32}, {1, 25, 24}, {1, 25, 32}, {1, 100, 32}, {1, 50, 64}, {0, 50, 32}};
    private static int[][] modes = MODES;
    private static final long[] samples = new long[30000];
    private static final double[] gpuSamples = new double[30000];
    private static final List<String> results = new ArrayList<>(List.of("clouds,resolution,steps,frames,mean_ms,median_ms,p95_ms,cloud_gpu_mean_ms,cloud_gpu_median_ms"));
    private static long start, phaseStart, previous;
    private static int phase = -1, count, gpuCount;
    private static double x, z;

    public static void frame() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.getSingleplayerServer() == null) return;
        long now = System.nanoTime();
        if (start == 0) {
            start = now;
            FogGpuTimer.sink = CloudPerformanceCheck::recordGpu;
            x = mc.player.getX(); z = mc.player.getZ();
            if (Boolean.getBoolean("broje.cloudUserConfig")) {
                int resolution = BrojeClient.CONFIG.getCloudResolution(), quality = BrojeClient.CONFIG.getCloudQuality();
                modes = new int[][] {{0, resolution, quality}, {1, resolution, quality}, {0, resolution, quality}};
            } else BrojeClient.CONFIG.resetToDefaults();
            BrojeClient.CONFIG.setEnabled(true);
            mc.options.hideGui = true;
            commands("gamemode spectator @a", "gamerule minecraft:advance_time false", "time set 3000", "weather clear");
            apply(modes[0]);
        }
        // Looking up into a cloud field: the expensive, typical view.
        mc.player.setPos(x, 88, z); mc.player.setYRot(0); mc.player.setXRot(-25);
        mc.player.setDeltaMovement(0, 0, 0); mc.player.setOldPosAndRot();
        if (phase < 0) {
            if (now - start < 15_000_000_000L) return;
            phase = 0; phaseStart = now;
        }
        long elapsed = now - phaseStart;
        if (elapsed > 2_000_000_000L && previous != 0 && count < samples.length) samples[count++] = now - previous;
        previous = now;
        if (elapsed < 6_000_000_000L) return;
        long[] sorted = Arrays.copyOf(samples, count);
        Arrays.sort(sorted);
        double[] gpuSorted = Arrays.copyOf(gpuSamples, gpuCount);
        Arrays.sort(gpuSorted);
        int[] mode = modes[phase];
        String row = String.format(java.util.Locale.ROOT, "%s,%d,%d,%d,%.3f,%.3f,%.3f,%.3f,%.3f", mode[0] == 1 ? "on" : "off",
                mode[1], mode[2], count, Arrays.stream(sorted).average().orElse(0) / 1e6, sorted[count / 2] / 1e6,
                sorted[(int) (count * 0.95)] / 1e6, Arrays.stream(gpuSorted).average().orElse(0),
                gpuCount > 0 ? gpuSorted[gpuCount / 2] : 0);
        results.add(row);
        BrojeClient.LOGGER.info("CLOUD PERFORMANCE: {}", row);
        Screenshot.grab(mc.gameDirectory, "cloud-perf-" + phase + ".png", mc.getMainRenderTarget(), 1, message -> {});
        if (++phase == modes.length) {
            String report = Boolean.getBoolean("broje.cloudUserConfig") ? "cloud-performance-user.csv" : "cloud-performance.csv";
            try { Files.write(mc.gameDirectory.toPath().resolve(report), results); }
            catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
            mc.stop();
            return;
        }
        apply(modes[phase]);
        count = 0; gpuCount = 0; previous = 0; phaseStart = now;
    }

    private static void apply(int[] mode) {
        BrojeClient.CONFIG.setCloudsEnabled(mode[0] == 1);
        BrojeClient.CONFIG.setCloudResolution(mode[1]);
        BrojeClient.CONFIG.setCloudQuality(mode[2]);
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
