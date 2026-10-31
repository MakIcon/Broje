#version 450
// BLOOM_COMMON
void main() {
    vec2 uv=gl_FragCoord.xy/BloomTarget.xy;
    fragColor=vec4(tent(Source0,uv,1),0);
}
