#version 450
#ifdef TEXTURED
layout(binding=1) uniform sampler2D Sampler0;
layout(location=0) in vec2 texCoord0;
#endif
layout(location=0) out vec4 fragColor;
void main() {
#ifdef TEXTURED
    if (texture(Sampler0, texCoord0).a < 0.1) discard;
#endif
    fragColor = vec4(1); // the color target holds sunlight transmittance: bodies leave it clear
}
