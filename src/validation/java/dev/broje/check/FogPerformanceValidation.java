package dev.broje.check;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/** Compare reconstruction against full-resolution fog, including shadow-map lighting. */
public final class FogPerformanceValidation {
    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[0]);
        Path captures = directory.resolve("screenshots");
        compare(captures, "perf-0-volume-100.png", "perf-1-volume-50.png");
        compare(captures, "perf-0-volume-100.png", "perf-2-volume-75.png");
        compare(captures, "perf-3-volume-100.png", "perf-4-volume-50.png");
        compare(captures, "perf-5-shafts-100.png", "perf-6-shafts-50.png");
        double full = 0, reduced = 0;
        int fullCount = 0, reducedCount = 0;
        for (String line : Files.readAllLines(directory.resolve("fog-performance.csv")).subList(1, 8)) {
            String[] row = line.split(",");
            if (Integer.parseInt(row[2]) < 30) throw new AssertionError("Too few measured frames: " + line);
            double milliseconds = Double.parseDouble(row[4]);
            if (!(milliseconds > 0) || !Double.isFinite(milliseconds)) throw new AssertionError("Invalid frame time");
            System.out.println("MEASURED: " + line);
            if (!row[0].equals("volume")) continue;
            if (row[1].equals("100")) { full += milliseconds; fullCount++; }
            if (row[1].equals("50")) { reduced += milliseconds; reducedCount++; }
        }
        System.out.printf(java.util.Locale.ROOT, "Volume median frame time: 100%% %.3f ms; 50%% %.3f ms.%n",
                full / fullCount, reduced / reducedCount);
        // Performance is reported rather than asserted: presentation, other GPU work,
        // clocks and the rest of the Minecraft frame vary across machines and runs.
    }

    private static void compare(Path directory, String reference, String candidate) throws Exception {
        BufferedImage a = ImageIO.read(directory.resolve(reference).toFile());
        BufferedImage b = ImageIO.read(directory.resolve(candidate).toFile());
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) throw new AssertionError("Capture dimensions differ");
        long sum = 0, count = 0, large = 0;
        // Omit clouds/tutorials; the terrain region includes cutout vegetation and thin pillars.
        for (int y = a.getHeight() / 2; y < a.getHeight() * 9 / 10; y++) {
            for (int x = a.getWidth() / 10; x < a.getWidth() * 9 / 10; x++) {
                int p = a.getRGB(x, y), q = b.getRGB(x, y);
                for (int shift : new int[] {0, 8, 16}) {
                    int difference = Math.abs(((p >>> shift) & 255) - ((q >>> shift) & 255));
                    sum += difference; count++;
                    if (difference > 8) large++;
                }
            }
        }
        double mean = (double) sum / count, fraction = (double) large / count;
        if (mean > 2 || fraction > 0.02) throw new AssertionError("Fog reconstruction changed the image: " + candidate + " / " + mean + " / " + fraction);
        System.out.printf(java.util.Locale.ROOT, "PASS: %s mean RGB error %.3f/255, channels differing by >8: %.3f%%.%n", candidate, mean, fraction * 100);
    }
}
