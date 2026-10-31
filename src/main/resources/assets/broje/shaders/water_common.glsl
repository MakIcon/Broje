// Original Broje water model. Expanded by WaterRenderer before SPIR-V compilation.
layout(std140,binding=3) uniform WaterUniforms {
    mat4 WaterVP;
    mat4 WaterInverseVP;
    mat4 BrojeShadowMatrix;
    vec4 WaterCamera;       // world xyz, wave clock (periodic)
    vec4 WaterOptics;       // wave amplitude, refraction, extinction scale, SSR steps
    vec4 WaterEffects;      // SSR, caustics, shafts, camera submerged
    vec4 WaterSun;          // toward sun, daylight after rain
    vec4 WaterSky;          // zenith rgb, rain
    vec4 WaterAmbient;      // air horizon rgb, local sky light
    vec4 WaterStill;
    vec4 WaterFlow;
    vec4 WaterOverlay;
    vec4 WaterShadow;       // map ready, distortion, range, surface sky light
    vec4 WaterMedium;       // local water surface Y, biome rgb
    vec4 WaterScreen;       // width, height, zero-to-one, debug
    vec4 WaterReflection;   // mirror enabled, reflection strength, shaft strength, shaft steps
    vec4 WaterPerformance;  // SSR range, colour filter taps, precise tracing, probe trace steps
    vec4 WaterProbe;        // capture centre relative x/z, water-plane Y, radius
};
layout(binding=6) uniform sampler2D WaterColor;
layout(binding=7) uniform sampler2D WaterDepth;
layout(binding=8) uniform sampler2D WaterSunDepth;
layout(binding=9) uniform sampler2D WaterMirror;
layout(binding=10) uniform sampler2D WaterOpaqueDepth;

bool inSprite(vec2 uv,vec4 bounds) {
    return all(greaterThanEqual(uv,bounds.xy))&&all(lessThanEqual(uv,bounds.zw));
}
bool isWater(vec2 uv) {
    return inSprite(uv,WaterStill)||inSprite(uv,WaterFlow)||inSprite(uv,WaterOverlay);
}

float waveHash(vec2 cell) {
    uvec2 c=uvec2(mod(cell,64.0));
    uint h=c.x*1597334677u^c.y*3812015801u;h=(h^(h>>16))*2246822519u;h^=h>>13;
    return float(h&65535u)/65535.0;
}
// Smooth random height and its analytic gradient. Independent advected scales
// remove the parallel sine bands, without screen-space randomness or flicker.
vec3 waveNoise(vec2 p) {
    vec2 cell=floor(p),f=fract(p);
    vec2 u=f*f*f*(f*(f*6-15)+10),du=30*f*f*(f-1)*(f-1);
    float a=waveHash(cell),b=waveHash(cell+vec2(1,0));
    float c=waveHash(cell+vec2(0,1)),d=waveHash(cell+vec2(1,1));
    return vec3(mix(mix(a,b,u.x),mix(c,d,u.x),u.y)-0.5,
                mix(b-a,d-c,u.y)*du.x,mix(c-a,d-b,u.x)*du.y);
}
vec3 wave(vec2 p) {
    float t=WaterCamera.w;
    vec3 a=waveNoise((p+vec2(0.75,0.5)*t)*0.125);
    vec3 b=waveNoise((p+vec2(-0.25,0.5)*t)*0.3125+vec2(17,29));
    vec3 c=waveNoise((p+vec2(0.25,-0.75)*t)*0.75+vec2(43,11));
    return (a*vec3(0.08,0.01,0.01)+b*vec3(0.035,0.0109375,0.0109375)+
            c*vec3(0.012,0.009,0.009))*WaterOptics.x;
}
vec2 capillarySlope(vec2 p) {
    vec3 a=waveNoise((p+vec2(0.25,-0.5)*WaterCamera.w)*1.5+vec2(7,39));
    vec3 b=waveNoise((p+vec2(-0.5,0.25)*WaterCamera.w)*3.0+vec2(31,13));
    return (a.yz*0.021+b.yz*0.012)*WaterOptics.x;
}
vec2 safeUV(vec2 uv) { return clamp(uv,0.5/WaterScreen.xy,1.0-0.5/WaterScreen.xy); }
float depthAt(vec2 uv) {
    ivec2 size=textureSize(WaterDepth,0);
    return texelFetch(WaterDepth,clamp(ivec2(uv*size),ivec2(0),size-1),0).r;
}
vec3 positionAt(vec2 uv,float depth) {
    vec4 q=WaterInverseVP*vec4(uv.x*2-1,1-uv.y*2,WaterScreen.z>0.5?depth:depth*2-1,1);
    return q.xyz/(abs(q.w)<1e-7?1e-7:q.w);
}
bool projectPoint(vec3 p,out vec2 uv) {
    vec4 q=WaterVP*vec4(p,1);
    if(q.w<=0.01)return false;
    uv=vec2(q.x,-q.y)/q.w*0.5+0.5;
    return all(greaterThan(uv,vec2(0.001)))&&all(lessThan(uv,vec2(0.999)));
}
float sunlight(vec3 p) {
    if(WaterShadow.x<0.5)return WaterShadow.w;
    vec4 q=BrojeShadowMatrix*vec4(p,1);
    float warp=length(q.xy)*WaterShadow.y+1-WaterShadow.y;
    vec2 uv=vec2(q.x,-q.y)/max(warp,0.02)*0.5+0.5;
    if(any(lessThan(uv,vec2(0.001)))||any(greaterThan(uv,vec2(0.999)))||q.z<=0||q.z>=1)return WaterShadow.w;
    ivec2 size=textureSize(WaterSunDepth,0);
    float visibility=step(q.z-0.00008,texelFetch(WaterSunDepth,clamp(ivec2(uv*size),ivec2(0),size-1),0).r);
    return mix(visibility,WaterShadow.w,smoothstep(WaterShadow.z*0.7,WaterShadow.z,length(p)));
}
float caustics(vec3 world) {
    vec2 p=world.xz+world.y*vec2(0.23,-0.19);
    const float K=0.0245436926;
    // Curved, continuously refracting cells rather than a fixed polygon lattice.
    vec2 warp=vec2(sin(dot(p,vec2(47,19)*K)+WaterCamera.w*0.78539816),
                   sin(dot(p,vec2(-23,53)*K)-WaterCamera.w*0.39269908));
    p+=warp*0.32;
    vec2 cell=floor(p),local=fract(p);
    float first=10,second=10;
    for(int y=-1;y<=1;y++)for(int x=-1;x<=1;x++) {
        vec2 offset=vec2(x,y);
        uvec2 c=uvec2(mod(cell+offset,64.0));
        uint h=c.x*1597334677u^c.y*3812015801u;h=(h^(h>>16))*2246822519u;h^=h>>13;
        vec2 random=vec2(h&65535u,h>>16)/65535.0;
        vec2 site=offset+0.5+0.32*cos(random*6.2831853+WaterCamera.w*vec2(0.78539816,-0.39269908));
        float d=dot(site-local,site-local);
        if(d<first){second=first;first=d;}else second=min(second,d);
    }
    float edge=sqrt(second)-sqrt(first);
    float line=1-smoothstep(0.015,0.16,edge);
    float focus=0.65+0.35*sin(dot(world.xz,vec2(9,13)*K)+WaterCamera.w*0.78539816);
    return line*line*focus;
}
vec3 extinction() { return vec3(0.13,0.038,0.018)*WaterOptics.z; }
vec3 waterScatter(vec3 tint) {
    vec3 normalized=tint/max(max(tint.r,tint.g),max(tint.b,0.01));
    float illumination=0.04+0.96*max(WaterAmbient.w,WaterShadow.w)*(0.04+0.96*WaterSun.w);
    return mix(vec3(0.025,0.24,0.29),normalized*vec3(0.08,0.29,0.33),0.35)*illumination;
}
