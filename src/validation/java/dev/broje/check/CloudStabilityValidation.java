package dev.broje.check;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/** A stationary cloud must not ripple while camera, wind and weather are frozen. */
public final class CloudStabilityValidation {
    public static void main(String[] args) throws Exception {
        Path captures = Path.of(args[0]);
        for (int frame = 17; frame < 20; frame++) {
            BufferedImage a = ImageIO.read(captures.resolve("clouds-" + frame + ".png").toFile());
            BufferedImage b = ImageIO.read(captures.resolve("clouds-" + (frame + 1) + ".png").toFile());
            if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight())
                throw new AssertionError("Cloud stability captures must have matching dimensions");
            // The upper sky/cloud region excludes the terrain and any animals.
            int rows = (int) (a.getHeight() * 0.78);
            long difference = 0;
            for (int y = 0; y < rows; y++) {
                for (int x = 0; x < a.getWidth(); x++) {
                    int ca = a.getRGB(x, y), cb = b.getRGB(x, y);
                    for (int shift = 0; shift < 24; shift += 8)
                        difference += Math.abs(((ca >> shift) & 255) - ((cb >> shift) & 255));
                }
            }
            double mean = difference / (3.0 * rows * a.getWidth());
            if (mean > 0.05) throw new AssertionError("Stationary clouds shimmer: frames " + frame + "/" + (frame + 1) + ", MAE=" + mean);
            System.out.printf(java.util.Locale.ROOT, "PASS: stationary clouds %d/%d, mean channel change %.6f/255%n", frame, frame + 1, mean);
        }
    }
}
