package dev.broje.mixin;

import dev.broje.render.WaterMedium;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FogType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Camera.class)
public abstract class WaterCameraMixin {
    @Inject(method="getFluidInCamera",at=@At("RETURN"),cancellable=true)
    private void broje$sealedWater(CallbackInfoReturnable<FogType> cir) {
        var level=Minecraft.getInstance().level;
        if(cir.getReturnValue()!=FogType.NONE||level==null||!Level.OVERWORLD.equals(level.dimension())||!WaterMedium.enabled())return;
        var camera=(Camera)(Object)this;
        if(WaterMedium.sealed(level,BlockPos.containing(camera.position())))cir.setReturnValue(FogType.WATER);
    }
}
