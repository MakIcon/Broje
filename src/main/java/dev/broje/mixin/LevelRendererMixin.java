package dev.broje.mixin;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import dev.broje.render.ShadowRenderer;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
    // Before the frame graph is built: no world pass has been recorded yet and the
    // entity submit storage is still empty, so the sun map can use both.
    @Inject(method = "renderLevel", at = @At(value = "INVOKE", ordinal = 0, shift = At.Shift.AFTER,
            target = "Lcom/mojang/blaze3d/systems/RenderSystem;getModelViewStack()Lorg/joml/Matrix4fStack;"))
    private void broje$sunMap(GraphicsResourceAllocator allocator, DeltaTracker deltaTracker, boolean renderOutline,
                              CameraRenderState camera, Matrix4fc modelView, GpuBufferSlice terrainFog, Vector4f fogColor,
                              boolean renderSky, ChunkSectionsToRender sections, CallbackInfo ci) {
        ShadowRenderer.renderFrame(deltaTracker);
    }
}
