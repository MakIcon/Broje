package dev.broje.mixin;

import dev.broje.BrojeClient;
import dev.broje.render.WaterRenderer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FogType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FogRenderer.class)
public abstract class WaterFogMixin {
    // Use the native AIR sky/cloud color throughout the waterline. Broje's depth
    // pass owns the water volume; a water-colored sky source tinted clouds purple.
    @Inject(method="getFogType",at=@At("RETURN"),cancellable=true)
    private void broje$airSky(Camera camera,CallbackInfoReturnable<FogType> cir) {
        var mc=Minecraft.getInstance();var config=BrojeClient.CONFIG;
        if(cir.getReturnValue()!=FogType.WATER||mc.level==null||config==null||!config.isEnabled()||!config.isWaterEnabled()
                ||!WaterRenderer.healthy()||!Level.OVERWORLD.equals(mc.level.dimension()))return;
        if(camera.entity() instanceof LivingEntity living&&(living.hasEffect(MobEffects.BLINDNESS)||living.hasEffect(MobEffects.DARKNESS)))return;
        cir.setReturnValue(FogType.ATMOSPHERIC);
    }
    // The underwater post-pass owns absorption. Remove the native blue distance
    // curtain before its UBO is uploaded, retaining the render-distance boundary.
    @Inject(method="setupFog",at=@At("RETURN"))
    private void broje$waterMedium(Camera camera,int distance,DeltaTracker delta,float dark,ClientLevel level,
                                   CallbackInfoReturnable<FogData> cir) {
        var config=BrojeClient.CONFIG;
        if(config==null||!config.isEnabled()||!config.isWaterEnabled()||!WaterRenderer.healthy()||!Level.OVERWORLD.equals(level.dimension())
                ||camera.getFluidInCamera()!=FogType.WATER)return;
        if(camera.entity() instanceof LivingEntity living&&(living.hasEffect(MobEffects.BLINDNESS)||living.hasEffect(MobEffects.DARKNESS)))return;
        FogData fog=cir.getReturnValue();fog.environmentalStart=100000;fog.environmentalEnd=100001;
    }
}
