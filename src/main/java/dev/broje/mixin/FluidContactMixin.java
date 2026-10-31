package dev.broje.mixin;

import dev.broje.render.WaterMedium;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.vulkanmod.render.chunk.build.renderer.FluidRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value=FluidRenderer.class,remap=false)
public abstract class FluidContactMixin {
    @Inject(method="getHeight(Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/world/level/material/Fluid;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)F",at=@At("RETURN"),cancellable=true)
    private void broje$seal(BlockAndTintGetter level,Fluid fluid,BlockPos pos,BlockState state,CallbackInfoReturnable<Float> cir) {
        if(WaterMedium.enabled()&&state.getFluidState().is(FluidTags.WATER)&&WaterMedium.sealed(level,pos))cir.setReturnValue(1f);
    }
}
