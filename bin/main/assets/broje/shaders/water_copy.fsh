#version 450
layout(binding=0) uniform sampler2D Source;
layout(location=0) out vec4 fragColor;
void main() { fragColor=texelFetch(Source,ivec2(gl_FragCoord.xy),0); }
