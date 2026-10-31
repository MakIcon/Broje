package dev.broje.check;

import dev.broje.BrojeClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

/** Moving-camera regression in the disposable world, with wind/weather frozen. */
public final class CloudMotionCheck {
    private static long start;
    private static int frame;

    public static void frame() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.getSingleplayerServer() == null) return;
        if (start == 0) {
            start = System.currentTimeMillis();
            BrojeClient.CONFIG.setCloudWind(0);
            BrojeClient.CONFIG.setCloudEvolution(0);
            mc.options.bobView().set(false);
            mc.options.hideGui = true;
            var server = mc.getSingleplayerServer();
            server.execute(() -> {
                for (String command : new String[] {"gamemode spectator @a", "gamerule minecraft:advance_time false",
                        "gamerule minecraft:advance_weather false", "time set 6000", "weather clear"})
                    server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
            });
        }
        if (System.currentTimeMillis() - start < 10000) { pose(mc, 0); return; }
        frame++;
        // Move for 60 frames, then hold the exact endpoint for another 60.
        int cycle = (frame - 1) / 120;
        int local = (frame - 1) % 120;
        double progress = Math.min(local / 59.0, 1.0);
        double position = cycle == 0 ? progress : 1.0 - progress;
        if (cycle < 2) {
            // Two complete frames at the endpoint precede the first capture.
            if (local == 62 || local == 115) capture(mc, cycle, local == 62 ? "arrival" : "held");
            pose(mc, position);
        } else if (local > 60) {
            BrojeClient.LOGGER.info("CLOUD MOTION CHECK COMPLETE");
            mc.stop();
        }
    }

    private static void pose(Minecraft mc, double t) {
        mc.player.setPos(-896 + 48 * t, 220 + 8 * t, 768 + 32 * t);
        mc.player.setYRot((float) (16 * t));
        mc.player.setXRot(-5);
        mc.player.setDeltaMovement(0, 0, 0);
        mc.player.setOldPosAndRot();
    }

    private static void capture(Minecraft mc, int cycle, String phase) {
        String name = "cloud-motion-" + cycle + "-" + phase + ".png";
        Screenshot.grab(mc.gameDirectory, name, mc.getMainRenderTarget(), 1,
                message -> BrojeClient.LOGGER.info("CLOUD MOTION {} pose={}, yaw={}: {}", name,
                        mc.player.position(), mc.player.getYRot(), message.getString()));
    }
}
