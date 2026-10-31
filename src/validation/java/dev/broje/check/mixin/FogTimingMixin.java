package dev.broje.check.mixin;

import dev.broje.check.FogGpuTimer;
import dev.broje.render.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = FogRenderer.class, remap = false)
public class FogTimingMixin {
    @Inject(method = "draw", at = @At("HEAD"))
    private void broje$startFog(CallbackInfo ci) {
        if (Boolean.getBoolean("broje.fogPerformanceCheck")) FogGpuTimer.begin();
    }

    @Inject(method = "draw", at = @At("RETURN"))
    private void broje$endFog(CallbackInfo ci) {
        if (Boolean.getBoolean("broje.fogPerformanceCheck")) FogGpuTimer.end();
    }
}
