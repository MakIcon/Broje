package dev.broje.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.broje.BrojeClient;
import net.minecraft.client.renderer.SkyRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SkyRenderer.class)
public abstract class SkyRendererMixin {
    @Inject(method="renderSun",at=@At("HEAD"),cancellable=true)
    private void broje$roundSun(float brightness,PoseStack pose,CallbackInfo ci) {
        if(dev.broje.render.SunRenderer.render(brightness,pose))ci.cancel();
    }
    @Inject(method = "renderSunMoonAndStars", at = @At("HEAD"))
    private void broje$tiltOrbit(PoseStack pose, float sun, float moon, float stars,
                                net.minecraft.world.level.MoonPhase phase, float rain, float brightness, CallbackInfo ci) {
        pose.pushPose();
        if (BrojeClient.CONFIG != null && BrojeClient.CONFIG.isEnabled())
            pose.mulPose(Axis.XP.rotationDegrees(BrojeClient.CONFIG.getSunPathTilt()));
    }

    @Inject(method = "renderSunMoonAndStars", at = @At("RETURN"))
    private void broje$restoreOrbit(PoseStack pose, float sun, float moon, float stars,
                                   net.minecraft.world.level.MoonPhase phase, float rain, float brightness, CallbackInfo ci) {
        pose.popPose();
    }
}
