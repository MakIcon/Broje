package dev.broje.check;

import dev.broje.BrojeClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.InactivityFpsLimit;
import net.minecraft.client.Screenshot;

/** A basin, colored shore and overhang built only in a disposable copied save. */
public final class WaterGameCheck {
    private static final java.lang.reflect.Field WATER_VISION;
    static {
        try {WATER_VISION=net.minecraft.client.player.LocalPlayer.class.getDeclaredField("waterVisionTime");WATER_VISION.setAccessible(true);}
        catch(ReflectiveOperationException error){throw new ExceptionInInitializerError(error);}
    }
    private static long start,next;
    private static int stage,x,z;
    private static double cameraY=170,cameraZ,cameraX;
    private static float pitch=18,yaw;
    private static boolean sunView;
    private static long previousFrame;
    private static double benchmarkNanos;
    private static int benchmarkFrames;
    public static void frame() {
        Minecraft mc=Minecraft.getInstance();
        if(mc.level==null||mc.player==null||mc.getSingleplayerServer()==null)return;
        // Compare effects at a fixed native eye adaptation, not at different
        // points in Minecraft's gradual underwater lightmap adaptation.
        try {WATER_VISION.setInt(mc.player,600);}catch(IllegalAccessException error){throw new IllegalStateException(error);}
        var c=BrojeClient.CONFIG;
        long frameTime=System.nanoTime();
        if(stage>=31&&stage<=34&&System.currentTimeMillis()>next-900&&previousFrame!=0) {
            benchmarkNanos+=frameTime-previousFrame;benchmarkFrames++;
        }
        previousFrame=frameTime;
        if(start==0) {
            start=System.currentTimeMillis();next=start+15000;
            mc.options.inactivityFpsLimit().set(InactivityFpsLimit.MINIMIZED);
            mc.options.hideGui=true;
            // Fixed disposable test coordinates keep procedural lighting/waves
            // reproducible even while the user moves in the source world.
            x=114;z=-215;cameraZ=z-3;cameraX=x+0.5;
            c.setEnabled(true);c.setFogEnabled(false);c.setShadowsEnabled(true);c.setSunPathTilt(20);
            c.setWaterEnabled(false);c.setWaterWaves(65);c.setWaterRefraction(45);c.setWaterAbsorption(100);c.setWaterQuality(32);
            c.setWaterSsrRays(3);c.setWaterSsrPrecise(true);c.setWaterSsrDistance(112);
            c.setWaterCaustics(100);c.setUnderwaterShafts(true);
            System.setProperty("broje.waterTimeMillis","0");
            command("gamemode spectator @a");command("weather clear");command("time set 4000");command("gamerule minecraft:advance_time false");
            fill(-20,158,-8,20,172,32,"air");fill(-20,173,-8,20,185,32,"air");
            fill(-18,159,0,18,167,30,"sandstone");fill(-16,160,1,16,167,28,"water");
            fill(-16,160,1,16,161,7,"sandstone");
            fill(-18,168,29,18,168,32,"grass_block");
            fill(-12,169,29,-8,177,30,"red_concrete");fill(-3,169,29,1,175,30,"white_concrete");
            fill(6,169,29,10,180,30,"yellow_concrete");
            fill(12,170,4,13,175,8,"lime_stained_glass");
            // The overhang casts real occlusion into the submerged volume.
            fill(-16,173,10,-8,173,24,"stone");
            command("setblock "+(x-5)+" 160 "+(z+18)+" minecraft:seagrass");
            fill(5,160,19,5,163,19,"kelp_plant");
            command("setblock "+(x+5)+" 164 "+(z+19)+" minecraft:kelp");
            if(Boolean.getBoolean("broje.waterFocusCheck")) {
                stage=35;c.setWaterEnabled(true);c.setWaterWaves(65);c.setWaterAbsorption(100);c.setWaterCaustics(0);
                c.setUnderwaterReflections(false);c.setUnderwaterShafts(true);c.setUnderwaterShaftStrength(100);c.setUnderwaterShaftQuality(32);
                cameraY=163;cameraZ=z+6;pitch=-5;
            }
            if(Boolean.getBoolean("broje.waterShoreCheck")){stage=52;shoreScene();}
            if(Boolean.getBoolean("broje.waterMotionCheck")) {
                shoreScene();c.setWaterWaves(90);c.setWaterRefraction(40);c.setWaterQuality(32);c.setWaterSsrRays(3);
                c.setSunBloomStrength(0);mc.options.cloudStatus().set(net.minecraft.client.CloudStatus.OFF);
                WaterMotionCheck.prepare(mc);
            }
        }
        if(sunView) {
            var sun=dev.broje.render.SunDirection.at(mc.gameRenderer.getGameRenderState().levelRenderState.skyRenderState.sunAngle,c.getSunPathTilt(),new org.joml.Vector3f());
            yaw=(float)Math.toDegrees(Math.atan2(-sun.x,sun.z));pitch=-(float)Math.toDegrees(Math.asin(sun.y));
        }
        if(Boolean.getBoolean("broje.waterMotionCheck"))yaw=WaterMotionCheck.yaw();
        mc.player.setPos(cameraX,cameraY,cameraZ);mc.player.setYRot(yaw);mc.player.setXRot(pitch);
        mc.player.setDeltaMovement(0,0,0);mc.player.setOldPosAndRot();
        if(System.currentTimeMillis()<next)return;
        if(Boolean.getBoolean("broje.waterMotionCheck")){WaterMotionCheck.frame(mc);return;}
        next=System.currentTimeMillis()+1800;
        switch(stage++) {
            case 0 -> { capture("01-vanilla.png");c.setWaterEnabled(true); }
            case 1 -> { capture("02-water.png");c.setWaterSsr(false); }
            case 2 -> { capture("03-sky-only.png");c.setWaterSsr(true);System.setProperty("broje.waterDebug","2"); }
            case 3 -> { capture("04-ssr-hits.png");System.setProperty("broje.waterDebug","1"); }
            case 4 -> { capture("05-material-mask.png");System.clearProperty("broje.waterDebug");c.setWaterWaves(0); }
            case 5 -> { capture("06-calm.png");c.setWaterWaves(65);System.setProperty("broje.waterTimeMillis","2700"); }
            case 6 -> { capture("07-waves.png");c.setWaterRefraction(0); }
            case 7 -> { capture("08-no-refraction.png");c.setWaterRefraction(45);cameraY=164;cameraZ=z+10;pitch=12; }
            case 8 -> { capture("09-underwater.png");c.setUnderwaterShafts(false); }
            case 9 -> { capture("10-no-shafts.png");c.setWaterCaustics(0); }
            case 10 -> { capture("11-no-caustics.png");c.setWaterCaustics(100);c.setUnderwaterShafts(true);pitch=-38; }
            case 11 -> { capture("12-surface-below.png");c.setWaterEnabled(false);pitch=12; }
            case 12 -> { capture("13-underwater-vanilla.png");c.setWaterEnabled(true);command("time set midnight"); }
            case 13 -> { capture("14-underwater-night.png");command("time set 4000");cameraY=170;cameraZ=z-3;pitch=18;c.setFogEnabled(true); }
            case 14 -> { capture("15-water-with-fog.png");c.setEnabled(false); }
            case 15 -> {
                capture("16-master-off.png");c.setEnabled(true);c.setFogEnabled(false);c.setWaterWaves(200);c.setWaterCaustics(200);
                c.setWaterAbsorption(200);c.setWaterRefraction(100);c.setWaterQuality(16);
                c.setUnderwaterShafts(false);c.setUnderwaterReflections(true);c.setUnderwaterReflectionStrength(20);
                cameraY=165.5;cameraZ=z+13;pitch=-12;
            }
            case 16 -> { capture("17-grazing-max.png");pitch=-14; }
            case 17 -> { capture("18-grazing-adjacent.png");c.setUnderwaterReflections(false); }
            case 18 -> { capture("19-reflection-off.png");c.setUnderwaterReflections(true);c.setUnderwaterReflectionStrength(100); }
            case 19 -> { capture("20-reflection-max.png");c.setUnderwaterReflectionStrength(20);c.setWaterCaustics(0); }
            case 20 -> { capture("21-surface-no-caustics.png");System.setProperty("broje.waterDebug","1"); }
            case 21 -> { capture("22-underwater-material.png");System.clearProperty("broje.waterDebug");c.setWaterCaustics(200);pitch=-80; }
            case 22 -> {capture("23-look-up.png");cameraY=166.15;pitch=0;c.setUnderwaterReflectionStrength(100);}
            case 23 -> {capture("24-waterline-below.png");cameraY=166.25;}
            case 24 -> {capture("25-waterline-contact.png");cameraY=166.35;}
            case 25 -> {capture("26-waterline-above.png");cameraY=164;pitch=-12;}
            case 26 -> {capture("27-probe-pitch-a.png");pitch=-10;}
            case 27 -> {capture("28-probe-pitch-b.png");c.setUnderwaterReflectionInterval(8);pitch=-8;}
            case 28 -> {capture("29-probe-sparse.png");System.setProperty("broje.waterDebug","6");pitch=-12;c.setUnderwaterReflectionInterval(2);}
            case 29 -> {capture("30-probe-valid-a.png");pitch=-10;}
            case 30 -> {
                capture("31-probe-valid-b.png");System.clearProperty("broje.waterDebug");
                cameraY=170;cameraZ=z-3;pitch=18;c.setWaterWaves(65);c.setWaterAbsorption(100);c.setWaterQuality(16);
                c.setWaterSsr(true);c.setWaterSsrRays(1);c.setWaterSsrDistance(48);c.setWaterSsrPrecise(false);
            }
            case 31 -> {capture("32-ssr-fast.png");benchmark("SSR-fast");c.setWaterQuality(64);c.setWaterSsrRays(5);c.setWaterSsrDistance(112);c.setWaterSsrPrecise(true);}
            case 32 -> {
                capture("33-ssr-quality.png");benchmark("SSR-quality");cameraY=164;cameraZ=z+10;pitch=-35;
                c.setWaterSsr(false);c.setUnderwaterReflectionResolution(128);c.setUnderwaterReflectionDistance(32);c.setUnderwaterReflectionInterval(8);
            }
            case 33 -> {capture("34-probe-fast.png");benchmark("probe-fast");c.setUnderwaterReflectionResolution(512);c.setUnderwaterReflectionDistance(96);c.setUnderwaterReflectionInterval(1);}
            case 34 -> {
                capture("35-probe-quality.png");benchmark("probe-quality");
                cameraY=163;cameraZ=z+6;pitch=-5;c.setWaterQuality(32);c.setWaterWaves(65);
                c.setUnderwaterReflections(false);c.setWaterCaustics(0);c.setUnderwaterShafts(true);
                c.setUnderwaterShaftStrength(100);c.setUnderwaterShaftQuality(32);
            }
            case 35 -> {capture("36-beams-on.png");c.setUnderwaterShafts(false);}
            case 36 -> {capture("37-beams-off.png");c.setUnderwaterShafts(true);c.setUnderwaterShaftStrength(200);}
            case 37 -> {
                capture("38-beams-max.png");c.setUnderwaterShaftStrength(100);
                cameraY=166.25;pitch=0;c.setWaterWaves(200);
            }
            case 38 -> {capture("39-waterline-beams.png");c.setUnderwaterShafts(false);}
            case 39 -> {
                capture("40-waterline-no-beams.png");
                fill(-18,130,0,18,144,30,"sandstone");fill(-18,145,0,18,159,30,"sandstone");
                fill(-16,131,1,16,159,28,"water");
                fill(-2,164,18,0,172,20,"oak_log");
                c.setUnderwaterReflections(true);c.setUnderwaterReflectionStrength(100);
                cameraX=x-0.5;cameraZ=z+13;cameraY=162.33;pitch=0;
            }
            case 40 -> {capture("41-log-contact.png");cameraZ=z+19;}
            case 41 -> {
                capture("42-under-log.png");
                if(mc.gameRenderer.getGameRenderState().levelRenderState.cameraRenderState.fogType!=net.minecraft.world.level.material.FogType.WATER)
                    throw new AssertionError("Eye in the sealed 1/9 block under a submerged log must remain WATER");
                BrojeClient.LOGGER.info("WATER CONTACT CHECK: sealed camera remains WATER");
                cameraX=x+5.5;cameraZ=z+13;cameraY=156;pitch=-35;System.setProperty("broje.waterDebug","6");
            }
            case 42 -> {capture("43-deep-probe-a.png");cameraY=144;}
            case 43 -> {capture("44-deep-probe-b.png");pitch=-30;}
            case 44 -> {
                capture("45-deep-probe-pitch.png");System.clearProperty("broje.waterDebug");
                mc.options.cloudStatus().set(net.minecraft.client.CloudStatus.OFF);
                cameraX=x+0.5;cameraZ=z+10;cameraY=171;sunView=true;
            }
            case 45 -> {capture("46-round-sun.png");cameraY=163;c.setUnderwaterReflections(false);c.setUnderwaterShafts(true);c.setUnderwaterShaftStrength(100);}
            case 46 -> {
                capture("47-solar-beams.png");c.setUnderwaterShaftQuality(12);
                sunView=false;yaw=0;pitch=-5;cameraZ=z+6;
            }
            case 47 -> {capture("48-beams-low.png");c.setUnderwaterShaftQuality(48);}
            case 48 -> {capture("49-beams-high.png");c.setUnderwaterShafts(false);}
            case 49 -> {
                capture("50-beam-quality-off.png");cameraX=x+5.5;cameraZ=z+13;cameraY=144;pitch=-55;
                c.setUnderwaterReflections(true);c.setUnderwaterReflectionStrength(100);
            }
            case 50 -> {capture("51-deep-reflection.png");c.setUnderwaterReflections(false);}
            case 51 -> {capture("52-deep-reflection-off.png");shoreScene();}
            case 52 -> {capture("53-shore-fast.png");System.setProperty("broje.waterDebug","2");}
            case 53 -> {capture("54-shore-fast-hit.png");System.clearProperty("broje.waterDebug");c.setWaterSsrPrecise(true);}
            case 54 -> {capture("55-shore-precise.png");c.setWaterSsr(false);}
            case 55 -> {capture("56-shore-sky.png");c.setWaterSsr(true);c.setWaterQuality(16);c.setWaterSsrRays(1);c.setWaterSsrPrecise(false);}
            case 56 -> {capture("57-shore-economy.png");System.setProperty("broje.waterDebug","2");}
            case 57 -> {capture("58-shore-economy-hit.png");System.setProperty("broje.waterDebug","1");}
            case 58 -> {
                capture("59-shore-mask.png");System.clearProperty("broje.waterDebug");
                mc.options.cloudStatus().set(net.minecraft.client.CloudStatus.OFF);
                cameraX=x+0.5;cameraY=171;cameraZ=z+10;sunView=true;c.setSunBloomStrength(0);
            }
            case 59 -> {capture("60-sun-no-bloom.png");c.setSunBloomStrength(100);}
            case 60 -> {capture("61-sun-bloom.png");c.setSunBloomStrength(200);c.setSunBloomRadius(150);}
            case 61 -> {
                capture("62-sun-strong.png");c.setSunBloomStrength(100);c.setSunBloomRadius(100);
                var sun=dev.broje.render.SunDirection.at(mc.gameRenderer.getGameRenderState().levelRenderState.skyRenderState.sunAngle,c.getSunPathTilt(),new org.joml.Vector3f());
                int ax=(int)Math.floor(cameraX+sun.x*12)-x,ay=(int)Math.floor(cameraY+1.62+sun.y*12),az=(int)Math.floor(cameraZ+sun.z*12)-z;
                fill(ax-3,ay-3,az-3,ax+3,ay+3,az+3,"black_concrete");
            }
            case 62 -> {capture("63-sun-blocked-bloom.png");c.setSunBloomStrength(0);}
            case 63 -> {capture("64-sun-blocked-off.png");}
            default -> { BrojeClient.LOGGER.info("WATER CHECK COMPLETE");mc.stop(); }
        }
    }
    private static void shoreScene() {
        var c=BrojeClient.CONFIG;
        fill(-16,160,8,-13,168,26,"sand");fill(-12,160,12,-10,166,26,"sand");fill(-9,160,16,-7,165,26,"sand");
        fill(-15,169,18,-15,175,18,"oak_log");fill(-18,173,15,-12,176,21,"oak_leaves[persistent=true]");
        cameraX=x-3;cameraZ=z+5;cameraY=169;pitch=25;yaw=30;sunView=false;
        c.setEnabled(true);c.setWaterEnabled(true);c.setWaterWaves(0);c.setWaterRefraction(0);c.setWaterAbsorption(115);
        c.setWaterCaustics(0);c.setWaterSsr(true);c.setWaterSsrRays(3);c.setWaterSsrDistance(64);c.setWaterSsrPrecise(false);c.setWaterQuality(64);
        c.setUnderwaterShafts(false);System.setProperty("broje.waterTimeMillis","2700");
    }
    private static void benchmark(String name) {
        BrojeClient.LOGGER.info("WATER BENCH {}: {} ms/frame, {} samples",name,
                benchmarkFrames>0?benchmarkNanos/benchmarkFrames/1000000.0:0,benchmarkFrames);
        benchmarkNanos=0;benchmarkFrames=0;
    }
    private static void fill(int a,int b,int d,int e,int f,int g,String block) {
        command("fill "+(x+a)+" "+b+" "+(z+d)+" "+(x+e)+" "+f+" "+(z+g)+" minecraft:"+block);
    }
    private static void command(String text) {
        var server=Minecraft.getInstance().getSingleplayerServer();
        server.execute(()->server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),text));
    }
    private static void capture(String name) {
        var mc=Minecraft.getInstance();
        Screenshot.grab(mc.gameDirectory,name,mc.getMainRenderTarget(),1,message->BrojeClient.LOGGER.info("WATER CHECK {}: {}",name,message.getString()));
    }
}
