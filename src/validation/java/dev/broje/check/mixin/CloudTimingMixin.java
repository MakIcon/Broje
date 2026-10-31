package dev.broje.check.mixin;

import dev.broje.check.FogGpuTimer;
import dev.broje.render.CloudRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = CloudRenderer.class, remap = false)
public class CloudTimingMixin {
    @Inject(method = "draw", at = @At("HEAD"))
    private void broje$startClouds(CallbackInfo ci) {
        if (Boolean.getBoolean("broje.cloudPerformanceCheck")) FogGpuTimer.begin();
    }

    @Inject(method = "draw", at = @At("RETURN"))
    private void broje$endClouds(CallbackInfo ci) {
        if (Boolean.getBoolean("broje.cloudPerformanceCheck")) FogGpuTimer.end();
    }
}
