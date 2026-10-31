package dev.broje.mixin;

import dev.broje.render.CloudRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = net.vulkanmod.render.sky.CloudRenderer.class, remap = false)
public abstract class VanillaCloudsMixin {
    // VulkanMod draws the flat layer in place of Minecraft's; the volumetric pass replaces both.
    @Inject(method = "renderClouds", at = @At("HEAD"), cancellable = true)
    private void broje$volumetricClouds(CallbackInfo ci) {
        if (CloudRenderer.replacesVanilla()) ci.cancel();
    }
}
