package dev.broje.mixin;

import dev.broje.render.FogRenderer;
import net.vulkanmod.render.chunk.WorldRenderer;
import net.vulkanmod.render.vertex.TerrainRenderType;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = WorldRenderer.class, remap = false)
public abstract class WorldRendererMixin {
    // VulkanMod rebinds the main target first thing in this method, so the fog may end
    // the current pass here to keep the opaque depth before glass and water are drawn.
    @Inject(method = "renderSectionLayer", at = @At("HEAD"))
    private void broje$opaqueDepth(TerrainRenderType type, double x, double y, double z, Matrix4f modelView,
                                   Matrix4f projection, CallbackInfo ci) {
        if (type == TerrainRenderType.TRANSLUCENT) FogRenderer.snapshotOpaqueDepth();
    }
}
