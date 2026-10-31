layout(std140,binding=0) uniform SolarBloomUniforms {
    vec4 BloomTarget; // target pixels, strength, spread
    vec4 SolarSource; // centre UV, apparent core radii UV
    vec4 BloomState;  // camera submerged, full screen pixels, unused
};
layout(binding=1) uniform sampler2D Source0;
layout(binding=2) uniform sampler2D SceneDepth;
layout(location=0) out vec4 fragColor;
vec2 boundedUV(vec2 uv,sampler2D image) {
    vec2 pixel=0.5/vec2(textureSize(image,0));
    return clamp(uv,pixel,1-pixel);
}
vec3 tent(sampler2D image,vec2 uv,float spread) {
    vec2 pixel=spread/vec2(textureSize(image,0));
    vec3 result=vec3(0);
    for(int y=-1;y<=1;y++)for(int x=-1;x<=1;x++)
        result+=texture(image,boundedUV(uv+vec2(x,y)*pixel,image)).rgb*float((x==0?2:1)*(y==0?2:1));
    return result/16;
}
