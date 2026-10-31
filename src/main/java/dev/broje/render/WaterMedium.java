package dev.broje.render;

import dev.broje.BrojeClient;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/** Water contact rules shared by mesh building and camera classification. */
public final class WaterMedium {
    private WaterMedium() {}
    public static boolean enabled() {
        var c=BrojeClient.CONFIG;
        var level=net.minecraft.client.Minecraft.getInstance().level;
        return c!=null&&c.isEnabled()&&c.isWaterEnabled()&&WaterRenderer.healthy()
                &&level!=null&&Level.OVERWORLD.equals(level.dimension());
    }
    public static boolean sealed(BlockGetter level,BlockPos pos) {
        BlockState above=level.getBlockState(pos.above());
        return level.getFluidState(pos).is(FluidTags.WATER)&&above.isSolidRender();
    }
    public static float surface(Level level,BlockPos eye,float fallback) {
        float result=fallback;
        boolean found=false;
        // A submerged log is a ceiling, not the ocean's free surface. Connected
        // neighbouring water columns retain the actual level above that ceiling.
        for(int dz=-1;dz<=1;dz++)for(int dx=-1;dx<=1;dx++) {
            var cursor=eye.offset(dx,0,dz).mutable();
            if(!level.getFluidState(cursor).is(FluidTags.WATER))cursor.move(0,-1,0);
            if(!level.getFluidState(cursor).is(FluidTags.WATER))continue;
            float top=cursor.getY();
            for(int i=0;i<128&&level.getFluidState(cursor).is(FluidTags.WATER);i++) {
                top=cursor.getY()+(sealed(level,cursor)?1:level.getFluidState(cursor).getHeight(level,cursor));cursor.move(0,1,0);
            }
            if(!found||top>result){result=top;found=true;}
        }
        return result;
    }
}
