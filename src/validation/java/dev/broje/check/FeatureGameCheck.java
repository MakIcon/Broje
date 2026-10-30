package dev.broje.check;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import dev.broje.BrojeClient;
import dev.broje.config.ActionOption;
import dev.broje.config.BrojeConfig;
import dev.broje.render.ShadowRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;
import net.vulkanmod.config.gui.VOptionScreen;
import net.vulkanmod.config.option.Option;
import net.vulkanmod.config.option.OptionPage;
import net.vulkanmod.vulkan.VRenderSystem;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector4f;

/** Native integration scene. Runs only in the disposable ShadowCheck save. */
public final class FeatureGameCheck {
    private static long start, last;
    private static int stage;
    private static double cx=6.5,cy=105,cz=9;
    private static float yaw=145,pitch=35;
    private static final Matrix4f world=new Matrix4f();
    private static final Vector3d camera=new Vector3d();
    private static ActionOption reset;

    public static void captureWorldTransform() {
        var c=Minecraft.getInstance().gameRenderer.getGameRenderState().levelRenderState.cameraRenderState;
        world.set(VRenderSystem.projection).mul(c.viewRotationMatrix);camera.set(c.pos.x,c.pos.y,c.pos.z);
    }

    public static void frame() {
        Minecraft mc=Minecraft.getInstance();
        if(mc.level==null||mc.player==null||mc.getSingleplayerServer()==null)return;
        var config=BrojeClient.CONFIG;
        if(start==0) {
            start=System.currentTimeMillis();
            config.resetToDefaults();config.setEnabled(true);config.setFogEnabled(false);config.setLightingEnabled(false);
            commands("gamerule minecraft:advance_time false","gamerule minecraft:send_command_feedback false",
                    "gamemode spectator @a","kill @e[type=!minecraft:player]",
                    "fill -20 100 -20 20 100 20 minecraft:smooth_stone","fill -20 101 -20 20 116 20 minecraft:air",
                    "summon minecraft:cow 0.5 101 0.5 {NoAI:1b,Silent:1b,Rotation:[90f,0f]}",
                    "time set 2500","weather clear","tp @a 6.5 105 9 145 35");
            mc.options.bobView().set(false);mc.options.hideGui=true;
        }
        long now=System.currentTimeMillis();
        if(now-start>12000&&now-last>650) {
            last=now;
            if(stage==0) {
                check(ShadowRenderer.mapReady(),"Sun map must actually be active");
                capture("feature-entity.png");checkBlobs(false);commands("tick freeze");
                System.setProperty("broje.shadowDebug","floor");
            } else if(stage<=8) {
                capture("feature-still-"+(stage-1)+".png");probes("feature-still-"+(stage-1),false);
                // A frozen body and sun: move only the camera inside the map's grid cell.
                cx=6.5+stage*0.05;
            } else if(stage==9) {
                config.setEntityShadows(false);
            } else if(stage==10) {
                capture("feature-no-body.png");probes("feature-no-body",false);checkBlobs(true);
                System.clearProperty("broje.shadowDebug");config.setEntityShadows(true);
                mc.options.hideGui=false;openSettings();
            } else if(stage==11) {
                capture("feature-reset-button.png");
                config.setFogDensity(3);config.setColoredBrightness(180);config.setColoredBlend(0);
                var widget=reset.getWidget();
                check(widget.mouseClicked(new MouseButtonEvent(widget.getX()+20,widget.getY()+10,new MouseButtonInfo(0,0)),false),"Reset button must accept a click across its face");
                check(config.getFogDensity()==45 && config.getColoredBrightness()==100 && config.getColoredBlend()==2 && config.isEnabled(),"Button must reset all pages immediately");
                BrojeConfig saved=BrojeConfig.load(mc.gameDirectory.toPath().resolve("config/broje.json"));
                check(saved.getFogDensity()==45&&saved.getColoredBlend()==2,"Reset button must save its result");
                check(!reset.isChanged(),"Action button must never become a pending toggle");
                BrojeClient.LOGGER.info("PASS: actual reset button click, cross-page reset, persistence and master switch");
                mc.setScreen(null);mc.options.hideGui=true;
                config.setFogEnabled(false);config.setLightingEnabled(false);config.setColoredSaturation(100);
                cx=0.5;cy=104;cz=7;yaw=180;pitch=45;
                commands("tp @e[type=!minecraft:player] 1000 64 1000","fill -12 101 -12 12 112 12 minecraft:air",
                        "fill -4 105 -4 -1 105 -2 minecraft:red_stained_glass","fill -2 106 -4 1 106 -2 minecraft:blue_stained_glass",
                        "fill -4 105 1 -1 105 3 minecraft:yellow_stained_glass","fill -2 106 1 1 106 3 minecraft:cyan_stained_glass",
                        "time set 6000");
                config.setColoredBlend(0);
            } else if(stage==14) {
                capture("feature-glass-realistic.png");probes("feature-glass-realistic",true);config.setColoredBlend(1);
            } else if(stage==16) {
                capture("feature-glass-pigment.png");probes("feature-glass-pigment",true);config.setColoredBlend(2);
            } else if(stage==18) {
                capture("feature-glass-bright.png");probes("feature-glass-bright",true);config.setColoredOverlap(180);
            } else if(stage==20) {
                capture("feature-glass-overlap.png");probes("feature-glass-overlap",true);
                config.setColoredOverlap(100);config.setColoredSaturation(0);
            } else if(stage==22) {
                capture("feature-glass-grey.png");probes("feature-glass-grey",true);
                config.setColoredSaturation(160);config.setColoredBrightness(150);
            } else if(stage==24) {
                capture("feature-glass-vivid.png");probes("feature-glass-vivid",true);
                BrojeClient.LOGGER.info("FEATURE CHECK COMPLETE");mc.stop();
            }
            stage++;
        }
        mc.player.setPos(cx,cy,cz);mc.player.setYRot(yaw);mc.player.setXRot(pitch);
        mc.player.setDeltaMovement(0,0,0);mc.player.setOldPosAndRot();
    }

    private static void checkBlobs(boolean vanilla) {
        var mc=Minecraft.getInstance();
        var entity=java.util.stream.StreamSupport.stream(mc.level.entitiesForRendering().spliterator(),false)
                .filter(e->e.getType()==net.minecraft.world.entity.EntityType.COW&&Math.abs(e.getX()-0.5)<0.1).findFirst().orElseThrow();
        var dispatcher=mc.getEntityRenderDispatcher();
        var cow=dispatcher.extractEntity(entity,1);
        var camera=mc.gameRenderer.getGameRenderState().levelRenderState.cameraRenderState;
        var submits=new net.minecraft.client.renderer.SubmitNodeStorage();
        dispatcher.submit(cow,camera,cow.x-camera.pos.x,cow.y-camera.pos.y,cow.z-camera.pos.z,
                new com.mojang.blaze3d.vertex.PoseStack(),submits);
        check(cow.shadowPieces.isEmpty()!=vanilla,vanilla?"Vanilla fallback must return with body shadows off":"Body shadow must replace the vanilla disk");
        submits.clear();
        BrojeClient.LOGGER.info("PASS: vanilla contact disk fallback={}",vanilla);
    }

    @SuppressWarnings("unchecked")
    private static void openSettings() {
        try {
            Minecraft mc=Minecraft.getInstance();
            VOptionScreen screen=new VOptionScreen(Component.literal("Broje validation"),null);mc.setScreen(screen);
            var pagesField=VOptionScreen.class.getDeclaredField("optionPages");pagesField.setAccessible(true);
            List<OptionPage> pages=(List<OptionPage>)pagesField.get(screen);
            int index=-1;
            for(int i=0;i<pages.size();i++)for(var block:pages.get(i).optionBlocks)for(Option<?> option:block.options())
                if(option instanceof ActionOption action){reset=action;index=i;}
            check(index>=0,"Reset must be a real action option");
            var current=VOptionScreen.class.getDeclaredField("currentListIdx");current.setAccessible(true);current.setInt(screen,index);
            var build=VOptionScreen.class.getDeclaredMethod("buildPage");build.setAccessible(true);build.invoke(screen);
        }catch(ReflectiveOperationException exception){throw new IllegalStateException(exception);}
    }

    private static void probes(String name,boolean glass) {
        List<String> lines=new ArrayList<>();
        if(glass) {
            for(double z:new double[]{-3.1,2.1})for(double x:new double[]{-3.1,-1.1,0.9})point(lines,x,101.002,z);
        }else for(double z=-3;z<=3;z+=0.125)for(double x=-6;x<=6;x+=0.125)point(lines,x,101.002,z);
        try{Files.write(Minecraft.getInstance().gameDirectory.toPath().resolve("screenshots/"+name+".txt"),lines);}
        catch(Exception exception){throw new IllegalStateException(exception);}
    }
    private static void point(List<String> lines,double x,double y,double z) {
        var mc=Minecraft.getInstance();
        Vector4f p=world.transform(new Vector4f((float)(x-camera.x),(float)(y-camera.y),(float)(z-camera.z),1));
        lines.add((p.x/p.w*0.5+0.5)*mc.getWindow().getWidth()+","+(0.5-p.y/p.w*0.5)*mc.getWindow().getHeight());
    }
    private static void capture(String name) {
        var mc=Minecraft.getInstance();Screenshot.grab(mc.gameDirectory,name,mc.getMainRenderTarget(),1,
                message->BrojeClient.LOGGER.info("FEATURE CHECK {}",name));
    }
    private static void commands(String... commands) {
        var server=Minecraft.getInstance().getSingleplayerServer();server.execute(()->{
            for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++)server.overworld().getChunk(x,z);
            for(String text:commands)server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),text);
        });
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
