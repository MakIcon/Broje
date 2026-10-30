#version 450
layout(binding=0) uniform Transform { mat4 MVP; };
layout(binding=2) uniform Sections { ivec4 SectionOffsets[128]; vec4 SectionFadeFactors[128]; };
layout(push_constant) uniform Area { vec3 ModelOffset; };
layout(binding=5) uniform sampler2D Sampler2; // light map

layout(location=0) in ivec4 Position;
layout(location=1) in uvec2 UV0;
layout(location=2) in uint PackedColor;

layout(location=0) out vec4 vertexColor;
layout(location=1) out vec2 texCoord0;
layout(location=2) out vec3 relativePosition;
layout(location=3) out float skyLight;
layout(location=4) out flat float fadeFactor;
layout(location=5) out float blockLight;

void main() {
    int section = gl_InstanceIndex >> 2, slot = gl_InstanceIndex & 3;
    vec3 sectionOffset = vec3(bitfieldExtract(ivec3(SectionOffsets[section][slot]) >> ivec3(0,16,8),0,8));
    relativePosition = vec3(Position.xyz) / 2048.0 + ModelOffset + sectionOffset;
    gl_Position = MVP * vec4(relativePosition,1);
    // Block light in bits 4-7, sky light in bits 12-15 of the packed fourth component.
    // Unsigned: a signed extract would turn sky light 8-15 into negative texel coordinates.
    uint packedLight = uint(Position.w);
    ivec2 light = ivec2(bitfieldExtract(packedLight,4,4), bitfieldExtract(packedLight,12,4));
    vertexColor = unpackUnorm4x8(PackedColor) * texelFetch(Sampler2, light, 0);
    skyLight = float(light.y) / 15.0;
    blockLight = float(light.x) / 15.0;
    fadeFactor = SectionFadeFactors[section][slot];
    texCoord0 = vec2(UV0) / 32768.0;
}
