package dev.broje.check;

import dev.broje.BrojeClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

/** Isolated integration test mod; never included in the Broje release artifact. */
public final class FogGameCheck {
    private static long start;
    private static int stage;
    private static double cameraX,cameraZ;

    public static void frame() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.getSingleplayerServer() == null) return;
        mc.player.setYRot(0);
        mc.player.setXRot(15);
        mc.player.setDeltaMovement(0, 0, 0);
        if (start == 0) {
            start = System.currentTimeMillis();
            double x = mc.player.getX(), z = mc.player.getZ();
            cameraX=x;cameraZ=z;
            var server = mc.getSingleplayerServer();
            server.execute(() -> {
                var commands = server.getCommands();
                var source = server.createCommandSourceStack();
                commands.performPrefixedCommand(source, "gamemode spectator @a");
                commands.performPrefixedCommand(source, "tp @a " + x + " 88 " + z + " 0 15");
                commands.performPrefixedCommand(source, "time set noon");
                commands.performPrefixedCommand(source, "weather clear");
            });
            var config = BrojeClient.CONFIG;
            config.setEnabled(true);
            config.setShadowsEnabled(false);
            config.setWaterEnabled(false);
            config.setFogEnabled(false);
            config.setVolumetricFog(true);
            config.setFogDensity(80);
            config.setFogHeight(120);
            config.setFogStart(4);
            config.setFogWind(0);
            config.setFogDistance(256);
            config.setFogQuality(32);
        }
        long elapsed = System.currentTimeMillis() - start;
        if (stage == 0 && elapsed > 12000) {
            capture("01-disabled.png");
            BrojeClient.CONFIG.setFogEnabled(true);
            stage++;
        } else if (stage == 1 && elapsed > 14500) {
            capture("02-volume.png");
            BrojeClient.CONFIG.setVolumetricFog(false);
            stage++;
        } else if (stage == 2 && elapsed > 17000) {
            capture("03-height.png");
            System.setProperty("broje.fogDebug", "solid");
            stage++;
        } else if (stage == 3 && elapsed > 19500) {
            capture("04-coverage.png");
            System.setProperty("broje.fogDebug", "depth");
            stage++;
        } else if (stage == 4 && elapsed > 22000) {
            capture("05-depth.png");
            System.clearProperty("broje.fogDebug");
            stage++;
        } else if (stage == 5 && elapsed > 24500) {
            BrojeClient.CONFIG.setVolumetricFog(true);
            BrojeClient.CONFIG.setFogStart(0);
            int x = (int) Math.floor(mc.player.getX()), z = (int) Math.floor(mc.player.getZ());
            command("fill " + (x-16) + " 70 " + (z+3) + " " + (x+16) + " 110 " + (z+3) + " minecraft:stone");
            stage++;
        } else if (stage == 6 && elapsed > 28000) {
            capture("06-wall-volume.png");
            BrojeClient.CONFIG.setFogEnabled(false);
            stage++;
        } else if (stage == 7 && elapsed > 30500) {
            capture("07-wall-disabled.png");
            BrojeClient.CONFIG.setFogEnabled(true);
            int x = (int) Math.floor(mc.player.getX()), z = (int) Math.floor(mc.player.getZ());
            command("fill " + (x-2) + " 87 " + (z-2) + " " + (x+2) + " 93 " + (z+2) + " minecraft:water");
            stage++;
        } else if (stage == 8 && elapsed > 34000) {
            capture("08-underwater-enabled.png");
            BrojeClient.CONFIG.setFogEnabled(false);
            stage++;
        } else if (stage == 9 && elapsed > 36500) {
            capture("09-underwater-disabled.png");
            stage++;
        } else if (stage == 10 && elapsed > 40500) {
            BrojeClient.LOGGER.info("FOG CHECK COMPLETE: compare run-fog-check/screenshots captures.");
            stage++;
            mc.stop();
        }
        // Keep both interpolation endpoints fixed; this test compares the same camera
        // inside water, rather than a player drifting through its animated surface.
        mc.player.setPos(cameraX,88,cameraZ);
        mc.player.setOldPosAndRot();
    }

    private static void command(String text) {
        var server = Minecraft.getInstance().getSingleplayerServer();
        server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), text));
    }

    private static void capture(String name) {
        Minecraft mc = Minecraft.getInstance();
        Screenshot.grab(mc.gameDirectory, name, mc.getMainRenderTarget(), 1,
                message -> BrojeClient.LOGGER.info("FOG CHECK {}: {}", name, message.getString()));
    }
}
