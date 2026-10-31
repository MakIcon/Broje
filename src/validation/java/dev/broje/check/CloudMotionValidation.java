package dev.broje.check;

import java.nio.file.Path;
import javax.imageio.ImageIO;

/** A held pose must not change as old moving-camera cloud history settles. */
public final class CloudMotionValidation {
    public static void main(String[] args) throws Exception {
        Path captures = Path.of(args[0]);
        for (int cycle = 0; cycle < 2; cycle++) {
            var a = ImageIO.read(captures.resolve("cloud-motion-" + cycle + "-arrival.png").toFile());
            var b = ImageIO.read(captures.resolve("cloud-motion-" + cycle + "-held.png").toFile());
            if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight())
                throw new AssertionError("Motion captures must have matching dimensions");
            int rows = (int) (a.getHeight() * 0.78);
            long difference = 0, cloudPixels = 0;
            for (int y = 0; y < rows; y++) for (int x = 0; x < a.getWidth(); x++) {
                int ca = a.getRGB(x, y), cb = b.getRGB(x, y);
                int red = (ca >> 16) & 255, blue = ca & 255;
                if (red > 120 && blue - red < 35) cloudPixels++;
                for (int shift = 0; shift < 24; shift += 8)
                    difference += Math.abs(((ca >> shift) & 255) - ((cb >> shift) & 255));
            }
            double mean = difference / (3.0 * rows * a.getWidth());
            double coverage = cloudPixels / (double) (rows * a.getWidth());
            if (coverage < 0.08) throw new AssertionError("Cloud formation disappeared: visible fraction=" + coverage);
            System.out.printf(java.util.Locale.ROOT, "Visible cloud fraction %.3f%n", coverage);
            System.out.printf(java.util.Locale.ROOT, "Cloud motion %d: arrival/held mean channel change %.6f/255%n", cycle, mean);
            if (mean > 0.05) throw new AssertionError("Clouds keep changing after camera movement: MAE=" + mean);
        }
    }
}
