package dev.broje.check;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import dev.broje.BrojeClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector4f;
import net.vulkanmod.vulkan.VRenderSystem;

/** Deterministic solar test scene in a copy of the development world. */
public final class ShadowGameCheck {
    private static long start;
    private static int stage;
    private static float yaw=145,pitch=24;
    private static double camX=10,camY=107,camZ=14;
    private static final Matrix4f worldTransform=new Matrix4f();
    private static final Vector3d worldCamera=new Vector3d();
    private static long lastMotion;
    private static int motionFrame;
    private static int grassFrame;
    private static long grassStart;
    private static int cameraFrame;

    public static void captureWorldTransform() {
        var camera=Minecraft.getInstance().gameRenderer.getGameRenderState().levelRenderState.cameraRenderState;
        // The hand changes VRenderSystem.projection later in the frame. Project probes
        // with the same world transform used by the real shadow resolve, before that change.
        worldTransform.set(VRenderSystem.projection).mul(camera.viewRotationMatrix);
        worldCamera.set(camera.pos.x,camera.pos.y,camera.pos.z);
    }
    public static void frame() {
        Minecraft mc=Minecraft.getInstance();
        if(mc.level==null || mc.player==null || mc.getSingleplayerServer()==null)return;
        mc.player.setYRot(yaw);mc.player.setXRot(pitch);mc.player.setDeltaMovement(0,0,0);
        mc.player.setOldPosAndRot();
        if(start==0) {
            start=System.currentTimeMillis();
            var config=BrojeClient.CONFIG;
            config.setEnabled(true);config.setFogEnabled(false);config.setShadowsEnabled(false);
            config.setShadowResolution(2048);config.setShadowDistance(64);config.setShadowSamples(16);config.setShadowStrength(100);
            // The image checks measure the shadow map itself: keep the grass casters and leave GI out.
            config.setFoliageShadows(true);config.setLightingEnabled(false);
            // Wandering mobs would move their shadows across the stability probes.
            config.setEntityShadows(false);
            commands("gamerule minecraft:advance_time true","gamemode spectator @a","fill -20 100 -20 20 100 20 minecraft:smooth_stone",
                    "fill -20 101 -20 20 116 20 minecraft:air","fill 0 101 0 0 106 0 minecraft:stone",
                    "setblock -3 101 0 minecraft:stone_brick_stairs[facing=east]",
                    "setblock -5 101 0 minecraft:stone_slab[type=bottom]","fill -8 101 0 -7 101 0 minecraft:oak_fence",
                    "fill 4 104 3 6 104 5 minecraft:oak_leaves[persistent=true]",
                    "fill 5 101 4 5 103 4 minecraft:oak_log","time set 2000","weather clear",
                    "tp @a 10.0 107.0 14.0 145 24");
            if(Boolean.getBoolean("broje.grassOnly")) {
                config.setShadowsEnabled(true);System.setProperty("broje.shadowDebug","mask");
                stage=14;prepareGrass();
            }
            if(Boolean.getBoolean("broje.giOnly")) {
                config.setShadowsEnabled(true);config.setEntityShadows(true);
                config.setLightingEnabled(true);config.setBounceStrength(100);
                stage=40;prepareGi();
            }
            if(Boolean.getBoolean("broje.glassOnly")) {
                config.setShadowsEnabled(true);config.setLightingEnabled(true);config.setColoredShadows(true);
                camX=0.5;camY=104;camZ=7;yaw=180;pitch=45;stage=60;
                // Two layers of panes: red over blue and yellow over cyan overlap in the middle.
                commands("fill -12 100 -12 12 100 12 minecraft:smooth_stone","fill -12 101 -12 12 112 12 minecraft:air",
                        "fill -4 105 -4 -1 105 -2 minecraft:red_stained_glass","fill -2 106 -4 1 106 -2 minecraft:blue_stained_glass",
                        "fill -4 105 1 -1 105 3 minecraft:yellow_stained_glass","fill -2 106 1 1 106 3 minecraft:cyan_stained_glass",
                        "fill 3 105 -1 5 105 1 minecraft:white_stained_glass","time set 6000","weather clear",
                        "gamerule minecraft:advance_time false","tp @a 0.5 104 7 180 45");
                grassStart=System.currentTimeMillis();
            }
        }
        if(start>0&&stage==0&&Boolean.getBoolean("broje.filterOnly")) {
            var config=BrojeClient.CONFIG;
            config.setShadowsEnabled(true);config.setLightingEnabled(false);config.setColoredShadows(true);
            camX=3.5;camY=102.6;camZ=4.5;yaw=150;pitch=45;stage=80;
            commands("fill -12 100 -12 12 100 12 minecraft:smooth_stone","fill -12 101 -12 12 112 12 minecraft:air",
                    "fill 0 101 0 0 105 0 minecraft:stone","fill -3 101 2 -1 101 2 minecraft:oak_fence",
                    "setblock 2 103 0 minecraft:red_stained_glass","time set 1500","weather clear",
                    "gamerule minecraft:advance_time false","tp @a 3.5 102.6 4.5 150 45");
            grassStart=System.currentTimeMillis();
        }
        if(start>0&&stage==0&&Boolean.getBoolean("broje.fogGlassOnly")) {
            var config=BrojeClient.CONFIG;
            config.setShadowsEnabled(true);config.setFogEnabled(true);config.setVolumetricFog(false);config.setFogDensity(90);
            config.setFogStart(0);config.setFogHeight(140);
            camX=0.5;camY=101.5;camZ=4.5;yaw=180;pitch=0;stage=100;
            // A glass wall in front of the camera, open on the right, and a pool of water.
            commands("fill -12 100 -12 12 100 12 minecraft:smooth_stone","fill -12 101 -12 12 112 12 minecraft:air",
                    "fill -8 101 -1 1 106 -1 minecraft:glass","fill -3 101 -2 -1 106 -2 minecraft:light_blue_stained_glass",
                    "fill 3 100 -6 8 100 2 minecraft:water","time set 3000","weather clear",
                    "gamerule minecraft:advance_time false","tp @a 0.5 101.5 4.5 180 0");
            grassStart=System.currentTimeMillis();
        }
        if(stage>=100){
            var config=BrojeClient.CONFIG;long t=System.currentTimeMillis()-grassStart;
            if(stage==100&&t>9000){capture("100-fog-glass.png");commands("fill -8 101 -2 1 106 -1 minecraft:air");stage++;}
            else if(stage==101&&t>11500){capture("101-fog-no-glass.png");config.setFogEnabled(false);stage++;}
            else if(stage==102&&t>13500){capture("102-no-fog.png");config.setFogEnabled(true);config.setVolumetricFog(true);
                commands("fill -8 101 -1 1 106 -1 minecraft:glass");stage++;}
            else if(stage==103&&t>16000){capture("103-volume-glass.png");BrojeClient.LOGGER.info("SHADOW CHECK COMPLETE");stage++;mc.stop();}
            mc.player.setPos(camX,camY,camZ);mc.player.setYRot(yaw);mc.player.setXRot(pitch);mc.player.setOldPosAndRot();
            return;
        }
        if(stage>=80){filterFrame(mc);return;}
        if(stage>=60){glassFrame(mc);return;}
        if(stage>=40){giFrame(mc);return;}
        long time=System.currentTimeMillis()-start;
        if(stage==0 && time>14000){capture("01-disabled.png");BrojeClient.CONFIG.setShadowsEnabled(true);stage++;}
        else if(stage==1 && time>19000){capture("02-shadows.png");System.setProperty("broje.shadowDebug","mask");stage++;}
        else if(stage==2 && time>21500){capture("03-mask.png");probes("contact");System.setProperty("broje.shadowDebug","map");stage++;}
        else if(stage==3 && time>24000){capture("04-map.png");System.clearProperty("broje.shadowDebug");
            commands("time set 500");stage++;}
        else if(stage==4 && time>27500){capture("05-low-sun.png");System.setProperty("broje.shadowDebug","mask");stage++;}
        else if(stage==5 && time>30000){capture("06-low-mask.png");probes("low-contact");System.clearProperty("broje.shadowDebug");
            commands("fill -1 101 -1 1 107 1 minecraft:air","fill -18 101 0 -17 135 1 minecraft:stone",
                    "fill 17 101 0 18 135 1 minecraft:stone","time set 2000","tp @a 0.5 103.0 12.0 180 10");
            camX=0.5;camY=103;camZ=12;yaw=180;pitch=10;stage++;}
        else if(stage==6 && time>35500){capture("07-offscreen.png");System.setProperty("broje.shadowDebug","mask");stage++;}
        else if(stage==7 && time>38000){capture("08-offscreen-mask.png");probes("offscreen");System.clearProperty("broje.shadowDebug");
            commands("time set midnight");stage++;}
        else if(stage==8 && time>41500){capture("09-night.png");BrojeClient.CONFIG.setShadowsEnabled(false);stage++;}
        else if(stage==9 && time>44000){capture("10-night-disabled.png");
            commands("fill 4 101 3 6 104 5 minecraft:air","fill -18 101 0 -17 135 1 minecraft:air",
                    "fill 17 101 0 18 135 1 minecraft:air","fill 0 101 0 0 106 0 minecraft:stone",
                    "time set 500","tp @a -4.0 103.0 6.0 210 25");
            camX=-4;camY=103;camZ=6;yaw=210;pitch=25;BrojeClient.CONFIG.setShadowsEnabled(true);stage++;}
        else if(stage==10 && time>48500){capture("11-close-contact.png");System.setProperty("broje.shadowDebug","mask");stage++;}
        else if(stage==11 && time>51000){capture("12-close-contact-mask.png");probes("close-contact");
            System.clearProperty("broje.shadowDebug");BrojeClient.CONFIG.setFogEnabled(true);
            BrojeClient.CONFIG.setFogDensity(45);BrojeClient.CONFIG.setFogHeight(110);stage++;}
        else if(stage==12 && time>54500){capture("13-shadows-and-fog.png");BrojeClient.CONFIG.setFogEnabled(false);
            System.setProperty("broje.shadowDebug","mask");stage++;}
        else if(stage==13 && time>57000 && System.currentTimeMillis()-lastMotion>250){
            capture("14-motion-"+motionFrame+".png");lastMotion=System.currentTimeMillis();
            if(++motionFrame==8){stage++;prepareGrass();}}
        else if(stage==14 && System.currentTimeMillis()-grassStart>5000 && System.currentTimeMillis()-lastMotion>150) {
            capture("15-grass-"+grassFrame+".png");lastMotion=System.currentTimeMillis();
            if(++grassFrame==16){
                capture("16-grass-mask.png");System.clearProperty("broje.shadowDebug");stage++;
                grassStart=System.currentTimeMillis();
            }
        }
        else if(stage==15 && System.currentTimeMillis()-grassStart>1000) {
            capture("17-grass-shadows.png");
            commands("gamerule minecraft:advance_time false","time set 500");
            System.setProperty("broje.shadowDebug","floor");
            grassStart=System.currentTimeMillis();stage++;
        }
        else if(stage==16 && System.currentTimeMillis()-grassStart>2000 && System.currentTimeMillis()-lastMotion>150) {
            capture("18-grass-camera-"+cameraFrame+".png");grassProbes(cameraFrame);
            lastMotion=System.currentTimeMillis();
            if(++cameraFrame==16){
                // Reference views of the full lighting model, for visual review only.
                System.clearProperty("broje.shadowDebug");
                commands("fill 0 101 0 0 106 0 minecraft:stone","fill -3 101 -6 -1 104 -4 minecraft:stone_bricks",
                        "fill 4 104 -4 6 104 -2 minecraft:oak_leaves[persistent=true]","time set 2500");
                camX=7.5;camY=106;camZ=9;yaw=135;pitch=30;
                grassStart=System.currentTimeMillis();stage++;
            } else camX=0.5+cameraFrame/16.0;
        }
        else if(stage==17 && System.currentTimeMillis()-grassStart>3000) {
            capture("19-lighting-off.png");BrojeClient.CONFIG.setLightingEnabled(true);BrojeClient.CONFIG.setFoliageShadows(false);
            grassStart=System.currentTimeMillis();stage++;
        }
        else if(stage==18 && System.currentTimeMillis()-grassStart>1500) {
            capture("20-lighting-on.png");camY=140;pitch=80;grassStart=System.currentTimeMillis();stage++;
        }
        else if(stage==19 && System.currentTimeMillis()-grassStart>4000) {
            capture("21-meadow-from-above.png");
            // Entity casters: a mob, an armor stand and dropped items on the platform.
            commands("time set 2500","summon minecraft:cow -6 101 6 {NoAI:1b,Rotation:[90f,0f]}",
                    "summon minecraft:armor_stand -2 101 7 {NoGravity:1b}",
                    "summon minecraft:item 1 101.2 7 {Item:{id:\"minecraft:diamond_sword\",count:1},PickupDelay:32767,Age:-32768}",
                    "summon minecraft:item 3 101.2 7 {Item:{id:\"minecraft:oak_log\",count:1},PickupDelay:32767,Age:-32768}");
            camX=-1.5;camY=105;camZ=12.5;yaw=180;pitch=35;BrojeClient.CONFIG.setEntityShadows(true);
            grassStart=System.currentTimeMillis();stage++;
        }
        else if(stage==20 && System.currentTimeMillis()-grassStart>3000) {
            capture("22-entities.png");BrojeClient.CONFIG.setEntityShadows(false);
            grassStart=System.currentTimeMillis();stage++;
        }
        else if(stage==21 && System.currentTimeMillis()-grassStart>1500) {
            capture("23-entities-off.png");BrojeClient.CONFIG.setEntityShadows(true);
            // Close-up of grass with plant casters and GI: no banding, no shifted bright copy.
            BrojeClient.CONFIG.setFoliageShadows(true);BrojeClient.CONFIG.setLightingEnabled(true);
            commands("time set 3000");
            camX=1.5;camY=102.6;camZ=4.5;yaw=200;pitch=40;
            grassStart=System.currentTimeMillis();stage++;
        }
        else if(stage==22 && System.currentTimeMillis()-grassStart>2500) {
            capture("24-grass-gi.png");
            BrojeClient.LOGGER.info("SHADOW CHECK COMPLETE");stage++;mc.stop();
        }
        mc.player.setPos(camX,camY,camZ);
        mc.player.setYRot(yaw);mc.player.setXRot(pitch);mc.player.setOldPosAndRot();
    }
    private static void prepareGrass() {
        camX=0.5;camY=102;camZ=10;yaw=180;pitch=34;
        List<String> setup=new ArrayList<>(List.of(
                "fill -12 101 -10 12 108 10 minecraft:air", "time set 500",
                "tp @a 0.5 102.0 10.0 180 34"));
        for(int x=-8;x<=8;x+=2)for(int z=-6;z<=5;z+=2) {
            boolean tall=(x+z)%4==0;
            setup.add("setblock "+x+" 101 "+z+" minecraft:"+(tall?"tall_grass[half=lower]":"short_grass"));
            if(tall)setup.add("setblock "+x+" 102 "+z+" minecraft:tall_grass[half=upper]");
        }
        setup.add("fill 4 104 -4 6 104 -2 minecraft:oak_leaves[persistent=true]");
        commands(setup.toArray(String[]::new));
        grassStart=System.currentTimeMillis();
    }
    /** Grass on grass blocks and a dirt wall with an armor stand in front: GI copies of small casters. */
    private static void prepareGi() {
        camX=0.5;camY=102.4;camZ=4.2;yaw=180;pitch=35;
        List<String> setup=new ArrayList<>(List.of("fill -12 100 -12 12 100 12 minecraft:grass_block",
                "fill -12 101 -12 12 112 12 minecraft:air","time set 1500",
                "fill 0 101 -3 1 103 -3 minecraft:dirt",
                "summon minecraft:armor_stand 0.5 101 -1.6 {NoGravity:1b,Rotation:[20f,0f]}",
                "tp @a 0.5 102.4 4.2 180 35"));
        for(int x=-3;x<=3;x++)for(int z=0;z<=3;z++)if((x+z)%2==0)setup.add("setblock "+x+" 101 "+z+" minecraft:short_grass");
        commands(setup.toArray(String[]::new));
        grassStart=System.currentTimeMillis();
    }
    /** One capture of the same shadows per smoothing mode. */
    private static void filterFrame(Minecraft mc) {
        var config=BrojeClient.CONFIG;long t=System.currentTimeMillis()-grassStart;
        int mode=stage-80;
        if(mode<5&&t>9000+mode*2000L){
            capture("8"+mode+"-filter-"+mode+".png");
            if(mode<4)config.setShadowFilter(mode+1);
            else {config.setShadowFilter(2);BrojeClient.LOGGER.info("SHADOW CHECK COMPLETE");mc.stop();}
            stage++;
        } else if(mode==0&&t<9000)config.setShadowFilter(0);
        mc.player.setPos(camX,camY,camZ);
        mc.player.setYRot(yaw);mc.player.setXRot(pitch);mc.player.setOldPosAndRot();
    }
    private static void glassFrame(Minecraft mc) {
        var config=BrojeClient.CONFIG;long t=System.currentTimeMillis()-grassStart;
        if(stage==60&&t>9000){capture("60-glass-colored.png");config.setColoredShadows(false);stage++;}
        else if(stage==61&&t>11000){capture("61-glass-plain.png");config.setColoredShadows(true);
            commands("time set 4000");stage++;}
        else if(stage==62&&t>14000){capture("62-glass-morning.png");config.setColoredContrast(300);stage++;}
        else if(stage==63&&t>16000){capture("63-glass-contrast-300.png");config.setColoredContrast(100);config.setColoredMixing(0);stage++;}
        else if(stage==64&&t>18000){capture("64-glass-mixing-0.png");config.setColoredMixing(100);
            BrojeClient.LOGGER.info("SHADOW CHECK COMPLETE");stage++;mc.stop();}
        mc.player.setPos(camX,camY,camZ);
        mc.player.setYRot(yaw);mc.player.setXRot(pitch);mc.player.setOldPosAndRot();
    }
    private static void giFrame(Minecraft mc) {
        var config=BrojeClient.CONFIG;long t=System.currentTimeMillis()-grassStart;
        if(stage==40&&t>9000){capture("40-gi-grass.png");config.setBounceStrength(0);stage++;}
        else if(stage==41&&t>11000){capture("41-nogi-grass.png");config.setBounceStrength(100);
            camX=0.5;camY=101.6;camZ=0.2;yaw=180;pitch=5;stage++;}
        else if(stage==42&&t>14000){capture("42-gi-wall.png");config.setBounceStrength(0);stage++;}
        else if(stage==43&&t>16000){capture("43-nogi-wall.png");config.setBounceStrength(100);
            commands("fill -3 101 0 3 101 3 minecraft:air","setblock 0 101 1 minecraft:short_grass","time set 3000");
            closeUp(0);stage++;}
        // Close-ups of one tuft from four sides of the sun, with and without GI.
        else if(stage>=44&&stage<52&&t>19000+(stage-44)*2000L){
            int view=(stage-44)/2;boolean gi=(stage-44)%2==0;
            capture("4"+(4+view)+(gi?"-gi":"-nogi")+"-tuft.png");
            config.setBounceStrength(gi?0:100);if(!gi)closeUp(view+1);
            if(++stage==52){BrojeClient.LOGGER.info("SHADOW CHECK COMPLETE");mc.stop();}}
        mc.player.setPos(camX,camY,camZ);
        mc.player.setYRot(yaw);mc.player.setXRot(pitch);mc.player.setOldPosAndRot();
    }
    private static void closeUp(int view) {
        yaw=view*90;pitch=50;double a=Math.toRadians(yaw);
        // Camera 1.3 blocks back from the tuft at (0.5, 101, 1.5), looking at its base.
        camX=0.5+Math.sin(a)*1.1;camZ=1.5-Math.cos(a)*1.1;camY=101+1.1*Math.tan(Math.toRadians(pitch))-1.62;
    }
    private static void grassProbes(int frame) {
        var mc=Minecraft.getInstance();List<String> lines=new ArrayList<>();
        for(double x=-8;x<=8;x+=0.25)for(double z=-6;z<=6;z+=0.25) {
            Vector4f clip=worldTransform.transform(new Vector4f((float)(x-worldCamera.x),
                    (float)(101.002-worldCamera.y),(float)(z-worldCamera.z),1));
            float sx=(clip.x/clip.w*0.5f+0.5f)*mc.getWindow().getWidth();
            float sy=(0.5f-clip.y/clip.w*0.5f)*mc.getWindow().getHeight();
            lines.add(sx+","+sy);
        }
        try{Files.write(mc.gameDirectory.toPath().resolve("screenshots/18-grass-probes-"+frame+".txt"),lines);}
        catch(Exception exception){throw new IllegalStateException(exception);}
    }
    private static void commands(String... texts) {
        var server=Minecraft.getInstance().getSingleplayerServer();
        server.execute(()->{
            // The copied spawn can be outside this scene. Load all edited chunks before
            // /fill, otherwise Minecraft rejects the platform while single-block edits work.
            for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++)server.overworld().getChunk(x,z);
            for(String text:texts)server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),text);
        });
    }
    private static void capture(String name){var mc=Minecraft.getInstance();Screenshot.grab(mc.gameDirectory,name,mc.getMainRenderTarget(),1,
            message->BrojeClient.LOGGER.info("SHADOW CHECK {} {}",name,message.getString()));}
    private static void probes(String name) {
        var mc=Minecraft.getInstance();var state=mc.gameRenderer.getGameRenderState();var camera=state.levelRenderState.cameraRenderState;
        double sunX=-Math.sin(state.levelRenderState.skyRenderState.sunAngle);
        List<String> lines=new ArrayList<>();
        double contactX=sunX<0?1.04:-0.04;
        double[][] points=name.equals("offscreen")?new double[][]{{0.5,101.005,0.5},{0.5,101.005,4}}:
                new double[][]{{contactX,101.005,0.5},{contactX+(sunX<0?0.3:-0.3),101.005,0.5},{-4,101.005,5},{4,101.005,-5}};
        for(double[] point:points) {
            Vector4f clip=worldTransform.transform(new Vector4f((float)(point[0]-worldCamera.x),(float)(point[1]-worldCamera.y),(float)(point[2]-worldCamera.z),1));
            int x=(int)((clip.x/clip.w*0.5+0.5)*mc.getWindow().getWidth());
            int y=(int)((0.5-clip.y/clip.w*0.5)*mc.getWindow().getHeight());
            lines.add(x+","+y);
        }
        try{Path path=mc.gameDirectory.toPath().resolve("screenshots/"+name+"-probes.txt");Files.write(path,lines);}
        catch(Exception exception){throw new IllegalStateException(exception);}
    }
}
