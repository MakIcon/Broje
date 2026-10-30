package dev.broje.check;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/** Catches bright one-pixel cloud streaks at the far plane, including sunset. */
public final class AtmosphereCaptureValidation {
    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[0]);
        for (int frame : new int[] {4,5,6,7,12,13,14,15}) {
            BufferedImage image = ImageIO.read(directory.resolve(String.format("atmosphere-%02d.png", frame)).toFile());
            if (image == null) throw new AssertionError("Missing atmosphere capture " + frame);
            int spikes = 0;
            // Distant cloud band; excludes near clouds, terrain, crosshair and notices.
            for (int y = image.getHeight()*34/100+1; y < image.getHeight()*55/100-1; y++) {
                for (int x = image.getWidth()/20; x < image.getWidth()*19/20; x++) {
                    double center = brightness(image.getRGB(x,y));
                    double beside = Math.min(brightness(image.getRGB(x,y-1)), brightness(image.getRGB(x,y+1)));
                    if (center > 220 && center-beside > 20) spikes++;
                }
            }
            if (spikes > 5) throw new AssertionError("Far clouds contain luminous streaks, frame " + frame + ": " + spikes);
            System.out.println("PASS: far clouds/horizon frame " + frame + ", luminous streaks=" + spikes);
        }
    }

    private static double brightness(int rgb) {
        return (((rgb>>>16)&255)+((rgb>>>8)&255)+(rgb&255))/3.0;
    }
}
