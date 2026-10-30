#version 450
// Translucent casters (stained glass, ice, slime, honey) after the opaque map is drawn.
// Depth test only, no depth write. Realistic/pigment modes multiply filters;
// colored-light mode sums hue and layer count. The receiver resolves the chosen style.
layout(binding=3) uniform sampler2D Sampler0;
layout(binding=1) uniform CasterParameters { vec4 ShadowAlpha; vec4 BrojeTintBlend; };
layout(location=0) in vec2 atlasUV;
layout(location=1) in float vertexAlpha;
layout(location=4) in vec3 vertexRgb;
layout(location=0) out vec4 transmittance;
void main() {
    // Water is tinted by the biome through its vertex color; it stays out of the map.
    if(max(vertexRgb.r,max(vertexRgb.g,vertexRgb.b))-min(vertexRgb.r,min(vertexRgb.g,vertexRgb.b)) > 0.08) discard;
    vec4 texel = textureLod(Sampler0,atlasUV,0);
    float alpha = texel.a * vertexAlpha;
    if(alpha < 0.02) discard;
    // The pane passes its own hue at full strength in the brightest channel, so red
    // glass throws red light rather than a dim grey; dark glass stays dark.
    vec3 linear = pow(texel.rgb,vec3(2.2));
    vec3 filterColor = linear / max(max(linear.r,max(linear.g,linear.b)),0.3);
    vec3 block = mix(vec3(1.0),filterColor*0.85,alpha);
    // Light crosses two faces of every block or pane: each face holds half the absorption.
    // Additive mode accumulates hue and a layer count in half floats. Averaging
    // happens at the receiver, so two bright panes keep a bright overlap, in any order.
    transmittance = BrojeTintBlend.x > 1.5 ? vec4(block*0.5,0.5) : vec4(sqrt(block),0.7);
}
