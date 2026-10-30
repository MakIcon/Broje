#version 450
// Snapshot of the world depth before translucent terrain: the fog reads it to see
// through glass, whose own depth would otherwise end the ray at the pane.
layout(std140, binding = 0) uniform BrojeCopyUniforms { vec4 BrojeCamera; };
layout(binding = 1) uniform sampler2D Sampler0;
layout(location = 0) out vec4 opaqueDepth;
void main() {
    ivec2 size = textureSize(Sampler0, 0);
    opaqueDepth = vec4(texelFetch(Sampler0, clamp(ivec2(gl_FragCoord.xy), ivec2(0), size - 1), 0).r, 0, 0, 1);
}
