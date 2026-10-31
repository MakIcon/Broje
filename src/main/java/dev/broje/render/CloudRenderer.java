package dev.broje.render;

import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import com.google.gson.JsonParser;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.broje.BrojeClient;
import dev.broje.config.BrojeConfig;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FogType;
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
import net.vulkanmod.vulkan.texture.VTextureSelector;
import net.vulkanmod.vulkan.texture.VulkanImage;
import net.vulkanmod.vulkan.util.MappedBuffer;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * Volumetric clouds in place of the flat vanilla layer: Quasar's cloud volume (its
 * natural cloud map, height profile, lighting and ray march) marched at reduced
 * resolution, filtered within the current frame, then composited over the sky and
 * terrain with depth-aware upsampling.
 */
public final class CloudRenderer {
    /** The cloud map origin (camera plus wind) wraps here to stay precise as a float. */
    private static final int PERIOD = 61440;
    /** Cloud shadows have broad penumbrae: eight-block texels are sufficient. */
    private static final int SHADOW_SIZE = 192;
    private static final float SHADOW_RANGE = 1536;
    /** The sky hemisphere for water reflections: waves blur it, so it can be small. */
    private static final int SKY_SIZE = 192;
    private static CloudRenderer instance;
    private static boolean failed;
    // Water draws before the clouds as well, so it reflects the previous frame's sky.
    private static boolean skyReady;
    // The last shadow map: where it was centred, its plane and its light. Terrain draws
    // before the clouds, so it reads the map of the previous frame through these.
    private static boolean shadowReady;
    private static double shadowX, shadowZ;
    private static float shadowPlane;
    private static final Vector3f shadowLight = new Vector3f();

    private final Map<String, MappedBuffer> uniforms = new LinkedHashMap<>();
    private final Map<VulkanImage, Framebuffer> colorTargets = new IdentityHashMap<>();
    private final Matrix4f inverseProjection = new Matrix4f();
    private final Matrix4f inverseView = new Matrix4f();
    private final GraphicsPipeline marchPipeline, filterPipeline, resolvePipeline, shadowPipeline, skyPipeline;
    private final FogRenderer.DepthSampler depthSampler = new FogRenderer.DepthSampler();
    private final VulkanImage noise = CloudNoise.load();
    private final Vector3f currentShadowLight = new Vector3f();
    private float currentShadowPlane;
    private Framebuffer reducedTarget, shadowTarget, skyTarget;
    private Framebuffer filteredTarget;
    private int frameIndex;
    // A pass never samples the image it writes.
    private VulkanImage sampledClouds;
    private RenderPass cloudPass, reducedPass;
    private boolean firstFrame = true;

    private CloudRenderer() {
        uniforms.put("BrojeInverseProjection", new MappedBuffer(64));
        uniforms.put("BrojeInverseView", new MappedBuffer(64));
        for (String name : new String[] {"BrojeCamera", "BrojeCloudShape", "BrojeCloudTime", "BrojeCloudMarch", "BrojeCloudSun",
                "BrojeCloudSky", "BrojeCloudHorizon", "BrojeCloudTarget", "BrojeCloudShadowLight", "BrojeCloudShadowArea", "BrojeCloudLook"}) {
            uniforms.put(name, new MappedBuffer(16));
        }
        marchPipeline = createPipeline("march", "");
        filterPipeline = createPipeline("filter", "#define CLOUDS_FILTER\n");
        resolvePipeline = createPipeline("resolve", "#define CLOUDS_RESOLVE\n");
        shadowPipeline = createPipeline("shadow", "#define CLOUDS_SHADOW\n");
        skyPipeline = createPipeline("sky", "#define CLOUDS_SKY\n");
        Renderer.getInstance().addOnResizeCallback(this::destroyTargets);
        BrojeClient.LOGGER.info("Broje cloud pipelines compiled: reduced ray march, current-frame filter and depth-aware composite.");
    }

    private GraphicsPipeline createPipeline(String name, String defines) {
        Pipeline.Builder builder = new Pipeline.Builder(CustomVertexFormat.NONE, "broje:clouds_" + name);
        builder.setUniformSupplierGetter(info -> () -> uniforms.get(info.name));
        builder.applyConfig(PipelineConfig.fromJson("clouds", JsonParser.parseString(FogRenderer.resource("clouds.json")).getAsJsonObject()));
        builder.getImageDescriptors().set(0, depthSampler);
        builder.getImageDescriptors().set(1, new ImageDescriptor(2, "sampler2D", "Sampler1", 11, VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER) {
            @Override public VulkanImage getImage() {
                VulkanImage opaque = FogRenderer.opaqueDepth();
                return opaque != null ? opaque : VTextureSelector.getWhiteTexture();
            }
        });
        builder.getImageDescriptors().set(2, new ImageDescriptor(3, "sampler2D", "Sampler2", 11, VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER) {
            @Override public VulkanImage getImage() {
                return sampledClouds != null ? sampledClouds : VTextureSelector.getWhiteTexture();
            }
        });
        builder.getImageDescriptors().set(3, new ImageDescriptor(4, "sampler2D", "Sampler3", 11, VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER) {
            @Override public VulkanImage getImage() { return noise; }
        });
        builder.setShaderSrc(SPIRVUtils.ShaderKind.VERTEX_SHADER, FogRenderer.resource("fog.vsh"));
        String fragment = FogRenderer.resource("clouds.fsh");
        int versionEnd = fragment.indexOf('\n') + 1;
        builder.setShaderSrc(SPIRVUtils.ShaderKind.FRAGMENT_SHADER,
                fragment.substring(0, versionEnd) + defines + fragment.substring(versionEnd));
        GraphicsPipeline result = builder.createGraphicsPipeline();
        result.getBuffers().forEach(buffer -> { buffer.setUseGlobalBuffer(true); buffer.setUpdate(true); });
        return result;
    }

    /** Broje owns the overworld sky layer; the flat vanilla clouds must not be drawn under it. */
    public static boolean replacesVanilla() {
        BrojeConfig config = BrojeClient.CONFIG;
        Minecraft minecraft = Minecraft.getInstance();
        return !failed && config != null && config.isEnabled() && config.isCloudsEnabled()
                && minecraft.level != null && Level.OVERWORLD.equals(minecraft.level.dimension());
    }

    static boolean wanted(GameRenderState state) {
        CameraRenderState camera = state.levelRenderState.cameraRenderState;
        return replacesVanilla() && camera.fogType == FogType.NONE && !camera.entityRenderState.doesMobEffectBlockSky;
    }

    /** Called after the level, before the fog: the haze then also covers distant clouds. */
    public static void render(GameRenderState state, DeltaTracker deltaTracker) {
        if (!wanted(state)) {
            shadowReady = false;
            skyReady = false;
            return;
        }
        try {
            if (instance == null) instance = new CloudRenderer();
            instance.draw(state, deltaTracker, BrojeClient.CONFIG);
        } catch (RuntimeException exception) {
            failed = true;
            shadowReady = false;
            skyReady = false;
            BrojeClient.LOGGER.error("Broje clouds failed. Returning to the vanilla clouds.", exception);
        }
    }

    private void draw(GameRenderState state, DeltaTracker deltaTracker, BrojeConfig config) {
        Renderer renderer = Renderer.getInstance();
        Framebuffer main = renderer.getMainPass().getMainFramebuffer();
        if (main == null || main.getDepthAttachment() == null || main.getWidth() <= 0 || main.getHeight() <= 0) return;

        // Only Ultra marches at full resolution; the others are capped for 4K.
        ensureReducedTarget(main, config.getCloudQuality() >= 3 ? 100 : 50);
        updateUniforms(state, deltaTracker, config);
        VulkanImage depth = main.getDepthAttachment();
        Framebuffer colorOnly = colorTargets.computeIfAbsent(main.getColorAttachment(), color -> Framebuffer.builder(color, null).build());
        if (cloudPass == null) {
            RenderPass.Builder builder = RenderPass.builder(colorOnly);
            builder.getColorAttachmentInfo().setOps(VK10.VK_ATTACHMENT_LOAD_OP_LOAD, VK10.VK_ATTACHMENT_STORE_OP_STORE);
            cloudPass = builder.build();
        }

        renderer.endRenderPass();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();
            VulkanImage.transitionLayout(stack, commandBuffer, depth, depth.getCurrentLayout(), VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                    VK10.VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT | VK10.VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT,
                    VK10.VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT | VK10.VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,
                    VK10.VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK10.VK_ACCESS_SHADER_READ_BIT);
            VulkanImage previousTexture = VTextureSelector.getImage(0);
            try {
                VTextureSelector.bindTexture(0, depth);
                sampledClouds = null;
                drawPass(marchPipeline, reducedTarget, reducedPass, false, stack);
                readable(stack, reducedTarget);
                // No previous-frame color: translucent cloud volumes do not have
                // one depth with which to reproject all overlapping cloud surfaces.
                sampledClouds = reducedTarget.getColorAttachment();
                drawPass(filterPipeline, filteredTarget, reducedPass, false, stack);
                readable(stack, filteredTarget);
                sampledClouds = filteredTarget.getColorAttachment();
                drawPass(resolvePipeline, colorOnly, cloudPass, true, stack);
                if (!config.isCloudShadows()) shadowReady = false;
                CameraRenderState camera = state.levelRenderState.cameraRenderState;
                if (config.isCloudShadows() && (!shadowReady || frameIndex % 2 == 0
                        || Math.abs(camera.pos.x - shadowX) > 32 || Math.abs(camera.pos.z - shadowZ) > 32)) {
                    sampledClouds = null;
                    drawPass(shadowPipeline, shadowTarget, reducedPass, false, stack);
                    readable(stack, shadowTarget);
                    shadowX = camera.pos.x; shadowZ = camera.pos.z;
                    shadowPlane = currentShadowPlane;
                    shadowLight.set(currentShadowLight);
                    shadowReady = true;
                } else if (!skyReady || frameIndex % 2 == 1) {
                    // Alternates with the shadow map. A short march is enough for a
                    // reflection that waves distort anyway.
                    sampledClouds = null;
                    vector("BrojeCloudMarch", 64, 32, 2, 0);
                    drawPass(skyPipeline, skyTarget, reducedPass, false, stack);
                    readable(stack, skyTarget);
                    skyReady = true;
                }
            } finally {
                sampledClouds = null;
                frameIndex++;
                VTextureSelector.bindTexture(0, previousTexture);
                VulkanImage.transitionLayout(stack, commandBuffer, depth, depth.getCurrentLayout(), VK10.VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL,
                        VK10.VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK10.VK_ACCESS_SHADER_READ_BIT,
                        VK10.VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT | VK10.VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT,
                        VK10.VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT | VK10.VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT);
                renderer.getMainPass().rebindMainTarget();
            }
        }
        if (firstFrame) {
            firstFrame = false;
            BrojeClient.LOGGER.info("Broje clouds rendering at {}x{} (march {}x{}), base Y={}, thickness {}.", main.getWidth(), main.getHeight(),
                    reducedTarget.getWidth(), reducedTarget.getHeight(), config.getCloudHeight(), thickness(config));
        }
    }

    private static void readable(MemoryStack stack, Framebuffer target) {
        VulkanImage image = target.getColorAttachment();
        VulkanImage.transitionLayout(stack, Renderer.getCommandBuffer(), image, image.getCurrentLayout(), VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                VK10.VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK10.VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
                VK10.VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK10.VK_ACCESS_SHADER_READ_BIT);
    }

    private void ensureReducedTarget(Framebuffer main, int percent) {
        int width = Math.max(1, (main.getWidth() * percent + 99) / 100);
        int height = Math.max(1, (main.getHeight() * percent + 99) / 100);
        // At 4K, a nominal 50% otherwise does four times the volumetric work of
        // 1080p. Full (100%) remains an explicit uncapped quality setting.
        if (percent < 100) {
            double scale = Math.min(1.0, Math.min(960.0 / width, 540.0 / height));
            width = Math.max(1, (int) Math.round(width * scale));
            height = Math.max(1, (int) Math.round(height * scale));
        }
        if (reducedTarget != null && reducedTarget.getWidth() == width && reducedTarget.getHeight() == height) return;
        if (reducedTarget != null) { Vulkan.waitIdle(); reducedTarget.cleanUp(); }
        if (filteredTarget != null) filteredTarget.cleanUp();
        filteredTarget = Framebuffer.builder(width, height, 1, false).setFormat(VK10.VK_FORMAT_R16G16B16A16_SFLOAT).build();
        if (shadowTarget == null)
            shadowTarget = Framebuffer.builder(SHADOW_SIZE, SHADOW_SIZE, 1, false).setFormat(VK10.VK_FORMAT_R16G16B16A16_SFLOAT).build();
        if (skyTarget == null)
            skyTarget = Framebuffer.builder(SKY_SIZE, SKY_SIZE, 1, false).setFormat(VK10.VK_FORMAT_R16G16B16A16_SFLOAT)
                    .setLinearFiltering(true).build();
        reducedTarget = Framebuffer.builder(width, height, 1, false).setFormat(VK10.VK_FORMAT_R16G16B16A16_SFLOAT).build();
        if (reducedPass == null) {
            RenderPass.Builder builder = RenderPass.builder(reducedTarget);
            builder.getColorAttachmentInfo().setOps(VK10.VK_ATTACHMENT_LOAD_OP_DONT_CARE, VK10.VK_ATTACHMENT_STORE_OP_STORE);
            reducedPass = builder.build();
        }
    }

    private void drawPass(GraphicsPipeline shader, Framebuffer target, RenderPass pass, boolean composite, MemoryStack stack) {
        Renderer renderer = Renderer.getInstance();
        FogRenderer.RenderState saved = new FogRenderer.RenderState();
        vector("BrojeCloudTarget", target.getWidth(), target.getHeight(), 0, 0);
        try {
            renderer.beginRenderPass(pass, target);
            Renderer.setViewport(0, 0, target.getWidth(), target.getHeight(), stack);
            Renderer.setScissor(0, 0, target.getWidth(), target.getHeight());
            VRenderSystem.depthTest = false;
            VRenderSystem.depthMask = false;
            VRenderSystem.cull = false;
            VRenderSystem.topology = VK10.VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
            VRenderSystem.polygonMode = VK10.VK_POLYGON_MODE_FILL;
            VRenderSystem.logicOp = false;
            VRenderSystem.colorMask = 15;
            // Composite: scene * transmittance + in-scattered light; scene alpha unchanged.
            PipelineState.blendInfo = composite ? new PipelineState.BlendInfo(true, VK10.VK_BLEND_FACTOR_ONE,
                    VK10.VK_BLEND_FACTOR_SRC_ALPHA, VK10.VK_BLEND_FACTOR_ZERO, VK10.VK_BLEND_FACTOR_ONE, VK10.VK_BLEND_OP_ADD)
                    : new PipelineState.BlendInfo(false, 1, 0, 1, 0, 0);
            renderer.bindGraphicsPipeline(shader);
            renderer.uploadAndBindUBOs(shader);
            VK10.vkCmdDraw(Renderer.getCommandBuffer(), 3, 1, 0, 0);
        } finally {
            renderer.endRenderPass();
            saved.restore();
        }
    }

    /** Big clouds are also taller: 48 blocks at 50% size, 60 at 200%, 76 at 400%. */
    private static float thickness(BrojeConfig config) {
        return 44 + 8 * config.getCloudSize() * 0.01f;
    }

    private void updateUniforms(GameRenderState state, DeltaTracker deltaTracker, BrojeConfig config) {
        CameraRenderState camera = state.levelRenderState.cameraRenderState;
        inverseProjection.set(VRenderSystem.projection).invert().get(uniforms.get("BrojeInverseProjection").buffer);
        inverseView.set(camera.viewRotationMatrix).invert().get(uniforms.get("BrojeInverseView").buffer);
        float partialTick = deltaTracker.getGameTimeDeltaPartialTick(false);
        Level level = Minecraft.getInstance().level;
        float rain = level.getRainLevel(partialTick);
        float thunder = level.getThunderLevel(partialTick);

        // Quasar's motion: wind of up to 0.1 blocks per tick at 100%, and a formation
        // clock that cross-fades four layouts, so clouds form and dissolve as they drift.
        // Promote before adding the fractional tick: long + float would lose sub-tick
        // precision in older worlds and make the clouds advance in small jumps.
        double ticks = (double) state.levelRenderState.gameTime + partialTick;
        double speed = config.getCloudWind() * 0.02;
        double travelled = ticks * speed * 0.05;
        vector("BrojeCamera", wrap(camera.pos.x + travelled * 0.94, PERIOD), (float) camera.pos.y,
                wrap(camera.pos.z + travelled * 0.342, PERIOD), RenderSystem.getDevice().isZZeroToOne() ? 1 : 0);
        float thickness = thickness(config);
        float coverage = 0.05f + 0.9f * Math.min(1, config.getCloudCoverage() * 0.01f + rain * 0.2f);
        vector("BrojeCloudShape", config.getCloudHeight(), thickness, coverage, config.getCloudSize() * 0.01f);
        vector("BrojeCloudTime", wrap(ticks * speed * 0.0009, 4), rain, thunder, FogRenderer.opaqueDepth() != null ? 1 : 0);
        // Low, Medium, High, Ultra: Quasar's primary steps at the horizon and zenith, light steps.
        int quality = config.getCloudQuality();
        vector("BrojeCloudMarch", 64 + 32 * quality, 32 + 16 * quality, quality == 0 ? 2 : quality == 3 ? 4 : 3, 0);

        // The shader lights by the sun and, opposite it, the moon; both fade at the horizon.
        SkyRenderState sky = state.levelRenderState.skyRenderState;
        Vector3f sun = SunDirection.at(sky.sunAngle, config.getSunPathTilt(), new Vector3f());
        // Night is complete once the sun is 18 degrees below the horizon.
        float night = smoothstep(0, 0.309f, -sun.y);
        vector("BrojeCloudSun", sun.x, sun.y, sun.z, night);
        // Quasar's sky radiance: blue by day, paler toward sunset, grey in rain and storms.
        float sunset = Math.min(sq((1 - Math.max(sun.y, 0)) * 1.1f), 1);
        float daySky = 0.6f + 0.4f * (1 - sunset);
        vector("BrojeCloudSky", skyRadiance(0.4f * daySky, rain, thunder, night), skyRadiance(0.6f * daySky, rain, thunder, night),
                skyRadiance(daySky, rain, thunder, night), 1 - rain);
        Vector4f horizon = camera.fogData.color;
        vector("BrojeCloudHorizon", horizon.x, horizon.y, horizon.z, 0.55f);

        // Every cloud body lies above the shadow plane. The shadow light never grazes:
        // a low sun would stretch the shadows across the whole map.
        currentShadowPlane = config.getCloudHeight() - thickness * 1.75f - 8;
        currentShadowLight.set(sun).normalize();
        currentShadowLight.set(currentShadowLight.x, Math.max(currentShadowLight.y, 0.2f), currentShadowLight.z).normalize();
        vector("BrojeCloudShadowLight", currentShadowLight.x, currentShadowLight.y, currentShadowLight.z, 0);
        vector("BrojeCloudShadowArea", 0, 0, SHADOW_RANGE, currentShadowPlane);
        vector("BrojeCloudLook", config.getCloudDensity() * 0.01f, config.getCloudAiriness() * 0.01f, config.getCloudRelief(), 0);
    }

    private static float skyRadiance(float day, float rain, float thunder, float night) {
        return lerp(lerp(day, 0.6f, rain), 0.3f, thunder) * (1 - night) + night * 0.02f;
    }

    private static float sq(float value) { return value * value; }

    private static float smoothstep(float edge0, float edge1, float value) {
        float t = Math.max(0, Math.min(1, (value - edge0) / (edge1 - edge0)));
        return t * t * (3 - 2 * t);
    }

    private static float lerp(float a, float b, float t) { return a + (b - a) * t; }

    private static float wrap(double value, int period) { return (float) (value - Math.floor(value / period) * period); }

    private void vector(String name, float x, float y, float z, float w) {
        MappedBuffer buffer = uniforms.get(name);
        buffer.putFloat(0, x); buffer.putFloat(4, y); buffer.putFloat(8, z); buffer.putFloat(12, w);
    }

    /**
     * Clouds of the upper sky hemisphere around the camera, stereographically mapped to
     * a disk: premultiplied display color and the transmittance of the sky behind them.
     * Null while there is none to read.
     */
    public static VulkanImage skyMap() {
        return skyReady && instance != null && instance.skyTarget != null ? instance.skyTarget.getColorAttachment() : null;
    }

    /** The cloud shadow map, or null while there is none to read. */
    static VulkanImage shadowMap() {
        return shadowReady && instance != null && instance.shadowTarget != null ? instance.shadowTarget.getColorAttachment() : null;
    }

    /**
     * Terrain uniforms for the cloud shadow map, relative to the camera at (x, y, z):
     * camera minus map centre x/z, map plane height, strength (0 = none); then the inverse
     * map range and the light direction.
     */
    static void shadowUniforms(double x, double y, double z, MappedBuffer placement, MappedBuffer map) {
        boolean on = shadowMap() != null;
        put(placement, (float) (x - shadowX), (float) (z - shadowZ), (float) (shadowPlane - y), on ? 0.8f : 0);
        put(map, 1 / SHADOW_RANGE, shadowLight.x, shadowLight.y, shadowLight.z);
    }

    private static void put(MappedBuffer buffer, float x, float y, float z, float w) {
        buffer.putFloat(0, x); buffer.putFloat(4, y); buffer.putFloat(8, z); buffer.putFloat(12, w);
    }

    public static void close() {
        if (instance == null) return;
        VK10.vkDeviceWaitIdle(Vulkan.getVkDevice());
        instance.marchPipeline.cleanUp();
        instance.filterPipeline.cleanUp();
        instance.resolvePipeline.cleanUp();
        instance.shadowPipeline.cleanUp();
        instance.skyPipeline.cleanUp();
        instance.noise.free();
        instance.destroyTargets();
        if (instance.cloudPass != null) instance.cloudPass.cleanUp();
        if (instance.reducedPass != null) instance.reducedPass.cleanUp();
        instance.uniforms.values().forEach(buffer -> MemoryUtil.memFree(buffer.buffer));
        instance = null;
    }

    private void destroyTargets() {
        Vulkan.waitIdle();
        depthSampler.close();
        colorTargets.values().forEach(target -> target.cleanUp(false));
        colorTargets.clear();
        if (reducedTarget != null) { reducedTarget.cleanUp(); reducedTarget = null; }
        if (filteredTarget != null) { filteredTarget.cleanUp(); filteredTarget = null; }
        if (shadowTarget != null) { shadowTarget.cleanUp(); shadowTarget = null; }
        if (skyTarget != null) { skyTarget.cleanUp(); skyTarget = null; }
        skyReady = false;
        shadowReady = false;
    }
}
