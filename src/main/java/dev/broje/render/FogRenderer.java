package dev.broje.render;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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

/** A color-only scattering pass: the world depth is sampled, never cleared or written. */
public final class FogRenderer {
    private static FogRenderer instance;
    private static boolean failed;
    private static boolean snapshotReady;

    private final Map<String, MappedBuffer> uniforms = new LinkedHashMap<>();
    private final Map<VulkanImage, Framebuffer> colorTargets = new IdentityHashMap<>();
    private final Matrix4f inverseProjection = new Matrix4f();
    private final Matrix4f inverseView = new Matrix4f();
    private GraphicsPipeline pipeline;
    private final GraphicsPipeline copyPipeline;
    private final DepthSampler depthSampler = new DepthSampler();
    private final DepthSampler copySampler = new DepthSampler();
    private Framebuffer opaqueTarget;
    private RenderPass fogPass, copyPass;
    private boolean firstFrame = true;

    private FogRenderer() {
        uniforms.put("BrojeInverseProjection", new MappedBuffer(64));
        uniforms.put("BrojeInverseView", new MappedBuffer(64));
        for (String name : new String[] {"BrojeCamera", "BrojeFog", "BrojeVolume", "BrojeAtmosphere", "BrojeSun", "BrojeShaft", "BrojeShaftRange"}) {
            uniforms.put(name, new MappedBuffer(16));
        }
        Pipeline.Builder builder = new Pipeline.Builder(CustomVertexFormat.NONE, "broje:fog");
        // The sun map's matrix is owned by the shadow pass of the same frame.
        MappedBuffer identity = new MappedBuffer(64);
        new Matrix4f().get(identity.buffer);
        builder.setUniformSupplierGetter(info -> uniforms.containsKey(info.name) ? () -> uniforms.get(info.name) : () -> {
            MappedBuffer shared = ShadowRenderer.uniform(info.name);
            return shared != null ? shared : identity;
        });
        builder.applyConfig(PipelineConfig.fromJson("fog", JsonParser.parseString(resource("fog.json")).getAsJsonObject()));
        // D24S8 attachment views contain both aspects. A sampled descriptor MUST select
        // only depth; using the attachment view returns undefined values on Vulkan.
        builder.getImageDescriptors().set(0, depthSampler);
        builder.getImageDescriptors().set(1, new SunMapSampler());
        builder.getImageDescriptors().set(2, new OpaqueSampler());
        builder.setShaderSrc(SPIRVUtils.ShaderKind.VERTEX_SHADER, resource("fog.vsh"));
        builder.setShaderSrc(SPIRVUtils.ShaderKind.FRAGMENT_SHADER, resource("fog.fsh"));
        pipeline = builder.createGraphicsPipeline();
        pipeline.getBuffers().forEach(buffer -> { buffer.setUseGlobalBuffer(true); buffer.setUpdate(true); });
        Pipeline.Builder copy = new Pipeline.Builder(CustomVertexFormat.NONE, "broje:depth_copy");
        copy.setUniformSupplierGetter(info -> () -> uniforms.get("BrojeCamera"));
        copy.applyConfig(PipelineConfig.fromJson("depth_copy", JsonParser.parseString(resource("depth_copy.json")).getAsJsonObject()));
        copy.getImageDescriptors().set(0, copySampler);
        copy.setShaderSrc(SPIRVUtils.ShaderKind.VERTEX_SHADER, resource("fog.vsh"));
        copy.setShaderSrc(SPIRVUtils.ShaderKind.FRAGMENT_SHADER, resource("depth_copy.fsh"));
        copyPipeline = copy.createGraphicsPipeline();
        copyPipeline.getBuffers().forEach(buffer -> { buffer.setUseGlobalBuffer(true); buffer.setUpdate(true); });
        Renderer.getInstance().addOnResizeCallback(() -> {
            depthSampler.close();
            copySampler.close();
            colorTargets.clear();
        });
        BrojeClient.LOGGER.info("Broje fog pipeline compiled: depth-limited height fog and 3D ray marching.");
    }

    /** Called once per frame before the level is drawn. */
    public static void beginFrame() {
        snapshotReady = false;
    }

    private static boolean wanted(GameRenderState state) {
        BrojeConfig config = BrojeClient.CONFIG;
        Minecraft minecraft = Minecraft.getInstance();
        CameraRenderState camera = state.levelRenderState.cameraRenderState;
        return !failed && config != null && config.isEnabled() && config.isFogEnabled() && config.getFogDensity() > 0
                && minecraft.level != null && Level.OVERWORLD.equals(minecraft.level.dimension())
                && camera.fogType == FogType.NONE && !camera.entityRenderState.doesMobEffectBlockSky;
    }

    /**
     * Called right before VulkanMod draws translucent terrain: keeps the depth of the
     * opaque world, so the fog can run on behind glass instead of stopping at the pane.
     */
    public static void snapshotOpaqueDepth() {
        if (snapshotReady || instance == null || !wanted(Minecraft.getInstance().gameRenderer.getGameRenderState())) return;
        try {
            snapshotReady = instance.copyOpaqueDepth();
        } catch (RuntimeException exception) {
            failed = true;
            BrojeClient.LOGGER.error("Broje fog could not keep the opaque depth. Fog is off.", exception);
        }
    }

    private boolean copyOpaqueDepth() {
        Renderer renderer = Renderer.getInstance();
        Framebuffer main = renderer.getMainPass().getMainFramebuffer();
        if (main == null || main.getDepthAttachment() == null || main.getWidth() <= 0 || main.getHeight() <= 0) return false;
        if (opaqueTarget == null || opaqueTarget.getWidth() != main.getWidth() || opaqueTarget.getHeight() != main.getHeight()) {
            if (opaqueTarget != null) { Vulkan.waitIdle(); opaqueTarget.cleanUp(); }
            opaqueTarget = Framebuffer.builder(main.getWidth(), main.getHeight(), 1, false).setFormat(VK10.VK_FORMAT_R32_SFLOAT).build();
            if (copyPass == null) {
                RenderPass.Builder builder = RenderPass.builder(opaqueTarget);
                builder.getColorAttachmentInfo().setOps(VK10.VK_ATTACHMENT_LOAD_OP_DONT_CARE, VK10.VK_ATTACHMENT_STORE_OP_STORE);
                copyPass = builder.build();
            }
        }
        VulkanImage depth = main.getDepthAttachment();
        renderer.endRenderPass();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();
            VulkanImage.transitionLayout(stack, commandBuffer, depth, depth.getCurrentLayout(), VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                    VK10.VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT | VK10.VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT,
                    VK10.VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT | VK10.VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,
                    VK10.VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK10.VK_ACCESS_SHADER_READ_BIT);
            renderer.beginRenderPass(copyPass, opaqueTarget);
            Renderer.setViewport(0, 0, main.getWidth(), main.getHeight(), stack);
            Renderer.setScissor(0, 0, main.getWidth(), main.getHeight());
            VulkanImage previousTexture = VTextureSelector.getImage(0);
            RenderState saved = new RenderState();
            try {
                VTextureSelector.bindTexture(0, depth);
                VRenderSystem.depthTest = false;
                VRenderSystem.depthMask = false;
                VRenderSystem.cull = false;
                VRenderSystem.topology = VK10.VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
                VRenderSystem.polygonMode = VK10.VK_POLYGON_MODE_FILL;
                VRenderSystem.logicOp = false;
                VRenderSystem.colorMask = 15;
                PipelineState.blendInfo = new PipelineState.BlendInfo(false, 1, 0, 1, 0, 0);
                renderer.bindGraphicsPipeline(copyPipeline);
                renderer.uploadAndBindUBOs(copyPipeline);
                VK10.vkCmdDraw(commandBuffer, 3, 1, 0, 0);
            } finally {
                saved.restore();
                VTextureSelector.bindTexture(0, previousTexture);
                renderer.endRenderPass();
                VulkanImage.transitionLayout(stack, commandBuffer, depth, depth.getCurrentLayout(), VK10.VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL,
                        VK10.VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK10.VK_ACCESS_SHADER_READ_BIT,
                        VK10.VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT | VK10.VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT,
                        VK10.VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT | VK10.VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT);
                VulkanImage color = opaqueTarget.getColorAttachment();
                VulkanImage.transitionLayout(stack, commandBuffer, color, color.getCurrentLayout(), VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                        VK10.VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK10.VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
                        VK10.VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK10.VK_ACCESS_SHADER_READ_BIT);
                renderer.getMainPass().rebindMainTarget();
            }
        }
        return true;
    }

    public static void render(GameRenderState state, DeltaTracker deltaTracker) {
        BrojeConfig config = BrojeClient.CONFIG;
        if (!wanted(state)) return;
        if (instance == null) {
            try {
                instance = new FogRenderer();
            } catch (RuntimeException exception) {
                failed = true;
                BrojeClient.LOGGER.error("Broje fog could not initialize. Keeping the normal VulkanMod image.", exception);
                return;
            }
        }
        instance.draw(state, deltaTracker, config);
    }

    private void draw(GameRenderState state, DeltaTracker deltaTracker, BrojeConfig config) {
        Renderer renderer = Renderer.getInstance();
        Framebuffer main = renderer.getMainPass().getMainFramebuffer();
        if (main == null || main.getDepthAttachment() == null || main.getWidth() <= 0 || main.getHeight() <= 0) return;

        updateUniforms(state, deltaTracker, config);
        VulkanImage depth = main.getDepthAttachment();
        Framebuffer colorOnly = colorTargets.computeIfAbsent(main.getColorAttachment(), color -> Framebuffer.builder(color, null).build());
        if (fogPass == null) {
            RenderPass.Builder builder = RenderPass.builder(colorOnly);
            builder.getColorAttachmentInfo().setOps(VK10.VK_ATTACHMENT_LOAD_OP_LOAD, VK10.VK_ATTACHMENT_STORE_OP_STORE);
            fogPass = builder.build();
        }

        // Do not attach a depth image while sampling it: that would be a Vulkan feedback hazard.
        renderer.endRenderPass();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();
            // Include both early AND late depth writes in the producer dependency.
            VulkanImage.transitionLayout(stack, commandBuffer, depth, depth.getCurrentLayout(), VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                    VK10.VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT | VK10.VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT,
                    VK10.VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT | VK10.VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,
                    VK10.VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK10.VK_ACCESS_SHADER_READ_BIT);
            renderer.beginRenderPass(fogPass, colorOnly);
            Renderer.setViewport(0, 0, main.getWidth(), main.getHeight(), stack);
            Renderer.setScissor(0, 0, main.getWidth(), main.getHeight());

            VulkanImage previousTexture = VTextureSelector.getImage(0);
            RenderState saved = new RenderState();
            try {
                VTextureSelector.bindTexture(0, depth);
                VRenderSystem.depthTest = false;
                VRenderSystem.depthMask = false;
                VRenderSystem.cull = false;
                VRenderSystem.topology = VK10.VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
                VRenderSystem.polygonMode = VK10.VK_POLYGON_MODE_FILL;
                VRenderSystem.logicOp = false;
                VRenderSystem.colorMask = 15;
                // Shader RGB is premultiplied in-scattering; alpha is 1 - transmittance.
                PipelineState.blendInfo = new PipelineState.BlendInfo(true, VK10.VK_BLEND_FACTOR_ONE,
                        VK10.VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA, VK10.VK_BLEND_FACTOR_ONE,
                        VK10.VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA, VK10.VK_BLEND_OP_ADD);
                renderer.bindGraphicsPipeline(pipeline);
                renderer.uploadAndBindUBOs(pipeline);
                VK10.vkCmdDraw(commandBuffer, 3, 1, 0, 0);
            } finally {
                saved.restore();
                VTextureSelector.bindTexture(0, previousTexture);
                renderer.endRenderPass();
                VulkanImage.transitionLayout(stack, commandBuffer, depth, depth.getCurrentLayout(), VK10.VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL,
                        VK10.VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK10.VK_ACCESS_SHADER_READ_BIT,
                        VK10.VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT | VK10.VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT,
                        VK10.VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT | VK10.VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT);
                renderer.getMainPass().rebindMainTarget();
            }
        }
        if (firstFrame) {
            firstFrame = false;
            BrojeClient.LOGGER.info("Broje fog rendering at {}x{}; world depth sampled before the hand/UI.", main.getWidth(), main.getHeight());
            BrojeClient.LOGGER.info("Fog uniforms: camera={}, far={}, density={}, volume={}; projection={}",
                    state.levelRenderState.cameraRenderState.pos, state.levelRenderState.cameraRenderState.depthFar,
                    config.getFogDensity(), config.isVolumetricFog(), VRenderSystem.projection);
            BrojeClient.LOGGER.info("Fog inverse: {} ; mapped m00={} m33={} depth format={}", inverseProjection,
                    uniforms.get("BrojeInverseProjection").getFloat(0), uniforms.get("BrojeInverseProjection").getFloat(60), depth.format);
        }
    }

    private void updateUniforms(GameRenderState state, DeltaTracker deltaTracker, BrojeConfig config) {
        CameraRenderState camera = state.levelRenderState.cameraRenderState;
        // VulkanMod captures the projection after view bobbing, damage tilt and portal distortion.
        inverseProjection.set(VRenderSystem.projection).invert().get(uniforms.get("BrojeInverseProjection").buffer);
        inverseView.set(camera.viewRotationMatrix).invert().get(uniforms.get("BrojeInverseView").buffer);
        // Both noise octaves are periodic over 4096 blocks, preserving precision near the world border.
        vector("BrojeCamera", wrap(camera.pos.x, 4096), (float) camera.pos.y, wrap(camera.pos.z, 4096),
                RenderSystem.getDevice().isZZeroToOne() ? 1 : 0);
        float rain = Minecraft.getInstance().level.getRainLevel(deltaTracker.getGameTimeDeltaPartialTick(false));
        vector("BrojeFog", config.getFogDensity() * 0.00014f * (1 + rain * 0.4f), config.getFogStart(),
                config.getFogHeight(), config.getFogFalloff());
        double seconds = (state.levelRenderState.gameTime + deltaTracker.getGameTimeDeltaPartialTick(false)) / 20.0;
        vector("BrojeVolume", config.getFogVariation() * 0.01f, wrap(seconds * config.getFogWind() * 0.008, 8192),
                config.isVolumetricFog() ? 1 : 0, config.getFogQuality());
        Vector4f color = camera.fogData.color;
        float grey = color.x * 0.2126f + color.y * 0.7152f + color.z * 0.0722f;
        vector("BrojeAtmosphere", grey * 0.35f + color.x * 0.65f, grey * 0.35f + color.y * 0.65f,
                grey * 0.35f + color.z * 0.65f, Math.min(config.getFogDistance(), camera.depthFar));
        SkyRenderState sky = state.levelRenderState.skyRenderState;
        Vector3f sun = SunDirection.at(sky.sunAngle, config.getSunPathTilt(), new Vector3f());
        float sunY = sun.y;
        String debug = System.getProperty("broje.fogDebug", "");
        float daylight = Math.max(0, Math.min(1, sunY * 3)) * (1 - rain * 0.8f);
        vector("BrojeSun", sun.x, sunY, sun.z,
                debug.equals("solid") ? -2 : debug.equals("depth") ? -1 : daylight);
        // Light shafts: the same distorted sun map the terrain was shaded with this frame.
        MappedBuffer flags = ShadowRenderer.uniform("BrojeFlags");
        boolean shafts = config.isLightShafts() && ShadowRenderer.mapReady() && config.isVolumetricFog();
        vector("BrojeShaft", shafts ? 1 : 0, 0.45f, flags != null ? flags.getFloat(8) : 0, snapshotReady ? 1 : 0);
        vector("BrojeShaftRange", config.getShadowDistance(), 0, 0, 0);
    }

    private static float wrap(double value, int period) { return (float) (value - Math.floor(value / period) * period); }

    private void vector(String name, float x, float y, float z, float w) {
        MappedBuffer buffer = uniforms.get(name);
        buffer.putFloat(0, x); buffer.putFloat(4, y); buffer.putFloat(8, z); buffer.putFloat(12, w);
    }

    private static String resource(String name) {
        try (InputStream stream = FogRenderer.class.getResourceAsStream("/assets/broje/shaders/" + name)) {
            if (stream == null) throw new IOException("Missing fog resource: " + name);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read Broje fog shader", exception);
        }
    }

    public static void close() {
        if (instance == null) return;
        VK10.vkDeviceWaitIdle(Vulkan.getVkDevice());
        instance.pipeline.cleanUp();
        instance.copyPipeline.cleanUp();
        instance.depthSampler.close();
        instance.copySampler.close();
        if (instance.opaqueTarget != null) instance.opaqueTarget.cleanUp();
        instance.uniforms.values().forEach(buffer -> MemoryUtil.memFree(buffer.buffer));
        instance.colorTargets.clear();
        instance = null;
    }

    private record RenderState(boolean depthTest, boolean depthMask, boolean cull, int topology,
                               int polygonMode, int colorMask, boolean logicOp, PipelineState.BlendInfo blend) {
        RenderState() {
            this(VRenderSystem.depthTest, VRenderSystem.depthMask, VRenderSystem.cull, VRenderSystem.topology,
                    VRenderSystem.polygonMode, VRenderSystem.colorMask, VRenderSystem.logicOp, PipelineState.blendInfo);
        }
        void restore() {
            VRenderSystem.depthTest = depthTest; VRenderSystem.depthMask = depthMask; VRenderSystem.cull = cull;
            VRenderSystem.topology = topology; VRenderSystem.polygonMode = polygonMode; VRenderSystem.colorMask = colorMask;
            VRenderSystem.logicOp = logicOp; PipelineState.blendInfo = blend;
        }
    }

    /** The sun depth map through its depth-only view, or a white stand-in before it exists. */
    private static final class SunMapSampler extends ImageDescriptor {
        SunMapSampler() { super(2, "sampler2D", "Sampler1", 11, VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER); }

        @Override
        public VulkanImage getImage() {
            VulkanImage map = ShadowRenderer.mapImage();
            return map != null ? map : VTextureSelector.getWhiteTexture();
        }

        @Override
        public long getImageView(VulkanImage image) {
            return image == ShadowRenderer.mapImage() ? ShadowRenderer.mapView(image) : super.getImageView(image);
        }
    }

    /** The opaque world depth of this frame, or a white (far) stand-in without it. */
    private static final class OpaqueSampler extends ImageDescriptor {
        OpaqueSampler() { super(3, "sampler2D", "Sampler2", 11, VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER); }

        @Override
        public VulkanImage getImage() {
            return snapshotReady && instance.opaqueTarget != null ? instance.opaqueTarget.getColorAttachment() : VTextureSelector.getWhiteTexture();
        }
    }

    private static final class DepthSampler extends ImageDescriptor {
        private final Map<VulkanImage, Long> views = new IdentityHashMap<>();

        DepthSampler() { super(1, "sampler2D", "Sampler0", 0, VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER); }

        @Override
        public long getImageView(VulkanImage image) {
            return views.computeIfAbsent(image, depth -> VulkanImage.createImageView(depth.getId(), depth.format,
                    VK10.VK_IMAGE_ASPECT_DEPTH_BIT, 1, 1));
        }

        void close() {
            views.values().forEach(view -> VK10.vkDestroyImageView(Vulkan.getVkDevice(), view, null));
            views.clear();
        }
    }
}
