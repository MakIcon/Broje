package dev.broje.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.broje.render.WaterRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ScreenEffectRenderer.class)
public abstract class WaterOverlayMixin {
    @Inject(method="renderWater",at=@At("HEAD"),cancellable=true)
    private static void broje$clearWater(Minecraft mc,PoseStack pose,MultiBufferSource buffers,CallbackInfo ci) {
        if(WaterRenderer.wanted(mc.gameRenderer.getGameRenderState()))ci.cancel();
    }
}
