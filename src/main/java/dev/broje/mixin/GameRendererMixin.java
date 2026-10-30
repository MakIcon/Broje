package dev.broje.mixin;

import dev.broje.render.FogRenderer;
import dev.broje.render.ShadowRenderer;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    // This point is BEFORE Minecraft clears world depth for the hand projection.
    @Inject(method = "renderLevel", at = @At(value = "INVOKE", shift = At.Shift.AFTER,
            target = "Lnet/minecraft/client/renderer/LevelRenderer;renderLevel(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lorg/joml/Matrix4fc;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;ZLnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;)V"))
    private void broje$renderFog(DeltaTracker deltaTracker, CallbackInfo ci) {
        FogRenderer.render(Minecraft.getInstance().gameRenderer.getGameRenderState(), deltaTracker);
    }

    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void broje$beginFrame(DeltaTracker deltaTracker, CallbackInfo ci) {
        ShadowRenderer.beginFrame();
        FogRenderer.beginFrame();
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void broje$closeFog(CallbackInfo ci) {
        ShadowRenderer.close();
        FogRenderer.close();
    }
}
