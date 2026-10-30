#version 450
layout(binding=1) uniform CasterParameters {vec4 ShadowAlpha;}; // cutout, plant casters, distortion, texel depth
layout(binding=3) uniform sampler2D Sampler0;
layout(location=0) in vec2 atlasUV;
layout(location=1) in float vertexAlpha;
layout(location=2) in vec3 worldPosition;
layout(location=3) in float texelDepth;
layout(location=0) out vec4 dummyColor;
// Depth only ever moves away from the sun, so early depth testing stays valid.
layout(depth_greater) out float gl_FragDepth;
void main() {
    // Derivatives first, while every invocation of the quad is still active.
    vec3 face = cross(dFdx(worldPosition), dFdy(worldPosition));
    // Ordinary rasterized UVs: no derivative extrapolation outside a grass blade and
    // no frame-dependent mip selection.
    if(textureLod(Sampler0,atlasUV,0).a * vertexAlpha < ShadowAlpha.x) discard;
    // Grass, flowers and saplings are crossed vertical quads at 45 degrees.
    // Block faces, leaves, slabs and fences are axis aligned and always cast.
    bool plant = false;
    if(dot(face,face) > 1e-12) {
        face = abs(normalize(face));
        plant = face.y < 0.3 && min(face.x,face.z) > 0.3;
    }
    if(plant && ShadowAlpha.y < 0.5) discard;
    // A blade stands on the ground it shades. Without a push, the soft filter of the
    // lit ground beside its base picks up the bottom of the blade and draws a dark seam
    // along it, a "V" at every tuft. A few texels are invisible in the cast shadow.
    gl_FragDepth = plant ? min(gl_FragCoord.z + texelDepth * 3.0, 1.0) : gl_FragCoord.z;
    dummyColor=vec4(0);
}
