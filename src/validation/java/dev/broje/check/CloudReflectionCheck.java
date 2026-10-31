package dev.broje.check;

import dev.broje.BrojeClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

/**
 * Clouds reflected by water seen while standing on a flat shore, where the reflected
 * rays leave the screen: a raised pool with a stone bank in the disposable fog-check
 * world. Debug views: 2 SSR hits, 7 reflected environment, 8 Fresnel weight,
 * 9 the former display-space Fresnel blend for comparison.
 */
public final class CloudReflectionCheck {
    // pitch, clouds on, water debug view
    private static final Object[][] VIEWS = {
            {12f, true, 0}, {12f, true, 9}, {12f, false, 0}, {12f, true, 7}, {12f, true, 8},
            {25f, true, 0}, {25f, true, 9}, {25f, false, 0}, {5f, true, 0}, {5f, true, 9}};
    private static long start, stageStart;
    private static int stage = -1;
    private static double x, z;

    public static void frame() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.getSingleplayerServer() == null) return;
        long now = System.currentTimeMillis();
        if (start == 0) {
            start = now;
            x = Math.floor(mc.player.getX()); z = Math.floor(mc.player.getZ());
            BrojeClient.CONFIG.resetToDefaults(); BrojeClient.CONFIG.setEnabled(true);
            BrojeClient.CONFIG.setFogEnabled(false); BrojeClient.CONFIG.setCloudCoverage(75); BrojeClient.CONFIG.setCloudWind(0);
            int px = (int) x, pz = (int) z;
            commands("gamemode spectator @a", "gamerule minecraft:advance_time false", "gamerule minecraft:advance_weather false",
                    "time set 3000", "weather clear");
            // /fill takes at most 32768 blocks: clear the air above the pool in slices.
            for (int slice = pz - 12; slice <= pz + 108; slice += 20)
                commands("fill " + (px - 40) + " 98 " + slice + " " + (px + 40) + " 115 " + (slice + 19) + " minecraft:air");
            // A shallow sandy pool, as along a beach, with a flat dry bank level with the water.
            commands("fill " + (px - 40) + " 98 " + (pz - 12) + " " + (px + 40) + " 99 " + (pz + 108) + " minecraft:sand",
                    "fill " + (px - 40) + " 100 " + (pz - 12) + " " + (px + 40) + " 100 " + (pz - 3) + " minecraft:sand",
                    "fill " + (px - 40) + " 100 " + (pz - 2) + " " + (px + 40) + " 100 " + (pz + 108) + " minecraft:water");
            mc.options.bobView().set(false);
            mc.options.hideGui = true;
        }
        if (now - start < 14000) return;
        if (stage < 0 || now - stageStart > 3000) {
            if (stage >= 0) capture(String.format("reflection-%02d.png", stage));
            if (++stage == VIEWS.length) {
                System.clearProperty("broje.waterDebug");
                BrojeClient.LOGGER.info("CLOUD REFLECTION CHECK COMPLETE"); mc.stop(); return;
            }
            BrojeClient.CONFIG.setCloudsEnabled((Boolean) VIEWS[stage][1]);
            System.setProperty("broje.waterDebug", String.valueOf(VIEWS[stage][2]));
            stageStart = now;
        }
        // Standing on the bank, two blocks from the water's edge, looking across it.
        mc.player.setPos(x + 0.5, 101, z - 4.5); mc.player.setYRot(0); mc.player.setXRot((Float) VIEWS[stage][0]);
        mc.player.setDeltaMovement(0, 0, 0); mc.player.setOldPosAndRot();
    }

    private static void commands(String... commands) {
        var server = Minecraft.getInstance().getSingleplayerServer();
        server.execute(() -> { for (String command : commands)
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command); });
    }

    private static void capture(String name) {
        Minecraft mc = Minecraft.getInstance();
        Screenshot.grab(mc.gameDirectory, name, mc.getMainRenderTarget(), 1,
                message -> BrojeClient.LOGGER.info("CLOUD REFLECTION CHECK {}: {}", name, message.getString()));
    }
}
