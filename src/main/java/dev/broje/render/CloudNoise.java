package dev.broje.render;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

import com.mojang.blaze3d.platform.NativeImage;
import net.vulkanmod.vulkan.texture.SamplerManager;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;

/**
 * The natural repeating cloud map of Quasar (from MakeUp Ultra Fast, LGPL-3.0) as one
 * red channel with linear-density mipmaps: ray segments filter detail smaller than a step.
 */
final class CloudNoise {
    private CloudNoise() {}

    static VulkanImage load() {
        byte[] pixels;
        int width, height;
        try (InputStream stream = CloudNoise.class.getResourceAsStream("/assets/broje/textures/cloud_map.png")) {
            if (stream == null) throw new IOException("Missing cloud map");
            try (NativeImage source = NativeImage.read(NativeImage.Format.RGBA, stream)) {
                width = source.getWidth();
                height = source.getHeight();
                int[] abgr = source.getPixelsABGR();
                pixels = new byte[width * height];
                for (int i = 0; i < pixels.length; i++) pixels[i] = (byte) (abgr[i] & 255);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Could not load the cloud map", exception);
        }
        int mipLevels = 32 - Integer.numberOfLeadingZeros(Math.max(width, height));
        VulkanImage image = VulkanImage.builder(width, height).setName("Broje cloud map")
                .setFormat(VK10.VK_FORMAT_R8_UNORM).setMipLevels(mipLevels)
                .setUsage(VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK10.VK_IMAGE_USAGE_SAMPLED_BIT)
                .setLinearFiltering(true).setClamp(false).createVulkanImage();
        try {
            image.setSampler(SamplerManager.getSampler(false, true, mipLevels - 1));
            for (int level = 0; level < mipLevels; level++) {
                ByteBuffer upload = MemoryUtil.memAlloc(pixels.length);
                try {
                    upload.put(pixels).flip();
                    image.uploadSubTextureAsync(level, width, height, 0, 0, 0, 0, width, upload);
                } finally {
                    MemoryUtil.memFree(upload);
                }
                // Box-filter the next level; the map wraps, so its texels stay periodic.
                int nextWidth = Math.max(width / 2, 1), nextHeight = Math.max(height / 2, 1);
                byte[] next = new byte[nextWidth * nextHeight];
                for (int y = 0; y < nextHeight; y++) {
                    for (int x = 0; x < nextWidth; x++) {
                        int sum = 0;
                        for (int dy = 0; dy < 2; dy++) for (int dx = 0; dx < 2; dx++)
                            sum += pixels[Math.min(y * 2 + dy, height - 1) * width + Math.min(x * 2 + dx, width - 1)] & 255;
                        next[y * nextWidth + x] = (byte) ((sum + 2) / 4);
                    }
                }
                pixels = next;
                width = nextWidth;
                height = nextHeight;
            }
        } catch (RuntimeException exception) {
            image.free();
            throw exception;
        }
        return image;
    }
}
