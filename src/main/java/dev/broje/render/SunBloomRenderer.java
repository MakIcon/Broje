package dev.broje.render;

import dev.broje.BrojeClient;
import net.minecraft.world.level.material.FogType;
import net.minecraft.client.renderer.state.GameRenderState;
import net.vulkanmod.render.vertex.CustomVertexFormat;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.framebuffer.RenderPass;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.shader.Pipeline;
import net.vulkanmod.vulkan.shader.PipelineConfig;
import net.vulkanmod.vulkan.shader.PipelineState;
import net.vulkanmod.vulkan.shader.SPIRVUtils;
import net.vulkanmod.vulkan.shader.descriptor.ImageDescriptor;
import net.vulkanmod.vulkan.texture.VulkanImage;
import net.vulkanmod.vulkan.util.MappedBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import java.util.IdentityHashMap;
import java.util.Map;

/** Solar-only HDR extraction and five reduced-resolution bloom scales. */
public final class SunBloomRenderer {
    private static SunBloomRenderer instance;
    private static boolean failed;
    private final MappedBuffer parameters=new MappedBuffer(16),solar=new MappedBuffer(16),state=new MappedBuffer(16);
    private final GraphicsPipeline extract,down,flux,add;
    private final Framebuffer[] levels=new Framebuffer[5];
    private VulkanImage source;
    private final Map<VulkanImage,Long> depthViews=new IdentityHashMap<>();
    private final Map<VulkanImage,Framebuffer> mainColors=new IdentityHashMap<>();
    private Framebuffer energy;
    private RenderPass smallPass,addPass;
    private VulkanImage depth;
    private int width,height;
    private SunBloomRenderer() {
        extract=create("extract");down=create("down");flux=create("flux");add=create("add");
        Renderer.getInstance().addOnResizeCallback(this::destroyTargets);
    }
    public static String shader(String name) {
        return WaterRenderer.shader(name).replace("// BLOOM_COMMON",WaterRenderer.shader("sun_bloom_common.glsl"));
    }
    private GraphicsPipeline create(String name) {
        var config=PipelineConfig.builder().addUB(PipelineConfig.UB.builder(0,VK10.VK_SHADER_STAGE_FRAGMENT_BIT)
                .addUniform("vec4","BloomTarget").addUniform("vec4","SolarSource").addUniform("vec4","BloomState").build());
        config.addImageDescriptor(1,"sampler2D","Source0",11);
        // VulkanMod's descriptor layout requires contiguous binding numbers.
        if(name.equals("extract"))config.addImageDescriptor(2,"sampler2D","SceneDepth",11);
        var builder=new Pipeline.Builder(CustomVertexFormat.NONE,"broje_sun_bloom_"+name);
        builder.setUniformSupplierGetter(info->()->switch(info.name){case "BloomTarget"->parameters;case "SolarSource"->solar;default->state;});
        builder.applyConfig(config.build());
        for(int i=0;i<builder.getImageDescriptors().size();i++) {
            final boolean isDepth=i==1;
            builder.getImageDescriptors().set(i,new ImageDescriptor(isDepth?2:1,"sampler2D",isDepth?"SceneDepth":"Source0",11,VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER){
                @Override public VulkanImage getImage(){return isDepth?depth:source;}
                @Override public long getImageView(VulkanImage image) {
                    if(!isDepth||!VulkanImage.isDepthFormat(image.format))return super.getImageView(image);
                    return depthViews.computeIfAbsent(image,d->VulkanImage.createImageView(d.getId(),d.format,VK10.VK_IMAGE_ASPECT_DEPTH_BIT,1,1));
                }
            });
        }
        builder.setShaderSrc(SPIRVUtils.ShaderKind.VERTEX_SHADER,WaterRenderer.shader("fog.vsh"));
        builder.setShaderSrc(SPIRVUtils.ShaderKind.FRAGMENT_SHADER,shader("sun_bloom_"+name+".fsh"));
        var result=builder.createGraphicsPipeline();result.getBuffers().forEach(b->{b.setUseGlobalBuffer(true);b.setUpdate(true);});return result;
    }
    public static void render(GameRenderState game) {
        var c=BrojeClient.CONFIG;var source=SunRenderer.screen;
        if(failed||c==null||!c.isEnabled()||c.getSunBloomStrength()==0||source.z<=0||Integer.getInteger("broje.waterDebug",0)!=0)return;
        if(source.x<-source.z||source.x>1+source.z||source.y<-source.w||source.y>1+source.w)return;
        try {if(instance==null)instance=new SunBloomRenderer();instance.draw(game);}
        catch(RuntimeException error){failed=true;BrojeClient.LOGGER.error("Solar bloom failed; retaining the solar disk",error);}
    }
    private void ensure(Framebuffer main) {
        if(energy!=null&&width==main.getWidth()&&height==main.getHeight())return;
        destroyTargets();width=main.getWidth();height=main.getHeight();
        for(int i=0;i<5;i++)levels[i]=Framebuffer.builder(Math.max(1,(width+(1<<(i+2))-1)>>(i+2)),
                Math.max(1,(height+(1<<(i+2))-1)>>(i+2)),1,false).setFormat(VK10.VK_FORMAT_R16G16B16A16_SFLOAT).setLinearFiltering(true).build();
        energy=Framebuffer.builder(1,1,1,false)
                .setFormat(VK10.VK_FORMAT_R16G16B16A16_SFLOAT).setLinearFiltering(true).build();
        if(smallPass==null)smallPass=pass(levels[0],false);
    }
    private static RenderPass pass(Framebuffer target,boolean load) {
        var builder=RenderPass.builder(target);
        builder.getColorAttachmentInfo().setOps(load?VK10.VK_ATTACHMENT_LOAD_OP_LOAD:VK10.VK_ATTACHMENT_LOAD_OP_DONT_CARE,VK10.VK_ATTACHMENT_STORE_OP_STORE);
        return builder.build();
    }
    private void draw(GameRenderState game) {
        var renderer=Renderer.getInstance();var main=renderer.getMainPass().getMainFramebuffer();
        ensure(main);
        VulkanImage opaque=WaterRenderer.opaqueDepthImage();
        depth=opaque!=null?opaque:main.getDepthAttachment();
        var c=BrojeClient.CONFIG;
        solar.putFloat(0,SunRenderer.screen.x);solar.putFloat(4,SunRenderer.screen.y);solar.putFloat(8,SunRenderer.screen.z);solar.putFloat(12,SunRenderer.screen.w);
        state.putFloat(0,game.levelRenderState.cameraRenderState.fogType==FogType.WATER?1:0);
        source=main.getColorAttachment();
        renderer.endRenderPass();
        try(var stack=MemoryStack.stackPush()) {
            WaterRenderer.read(main.getColorAttachment(),false,stack);WaterRenderer.read(depth,opaque==null,stack);
            drawPass(extract,levels[0],smallPass,false,stack);
            WaterRenderer.read(levels[0].getColorAttachment(),false,stack);
            WaterRenderer.attachment(main.getColorAttachment(),false,stack);
            if(opaque==null)WaterRenderer.attachment(depth,true,stack);
            for(int i=1;i<5;i++) {
                source=levels[i-1].getColorAttachment();
                drawPass(down,levels[i],smallPass,false,stack);WaterRenderer.read(levels[i].getColorAttachment(),false,stack);
            }
            source=levels[4].getColorAttachment();
            drawPass(flux,energy,smallPass,false,stack);WaterRenderer.read(energy.getColorAttachment(),false,stack);
            source=energy.getColorAttachment();
            var colorOnly=mainColors.computeIfAbsent(main.getColorAttachment(),color->Framebuffer.builder(color,null).build());
            if(addPass==null)addPass=pass(colorOnly,true);
            drawPass(add,colorOnly,addPass,true,stack);
        } finally {renderer.getMainPass().rebindMainTarget();}
    }
    private void drawPass(GraphicsPipeline pipeline,Framebuffer target,RenderPass pass,boolean additive,MemoryStack stack) {
        var c=BrojeClient.CONFIG;
        parameters.putFloat(0,target.getWidth());parameters.putFloat(4,target.getHeight());
        parameters.putFloat(8,c.getSunBloomStrength()*0.01f);parameters.putFloat(12,c.getSunBloomRadius()*0.01f);
        var renderer=Renderer.getInstance();var saved=new WaterRenderer.State();
        try {
            renderer.beginRenderPass(pass,target);
            Renderer.setViewport(0,0,target.getWidth(),target.getHeight(),stack);Renderer.setScissor(0,0,target.getWidth(),target.getHeight());
            VRenderSystem.depthTest=false;VRenderSystem.depthMask=false;VRenderSystem.cull=false;
            VRenderSystem.colorMask=15;VRenderSystem.logicOp=false;VRenderSystem.topology=VK10.VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
            VRenderSystem.polygonMode=VK10.VK_POLYGON_MODE_FILL;
            PipelineState.blendInfo=additive?new PipelineState.BlendInfo(true,VK10.VK_BLEND_FACTOR_ONE,VK10.VK_BLEND_FACTOR_ONE,
                    VK10.VK_BLEND_FACTOR_ZERO,VK10.VK_BLEND_FACTOR_ONE,VK10.VK_BLEND_OP_ADD):new PipelineState.BlendInfo(false,1,0,1,0,0);
            renderer.bindGraphicsPipeline(pipeline);renderer.uploadAndBindUBOs(pipeline);VK10.vkCmdDraw(Renderer.getCommandBuffer(),3,1,0,0);
        }finally{renderer.endRenderPass();saved.restore();}
    }
    private void destroyTargets() {
        Vulkan.waitIdle();
        for(int i=0;i<5;i++)if(levels[i]!=null){levels[i].cleanUp();levels[i]=null;}
        if(energy!=null){energy.cleanUp();energy=null;}
        // These wrappers borrow Minecraft's attachment. Free only the wrapper,
        // otherwise the engine later frees the same native image a second time.
        mainColors.values().forEach(target->target.cleanUp(false));mainColors.clear();
        depthViews.values().forEach(view->VK10.vkDestroyImageView(Vulkan.getVkDevice(),view,null));depthViews.clear();
    }
    public static void close() {
        if(instance!=null){instance.destroyTargets();
            if(instance.smallPass!=null)instance.smallPass.cleanUp();if(instance.addPass!=null)instance.addPass.cleanUp();
            for(var pipeline:new GraphicsPipeline[]{instance.extract,instance.down,instance.flux,instance.add})pipeline.cleanUp();
            MemoryUtil.memFree(instance.parameters.buffer);MemoryUtil.memFree(instance.solar.buffer);MemoryUtil.memFree(instance.state.buffer);instance=null;}
        failed=false;
    }
}
