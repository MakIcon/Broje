#version 450
// BLOOM_COMMON
void main() {
    vec2 uv=gl_FragCoord.xy/BloomTarget.xy;
    vec2 local=(uv-SolarSource.xy)/max(SolarSource.zw,vec2(0.00001));
    float disc=1-smoothstep(1.0,1.35,length(local));
    fragColor=vec4(0);
    if(disc<=0)return;
    vec3 bright=vec3(0);
    vec2 pixel=1/vec2(textureSize(Source0,0));
    for(int y=0;y<2;y++)for(int x=0;x<2;x++) {
        vec2 sampleUV=boundedUV(uv+(vec2(x,y)-0.5)*2*pixel,Source0);
        float depth=texture(SceneDepth,sampleUV).r;
        vec3 color=texture(Source0,sampleUV).rgb;
        float energy=smoothstep(0.65,0.95,dot(color,vec3(0.2126,0.7152,0.0722)));
        // With Broje water this is the saved opaque depth, so the transmitted
        // sun remains visible but an opaque block rejects it on either side.
        if(depth>0.999999)bright+=color*energy;
    }
    fragColor=vec4(bright*2.0*disc,0); // four samples, HDR source radiance 8
}
