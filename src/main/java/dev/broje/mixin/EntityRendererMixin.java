package dev.broje.mixin;

import dev.broje.render.ShadowRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin {
    // The extra sun view does not need Minecraft's world/block search for a blob.
    @Inject(method="extractShadow", at=@At("HEAD"), cancellable=true)
    private void broje$skipSunContactDisk(EntityRenderState state, Minecraft minecraft, Level level, CallbackInfo ci) {
        if (ShadowRenderer.renderingEntities()) { state.shadowPieces.clear(); ci.cancel(); }
    }
}
