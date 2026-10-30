#version 450
layout(binding=0) uniform ShadowTransform { mat4 ShadowMatrix; };
layout(binding=1) uniform CasterParameters { vec4 ShadowAlpha; }; // cutout, plant casters, distortion, texel depth
layout(binding=2) uniform Sections { ivec4 SectionOffsets[128]; vec4 SectionFadeFactors[128]; };
layout(push_constant) uniform Area {vec3 ModelOffset;};
layout(location=0) in ivec4 Position;
layout(location=1) in uvec2 UV0;
layout(location=2) in uint PackedColor;
layout(location=0) out vec2 atlasUV;
layout(location=1) out float vertexAlpha;
layout(location=2) out vec3 worldPosition;
layout(location=3) out float texelDepth;
layout(location=4) out vec3 vertexRgb;
void main() {
    int packed = SectionOffsets[gl_InstanceIndex >> 2][gl_InstanceIndex & 3];
    vec3 sectionOffset = vec3(bitfieldExtract(ivec3(packed) >> ivec3(0,16,8),0,8));
    vec3 cameraRelative = vec3(Position.xyz) / 2048.0 + ModelOffset + sectionOffset;
    vec4 clip = ShadowMatrix * vec4(cameraRelative,1);
    // Radial distortion: texels concentrate around the camera, as in Beryl.
    float distortion = length(clip.xy) * ShadowAlpha.z + (1.0 - ShadowAlpha.z);
    clip.xy /= distortion;
    clip.z = clamp(clip.z, 0.0, 1.0); // Casters beyond the depth range still block light.
    gl_Position = clip;
    // One map texel here in depth units, matching localTexel() of the receivers.
    texelDepth = ShadowAlpha.w * distortion * distortion / max(1.0 - ShadowAlpha.z, 0.05);
    atlasUV = vec2(UV0) / 32768.0;
    vec4 color = unpackUnorm4x8(PackedColor);
    vertexAlpha = color.a;
    vertexRgb = color.rgb;
    worldPosition = cameraRelative;
}
