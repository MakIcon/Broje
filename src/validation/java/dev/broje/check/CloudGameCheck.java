package dev.broje.check;

import dev.broje.BrojeClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.world.level.levelgen.Heightmap;

/** Volumetric cloud views at default settings, in the disposable fog-check world only. */
public final class CloudGameCheck {
    private static final double WALK = -1;
    // y (WALK: on foot, with view bobbing), yaw, pitch (negative looks up), time of day, rain
    private static final Object[][] VIEWS = {
            {88.0, 0f, -25f, 3000, false},    // daylight, looking up
            {88.0, -90f, -30f, 1500, false},  // against the morning sun: silver lining
            {88.0, 90f, -8f, 12000, false},   // sunset, the far layer
            {236.0, 0f, 0f, 6000, false},     // inside the layer
            {420.0, 0f, 35f, 6000, false},    // above the layer
            {88.0, 0f, -25f, 6000, true},     // rain
            {88.0, 0f, -25f, 18000, false},   // moonlit night
            {150.0, 45f, -6f, 4000, false},   // from the side: bases at different heights
            {340.0, 0f, -15f, 6000, false},   // between the decks
            {WALK, 0f, -20f, 3000, false},    // walking: the clouds must not shake or smear
            {140.0, 30f, 55f, 6000, false},   // the ground at noon: cloud shadows
            {112.0, 0f, 0f, 6000, false},    // lower bank, level with its base
            {160.0, 0f, 0f, 6000, false},    // lower-to-main overlap
            {300.0, 0f, 0f, 6000, false},    // main-to-upper overlap
            {385.0, 0f, 0f, 6000, false},    // upper bank interior
            {220.0, 0f, 0f, 6000, false},    // moving horizontally inside the main bank
            {220.0, 0f, -5f, 6000, false},   // outside a tall formation, all three banks
            {220.0, 0f, -5f, 6000, false},   // fixed cloud formation: detect heat-haze shimmer
            {220.0, 0f, -5f, 6000, false},
            {220.0, 0f, -5f, 6000, false},
            {220.0, 0f, -5f, 6000, false},
    };
    private static long start, stageStart;
    private static int stage = -1;
    private static double x, z;

    public static void frame() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.getSingleplayerServer() == null) return;
        long now = System.currentTimeMillis();
        if (start == 0) {
            start = now;
            x = mc.player.getX(); z = mc.player.getZ();
            BrojeClient.CONFIG.resetToDefaults(); BrojeClient.CONFIG.setEnabled(true); BrojeClient.CONFIG.setCloudFluffiness(Integer.getInteger("broje.cloudFluff", 80));
            BrojeClient.CONFIG.setCloudStyle(Integer.getInteger("broje.cloudStyle", 2));
            BrojeClient.CONFIG.setCloudCoverage(Integer.getInteger("broje.cloudCover", 45));
            commands("gamemode spectator @a", "gamerule minecraft:advance_time false", "gamerule minecraft:advance_weather false");
            mc.options.bobView().set(false);
            mc.options.hideGui = true;
        }
        if (now - start < 12000) return;
        if (stage < 0 || now - stageStart > 2500) {
            if (stage >= 0) capture(String.format("clouds-%02d.png", stage), mc.getFps());
            if (++stage == VIEWS.length) { BrojeClient.LOGGER.info("CLOUD CHECK COMPLETE"); mc.stop(); return; }
            Object[] view = VIEWS[stage];
            if (stage >= 11) {
                // A reproducible weather peak with tall cumulus, instead of the
                // clear sky around the world's spawn. Tests must enter real cloud.
                BrojeClient.CONFIG.setCloudWind(0);
                BrojeClient.CONFIG.setCloudEvolution(0);
            }
            mc.options.keyUp.setDown(false);
            commands("time set " + view[3], (Boolean) view[4] ? "weather rain" : "weather clear");
            if ((Double) view[0] == WALK) {
                int ground = mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) Math.floor(x), (int) Math.floor(z));
                commands("gamemode creative @a", "tp @a " + x + " " + (ground + 1) + " " + z);
                mc.options.bobView().set(true);
            }
            stageStart = now;
        }
        Object[] view = VIEWS[stage];
        if ((Double) view[0] == WALK) {
            mc.player.setYRot((Float) view[1]); mc.player.setXRot((Float) view[2]);
            mc.options.keyUp.setDown(now - stageStart > 500);
            return;
        }
        double travel = stage == 15 ? (now - stageStart) * 0.035 : 0;
        double atX = stage >= 11 ? -896 : x;
        double atZ = stage >= 11 ? (stage >= 16 ? 768 : 1984) : z;
        mc.player.setPos(atX, (Double) view[0], atZ + travel); mc.player.setYRot((Float) view[1]); mc.player.setXRot((Float) view[2]);
        mc.player.setDeltaMovement(0, 0, 0); mc.player.setOldPosAndRot();
    }

    private static void commands(String... commands) {
        var server = Minecraft.getInstance().getSingleplayerServer();
        server.execute(() -> { for (String command : commands)
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command); });
    }

    private static void capture(String name, int fps) {
        Minecraft mc = Minecraft.getInstance();
        Screenshot.grab(mc.gameDirectory, name, mc.getMainRenderTarget(), 1,
                message -> BrojeClient.LOGGER.info("CLOUD CHECK {} at {} fps: {}", name, fps, message.getString()));
    }
}
