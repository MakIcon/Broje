package dev.broje.check;

import dev.broje.BrojeClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

/** Far-cloud/terrain regression scene, in the disposable fog-check world only. */
public final class AtmosphereGameCheck {
    private static long start, last;
    private static int stage;
    private static double x, z;

    public static void frame() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.getSingleplayerServer() == null) return;
        var config = BrojeClient.CONFIG;
        if (start == 0) {
            start = System.currentTimeMillis();
            try {
                for (int i=0; i<16; i++) java.nio.file.Files.deleteIfExists(
                        mc.gameDirectory.toPath().resolve(String.format("screenshots/atmosphere-%02d.png", i)));
            } catch (java.io.IOException exception) { throw new IllegalStateException(exception); }
            x = mc.player.getX(); z = mc.player.getZ();
            config.resetToDefaults(); config.setEnabled(false);
            commands("gamemode spectator @a", "tp @a " + x + " 88 " + z + " 0 -5",
                    "time set 3000", "weather clear", "gamerule minecraft:advance_time false");
            mc.options.bobView().set(false);
        }
        long now = System.currentTimeMillis();
        if (now - start > 12000 && now - last > 750) {
            capture(String.format("atmosphere-%02d.png", stage)); last = now;
            if (++stage == 4) config.setEnabled(true);
            if (stage == 8) config.setFogEnabled(false);
            if (stage == 12) { config.setFogEnabled(true); commands("time set 12000"); }
            if (stage == 16) { BrojeClient.LOGGER.info("ATMOSPHERE CHECK COMPLETE"); mc.stop(); }
        }
        mc.player.setPos(x, 88, z); mc.player.setYRot(0); mc.player.setXRot(-5);
        mc.player.setDeltaMovement(0,0,0); mc.player.setOldPosAndRot();
    }

    private static void commands(String... commands) {
        var server = Minecraft.getInstance().getSingleplayerServer();
        server.execute(() -> { for (String command : commands)
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command); });
    }

    private static void capture(String name) {
        Minecraft mc = Minecraft.getInstance();
        Screenshot.grab(mc.gameDirectory, name, mc.getMainRenderTarget(), 1,
                message -> BrojeClient.LOGGER.info("ATMOSPHERE CHECK {}: {}", name, message.getString()));
    }
}
