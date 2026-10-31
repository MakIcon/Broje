package dev.broje.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.broje.BrojeClient;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import net.vulkanmod.render.vertex.CustomVertexFormat;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.shader.Pipeline;
import net.vulkanmod.vulkan.shader.PipelineConfig;
import net.vulkanmod.vulkan.shader.PipelineState;
import net.vulkanmod.vulkan.shader.SPIRVUtils;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.util.MappedBuffer;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;

/** Analytic solar disk on the native celestial orbit, before clouds/terrain. */
public final class SunRenderer {
    private static SunRenderer instance;
    private static boolean failed;
    static final org.joml.Vector4f screen=new org.joml.Vector4f(-2,-2,0,0);
    public static void beginFrame() { screen.set(-2,-2,0,0); }
    private final MappedBuffer matrix=new MappedBuffer(64),color=new MappedBuffer(16);
    private final GraphicsPipeline pipeline;
    private SunRenderer() {
        var config=PipelineConfig.builder().addUB(PipelineConfig.UB.builder(0,VK10.VK_SHADER_STAGE_VERTEX_BIT|VK10.VK_SHADER_STAGE_FRAGMENT_BIT)
                .addUniform("mat4","SunMVP").addUniform("vec4","SunColor").build()).build();
        var builder=new Pipeline.Builder(CustomVertexFormat.NONE,"broje_sun");
        builder.setUniformSupplierGetter(info->()->info.name.equals("SunMVP")?matrix:color);
        builder.applyConfig(config);
        builder.setShaderSrc(SPIRVUtils.ShaderKind.VERTEX_SHADER,WaterRenderer.shader("sun.vsh"));
        builder.setShaderSrc(SPIRVUtils.ShaderKind.FRAGMENT_SHADER,WaterRenderer.shader("sun.fsh"));
        pipeline=builder.createGraphicsPipeline();
        pipeline.getBuffers().forEach(b->{b.setUseGlobalBuffer(true);b.setUpdate(true);});
    }
    public static boolean render(float brightness,PoseStack pose) {
        var mc=Minecraft.getInstance();var c=BrojeClient.CONFIG;
        if(failed||c==null||!c.isEnabled()||mc.level==null||!Level.OVERWORLD.equals(mc.level.dimension()))return false;
        try {
            if(instance==null)instance=new SunRenderer();
            instance.draw(brightness,pose);return true;
        }catch(RuntimeException exception){failed=true;BrojeClient.LOGGER.error("Round sun failed; retaining native sun",exception);return false;}
    }
    private void draw(float brightness,PoseStack pose) {
        Matrix4f transform=new Matrix4f(VRenderSystem.projection).mul(RenderSystem.getModelViewStack()).mul(pose.last().pose())
                .translate(0,100,0).scale(30,1,30);
        transform.get(matrix.buffer);
        var centre=transform.transform(new org.joml.Vector4f(0,0,0,1));
        if(centre.w>0.01) {
            var edgeX=transform.transform(new org.joml.Vector4f(0.19f,0,0,1));
            var edgeY=transform.transform(new org.joml.Vector4f(0,0,0.19f,1));
            screen.set(centre.x/centre.w*0.5f+0.5f,0.5f-centre.y/centre.w*0.5f,
                    (float)Math.hypot(edgeX.x/edgeX.w-centre.x/centre.w,edgeY.x/edgeY.w-centre.x/centre.w)*0.5f,
                    (float)Math.hypot(edgeX.y/edgeX.w-centre.y/centre.w,edgeY.y/edgeY.w-centre.y/centre.w)*0.5f);
        }
        var mc=Minecraft.getInstance();var c=BrojeClient.CONFIG;
        float height=SunDirection.at(mc.gameRenderer.getGameRenderState().levelRenderState.skyRenderState.sunAngle,c.getSunPathTilt(),new org.joml.Vector3f()).y;
        float warm=1-Math.clamp(height*3,0,1);
        color.putFloat(0,1);color.putFloat(4,0.94f-warm*0.28f);color.putFloat(8,0.78f-warm*0.36f);color.putFloat(12,brightness);
        var saved=new WaterRenderer.State();
        try {
            var renderer=Renderer.getInstance();renderer.getMainPass().rebindMainTarget();
            VRenderSystem.depthTest=true;VRenderSystem.depthMask=false;VRenderSystem.cull=false;
            VRenderSystem.colorMask=15;VRenderSystem.logicOp=false;
            VRenderSystem.topology=VK10.VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;VRenderSystem.polygonMode=VK10.VK_POLYGON_MODE_FILL;
            PipelineState.blendInfo=new PipelineState.BlendInfo(true,VK10.VK_BLEND_FACTOR_SRC_ALPHA,VK10.VK_BLEND_FACTOR_ONE,
                    VK10.VK_BLEND_FACTOR_ONE,VK10.VK_BLEND_FACTOR_ONE,VK10.VK_BLEND_OP_ADD);
            renderer.bindGraphicsPipeline(pipeline);renderer.uploadAndBindUBOs(pipeline);
            VK10.vkCmdDraw(Renderer.getCommandBuffer(),6,1,0,0);
        }finally{saved.restore();}
    }
    public static void close() {
        if(instance!=null){net.vulkanmod.vulkan.Vulkan.waitIdle();instance.pipeline.cleanUp();MemoryUtil.memFree(instance.matrix.buffer);MemoryUtil.memFree(instance.color.buffer);instance=null;}
        failed=false;
    }
}
