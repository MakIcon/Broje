package dev.broje.check;

import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.Vulkan;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkQueryPoolCreateInfo;

/** Nonblocking GPU timestamps, isolated to the validation mod. */
public final class FogGpuTimer {
    private static long pool;
    private static float period;
    private static boolean[] used;
    /** Receives each finished measurement in milliseconds. */
    public static java.util.function.DoubleConsumer sink = FogPerformanceCheck::recordGpu;

    public static void begin() {
        try (var stack = MemoryStack.stackPush()) {
            if (pool == 0) {
                var properties = VkPhysicalDeviceProperties.malloc(stack);
                VK10.vkGetPhysicalDeviceProperties(Vulkan.getVkDevice().getPhysicalDevice(), properties);
                period = properties.limits().timestampPeriod();
                used = new boolean[Renderer.getFramesNum()];
                var info = VkQueryPoolCreateInfo.calloc(stack).sType$Default()
                        .queryType(VK10.VK_QUERY_TYPE_TIMESTAMP).queryCount(used.length * 2);
                var handle = stack.mallocLong(1);
                if (VK10.vkCreateQueryPool(Vulkan.getVkDevice(), info, null, handle) != VK10.VK_SUCCESS)
                    throw new AssertionError("Could not create fog timestamp pool");
                pool = handle.get(0);
            }
            int frame = Renderer.getCurrentFrame(), slot = frame * 2;
            if (used[frame]) {
                var values = stack.callocLong(4);
                int result = VK10.vkGetQueryPoolResults(Vulkan.getVkDevice(), pool, slot, 2, values, 16,
                        VK10.VK_QUERY_RESULT_64_BIT | VK10.VK_QUERY_RESULT_WITH_AVAILABILITY_BIT);
                if (result == VK10.VK_SUCCESS && values.get(1) != 0 && values.get(3) != 0)
                    sink.accept((values.get(2) - values.get(0)) * (double) period / 1e6);
            }
            Renderer.getInstance().endRenderPass();
            VK10.vkCmdResetQueryPool(Renderer.getCommandBuffer(), pool, slot, 2);
            VK10.vkCmdWriteTimestamp(Renderer.getCommandBuffer(), VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, pool, slot);
            used[frame] = true;
        }
    }

    public static void end() {
        VK10.vkCmdWriteTimestamp(Renderer.getCommandBuffer(), VK10.VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
                pool, Renderer.getCurrentFrame() * 2 + 1);
    }

    public static void close() {
        if (pool == 0) return;
        Vulkan.waitIdle();
        VK10.vkDestroyQueryPool(Vulkan.getVkDevice(), pool, null);
        pool = 0;
    }
}
