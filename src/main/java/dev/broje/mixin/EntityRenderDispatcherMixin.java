package dev.broje.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.broje.render.ShadowRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {
    @Inject(method="submit", at=@At("HEAD"))
    private void broje$bodyShadow(EntityRenderState state, CameraRenderState camera, double x, double y, double z,
                                  PoseStack pose, SubmitNodeCollector collector, CallbackInfo ci) {
        if (ShadowRenderer.replacesVanillaShadow(x,y,z)) state.shadowPieces.clear();
    }
}
