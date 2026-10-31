#version 450
// BLOOM_COMMON
void main() {
    ivec2 size=textureSize(Source0,0);
    vec3 energy=vec3(0);
    // One output texel integrates the small pyramid level. Occlusion therefore
    // changes source energy, never the shape of the point-spread function.
    for(int y=0;y<size.y;y++)for(int x=0;x<size.x;x++)energy+=texelFetch(Source0,ivec2(x,y),0).rgb;
    float solarArea=3.14159265*SolarSource.z*SolarSource.w;
    vec3 visible=energy/(float(size.x*size.y)*max(solarArea,0.0000001)*8);
    fragColor=vec4(clamp(visible,vec3(0),vec3(2)),0);
}
