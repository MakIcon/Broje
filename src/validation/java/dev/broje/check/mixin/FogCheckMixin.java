package dev.broje.check.mixin;

import dev.broje.check.FogGameCheck;
import dev.broje.check.ShadowGameCheck;
import dev.broje.check.AtmosphereGameCheck;
import dev.broje.check.FeatureGameCheck;

import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.DeltaTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public class FogCheckMixin {
    @Inject(method = "renderLevel", at = @At(value = "INVOKE", shift = At.Shift.AFTER,
            target = "Lnet/minecraft/client/renderer/LevelRenderer;renderLevel(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lorg/joml/Matrix4fc;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;ZLnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;)V"))
    private void broje$worldTransform(DeltaTracker deltaTracker, CallbackInfo ci) {
        if(Boolean.getBoolean("broje.shadowCheck"))ShadowGameCheck.captureWorldTransform();
        if(Boolean.getBoolean("broje.featureCheck"))FeatureGameCheck.captureWorldTransform();
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void broje$check(CallbackInfo ci) {
        if(Boolean.getBoolean("broje.featureCheck")) FeatureGameCheck.frame();
        else if(Boolean.getBoolean("broje.atmosphereCheck")) AtmosphereGameCheck.frame();
        else if(Boolean.getBoolean("broje.shadowCheck"))ShadowGameCheck.frame();else FogGameCheck.frame();
    }
}
