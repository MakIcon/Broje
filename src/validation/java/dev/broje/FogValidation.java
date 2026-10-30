package dev.broje;

import java.nio.file.Files;
import java.nio.file.Path;
import dev.broje.config.BrojeConfig;
import dev.broje.render.ShadowProjection;
import org.joml.Vector3f;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import dev.broje.render.SunDirection;
import org.lwjgl.util.shaderc.Shaderc;

/** Standalone build verification using the same native compiler as VulkanMod. */
public final class FogValidation {
    public static void main(String[] args) throws Exception {
        Path shaders = Path.of(args[0]);
        long compiler = Shaderc.shaderc_compiler_initialize();
        long options = Shaderc.shaderc_compile_options_initialize();
        if (compiler == 0 || options == 0) throw new AssertionError("Shaderc initialization failed");
        try {
            Shaderc.shaderc_compile_options_set_target_env(options, Shaderc.shaderc_target_env_vulkan, Shaderc.shaderc_env_version_vulkan_1_2);
            Shaderc.shaderc_compile_options_set_optimization_level(options, Shaderc.shaderc_optimization_level_performance);
            compile(compiler, options, shaders.resolve("fog.vsh"), Shaderc.shaderc_vertex_shader);
            compile(compiler, options, shaders.resolve("fog.fsh"), Shaderc.shaderc_fragment_shader);
            compile(compiler, options, shaders.resolve("shadow_caster.vsh"), Shaderc.shaderc_vertex_shader);
            compile(compiler, options, shaders.resolve("shadow_caster.fsh"), Shaderc.shaderc_fragment_shader);
            compile(compiler, options, shaders.resolve("shadow_tint.fsh"), Shaderc.shaderc_fragment_shader);
            compile(compiler, options, shaders.resolve("depth_copy.fsh"), Shaderc.shaderc_fragment_shader);
            compile(compiler, options, shaders.resolve("terrain.vsh"), Shaderc.shaderc_vertex_shader);
            compile(compiler, options, shaders.resolve("terrain.fsh"), Shaderc.shaderc_fragment_shader);
            // Variants assembled at runtime: the cutout terrain layer and the entity casters.
            compile(compiler, options, "terrain.fsh (cutout)", withDefines(Files.readString(shaders.resolve("terrain.fsh")), "#define CUTOUT_LAYER\n"), Shaderc.shaderc_fragment_shader);
            String casterInputs = "layout(location=0) in vec3 Position;\nlayout(location=2) in vec2 UV0;\n#define TEXTURED\n";
            compile(compiler, options, "entity_caster.vsh", withDefines(Files.readString(shaders.resolve("entity_caster.vsh")), casterInputs), Shaderc.shaderc_vertex_shader);
            compile(compiler, options, "entity_caster.vsh (untextured)", withDefines(Files.readString(shaders.resolve("entity_caster.vsh")), "layout(location=0) in vec3 Position;\n"), Shaderc.shaderc_vertex_shader);
            compile(compiler, options, "entity_caster.fsh", withDefines(Files.readString(shaders.resolve("entity_caster.fsh")), "#define TEXTURED\n"), Shaderc.shaderc_fragment_shader);
        } finally {
            Shaderc.shaderc_compile_options_release(options);
            Shaderc.shaderc_compiler_release(compiler);
        }
        verifyShadowProjection();
        verifySunOrbit();
        Path directory = Files.createTempDirectory("broje-config-check-");
        try {
            Path file = directory.resolve("broje.json");
            Files.writeString(file, "{\"enabled\":true}");
            BrojeConfig migrated = BrojeConfig.load(file);
            check(migrated.isEnabled() && migrated.isFogEnabled() && migrated.isVolumetricFog(), "Old config migration");
            check(migrated.getFogQuality() == 32 && migrated.getFogDensity() == 45, "New default values");
            check(migrated.getSunPathTilt() == 0, "Old configs retain the untilted orbit");
            check(migrated.getColoredBlend()==2 && migrated.getColoredBrightness()==100
                    && migrated.getColoredSaturation()==130 && migrated.getColoredOverlap()==100,"Bright glass defaults in old configs");
            migrated.setColoredBlend(-1);migrated.setColoredBrightness(900);migrated.setColoredSaturation(-1);migrated.setColoredOverlap(900);
            check(migrated.getColoredBlend()==0 && migrated.getColoredBrightness()==200
                    && migrated.getColoredSaturation()==0 && migrated.getColoredOverlap()==200,"Colored style limits");
            migrated.setSunPathTilt(900);
            check(migrated.getSunPathTilt() == 60, "Sun tilt upper limit");
            migrated.setSunPathTilt(-900);
            check(migrated.getSunPathTilt() == -60, "Sun tilt lower limit");
            migrated.setSunPathTilt(20);
            check(migrated.isShadowsEnabled() && migrated.getShadowResolution()==2048 && migrated.getShadowSamples()==16, "Shadow defaults in old config");
            check(!migrated.isFoliageShadows() && migrated.isLightingEnabled() && migrated.getBounceStrength()==100 && migrated.getLightingQuality()==16, "Lighting defaults in old config");
            migrated.setShadowResolution(1500);
            check(migrated.getShadowResolution()==1536, "Beryl resolution steps");
            migrated.setBounceStrength(900);migrated.setLightingQuality(5);migrated.setFoliageShadows(true);
            migrated.setShadowResolution(8192);migrated.setShadowDistance(-5);migrated.setShadowSoftness(0);
            migrated.setFogDensity(500);
            migrated.setFogStart(-20);
            migrated.setFogFalloff(0);
            migrated.setFogQuality(9000);
            migrated.setVolumetricFog(false);
            migrated.save();
            BrojeConfig restored = BrojeConfig.load(file);
            check(restored.getColoredBlend()==0 && restored.getColoredBrightness()==200
                    && restored.getColoredSaturation()==0 && restored.getColoredOverlap()==200,"Colored style persistence");
            check(restored.getSunPathTilt() == 20, "Sun tilt save/load");
            check(restored.getShadowResolution()==4096 && restored.getShadowDistance()==32 && restored.getShadowSoftness()==50,"Shadow limits and persistence");
            check(restored.getBounceStrength()==200 && restored.getLightingQuality()==8
                    && restored.isFoliageShadows(), "Lighting limits and persistence");
            restored.setShadowDistance(1000);
            check(restored.getShadowDistance()==192, "Shadow distance up to 12 chunks");
            BrojeConfig reset = BrojeConfig.load(file);
            reset.setShadowFilter(0);reset.setColoredContrast(300);reset.setFogDensity(3);
            reset.resetToDefaults();
            check(reset.getSunPathTilt() == 0, "Reset solar orbit");
            check(reset.getColoredBlend()==2 && reset.getColoredBrightness()==100 && reset.getColoredSaturation()==130
                    && reset.getColoredOverlap()==100,"Reset all colored controls");
            check(reset.isEnabled() && reset.getShadowFilter()==2 && reset.getColoredContrast()==100 && reset.getFogDensity()==45
                    && reset.getShadowResolution()==2048 && !reset.isFoliageShadows(), "Reset to defaults keeps the master switch");
            check(restored.getFogDensity() == 100 && restored.getFogStart() == 0 && restored.getFogFalloff() == 8
                    && restored.getFogQuality() == 64 && !restored.isVolumetricFog(), "Clamping and saved settings");
            Files.writeString(file, "{\"enabled\":true,\"fogDensity\":\"broken\"}");
            check(!BrojeConfig.load(file).isEnabled(), "Malformed config must fail safely to disabled defaults");
            Files.writeString(file, "{\"enabled\":false,\"fogDensity\":0,\"fogQuality\":-1}");
            BrojeConfig zero = BrojeConfig.load(file);
            check(!zero.isEnabled() && zero.getFogDensity() == 0 && zero.getFogQuality() == 16, "Zero-density and low quality");
        } finally {
            Files.deleteIfExists(directory.resolve("broje.json"));
            Files.deleteIfExists(directory.resolve("broje.json.tmp"));
            Files.deleteIfExists(directory);
        }
        System.out.println("PASS: Vulkan fog/shadow/lighting shaders, config migration, limits, save/load and malformed input.");
    }

    private static String withDefines(String source, String defines) {
        int line = source.indexOf('\n') + 1;
        return source.substring(0, line) + defines + source.substring(line);
    }

    private static void compile(long compiler, long options, Path shader, int kind) throws Exception {
        compile(compiler, options, shader.getFileName().toString(), Files.readString(shader), kind);
    }

    private static void compile(long compiler, long options, String name, String source, int kind) {
        long result = Shaderc.shaderc_compile_into_spv(compiler, source, kind, name, "main", options);
        if (result == 0) throw new AssertionError("Shaderc returned no result for " + name);
        try {
            if (Shaderc.shaderc_result_get_compilation_status(result) != Shaderc.shaderc_compilation_status_success) {
                throw new AssertionError(Shaderc.shaderc_result_get_error_message(result));
            }
            System.out.println("PASS: " + name + " -> " + Shaderc.shaderc_result_get_length(result) + " bytes SPIR-V");
        } finally {
            Shaderc.shaderc_result_release(result);
        }
    }

    private static void verifyShadowProjection() {
        ShadowProjection near = new ShadowProjection(), far = new ShadowProjection();
        Vector3f sun = new Vector3f();
        Vector4f previous = null;
        for (int frame=0;frame<1000;frame++) {
            float angle=0.8f+frame*0.00002f;
            sun.set(-(float)Math.sin(angle),(float)Math.cos(angle),0);
            near.update(10.5,105.25,14.75,sun,80);
            far.update(29999990.5,105.25,-29999989.25,sun,80);
            check(near.matrix.equals(far.matrix,0.000001f),"Sun rotation must not depend on absolute world position");
            Vector4f point=near.matrix.transform(new Vector4f(5,-3,8,1));
            if(previous!=null) check(Math.hypot(point.x-previous.x,point.y-previous.y)*1024<0.1,
                    "A small solar movement must move near shadow coordinates continuously");
            previous=point;
        }
        System.out.println("PASS: continuous solar projection at origin and world border.");
        for(float extent:new float[]{48,80,208}) {
            ShadowProjection moving=new ShadowProjection();
            sun.set(-0.9f,0.3f,0).normalize();
            Vector4f first=null;
            // Camera moves inside one 2-block cell: a fixed world point keeps its map position.
            for(int frame=0;frame<240;frame++) {
                double x=0.1+frame*0.007,y=104.2+frame*0.006,z=14.1+frame*0.0075;
                moving.update(29999000+x,y,-29999000+z,sun,extent);
                Vector4f point=moving.matrix.transform(new Vector4f((float)(4-x),(float)(101-y),(float)(2-z),1));
                if(first==null)first=new Vector4f(point);
                check(Math.abs(point.x-first.x)*extent<0.002 && Math.abs(point.y-first.y)*extent<0.002,
                        "Moving the camera inside a grid cell must not move a fixed world shadow");
            }
        }
        System.out.println("PASS: world-locked sun map centre while the camera moves inside a 2-block cell.");
        check(ShadowProjection.distortion(32) < ShadowProjection.distortion(192) && ShadowProjection.distortion(192) <= 0.9f,
                "Longer shadow ranges concentrate more texels at the camera, as in Beryl");
    }

    private static void verifySunOrbit() {
        Vector3f sun = new Vector3f();
        for (int tilt : new int[] {-60, -20, 0, 20, 60}) {
            for (float angle : new float[] {0, 0.8f, 1.56f, 3.14f}) {
                SunDirection.at(angle, tilt, sun);
                // Actual vanilla celestial transform, with Broje's world rotation.
                Vector3f visible = new Matrix4f().rotateX((float)Math.toRadians(tilt))
                        .rotateY(-(float)Math.PI / 2).rotateX(angle).transformDirection(new Vector3f(0,1,0));
                check(sun.distance(visible) < 1e-5, "Sun, terrain and fog must agree at every tilt: " + tilt + "/" + angle);
                ShadowProjection projection = new ShadowProjection();
                projection.update(0, 100, 0, sun, 80);
                check(projection.matrix.isFinite(), "Tilted sun projection must stay finite");
            }
        }
        check(SunDirection.at(0, 0, sun).distance(new Vector3f(0,1,0)) < 1e-6,
                "At zero tilt the noon shadow falls directly beneath the caster");
        System.out.println("PASS: visible solar orbit matches shadow/fog direction; straight noon shadows and tilted projections.");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
