package dev.broje.render;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;

/** Immutable periodic volume, stored as bordered XY slices in a linear RGBA8 atlas. */
final class CloudNoise {
    private CloudNoise() {}

    static VulkanImage load() {
        byte[] bytes;
        try (InputStream stream = CloudNoise.class.getResourceAsStream("/assets/broje/textures/cloud_noise.rgba")) {
            if (stream == null) throw new IOException("Missing cloud noise atlas");
            bytes = stream.readAllBytes();
            if (bytes.length != 528 * 528 * 4) throw new IOException("Invalid cloud noise atlas size");
        } catch (IOException exception) {
            throw new IllegalStateException("Could not load cloud noise", exception);
        }
        VulkanImage image = VulkanImage.builder(528, 528).setName("Broje cloud noise")
                .setFormat(VK10.VK_FORMAT_R8G8B8A8_UNORM).setMipLevels(1)
                .setUsage(VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK10.VK_IMAGE_USAGE_SAMPLED_BIT)
                .setLinearFiltering(true).setClamp(true).createVulkanImage();
        ByteBuffer upload = MemoryUtil.memAlloc(bytes.length);
        try {
            upload.put(bytes).flip();
            image.uploadSubTextureAsync(0, 528, 528, 0, 0, 0, 0, 528, upload);
        } catch (RuntimeException exception) {
            image.free();
            throw exception;
        } finally {
            MemoryUtil.memFree(upload);
        }
        return image;
    }
}
