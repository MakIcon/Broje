package dev.broje.render;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.broje.BrojeClient;
import dev.broje.config.BrojeConfig;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.BlockPos;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.material.FogType;
import net.vulkanmod.render.chunk.ChunkArea;
import net.vulkanmod.render.chunk.RenderSection;
import net.vulkanmod.render.chunk.WorldRenderer;
import net.vulkanmod.render.chunk.util.StaticQueue;
import net.vulkanmod.render.engine.VkGpuTexture;
import net.vulkanmod.render.vertex.CustomVertexFormat;
import net.vulkanmod.render.vertex.TerrainRenderType;
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
import net.vulkanmod.vulkan.texture.VTextureSelector;
import net.vulkanmod.vulkan.texture.VulkanImage;
import net.vulkanmod.vulkan.util.MappedBuffer;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3d;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;

/** Independent forward water material plus a depth-limited underwater medium. */
public final class WaterRenderer {
    private static WaterRenderer instance;
    private static boolean failed, active, snapshotReady;
    private final Map<String, MappedBuffer> uniforms = new LinkedHashMap<>();
    private final Map<VulkanImage, Long> depthViews = new IdentityHashMap<>();
    private final Map<VulkanImage, Framebuffer> mainColors = new IdentityHashMap<>();
    private final Matrix4f vp = new Matrix4f();
    private final GraphicsPipeline terrain, mirrorTerrain, copyColor, copyDepth, underwater;
    private final Map<ChunkArea, StaticQueue<RenderSection>> mirrorQueues = new IdentityHashMap<>();
    private Object mirrorGrid;
    private boolean mirrorRendering;
    private boolean nearSurface;
    private long frameNumber,probeFrame=-100;
    private double probeX,probeZ;
    private float probePlane,probeRange;
    private VulkanImage sourceColor, sourceDepth;
    private Framebuffer colorSnapshot, depthSnapshot;
    private Framebuffer mirrorTarget;
    private RenderPass colorCopyPass, depthCopyPass, underwaterPass, mirrorPass;

    private WaterRenderer() {
        for (String name : new String[]{"WaterVP", "WaterInverseVP", "BrojeShadowMatrix"}) uniforms.put(name,new MappedBuffer(64));
        for (String name : new String[]{"WaterCamera","WaterOptics","WaterEffects","WaterSun","WaterSky","WaterAmbient",
                "WaterStill","WaterFlow","WaterOverlay","WaterShadow","WaterMedium","WaterScreen","WaterReflection","WaterPerformance","WaterProbe","WaterCloudSky"}) uniforms.put(name,new MappedBuffer(16));
        int vertex=VK10.VK_SHADER_STAGE_VERTEX_BIT, fragment=VK10.VK_SHADER_STAGE_FRAGMENT_BIT;
        PipelineConfig.Builder config=PipelineConfig.builder()
                .addUB(PipelineConfig.UB.builder(0,vertex).addUniform("mat4","MVP").build())
                .addUB(PipelineConfig.UB.builder(1,fragment).addUniform("vec4","FogColor")
                        .addUniform("float","FogEnvironmentalStart").addUniform("float","FogEnvironmentalEnd")
                        .addUniform("float","FogRenderDistanceStart").addUniform("float","FogRenderDistanceEnd")
                        .addUniform("float","AlphaCutout").build())
                .addUB(PipelineConfig.UB.builder(2,vertex).setSize(4096).build())
                .setPushConstants(PipelineConfig.UB.builder(0,vertex).addUniform("vec3","ModelOffset").build())
                .addImageDescriptor(4,"sampler2D","Sampler0",0)
                .addImageDescriptor(5,"sampler2D","Sampler2",2);
        addWaterConfig(config,vertex|fragment,false);
        terrain=create("water",CustomVertexFormat.COMPRESSED_TERRAIN,config.build(),"water.vsh","water.fsh");
        mirrorTerrain=create("water_mirror",CustomVertexFormat.COMPRESSED_TERRAIN,config.build(),"terrain.vsh","water_mirror.fsh");
        PipelineConfig.Builder post=PipelineConfig.builder();addWaterConfig(post,fragment,true);
        underwater=create("underwater",CustomVertexFormat.NONE,post.build(),"fog.vsh","underwater.fsh");
        PipelineConfig copy=PipelineConfig.builder().addImageDescriptor(0,"sampler2D","Source",0).build();
        copyColor=create("water_copy",CustomVertexFormat.NONE,copy,"fog.vsh","water_copy.fsh");
        copyColor.getImageDescriptors().set(0,new Image(0,"Source",()->sourceColor,false));
        copyDepth=create("water_depth",CustomVertexFormat.NONE,copy,"fog.vsh","water_copy.fsh");
        copyDepth.getImageDescriptors().set(0,new Image(0,"Source",()->sourceDepth,true));
        Renderer.getInstance().addOnResizeCallback(()->{
            Vulkan.waitIdle();closeDepthViews();closeMainColors();snapshotReady=false;
        });
        BrojeClient.LOGGER.info("Broje water: independent waves, SSR, refraction and underwater pipelines compiled.");
    }

    private void addWaterConfig(PipelineConfig.Builder config,int stages,boolean post) {
        var ub=PipelineConfig.UB.builder(post?0:3,stages);
        uniforms.forEach((name,buffer)->ub.addUniform(buffer.buffer.capacity()==64?"mat4":"vec4",name));
        config.addUB(ub.build()).addImageDescriptor(post?1:6,"sampler2D","WaterColor",11)
                .addImageDescriptor(post?2:7,"sampler2D","WaterDepth",11).addImageDescriptor(post?3:8,"sampler2D","WaterSunDepth",11)
                .addImageDescriptor(post?4:9,"sampler2D","WaterMirror",11)
                .addImageDescriptor(post?5:10,"sampler2D","WaterOpaqueDepth",11)
                .addImageDescriptor(post?6:11,"sampler2D","WaterClouds",11);
    }

    private GraphicsPipeline create(String name,com.mojang.blaze3d.vertex.VertexFormat format,PipelineConfig config,String vert,String frag) {
        Pipeline.Builder builder=new Pipeline.Builder(format,"broje:"+name);
        builder.setUniformSupplierGetter(info->uniforms.containsKey(info.name)?()->uniforms.get(info.name):null);
        builder.applyConfig(config);
        for(int i=0;i<builder.getImageDescriptors().size();i++) {
            int binding=builder.getImageDescriptors().get(i).getBinding();
            String sampler=builder.getImageDescriptors().get(i).name;
            if(sampler.equals("WaterColor"))builder.getImageDescriptors().set(i,new Image(binding,sampler,()->colorSnapshot.getColorAttachment(),false));
            if(sampler.equals("WaterDepth"))builder.getImageDescriptors().set(i,new Image(binding,sampler,
                    ()->name.equals("underwater")?sourceDepth:depthSnapshot.getColorAttachment(),true));
            if(sampler.equals("WaterOpaqueDepth"))builder.getImageDescriptors().set(i,new Image(binding,sampler,()->depthSnapshot.getColorAttachment(),false));
            if(sampler.equals("WaterSunDepth"))builder.getImageDescriptors().set(i,new Image(binding,sampler,()->{
                VulkanImage map=ShadowRenderer.mapImage();return map!=null?map:VTextureSelector.getWhiteTexture();
            },true));
            if(sampler.equals("WaterMirror"))builder.getImageDescriptors().set(i,new Image(binding,sampler,
                    ()->mirrorTarget!=null&&!mirrorRendering?mirrorTarget.getColorAttachment():VTextureSelector.getWhiteTexture(),false));
            if(sampler.equals("WaterClouds"))builder.getImageDescriptors().set(i,new Image(binding,sampler,()->{
                VulkanImage sky=CloudRenderer.skyMap();return sky!=null?sky:VTextureSelector.getWhiteTexture();
            },false));
        }
        builder.setShaderSrc(SPIRVUtils.ShaderKind.VERTEX_SHADER,shader(vert));
        builder.setShaderSrc(SPIRVUtils.ShaderKind.FRAGMENT_SHADER,shader(frag));
        GraphicsPipeline result=builder.createGraphicsPipeline();
        result.getBuffers().forEach(b->{b.setUseGlobalBuffer(true);b.setUpdate(true);});
        return result;
    }

    public static String shader(String name) {
        String source=resource(name);
        source=source.replace("// WATER_COMMON",resource("water_common.glsl"));
        if(name.equals("underwater.fsh"))source=source.replace("binding=3) uniform WaterUniforms","binding=0) uniform WaterUniforms")
                .replace("binding=6) uniform sampler2D WaterColor","binding=1) uniform sampler2D WaterColor")
                .replace("binding=7) uniform sampler2D WaterDepth","binding=2) uniform sampler2D WaterDepth")
                .replace("binding=8) uniform sampler2D WaterSunDepth","binding=3) uniform sampler2D WaterSunDepth")
                .replace("binding=9) uniform sampler2D WaterMirror","binding=4) uniform sampler2D WaterMirror")
                .replace("binding=10) uniform sampler2D WaterOpaqueDepth","binding=5) uniform sampler2D WaterOpaqueDepth")
                .replace("binding=11) uniform sampler2D WaterClouds","binding=6) uniform sampler2D WaterClouds");
        return source;
    }
    private static String resource(String name) {
        try(var stream=WaterRenderer.class.getResourceAsStream("/assets/broje/shaders/"+name)) {
            if(stream==null)throw new IOException(name);
            return new String(stream.readAllBytes(),StandardCharsets.UTF_8);
        } catch(IOException exception){throw new IllegalStateException("Water shader "+name,exception);}
    }
    public static boolean wanted(GameRenderState state) {
        var mc=Minecraft.getInstance();var c=BrojeClient.CONFIG;var camera=state.levelRenderState.cameraRenderState;
        return !failed&&c!=null&&c.isEnabled()&&c.isWaterEnabled()&&mc.level!=null&&Level.OVERWORLD.equals(mc.level.dimension())
                &&(camera.fogType==FogType.NONE||camera.fogType==FogType.WATER)&&!camera.entityRenderState.doesMobEffectBlockSky;
    }
    public static boolean healthy() { return !failed; }
    public static void beginFrame() { active=false;snapshotReady=false;if(instance!=null)instance.frameNumber++; }
    public static void prepare(GameRenderState state) {
        if(!wanted(state))return;
        try {
            if(instance==null)instance=new WaterRenderer();
            active=true;
        } catch(RuntimeException exception){failed=true;BrojeClient.LOGGER.error("Water initialization failed; keeping VulkanMod water",exception);}
    }
    public static GraphicsPipeline terrainPipeline() { return active&&snapshotReady?instance.terrain:null; }
    static VulkanImage opaqueDepthImage() { return active&&snapshotReady&&instance.depthSnapshot!=null?instance.depthSnapshot.getColorAttachment():null; }

    /** Called before translucent terrain, after native opaque geometry, entities and sky. */
    public static void snapshot(Matrix4f modelView,Matrix4f projection) {
        if(!active||snapshotReady)return;
        try {
            instance.vp.set(projection).mul(modelView);
            instance.updateUniforms();
            instance.capture(true);
            if(instance.uniforms.get("WaterReflection").getFloat(0)>0.5)
                instance.reflectUnderwater(modelView,projection);
            snapshotReady=true;
        } catch(RuntimeException exception){failed=true;active=false;BrojeClient.LOGGER.error("Water snapshot failed",exception);}
    }

    private void ensureTargets(Framebuffer main) {
        if(colorSnapshot!=null&&colorSnapshot.getWidth()==main.getWidth()&&colorSnapshot.getHeight()==main.getHeight())return;
        Vulkan.waitIdle();
        if(colorSnapshot!=null){colorSnapshot.cleanUp();depthSnapshot.cleanUp();}
        colorSnapshot=Framebuffer.builder(main.getWidth(),main.getHeight(),1,false)
                .setFormat(VK10.VK_FORMAT_R16G16B16A16_SFLOAT).setLinearFiltering(true).build();
        depthSnapshot=Framebuffer.builder(main.getWidth(),main.getHeight(),1,false).setFormat(VK10.VK_FORMAT_R32_SFLOAT).build();
        if(colorCopyPass==null){colorCopyPass=pass(colorSnapshot,false);depthCopyPass=pass(depthSnapshot,false);}
    }

    /** A persistent color/height field supplies off-screen seabed geometry. */
    private void reflectUnderwater(Matrix4f modelView,Matrix4f projection) {
        Renderer renderer=Renderer.getInstance();
        var mc=Minecraft.getInstance();var camera=mc.gameRenderer.getGameRenderState().levelRenderState.cameraRenderState;
        var world=WorldRenderer.getInstance();
        if(world==null||world.getSectionGrid()==null)return;
        var config=BrojeClient.CONFIG;
        int width=config.getUnderwaterReflectionResolution(),height=width;
        float surface=uniforms.get("WaterMedium").getFloat(0);
        int range=config.getUnderwaterReflectionDistance();
        if(mirrorTarget!=null&&mirrorTarget.getWidth()==width&&probeRange==range&&Math.abs(probePlane-surface)<0.01
                &&frameNumber-probeFrame<config.getUnderwaterReflectionInterval()
                &&Math.abs(camera.pos.x-probeX)<range*0.1&&Math.abs(camera.pos.z-probeZ)<range*0.1) {
            vector("WaterProbe",(float)(probeX-camera.pos.x),probePlane,(float)(probeZ-camera.pos.z),probeRange);return;
        }
        if(mirrorTarget==null||mirrorTarget.getWidth()!=width||mirrorTarget.getHeight()!=height) {
            Vulkan.waitIdle();if(mirrorTarget!=null)mirrorTarget.cleanUp();
            mirrorTarget=Framebuffer.builder(width,height,1,true).setFormat(VK10.VK_FORMAT_R16G16B16A16_SFLOAT).setLinearFiltering(true).build();
            if(mirrorPass==null) {
                var builder=RenderPass.builder(mirrorTarget);
                builder.getColorAttachmentInfo().setOps(VK10.VK_ATTACHMENT_LOAD_OP_CLEAR,VK10.VK_ATTACHMENT_STORE_OP_STORE);
                builder.getDepthAttachmentInfo().setOps(VK10.VK_ATTACHMENT_LOAD_OP_CLEAR,VK10.VK_ATTACHMENT_STORE_OP_STORE);
                mirrorPass=builder.build();
            }
        }
        var grid=world.getSectionGrid();
        if(grid!=mirrorGrid){mirrorQueues.clear();mirrorGrid=grid;}
        mirrorQueues.values().forEach(StaticQueue::clear);
        for(RenderSection section:grid.sections) {
            if(!section.isCompiled()||section.isCompletelyEmpty()||section.getChunkArea()==null
                    ||section.yOffset>surface
                    ||Math.abs(section.xOffset+8-camera.pos.x)>range||Math.abs(section.zOffset+8-camera.pos.z)>range
                    ||Math.abs(section.yOffset+8-surface)>128)continue;
            mirrorQueues.computeIfAbsent(section.getChunkArea(),key->new StaticQueue<>(512)).add(section);
        }
        Matrix4f savedView=new Matrix4f(VRenderSystem.modelView),savedProjection=new Matrix4f(VRenderSystem.projection);
        // A world-space top-down color/height probe is independent of view pitch.
        // Its origin is retained between updates and sampled in world coordinates.
        Matrix4f mirrorView=new Matrix4f().lookAt(0,surface+64-(float)camera.pos.y,0,
                0,surface-(float)camera.pos.y,0,0,0,-1);
        Matrix4f mirrorProjection=new Matrix4f().setOrtho(-range,range,-range,range,0.05f,256,
                RenderSystem.getDevice().isZZeroToOne());
        Vector3d virtualCamera=new Vector3d(camera.pos.x,surface+64,camera.pos.z);
        State saved=new State();int savedDepthFun=VRenderSystem.depthFun;
        VulkanImage atlas=((VkGpuTexture)mc.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView().texture()).getVulkanImage();
        VulkanImage oldTexture=VTextureSelector.getImage(0);
        float[] clear={VRenderSystem.clearColor.get(0),VRenderSystem.clearColor.get(1),VRenderSystem.clearColor.get(2),VRenderSystem.clearColor.get(3)};
        renderer.endRenderPass();mirrorRendering=true;
        try(MemoryStack stack=MemoryStack.stackPush()) {
            VRenderSystem.setClearColor(0,0,0,0);
            renderer.beginRenderPass(mirrorPass,mirrorTarget);
            Renderer.setViewport(0,0,width,height,stack);Renderer.setScissor(0,0,width,height);
            VRenderSystem.depthTest=true;VRenderSystem.depthMask=true;VRenderSystem.depthFun=515;VRenderSystem.cull=false;
            VRenderSystem.topology=VK10.VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;VRenderSystem.polygonMode=VK10.VK_POLYGON_MODE_FILL;
            VRenderSystem.colorMask=15;VRenderSystem.logicOp=false;PipelineState.blendInfo=new PipelineState.BlendInfo(false,1,0,1,0,0);
            VRenderSystem.applyModelViewMatrix(mirrorView);VRenderSystem.applyProjectionMatrix(mirrorProjection);VRenderSystem.calculateMVP();
            VTextureSelector.bindTexture(0,atlas);
            var index=Renderer.getDrawer().getQuadsIndexBuffer().getIndexBuffer();
            Renderer.getDrawer().bindIndexBuffer(Renderer.getCommandBuffer(),index,index.indexType.value);
            renderer.bindGraphicsPipeline(mirrorTerrain);
            for(TerrainRenderType type:new TerrainRenderType[]{TerrainRenderType.SOLID,TerrainRenderType.CUTOUT}) {
                for(var entry:mirrorQueues.entrySet()) {
                    var buffers=entry.getKey().getDrawBuffers();
                    if(entry.getValue().size()==0||buffers.getAreaBuffer(type)==null)continue;
                    buffers.bindBuffers(Renderer.getCommandBuffer(),mirrorTerrain,type,camera.pos.x,camera.pos.y,camera.pos.z,System.currentTimeMillis(),0,1);
                    renderer.uploadAndBindUBOs(mirrorTerrain);buffers.buildDrawBatchesDirect(virtualCamera,entry.getValue(),type);
                }
            }
            renderer.endRenderPass();read(mirrorTarget.getColorAttachment(),false,stack);
            probeX=camera.pos.x;probeZ=camera.pos.z;probePlane=surface;probeRange=range;probeFrame=frameNumber;
            vector("WaterProbe",0,probePlane,0,probeRange);
        } finally {
            mirrorRendering=false;saved.restore();VRenderSystem.depthFun=savedDepthFun;
            VRenderSystem.setClearColor(clear[0],clear[1],clear[2],clear[3]);VTextureSelector.bindTexture(0,oldTexture);
            VRenderSystem.applyModelViewMatrix(savedView);VRenderSystem.applyProjectionMatrix(savedProjection);VRenderSystem.calculateMVP();
            renderer.getMainPass().rebindMainTarget();
        }
    }
    private static RenderPass pass(Framebuffer target,boolean load) {
        var builder=RenderPass.builder(target);
        builder.getColorAttachmentInfo().setOps(load?VK10.VK_ATTACHMENT_LOAD_OP_LOAD:VK10.VK_ATTACHMENT_LOAD_OP_DONT_CARE,VK10.VK_ATTACHMENT_STORE_OP_STORE);
        return builder.build();
    }
    private void capture(boolean depth) {
        Renderer renderer=Renderer.getInstance();Framebuffer main=renderer.getMainPass().getMainFramebuffer();
        if(main==null||main.getWidth()<1||main.getDepthAttachment()==null)throw new IllegalStateException("Missing world framebuffer");
        ensureTargets(main);sourceColor=main.getColorAttachment();sourceDepth=main.getDepthAttachment();
        renderer.endRenderPass();
        try(MemoryStack stack=MemoryStack.stackPush()) {
            read(sourceColor,false,stack);
            fullscreen(copyColor,colorCopyPass,colorSnapshot,stack);
            read(colorSnapshot.getColorAttachment(),false,stack);
            attachment(sourceColor,false,stack);
            if(depth) {
                read(sourceDepth,true,stack);
                fullscreen(copyDepth,depthCopyPass,depthSnapshot,stack);
                read(depthSnapshot.getColorAttachment(),false,stack);
                attachment(sourceDepth,true,stack);
            }
            renderer.getMainPass().rebindMainTarget();
        }
    }
    public static void renderUnderwater(GameRenderState state,DeltaTracker delta) {
        if(!active||!snapshotReady||(state.levelRenderState.cameraRenderState.fogType!=FogType.WATER&&!instance.nearSurface))return;
        try { instance.drawUnderwater(); }
        catch(RuntimeException exception){failed=true;active=false;BrojeClient.LOGGER.error("Underwater pass failed",exception);}
    }
    private void drawUnderwater() {
        Renderer renderer=Renderer.getInstance();Framebuffer main=renderer.getMainPass().getMainFramebuffer();
        // Keep the exact terrain camera matrix and pre-translucent depth. Native
        // entity/particle draws may have changed the global model-view by now.
        updateUniforms();capture(false);
        Framebuffer colorOnly=mainColors.computeIfAbsent(main.getColorAttachment(),c->Framebuffer.builder(c,null).build());
        if(underwaterPass==null)underwaterPass=pass(colorOnly,true);
        renderer.endRenderPass();
        try(MemoryStack stack=MemoryStack.stackPush()) {
            read(sourceDepth,true,stack);
            fullscreen(underwater,underwaterPass,colorOnly,stack);
            attachment(sourceDepth,true,stack);
            renderer.getMainPass().rebindMainTarget();
        }
    }
    private void fullscreen(GraphicsPipeline pipeline,RenderPass pass,Framebuffer target,MemoryStack stack) {
        Renderer renderer=Renderer.getInstance();State saved=new State();
        try {
            renderer.beginRenderPass(pass,target);
            Renderer.setViewport(0,0,target.getWidth(),target.getHeight(),stack);Renderer.setScissor(0,0,target.getWidth(),target.getHeight());
            VRenderSystem.depthTest=false;VRenderSystem.depthMask=false;VRenderSystem.cull=false;
            VRenderSystem.topology=VK10.VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;VRenderSystem.polygonMode=VK10.VK_POLYGON_MODE_FILL;
            VRenderSystem.colorMask=15;VRenderSystem.logicOp=false;
            PipelineState.blendInfo=new PipelineState.BlendInfo(false,1,0,1,0,0);
            renderer.bindGraphicsPipeline(pipeline);renderer.uploadAndBindUBOs(pipeline);
            VK10.vkCmdDraw(Renderer.getCommandBuffer(),3,1,0,0);
        } finally {renderer.endRenderPass();saved.restore();}
    }
    static void read(VulkanImage image,boolean depth,MemoryStack stack) {
        VulkanImage.transitionLayout(stack,Renderer.getCommandBuffer(),image,image.getCurrentLayout(),VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                depth?VK10.VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT|VK10.VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT:VK10.VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                depth?VK10.VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT|VK10.VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT:VK10.VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
                VK10.VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,VK10.VK_ACCESS_SHADER_READ_BIT);
    }
    static void attachment(VulkanImage image,boolean depth,MemoryStack stack) {
        VulkanImage.transitionLayout(stack,Renderer.getCommandBuffer(),image,image.getCurrentLayout(),
                depth?VK10.VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL:VK10.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                VK10.VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,VK10.VK_ACCESS_SHADER_READ_BIT,
                depth?VK10.VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT|VK10.VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT:VK10.VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                depth?VK10.VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT|VK10.VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT:VK10.VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT);
    }
    private void updateUniforms() {
        var mc=Minecraft.getInstance();var state=mc.gameRenderer.getGameRenderState();var camera=state.levelRenderState.cameraRenderState;
        var config=BrojeClient.CONFIG;var level=mc.level;
        vp.get(uniforms.get("WaterVP").buffer);new Matrix4f(vp).invert().get(uniforms.get("WaterInverseVP").buffer);
        MappedBuffer shadow=ShadowRenderer.uniform("BrojeShadowMatrix");
        if(shadow!=null)MemoryUtil.memCopy(shadow.buffer,uniforms.get("BrojeShadowMatrix").buffer);else new Matrix4f().get(uniforms.get("BrojeShadowMatrix").buffer);
        float time=(Long.getLong("broje.waterTimeMillis",(long)((state.levelRenderState.gameTime+
                mc.getDeltaTracker().getGameTimeDeltaPartialTick(false))*50))%4096000)/1000f;
        vector("WaterCamera",wrap(camera.pos.x), (float)camera.pos.y,wrap(camera.pos.z),time);
        vector("WaterOptics",config.getWaterWaves()*0.01f,config.getWaterRefraction()*0.01f,config.getWaterAbsorption()*0.01f,config.getWaterQuality());
        boolean submerged=camera.fogType==FogType.WATER;
        vector("WaterEffects",config.isWaterSsr()?1:0,config.getWaterCaustics()*0.01f,config.isUnderwaterShafts()?1:0,submerged?1:0);
        Vector3f sun=SunDirection.at(state.levelRenderState.skyRenderState.sunAngle,config.getSunPathTilt(),new Vector3f());
        float rain=level.getRainLevel(mc.getDeltaTracker().getGameTimeDeltaPartialTick(false));
        float day=Math.max(0,Math.min(1,sun.y*3))*(1-rain*0.85f);
        vector("WaterSun",sun.x,sun.y,sun.z,day);
        int sky=state.levelRenderState.skyRenderState.skyColor;
        vector("WaterSky",(sky>>16&255)/255f,(sky>>8&255)/255f,(sky&255)/255f,rain);
        BlockPos pos=BlockPos.containing(camera.pos);float localSky=level.getBrightness(LightLayer.SKY,pos)/15f;
        var fog=camera.fogData.color;
        // Underwater fog color is not the sky's horizon color.
        vector("WaterAmbient",submerged?0.28f*day:fog.x,submerged?0.42f*day:fog.y,submerged?0.55f*day:fog.z,localSky);
        var atlas=mc.getAtlasManager().getAtlasOrThrow(AtlasIds.BLOCKS);
        sprite(atlas,"water_still","WaterStill");sprite(atlas,"water_flow","WaterFlow");sprite(atlas,"water_overlay","WaterOverlay");
        float surface=(float)camera.pos.y;
        var cursor=pos.mutable();
        if(!level.getFluidState(cursor).is(FluidTags.WATER))cursor.move(0,-1,0);
        boolean waterColumn=level.getFluidState(cursor).is(FluidTags.WATER);
        if(waterColumn) {
            for(int i=0;i<96&&level.getFluidState(cursor).is(FluidTags.WATER);i++) {
                surface=cursor.getY()+level.getFluidState(cursor).getHeight(level,cursor);cursor.move(0,1,0);
            }
            localSky=level.getBrightness(LightLayer.SKY,cursor)/15f;
        }
        surface=WaterMedium.surface(level,pos,surface);
        nearSurface=waterColumn&&Math.abs(camera.pos.y-surface)<0.5;
        float reflection=config.isUnderwaterReflections()?config.getUnderwaterReflectionStrength()*0.01f:0;
        vector("WaterReflection",submerged&&reflection>0?1:0,reflection,
                config.getUnderwaterShaftStrength()*0.01f,config.getUnderwaterShaftQuality());
        vector("WaterPerformance",config.getWaterSsrDistance(),config.getWaterSsrRays(),config.isWaterSsrPrecise()?1:0,
                config.getWaterQuality()==16?8:config.getWaterQuality()==32?12:16);
        vector("WaterProbe",(float)(probeX-camera.pos.x),probePlane,(float)(probeZ-camera.pos.z),probeRange);
        int biome=BiomeColors.getAverageWaterColor(level,pos);
        vector("WaterMedium",surface,(biome>>16&255)/255f,(biome>>8&255)/255f,(biome&255)/255f);
        MappedBuffer flags=ShadowRenderer.uniform("BrojeFlags");
        vector("WaterShadow",ShadowRenderer.mapReady()?1:0,flags!=null?flags.getFloat(8):0,config.getShadowDistance(),localSky);
        Framebuffer main=Renderer.getInstance().getMainPass().getMainFramebuffer();
        vector("WaterScreen",main.getWidth(),main.getHeight(),RenderSystem.getDevice().isZZeroToOne()?1:0,Integer.getInteger("broje.waterDebug",0));
        vector("WaterCloudSky",CloudRenderer.skyMap()!=null?1:0,0,0,0);
    }
    private void sprite(TextureAtlas atlas,String path,String uniform) {
        var sprite=atlas.getSprite(Identifier.withDefaultNamespace("block/"+path));
        if(sprite==atlas.missingSprite())vector(uniform,-2,-2,-1,-1);
        else vector(uniform,sprite.getU0(),sprite.getV0(),sprite.getU1(),sprite.getV1());
    }
    private static float wrap(double value) { return (float)(value-Math.floor(value/4096)*4096); }
    private void vector(String name,float x,float y,float z,float w) {
        var b=uniforms.get(name);b.putFloat(0,x);b.putFloat(4,y);b.putFloat(8,z);b.putFloat(12,w);
    }
    private void closeDepthViews() { depthViews.values().forEach(view->VK10.vkDestroyImageView(Vulkan.getVkDevice(),view,null));depthViews.clear(); }
    private void closeMainColors() {mainColors.values().forEach(f->f.cleanUp(false));mainColors.clear();}
    public static void close() {
        active=false;snapshotReady=false;
        if(instance==null)return;
        Vulkan.waitIdle();instance.closeDepthViews();instance.closeMainColors();
        instance.terrain.cleanUp();instance.mirrorTerrain.cleanUp();instance.copyColor.cleanUp();instance.copyDepth.cleanUp();instance.underwater.cleanUp();
        if(instance.colorCopyPass!=null){instance.colorCopyPass.cleanUp();instance.depthCopyPass.cleanUp();}
        if(instance.underwaterPass!=null)instance.underwaterPass.cleanUp();
        if(instance.mirrorPass!=null)instance.mirrorPass.cleanUp();
        if(instance.mirrorTarget!=null)instance.mirrorTarget.cleanUp();
        if(instance.colorSnapshot!=null){instance.colorSnapshot.cleanUp();instance.depthSnapshot.cleanUp();}
        instance.uniforms.values().forEach(b->MemoryUtil.memFree(b.buffer));instance=null;
    }
    private final class Image extends ImageDescriptor {
        private final Supplier<VulkanImage> supplier;private final boolean depth;
        Image(int binding,String name,Supplier<VulkanImage> supplier,boolean depth) {
            super(binding,"sampler2D",name,11,VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);this.supplier=supplier;this.depth=depth;
        }
        @Override public VulkanImage getImage(){return supplier.get();}
        @Override public long getImageView(VulkanImage image) {
            if(!depth||!VulkanImage.isDepthFormat(image.format))return super.getImageView(image);
            if(image==ShadowRenderer.mapImage())return ShadowRenderer.mapView(image);
            return depthViews.computeIfAbsent(image,d->VulkanImage.createImageView(d.getId(),d.format,VK10.VK_IMAGE_ASPECT_DEPTH_BIT,1,1));
        }
    }
    record State(boolean test,boolean mask,boolean cull,int topology,int polygon,int color,boolean logic,PipelineState.BlendInfo blend) {
        State(){this(VRenderSystem.depthTest,VRenderSystem.depthMask,VRenderSystem.cull,VRenderSystem.topology,VRenderSystem.polygonMode,VRenderSystem.colorMask,VRenderSystem.logicOp,PipelineState.blendInfo);}
        void restore(){VRenderSystem.depthTest=test;VRenderSystem.depthMask=mask;VRenderSystem.cull=cull;VRenderSystem.topology=topology;VRenderSystem.polygonMode=polygon;VRenderSystem.colorMask=color;VRenderSystem.logicOp=logic;PipelineState.blendInfo=blend;}
    }
}
