package dev.broje.check;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/** Image assertions catch a valid draw with an invalid depth/stencil sampler. */
public final class FogCaptureValidation {
    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[0]);
        BufferedImage off = image(directory, "01-disabled.png");
        double volume = difference(off, image(directory, "02-volume.png"));
        double height = difference(off, image(directory, "03-height.png"));
        check(volume > 8 && height > 8, "Fog must visibly change the terrain: volume=" + volume + ", height=" + height);
        BufferedImage depth = image(directory, "05-depth.png");
        int centerDepth = depth.getRGB(depth.getWidth()/2, depth.getHeight()*3/4);
        check(((centerDepth >>> 16) & 255) > 240 && ((centerDepth >>> 8) & 255) > 8,
                "Scene depth must be readable through a DEPTH-only view");
        BufferedImage coverage = image(directory, "04-coverage.png");
        for (double x : new double[] {0.05, 0.5, 0.95}) {
            for (double y : new double[] {0.2, 0.5, 0.95}) {
                int pixel = coverage.getRGB((int)(x*coverage.getWidth()), (int)(y*coverage.getHeight()));
                check(((pixel >>> 16)&255) > 230 && ((pixel >>> 8)&255) < 70, "Full-screen coverage");
            }
        }
        double wall = difference(image(directory, "06-wall-volume.png"), image(directory, "07-wall-disabled.png"));
        check(wall < 12, "A nearby stone wall must hide dense fog behind it: difference=" + wall);
        double water = difference(image(directory, "08-underwater-enabled.png"), image(directory, "09-underwater-disabled.png"));
        check(water < 4, "Underwater must retain vanilla fog: difference=" + water);
        System.out.printf("PASS: real Vulkan frames. Fog difference %.2f / %.2f; wall %.2f; water %.2f. Depth and coverage valid.%n",
                volume, height, wall, water);
    }

    private static BufferedImage image(Path directory, String name) throws Exception {
        BufferedImage result = ImageIO.read(directory.resolve(name).toFile());
        if (result == null) throw new AssertionError("Missing capture " + name);
        return result;
    }

    private static double difference(BufferedImage a, BufferedImage b) {
        check(a.getWidth() == b.getWidth() && a.getHeight() == b.getHeight(), "Same capture dimensions");
        double sum = 0;
        int count = 0;
        // Stable central terrain region; omit tutorial banner, chat and screenshot notifications.
        for (int y = a.getHeight()*55/100; y < a.getHeight()*85/100; y++) {
            for (int x = a.getWidth()/10; x < a.getWidth()*9/10; x++) {
                int p = a.getRGB(x,y), q = b.getRGB(x,y);
                for (int shift : new int[] {0,8,16}) sum += Math.abs(((p >>> shift)&255) - ((q >>> shift)&255));
                count += 3;
            }
        }
        return sum / count;
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
