#version 450
// BLOOM_COMMON
void main() {
    vec2 uv=gl_FragCoord.xy/BloomTarget.xy;
    vec2 local=(uv-SolarSource.xy)/max(SolarSource.zw*BloomTarget.w,vec2(0.00001));
    float r2=dot(local,local);
    // Analytic radial point spread: no square blur kernel, coarse pixel grid or
    // finite quad boundary can imprint its shape on the halo, at any spread.
    float spread=0.20*exp(-r2/4)+0.10*exp(-r2/36)+0.025*exp(-r2/225);
    vec3 visible=texelFetch(Source0,ivec2(0),0).rgb;
    vec3 glow=visible*vec3(1.0,0.97,0.90)*spread*BloomTarget.z;
    fragColor=vec4(1-exp(-glow),0);
}
