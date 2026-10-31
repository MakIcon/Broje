#version 450
// WATER_COMMON
layout(binding=4) uniform sampler2D Sampler0;
layout(location=0) in vec4 vertexColor;
layout(location=1) in vec2 texCoord0;
layout(location=2) in vec3 relativePosition;
layout(location=3) in float skyLight;
layout(location=0) out vec4 fragColor;
void main() {
    vec3 world=relativePosition+WaterCamera.xyz;
    if(world.y>WaterMedium.x-0.025)discard;
    vec4 material=texture(Sampler0,texCoord0)*vertexColor;
    if(material.a<0.1)discard;
    // Caustics illuminate real receivers in the main view only. Baking them into
    // the mirror made the underside look like bright, patterned ice.
    // Alpha stores the receiver's depth below the capture plane, plus one.
    // Zero is an empty texel, including at negative world heights.
    fragColor=vec4(material.rgb,max(WaterMedium.x-world.y,0.0)+1.0);
}
