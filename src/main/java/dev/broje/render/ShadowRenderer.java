package dev.broje.render;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

import com.google.gson.JsonParser;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import dev.broje.BrojeClient;
import dev.broje.config.BrojeConfig;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FogType;
import net.vulkanmod.interfaces.shader.ExtendedRenderPipeline;
import net.vulkanmod.render.chunk.ChunkArea;
import net.vulkanmod.render.chunk.RenderSection;
import net.vulkanmod.render.chunk.SectionGrid;
import net.vulkanmod.render.chunk.WorldRenderer;
import net.vulkanmod.render.chunk.buffer.DrawBuffers;
import net.vulkanmod.render.chunk.util.StaticQueue;
import net.vulkanmod.render.engine.VkGpuDevice;
import net.vulkanmod.render.engine.VkGpuTexture;
import net.vulkanmod.render.shader.PipelineManager;
import net.vulkanmod.render.vertex.CustomVertexFormat;
import net.vulkanmod.render.vertex.TerrainRenderType;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.framebuffer.RenderPass;
import net.vulkanmod.vulkan.memory.buffer.IndexBuffer;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.shader.Pipeline;
import net.vulkanmod.vulkan.shader.PipelineConfig;
import net.vulkanmod.vulkan.shader.PipelineState;
import net.vulkanmod.vulkan.shader.SPIRVUtils;
import net.vulkanmod.vulkan.shader.Uniforms;
import net.vulkanmod.vulkan.shader.descriptor.ImageDescriptor;
import net.vulkanmod.vulkan.texture.SamplerManager;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import net.vulkanmod.vulkan.texture.VulkanImage;
import net.vulkanmod.vulkan.util.MappedBuffer;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * Sun shadows in the forward terrain pass, the way Beryl shades them. At the start of
 * each level frame a radially distorted solar depth map is drawn from terrain, then from
 * entities, the player, dropped items and block entities through Minecraft's own
 * renderers. Broje's terrain shader replaces VulkanMod's for opaque and cutout layers
 * and darkens the direct sun share per face.
 */
public final class ShadowRenderer {
    private static final float DEPTH_SPAN = ShadowProjection.DEPTH_SPAN;
    private static ShadowRenderer instance;
    private static boolean failed;
    private static boolean active;
    private static boolean mapReady;
    private static boolean entityPass;
    private static final Field[] PIPELINE_FIELDS = java.util.Arrays.stream(RenderPipelines.class.getFields())
            .filter(field -> Modifier.isStatic(field.getModifiers()) && field.getType() == RenderPipeline.class)
            .toArray(Field[]::new);
    private static Function<TerrainRenderType, GraphicsPipeline> vulkanModShaders;
    private static final Function<TerrainRenderType, GraphicsPipeline> BROJE_SHADERS = ShadowRenderer::terrainShader;
    private final Map<String, MappedBuffer> uniforms = new LinkedHashMap<>();
    private final Map<ChunkArea, StaticQueue<RenderSection>> casterQueues = new IdentityHashMap<>();
    private final Map<String, GraphicsPipeline> entityCasters = new HashMap<>();
    private final Map<RenderPipeline, GraphicsPipeline> redirected = new IdentityHashMap<>();
    private final Map<VulkanImage, Long> depthViews = new IdentityHashMap<>();
    private final MapDescriptor mapDepth = new MapDescriptor(6);
    private final TintDescriptor mapTint = new TintDescriptor(7);
    private final ShadowProjection projection = new ShadowProjection();
    private final Matrix4f lightView = projection.view;
    private final Matrix4f lightMatrix = projection.matrix;
    private final Vector3f sun = new Vector3f();
    private final Vector3f probe = new Vector3f();
    private final Vector3d lightCamera = new Vector3d();
    private final GraphicsPipeline casterPipeline;
    private final GraphicsPipeline tintPipeline;
    private final GraphicsPipeline solidPipeline;
    private final GraphicsPipeline cutoutPipeline;
    private Framebuffer shadowTarget, tintStorage, tintTarget;
    private GpuTextureView colorView, depthView;
    private RenderPass casterPass, tintPass;
    private SectionGrid lastGrid;
    private float extent;
    private boolean logged;

    private ShadowRenderer() {
        for (String name : new String[] {"ShadowMatrix", "BrojeShadowMatrix", "BrojeShadowView", "BrojeShadowInverse"}) {
            uniforms.put(name, new MappedBuffer(64));
        }
        for (String name : new String[] {"ShadowAlpha", "BrojeSunLight", "BrojeShadowSettings", "BrojeShadowMap", "BrojeFlags", "BrojeLighting", "BrojeTint", "BrojeTintBlend"}) {
            uniforms.put(name, new MappedBuffer(16));
        }
        // Broje's other pipelines resolve these by name through VulkanMod's registry.
        uniforms.forEach((name, buffer) -> {
            if (!name.startsWith("Broje")) return;
            if (buffer.buffer.capacity() == 64) Uniforms.mat4f_uniformMap.put(name, () -> buffer);
            else Uniforms.vec4f_uniformMap.put(name, () -> buffer);
        });
        casterPipeline = terrainPipeline("shadow_caster", "shadow_caster", casterConfig(), true, "");
        tintPipeline = terrainPipeline("shadow_caster", "shadow_tint", casterConfig(), true, "");
        solidPipeline = terrainPipeline("terrain", "terrain", terrainConfig(), false, "");
        cutoutPipeline = terrainPipeline("terrain", "terrain", terrainConfig(), false, "#define CUTOUT_LAYER\n");
    }

    private static PipelineConfig casterConfig() {
        return PipelineConfig.fromJson("shadow_caster", JsonParser.parseString(resource("shadow_caster.json")).getAsJsonObject());
    }

    // Built in code: JSON samplers take their texture slot from the sampler name, and
    // the light map must come from slot 2 exactly as VulkanMod's terrain binds it.
    private static PipelineConfig terrainConfig() {
        int vertex = VK10.VK_SHADER_STAGE_VERTEX_BIT, fragment = VK10.VK_SHADER_STAGE_FRAGMENT_BIT;
        return PipelineConfig.builder()
                .withShader(SPIRVUtils.ShaderKind.VERTEX_SHADER, "terrain")
                .withShader(SPIRVUtils.ShaderKind.FRAGMENT_SHADER, "terrain")
                .addUB(PipelineConfig.UB.builder(0, vertex).addUniform("mat4", "MVP").build())
                .addUB(PipelineConfig.UB.builder(1, vertex | fragment).addUniform("vec4", "FogColor")
                        .addUniform("float", "FogEnvironmentalStart").addUniform("float", "FogEnvironmentalEnd")
                        .addUniform("float", "FogRenderDistanceStart").addUniform("float", "FogRenderDistanceEnd")
                        .addUniform("float", "AlphaCutout").build())
                .addUB(PipelineConfig.UB.builder(2, vertex).setSize(4096).build())
                .addUB(PipelineConfig.UB.builder(3, fragment).addUniform("mat4", "BrojeShadowMatrix")
                        .addUniform("mat4", "BrojeShadowView").addUniform("mat4", "BrojeShadowInverse")
                        .addUniform("vec4", "BrojeSunLight").addUniform("vec4", "BrojeShadowSettings")
                        .addUniform("vec4", "BrojeShadowMap").addUniform("vec4", "BrojeFlags")
                        .addUniform("vec4", "BrojeLighting").addUniform("vec4", "BrojeTint")
                        .addUniform("vec4", "BrojeTintBlend").build())
                .setPushConstants(PipelineConfig.UB.builder(0, vertex).addUniform("vec3", "ModelOffset").build())
                .addImageDescriptor(4, "sampler2D", "Sampler0", 0)
                .addImageDescriptor(5, "sampler2D", "Sampler2", 2)
                .addImageDescriptor(6, "sampler2D", "Sampler1", 11)
                .addImageDescriptor(7, "sampler2D", "Sampler3", 11)
                .build();
    }

    private GraphicsPipeline terrainPipeline(String vertex, String fragment, PipelineConfig config, boolean caster, String defines) {
        Pipeline.Builder builder = new Pipeline.Builder(CustomVertexFormat.COMPRESSED_TERRAIN, "broje:" + fragment);
        if (caster) builder.setUniformSupplierGetter(info -> uniforms.containsKey(info.name) ? () -> uniforms.get(info.name) : null);
        builder.applyConfig(config);
        if (!caster) {builder.getImageDescriptors().set(2, mapDepth);builder.getImageDescriptors().set(3, mapTint);}
        builder.setShaderSrc(SPIRVUtils.ShaderKind.VERTEX_SHADER, resource(vertex + ".vsh"));
        builder.setShaderSrc(SPIRVUtils.ShaderKind.FRAGMENT_SHADER, withDefines(resource(fragment + ".fsh"), defines));
        GraphicsPipeline result = builder.createGraphicsPipeline();
        // Terrain mirrors VulkanMod's own setup: section data arrives in binding 2 from DrawBuffers.
        result.getBuffers().forEach(buffer -> {buffer.setUseGlobalBuffer(true); if (caster) buffer.setUpdate(true);});
        return result;
    }

    private static String withDefines(String source, String defines) {
        int line = source.indexOf('\n') + 1;
        return source.substring(0, line) + defines + source.substring(line);
    }

    /**
     * Depth-only stand-in for an entity, item or block-entity pipeline of the given
     * vertex format: the same light transform and distortion as terrain casters, plus
     * the texture's alpha so fur, armor trims and flat items cut out.
     */
    private GraphicsPipeline entityCaster(VertexFormat format, boolean textured) {
        return entityCasters.computeIfAbsent(format.toString() + textured, key -> {
            int position = format.getElements().indexOf(VertexFormatElement.POSITION);
            int uv = format.getElements().indexOf(VertexFormatElement.UV0);
            int vertex = VK10.VK_SHADER_STAGE_VERTEX_BIT;
            PipelineConfig.Builder config = PipelineConfig.builder()
                    .withShader(SPIRVUtils.ShaderKind.VERTEX_SHADER, "entity_caster")
                    .withShader(SPIRVUtils.ShaderKind.FRAGMENT_SHADER, "entity_caster")
                    .addUB(PipelineConfig.UB.builder(0, vertex).addUniform("mat4", "MVP").addUniform("vec4", "BrojeFlags").build());
            if (textured) config.addImageDescriptor(1, "sampler2D", "Sampler0", 0);
            String inputs = "layout(location=" + position + ") in vec3 Position;\n"
                    + (textured ? "layout(location=" + uv + ") in vec2 UV0;\n#define TEXTURED\n" : "");
            Pipeline.Builder builder = new Pipeline.Builder(format, "broje:entity_caster");
            builder.applyConfig(config.build());
            builder.setShaderSrc(SPIRVUtils.ShaderKind.VERTEX_SHADER, withDefines(resource("entity_caster.vsh"), inputs));
            builder.setShaderSrc(SPIRVUtils.ShaderKind.FRAGMENT_SHADER, withDefines(resource("entity_caster.fsh"), textured ? "#define TEXTURED\n" : ""));
            GraphicsPipeline result = builder.createGraphicsPipeline();
            result.getBuffers().forEach(buffer -> buffer.setUseGlobalBuffer(true));
            return result;
        });
    }

    /** Called once per frame before the level is drawn. */
    public static void beginFrame() {
        active = false;
        mapReady = false;
    }

    /** True while this frame's sun map is valid, for the fog's light shafts. */
    public static boolean mapReady() {
        return mapReady && instance != null;
    }

    public static boolean renderingEntities() { return entityPass; }

    /** Replace vanilla contact disks only where a valid body shadow is available. */
    public static boolean replacesVanillaShadow(double x, double y, double z) {
        if (entityPass) return true;
        if (!active || !mapReady() || !BrojeClient.CONFIG.isEntityShadows()) return false;
        double range=BrojeClient.CONFIG.getShadowDistance()*0.7;
        return x*x+y*y+z*z < range*range
                && instance.insideLightBox((float)x,(float)y,(float)z,2);
    }

    public static MappedBuffer uniform(String name) {
        return instance == null ? null : instance.uniforms.get(name);
    }

    public static VulkanImage mapImage() {
        return instance == null || instance.shadowTarget == null ? null : instance.shadowTarget.getDepthAttachment();
    }

    public static long mapView(VulkanImage image) {
        return instance.depthViews.computeIfAbsent(image,
                value -> VulkanImage.createImageView(value.getId(), value.format, VK10.VK_IMAGE_ASPECT_DEPTH_BIT, 1, 1));
    }

    /** Called at the start of LevelRenderer.renderLevel, before any world pass is recorded. */
    public static void renderFrame(DeltaTracker deltaTracker) {
        BrojeConfig config = BrojeClient.CONFIG;
        Minecraft mc = Minecraft.getInstance();
        GameRenderState state = mc.gameRenderer.getGameRenderState();
        CameraRenderState camera = state.levelRenderState.cameraRenderState;
        if (failed || config == null || !config.isEnabled() || !(casting(state, config) || config.isLightingEnabled())
                || mc.level == null || !Level.OVERWORLD.equals(mc.level.dimension())
                || camera.fogType != FogType.NONE || camera.entityRenderState.doesMobEffectBlockSky) return;
        if (WorldRenderer.getInstance() == null || WorldRenderer.getInstance().getSectionGrid() == null) return;
        try {
            if (instance == null) instance = new ShadowRenderer();
            installShaderGetter();
            instance.renderMap(state, deltaTracker, config, camera.pos.x, camera.pos.y, camera.pos.z);
            active = true;
        } catch (RuntimeException | ReflectiveOperationException exception) {
            failed = true;
            BrojeClient.LOGGER.error("Could not initialize sun shadows", exception);
        }
    }

    private static GraphicsPipeline terrainShader(TerrainRenderType type) {
        if (!active || type == TerrainRenderType.TRANSLUCENT) return vulkanModShaders.apply(type);
        return type == TerrainRenderType.CUTOUT ? instance.cutoutPipeline : instance.solidPipeline;
    }

    // VulkanMod exposes a setter only. Keep its getter as the fallback, and take over
    // again if VulkanMod reinstalled its default after a resource reload.
    @SuppressWarnings("unchecked")
    private static void installShaderGetter() throws ReflectiveOperationException {
        Field field = PipelineManager.class.getDeclaredField("shaderGetter");
        field.setAccessible(true);
        Object current = field.get(null);
        if (current == BROJE_SHADERS) return;
        vulkanModShaders = (Function<TerrainRenderType, GraphicsPipeline>) current;
        PipelineManager.setShaderGetter(BROJE_SHADERS);
    }

    /** Whether the sun is up and shadows are on, so the depth map gets casters this frame. */
    private static boolean casting(GameRenderState state, BrojeConfig config) {
        return config.isShadowsEnabled() && config.getShadowStrength() > 0
                && Math.cos(state.levelRenderState.skyRenderState.sunAngle) > 0.025;
    }

    private void ensureTarget(int resolution) throws ReflectiveOperationException {
        if (shadowTarget != null && shadowTarget.getWidth() == resolution) return;
        if (shadowTarget != null) {
            Vulkan.waitIdle();
            closeViews();
            tintTarget.cleanUp(false);
            tintStorage.cleanUp();
            shadowTarget.cleanUp();
        }
        // Opaque depth and a native-compatible scratch color attachment for bodies.
        shadowTarget = Framebuffer.builder(resolution, resolution, 1, true).setFormat(VK10.VK_FORMAT_R8G8B8A8_UNORM).build();
        // Native Minecraft texture views support RGBA8 only. Keep the body target
        // compatible and accumulate glass in a separate floating color image.
        tintStorage = Framebuffer.builder(resolution,resolution,1,false).setFormat(VK10.VK_FORMAT_R16G16B16A16_SFLOAT).build();
        tintTarget = Framebuffer.builder(tintStorage.getColorAttachment(),shadowTarget.getDepthAttachment()).build();
        Field backend = GpuDevice.class.getDeclaredField("backend");
        backend.setAccessible(true);
        VkGpuDevice device = (VkGpuDevice) backend.get(RenderSystem.getDevice());
        colorView = device.createTextureView(device.gpuTextureFromVulkanImage(shadowTarget.getColorAttachment()));
        depthView = device.createTextureView(device.gpuTextureFromVulkanImage(shadowTarget.getDepthAttachment()));
        if (casterPass == null) {
            RenderPass.Builder builder = RenderPass.builder(shadowTarget);
            builder.getColorAttachmentInfo().setOps(VK10.VK_ATTACHMENT_LOAD_OP_CLEAR, VK10.VK_ATTACHMENT_STORE_OP_STORE);
            builder.getDepthAttachmentInfo().setOps(VK10.VK_ATTACHMENT_LOAD_OP_CLEAR, VK10.VK_ATTACHMENT_STORE_OP_STORE);
            casterPass = builder.build();
            builder = RenderPass.builder(tintTarget);
            builder.getColorAttachmentInfo().setOps(VK10.VK_ATTACHMENT_LOAD_OP_CLEAR, VK10.VK_ATTACHMENT_STORE_OP_STORE);
            builder.getDepthAttachmentInfo().setOps(VK10.VK_ATTACHMENT_LOAD_OP_LOAD, VK10.VK_ATTACHMENT_STORE_OP_STORE);
            tintPass = builder.build();
        }
    }

    private void updateUniforms(GameRenderState state, BrojeConfig config, boolean casting, double x, double y, double z) {
        float angle = state.levelRenderState.skyRenderState.sunAngle;
        SunDirection.at(angle, config.getSunPathTilt(), sun);
        extent = config.getShadowDistance() + 16;
        float distortion = ShadowProjection.distortion(config.getShadowDistance());
        projection.update(x,y,z,sun,extent);
        lightMatrix.get(uniforms.get("ShadowMatrix").buffer);
        lightMatrix.get(uniforms.get("BrojeShadowMatrix").buffer);
        lightView.get(uniforms.get("BrojeShadowView").buffer);
        new Matrix4f(lightMatrix).invert().get(uniforms.get("BrojeShadowInverse").buffer);
        Minecraft mc = Minecraft.getInstance();
        float rain = mc.level.getRainLevel(mc.getDeltaTracker().getGameTimeDeltaPartialTick(false));
        float visibility = casting ? Math.max(0, Math.min(1,(sun.y-0.025f)/0.10f)) * (1-rain*0.9f) : 0;
        vector("BrojeSunLight", sun.x,sun.y,sun.z,visibility);
        vector("BrojeShadowSettings",casting ? config.getShadowStrength()*0.006f : 0, config.getShadowSoftness()*0.01f,
                config.getShadowDistance(), config.getShadowSamples());
        if (config.isLightingEnabled()) {
            vector("BrojeLighting", config.getBounceStrength()*0.01f, config.getFoliageGlow()*0.01f,
                    config.getLightColor()*0.01f, config.getLightingQuality());
        } else {
            vector("BrojeLighting", 0,0,0,0);
        }
        vector("BrojeTint",config.getColoredContrast()*0.01f,config.getColoredMixing()*0.01f,
                config.getColoredBrightness()*0.01f,config.getColoredSaturation()*0.01f);
        vector("BrojeTintBlend",config.isColoredShadows()?config.getColoredBlend():0,config.getColoredOverlap()*0.01f,0,0);
        vector("BrojeShadowMap",config.getShadowResolution(),extent*2,DEPTH_SPAN,2*extent/config.getShadowResolution());
        String debug = System.getProperty("broje.shadowDebug", "");
        vector("BrojeFlags",debug.equals("mask")?1:debug.equals("map")?2:debug.equals("floor")?3:0,casting?1:0,distortion,config.getShadowFilter());
        vector("ShadowAlpha",0.1f,config.isFoliageShadows()?1:0,distortion,
                2*extent/config.getShadowResolution()/ShadowProjection.DEPTH_SPAN);
        lightCamera.set(x + sun.x*100000, y + sun.y*100000, z + sun.z*100000);
    }

    private boolean insideLightBox(float dx, float dy, float dz, float margin) {
        lightView.transformPosition(probe.set(dx, dy, dz));
        return Math.abs(probe.x) <= extent + margin && Math.abs(probe.y) <= extent + margin
                && probe.z <= margin && probe.z >= -DEPTH_SPAN - margin;
    }

    private int gatherCasters(double x, double y, double z) {
        WorldRenderer world = WorldRenderer.getInstance();
        SectionGrid grid = world.getSectionGrid();
        if (grid != lastGrid) {casterQueues.clear();lastGrid=grid;}
        casterQueues.values().forEach(StaticQueue::clear);
        int rebuilds = 0, count = 0;
        for (RenderSection section : grid.sections) {
            if (!insideLightBox((float)(section.xOffset+8-x), (float)(section.yOffset+8-y), (float)(section.zOffset+8-z), 14)) continue;
            // Warm loaded off-screen chunks too; never use the player's visibility/occlusion queue.
            if (section.isDirty() && rebuilds < 8 && section.rebuildChunkAsync(world.getTaskDispatcher(), world.renderRegionCache, WorldRenderer.getCameraPos())) {
                section.setNotDirty();rebuilds++;
            }
            if (!section.isCompiled() || section.isCompletelyEmpty() || section.getChunkArea()==null) continue;
            casterQueues.computeIfAbsent(section.getChunkArea(), key -> new StaticQueue<>(512)).add(section);
            count++;
        }
        return count;
    }

    private void renderMap(GameRenderState state, DeltaTracker deltaTracker, BrojeConfig config, double x, double y, double z)
            throws ReflectiveOperationException {
        Renderer renderer = Renderer.getInstance();
        boolean casting = casting(state, config);
        ensureTarget(config.getShadowResolution());
        updateUniforms(state,config,casting,x,y,z);
        // Without sun the pass only clears the map; the terrain shader then skips it.
        int casters = casting ? gatherCasters(x,y,z) : 0;
        int entities = 0;
        VulkanImage old0=VTextureSelector.getImage(0);
        var atlas = Minecraft.getInstance().getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS);
        VulkanImage atlasImage=((VkGpuTexture)atlas.getTextureView().texture()).getVulkanImage();
        long atlasSampler=atlasImage.getSampler();
        State saved = new State();
        if (renderer.getBoundRenderPass() != null) renderer.endRenderPass();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBuffer command = Renderer.getCommandBuffer();
            depthAttachment(shadowTarget.getDepthAttachment(),command,stack);
            // The color target starts white: sunlight passes untinted until glass is drawn.
            float[] clear = {VRenderSystem.clearColor.get(0),VRenderSystem.clearColor.get(1),VRenderSystem.clearColor.get(2),VRenderSystem.clearColor.get(3)};
            VRenderSystem.setClearColor(1,1,1,1);
            renderer.beginRenderPass(casterPass,shadowTarget);
            VRenderSystem.setClearColor(clear[0],clear[1],clear[2],clear[3]);
            Renderer.setViewport(0,0,shadowTarget.getWidth(),shadowTarget.getHeight(),stack);
            Renderer.setScissor(0,0,shadowTarget.getWidth(),shadowTarget.getHeight());
            VRenderSystem.depthTest=true;VRenderSystem.depthMask=true;VRenderSystem.depthFun=515;
            VRenderSystem.cull=false;VRenderSystem.topology=VK10.VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
            VRenderSystem.polygonMode=VK10.VK_POLYGON_MODE_FILL;VRenderSystem.colorMask=0;VRenderSystem.logicOp=false;
            PipelineState.blendInfo=new PipelineState.BlendInfo(false,1,0,1,0,0);
            Renderer.setDepthBias(0,0); // Contact is resolved on the receiver, not by pushing casters backwards.
            if (casting) {
                renderer.bindGraphicsPipeline(casterPipeline);
                // Like Beryl, use the base atlas level and a fixed cutout for thin foliage.
                atlasImage.setSampler(SamplerManager.getSampler(true,true,0));
                VTextureSelector.bindTexture(0,atlasImage);
                IndexBuffer index = Renderer.getDrawer().getQuadsIndexBuffer().getIndexBuffer();
                Renderer.getDrawer().bindIndexBuffer(command,index,index.indexType.value);
                for (TerrainRenderType type : new TerrainRenderType[]{TerrainRenderType.SOLID,TerrainRenderType.CUTOUT}) {
                    for (var entry : casterQueues.entrySet()) {
                        DrawBuffers buffers = entry.getKey().getDrawBuffers();
                        if (entry.getValue().size()==0 || buffers.getAreaBuffer(type)==null) continue;
                        buffers.bindBuffers(command,casterPipeline,type,x,y,z,System.currentTimeMillis(),0,1);
                        renderer.uploadAndBindUBOs(casterPipeline);
                        buffers.buildDrawBatchesDirect(lightCamera,entry.getValue(),type);
                    }
                }
            }
            renderer.endRenderPass();
            // Hand the vanilla renderers the state they expect before entities are drawn.
            saved.restore();
            atlasImage.setSampler(atlasSampler);
            VTextureSelector.bindTexture(0,old0);
            if (casting && config.isEntityShadows()) entities = renderEntities(state, deltaTracker, x, y, z);
            if (renderer.getBoundRenderPass() != null) renderer.endRenderPass();
            if (casting) renderTint(renderer, command, stack, atlasImage, x, y, z);
            saved.restore();
            atlasImage.setSampler(atlasSampler);
            VTextureSelector.bindTexture(0,old0);
            depthRead(shadowTarget.getDepthAttachment(),command,stack);
            if (casting) colorRead(tintStorage.getColorAttachment(),command,stack);
        } finally {
            saved.restore();
            atlasImage.setSampler(atlasSampler);
            VTextureSelector.bindTexture(0,old0);
            renderer.getMainPass().rebindMainTarget();
            Renderer.resetViewport();
            Renderer.resetScissor();
        }
        mapReady = casting;
        if (!logged && casting) {
            logged=true;
            BrojeClient.LOGGER.info("Sun lighting active in the terrain pass: {}px, {} blocks, {} caster sections, {} entity casters, plant casters {}, GI {}.",
                    config.getShadowResolution(),config.getShadowDistance(),casters,entities,config.isFoliageShadows(),config.isLightingEnabled());
        }
    }

    /**
     * Translucent blocks in front of the first opaque caster, multiplied into the color
     * target: two panes of different hue absorb what either lets through, as real
     * filters do. Depth is tested against the finished opaque map and never written.
     */
    private void renderTint(Renderer renderer, VkCommandBuffer command, MemoryStack stack, VulkanImage atlas, double x, double y, double z) {
        depthAttachment(shadowTarget.getDepthAttachment(),command,stack);
        float[] clear = {VRenderSystem.clearColor.get(0),VRenderSystem.clearColor.get(1),VRenderSystem.clearColor.get(2),VRenderSystem.clearColor.get(3)};
        boolean additive = BrojeClient.CONFIG.isColoredShadows() && BrojeClient.CONFIG.getColoredBlend()==2;
        float initial = additive ? 0 : 1;
        VRenderSystem.setClearColor(initial,initial,initial,initial);
        renderer.beginRenderPass(tintPass,tintTarget);
        VRenderSystem.setClearColor(clear[0],clear[1],clear[2],clear[3]);
        Renderer.setViewport(0,0,shadowTarget.getWidth(),shadowTarget.getHeight(),stack);
        Renderer.setScissor(0,0,shadowTarget.getWidth(),shadowTarget.getHeight());
        VRenderSystem.depthTest=true;VRenderSystem.depthMask=false;VRenderSystem.depthFun=513; // GL_LESS
        VRenderSystem.cull=false;VRenderSystem.topology=VK10.VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
        VRenderSystem.polygonMode=VK10.VK_POLYGON_MODE_FILL;VRenderSystem.colorMask=15;VRenderSystem.logicOp=false;
        PipelineState.blendInfo=additive
                ? new PipelineState.BlendInfo(true,VK10.VK_BLEND_FACTOR_ONE,VK10.VK_BLEND_FACTOR_ONE,
                    VK10.VK_BLEND_FACTOR_ONE,VK10.VK_BLEND_FACTOR_ONE,VK10.VK_BLEND_OP_ADD)
                : new PipelineState.BlendInfo(true,VK10.VK_BLEND_FACTOR_DST_COLOR,VK10.VK_BLEND_FACTOR_ZERO,
                    VK10.VK_BLEND_FACTOR_DST_ALPHA,VK10.VK_BLEND_FACTOR_ZERO,VK10.VK_BLEND_OP_ADD);
        Renderer.setDepthBias(0,0);
        renderer.bindGraphicsPipeline(tintPipeline);
        atlas.setSampler(SamplerManager.getSampler(true,true,0));
        VTextureSelector.bindTexture(0,atlas);
        for (var entry : casterQueues.entrySet()) {
            if (!BrojeClient.CONFIG.isColoredShadows()) break;
            DrawBuffers buffers = entry.getKey().getDrawBuffers();
            if (entry.getValue().size()==0 || buffers.getAreaBuffer(TerrainRenderType.TRANSLUCENT)==null) continue;
            // Binds the area's own sorted index buffer for translucent quads.
            buffers.bindBuffers(command,tintPipeline,TerrainRenderType.TRANSLUCENT,x,y,z,System.currentTimeMillis(),0,1);
            renderer.uploadAndBindUBOs(tintPipeline);
            buffers.buildDrawBatchesDirect(lightCamera,entry.getValue(),TerrainRenderType.TRANSLUCENT);
        }
        renderer.endRenderPass();
    }

    /**
     * Entities, the player (also in first person), dropped items and block entities,
     * drawn by Minecraft's own feature renderers into the sun map. While this runs every
     * compiled vanilla pipeline points at a depth-only caster with the same distortion.
     */
    private int renderEntities(GameRenderState state, DeltaTracker deltaTracker, double x, double y, double z) {
        entityPass = true;
        try { return renderEntityCasters(state,deltaTracker,x,y,z); }
        finally { entityPass = false; }
    }

    private int renderEntityCasters(GameRenderState state, DeltaTracker deltaTracker, double x, double y, double z) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        EntityRenderDispatcher dispatcher = mc.getEntityRenderDispatcher();
        FeatureRenderDispatcher features = mc.gameRenderer.getFeatureRenderDispatcher();
        SubmitNodeStorage storage = features.getSubmitNodeStorage();
        CameraRenderState camera = state.levelRenderState.cameraRenderState;
        PoseStack pose = new PoseStack();
        int count = 0;
        for (Entity entity : level.entitiesForRendering()) {
            if (entity.isSpectator()) continue; // invisible in the world, so no shadow either
            float dx = (float)(entity.getX()-x), dy = (float)(entity.getY()-y), dz = (float)(entity.getZ()-z);
            if (dx*dx+dz*dz > extent*extent || !insideLightBox(dx, dy, dz, 4)) continue;
            float partial = deltaTracker.getGameTimeDeltaPartialTick(!level.tickRateManager().isEntityFrozen(entity));
            EntityRenderState entityState = dispatcher.extractEntity(entity, partial);
            // Labels, leashes, outlines and fire are not solid bodies.
            entityState.nameTag = null;
            entityState.scoreText = null;
            entityState.leashStates = null;
            entityState.outlineColor = 0;
            entityState.displayFireAnimation = false;
            entityState.shadowPieces.clear();
            dispatcher.submit(entityState, camera, entityState.x-x, entityState.y-y, entityState.z-z, pose, storage);
            count++;
        }
        var blockEntities = mc.getBlockEntityRenderDispatcher();
        for (BlockEntityRenderState blockEntity : state.levelRenderState.blockEntityRenderStates) {
            float dx = (float)(blockEntity.blockPos.getX()-x), dy = (float)(blockEntity.blockPos.getY()-y), dz = (float)(blockEntity.blockPos.getZ()-z);
            if (dx*dx+dy*dy+dz*dz > 48*48) continue;
            pose.pushPose();
            pose.translate(dx, dy, dz);
            blockEntities.submit(blockEntity, pose, storage, camera);
            pose.popPose();
        }
        if (count == 0 && state.levelRenderState.blockEntityRenderStates.isEmpty()) return 0;

        Matrix4f savedProjection = new Matrix4f(VRenderSystem.projectionMatrix.buffer.asFloatBuffer());
        GpuTextureView savedColorOverride = RenderSystem.outputColorTextureOverride;
        GpuTextureView savedDepthOverride = RenderSystem.outputDepthTextureOverride;
        Matrix4fStack modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix().identity();
        VRenderSystem.applyModelViewMatrix(new Matrix4f());
        VRenderSystem.applyProjectionMatrix(new Matrix4f(lightMatrix));
        VRenderSystem.calculateMVP();
        redirectPipelines();
        RenderSystem.outputColorTextureOverride = colorView;
        RenderSystem.outputDepthTextureOverride = depthView;
        try {
            features.renderAllFeatures();
            mc.renderBuffers().bufferSource().endBatch();
        } finally {
            RenderSystem.outputColorTextureOverride = savedColorOverride;
            RenderSystem.outputDepthTextureOverride = savedDepthOverride;
            restorePipelines();
            features.clearSubmitNodes();
            modelView.popMatrix();
            VRenderSystem.applyModelViewMatrix(new Matrix4f(modelView));
            VRenderSystem.applyProjectionMatrix(savedProjection);
            VRenderSystem.calculateMVP();
        }
        return count;
    }

    private void redirectPipelines() {
        for (Field field : PIPELINE_FIELDS) {
            RenderPipeline pipeline;
            try { pipeline = (RenderPipeline) field.get(null); }
            catch (IllegalAccessException exception) { continue; }
            ExtendedRenderPipeline extended = ExtendedRenderPipeline.of(pipeline);
            GraphicsPipeline original = extended.getPipeline();
            VertexFormat format = pipeline.getVertexFormat();
            if (original == null || format == null || !format.contains(VertexFormatElement.POSITION)) continue;
            boolean textured = format.contains(VertexFormatElement.UV0) && pipeline.getSamplers().contains("Sampler0");
            redirected.put(pipeline, original);
            extended.setPipeline(entityCaster(format, textured));
        }
    }

    private void restorePipelines() {
        redirected.forEach((pipeline, original) -> ExtendedRenderPipeline.of(pipeline).setPipeline(original));
        redirected.clear();
    }

    private static void depthRead(VulkanImage image,VkCommandBuffer command,MemoryStack stack) {
        VulkanImage.transitionLayout(stack,command,image,image.getCurrentLayout(),VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                VK10.VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT|VK10.VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT,
                VK10.VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT|VK10.VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,
                VK10.VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,VK10.VK_ACCESS_SHADER_READ_BIT);
    }
    private static void colorRead(VulkanImage image,VkCommandBuffer command,MemoryStack stack) {
        VulkanImage.transitionLayout(stack,command,image,image.getCurrentLayout(),VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                VK10.VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,VK10.VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
                VK10.VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,VK10.VK_ACCESS_SHADER_READ_BIT);
    }
    private static void depthAttachment(VulkanImage image,VkCommandBuffer command,MemoryStack stack) {
        int old=image.getCurrentLayout();
        if(old==VK10.VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL)return;
        VulkanImage.transitionLayout(stack,command,image,old,VK10.VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL,
                old==0?VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT:VK10.VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                old==0?0:VK10.VK_ACCESS_SHADER_READ_BIT,
                VK10.VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT|VK10.VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT,
                VK10.VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT|VK10.VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT);
    }
    private void vector(String name,float x,float y,float z,float w) {
        MappedBuffer b=uniforms.get(name);b.putFloat(0,x);b.putFloat(4,y);b.putFloat(8,z);b.putFloat(12,w);
    }
    private static String resource(String name) {
        try(InputStream stream=ShadowRenderer.class.getResourceAsStream("/assets/broje/shaders/"+name)) {
            if(stream==null)throw new IOException(name);
            return new String(stream.readAllBytes(),StandardCharsets.UTF_8);
        } catch(IOException exception){throw new IllegalStateException("Shadow resource "+name,exception);}
    }
    private void closeViews() {
        depthViews.values().forEach(view->VK10.vkDestroyImageView(Vulkan.getVkDevice(),view,null));
        depthViews.clear();
    }
    public static void close() {
        active=false;
        mapReady=false;
        if(instance==null)return;
        Vulkan.waitIdle();
        instance.casterPipeline.cleanUp();instance.tintPipeline.cleanUp();instance.solidPipeline.cleanUp();instance.cutoutPipeline.cleanUp();
        instance.entityCasters.values().forEach(GraphicsPipeline::cleanUp);
        instance.closeViews();
        if(instance.tintTarget!=null)instance.tintTarget.cleanUp(false);
        if(instance.tintStorage!=null)instance.tintStorage.cleanUp();
        if(instance.shadowTarget!=null)instance.shadowTarget.cleanUp();
        instance.uniforms.values().forEach(buffer->MemoryUtil.memFree(buffer.buffer));
        instance=null;
    }
    /** The sun map, read through a depth-only view of its D24S8 attachment. */
    private final class MapDescriptor extends ImageDescriptor {
        MapDescriptor(int binding){super(binding,"sampler2D","Sampler1",11,VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);}
        @Override public VulkanImage getImage(){return shadowTarget.getDepthAttachment();}
        @Override public long getImageView(VulkanImage image){return mapView(image);}
    }
    /** Sunlight transmittance of translucent casters, the color target of the sun map. */
    private final class TintDescriptor extends ImageDescriptor {
        TintDescriptor(int binding){super(binding,"sampler2D","Sampler3",11,VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);}
        @Override public VulkanImage getImage(){return tintStorage.getColorAttachment();}
    }
    private record State(boolean test,boolean mask,int depthFun,boolean cull,int topology,int polygon,int color,boolean logic,PipelineState.BlendInfo blend) {
        State(){this(VRenderSystem.depthTest,VRenderSystem.depthMask,VRenderSystem.depthFun,VRenderSystem.cull,VRenderSystem.topology,
                VRenderSystem.polygonMode,VRenderSystem.colorMask,VRenderSystem.logicOp,PipelineState.blendInfo);}
        void restore(){VRenderSystem.depthTest=test;VRenderSystem.depthMask=mask;VRenderSystem.depthFun=depthFun;VRenderSystem.cull=cull;
            VRenderSystem.topology=topology;VRenderSystem.polygonMode=polygon;VRenderSystem.colorMask=color;VRenderSystem.logicOp=logic;PipelineState.blendInfo=blend;}
    }
}
