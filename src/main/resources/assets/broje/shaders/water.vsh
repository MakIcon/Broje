#version 450
// WATER_COMMON
layout(binding=0) uniform Transform { mat4 MVP; };
layout(binding=2) uniform Sections { ivec4 SectionOffsets[128]; vec4 SectionFadeFactors[128]; };
layout(push_constant) uniform Area { vec3 ModelOffset; };
layout(binding=5) uniform sampler2D Sampler2;
layout(location=0) in ivec4 Position;
layout(location=1) in uvec2 UV0;
layout(location=2) in uint PackedColor;
layout(location=0) out vec4 vertexColor;
layout(location=1) out vec2 texCoord0;
layout(location=2) out vec3 relativePosition;
layout(location=3) out float skyLight;
layout(location=4) out flat float fadeFactor;
layout(location=5) out vec3 biomeColor;
layout(location=6) out flat float waterMaterial;
void main() {
    int section=gl_InstanceIndex>>2,slot=gl_InstanceIndex&3;
    vec3 offset=vec3(bitfieldExtract(ivec3(SectionOffsets[section][slot])>>ivec3(0,16,8),0,8));
    relativePosition=vec3(Position.xyz)/2048.0+ModelOffset+offset;
    texCoord0=vec2(UV0)/32768.0;
    waterMaterial=isWater(texCoord0)?1:0;
    vec3 world=relativePosition+WaterCamera.xyz;
    // Only vertices along the top of a fluid block move; adjoining side tops use
    // the identical function, so waves don't open seams at chunk/quad boundaries.
    if(waterMaterial>0.5&&fract(world.y)>0.5&&fract(world.y)<0.96)
        relativePosition.y+=(wave(world.xz).x-0.095*WaterOptics.x);
    gl_Position=MVP*vec4(relativePosition,1);
    uint packedLight=uint(Position.w);
    ivec2 light=ivec2(bitfieldExtract(packedLight,4,4),bitfieldExtract(packedLight,12,4));
    vec4 base=unpackUnorm4x8(PackedColor);
    vertexColor=base*texelFetch(Sampler2,light,0);
    biomeColor=base.rgb;
    skyLight=float(light.y)/15.0;
    fadeFactor=SectionFadeFactors[section][slot];
}
