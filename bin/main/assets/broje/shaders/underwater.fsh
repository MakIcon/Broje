#version 450
// WATER_COMMON
layout(location=0) out vec4 fragColor;
float surfaceAt(vec2 xz) {
    return WaterMedium.x+wave(xz).x-0.095*WaterOptics.x;
}
// Only used when the eye is above a wave but water crosses the near plane.
// A bounded bracket cannot jump to a negative root and erase an entire ray.
float waterEntry(vec3 ray,float limit) {
    float amplitude=0.0635*WaterOptics.x;
    float middle=WaterMedium.x-0.095*WaterOptics.x;
    float lo=clamp((middle+amplitude-WaterCamera.y)/ray.y,0.0,limit);
    float hi=clamp((middle-amplitude-WaterCamera.y)/ray.y,lo,limit);
    if(WaterCamera.y+ray.y*hi>surfaceAt(WaterCamera.xz+ray.xz*hi))return limit;
    for(int j=0;j<8;j++) {
        float mid=(lo+hi)*0.5;
        if(WaterCamera.y+ray.y*mid>surfaceAt(WaterCamera.xz+ray.xz*mid))lo=mid;else hi=mid;
    }
    return (lo+hi)*0.5;
}
// Sunlight is focused into irregular ribbons at the surface. Advect the field
// with the waves, then extend it along the refracted solar direction, not the
// screen or a single sine stripe. Zero between ribbons prevents a uniform veil.
float erfApprox(float x) {
    float v=abs(x),t=1/(1+0.3275911*v);
    float e=1-(((((1.061405429*t-1.453152027)*t)+1.421413741)*t-0.284496736)*t+0.254829592)*t*exp(-v*v);
    return sign(x)*e;
}
float beamFocus(vec2 entry,vec2 segmentDelta,float footprint) {
    vec3 a=waveNoise((entry+vec2(0.5,0.25)*WaterCamera.w)*0.125+vec2(3,17));
    vec3 b=waveNoise((entry+vec2(-0.25,0.5)*WaterCamera.w)*0.3125+vec2(29,7));
    float fold=a.x+0.30*b.x-0.12;
    vec2 gradient=a.yz*0.125+b.yz*(0.30*0.3125);
    float span=abs(dot(gradient,segmentDelta));
    float width=0.065+min(footprint*0.04,0.03);
    // Integrate the smooth ribbon across the whole marching segment. Sampling
    // only its centre sliced narrow beams into repeated curved sheets.
    float ribbon=span>0.001?0.886226925*width/span*
        (erfApprox((fold+span*0.5)/width)-erfApprox((fold-span*0.5)/width)):exp(-fold*fold/(width*width));
    float envelope=smoothstep(-0.10,0.28,waveNoise(entry*0.0625+vec2(11,43)).x);
    return clamp(ribbon,0.0,1.0)*envelope;
}
void main() {
    vec2 uv=gl_FragCoord.xy/WaterScreen.xy;
    float depth=depthAt(uv);
    vec3 end=positionAt(uv,depth);
    float distance=depth>0.999999?64.0:min(length(end),80.0);
    vec3 ray=normalize(end);
    vec3 background=texture(WaterColor,safeUV(uv)).rgb;
    if(WaterScreen.w==1||WaterScreen.w==4||WaterScreen.w==6) {fragColor=vec4(background,1);return;}
    ivec2 opaqueSize=textureSize(WaterOpaqueDepth,0);
    float opaqueZ=texelFetch(WaterOpaqueDepth,clamp(ivec2(uv*opaqueSize),ivec2(0),opaqueSize-1),0).r;
    vec3 opaqueEnd=positionAt(uv,opaqueZ);
    bool receiver=depth<0.999999&&opaqueZ<0.999999&&abs(length(opaqueEnd)-length(end))<0.06;
    // Native water classification uses the undisplaced fluid surface. The eye can
    // already be above a wave: in that case forward water owns the submerged path.
    float cameraSurface=surfaceAt(WaterCamera.xz);
    float immersionDepth=cameraSurface-WaterCamera.y;
    float begin=0.0;
    bool entering=immersionDepth<=0;
    if(entering&&(ray.y>=-0.000001||!receiver)) {fragColor=vec4(background,1);return;}
    if(entering)begin=waterEntry(ray,distance);
    // The final depth already contains the displaced water mesh. Use that exact
    // exit for submerged eyes: another analytic root disagrees with its triangles
    // at grazing angles and used to expose bright sinusoidal strips on the bank.
    // A thin interface blends the volume continuously instead of adding a dusty
    // full-strength layer the moment the native camera switches medium.
    distance=max(distance-begin,0.0);
    if(!entering)distance*=smoothstep(0.0,0.15,immersionDepth);
    vec3 transmission=exp(-extinction()*distance);
    vec3 scatter=waterScatter(WaterMedium.yzw);
    vec3 color=background*transmission+scatter*(1-transmission);
    vec3 face=cross(dFdx(end),dFdy(end));
    float floorFacing=dot(face,face)>1e-12?abs(normalize(face).y):0;
    vec3 world=end+WaterCamera.xyz;
    float below=max(WaterMedium.x-world.y,0.0);
    float visibility=sunlight(end+vec3(0,0.04,0))*WaterSun.w;
    // Only submerged receiving surfaces, never the sky or upper side of the surface.
    if(receiver&&world.y<WaterMedium.x-0.35) {
        float focused=1-exp(-caustics(world)*WaterEffects.y*0.65);
        color+=background*transmission*focused*visibility*
            exp(-below*0.14)*(0.08+floorFacing*0.60);
    }
    if(WaterEffects.z>0.5&&WaterSun.w>0.001&&WaterShadow.x>0.5&&distance>0.001) {
        int count=min(int(WaterReflection.w),max(6,int(ceil(distance*3.0))));
        float stepSize=distance/float(count);
        float jitter=fract(52.9829189*fract(dot(gl_FragCoord.xy,vec2(0.06711056,0.00583715))));
        vec3 shafts=vec3(0);
        // Readable-surface mode preserves the sky's angular positions. Use that
        // same visible solar direction for beams instead of a second, bent sun.
        vec3 sunInWater=normalize(WaterSun.xyz);
        float mu=dot(ray,sunInWater);
        float phase=0.30+0.55*min(0.64/pow(max(1.36-1.2*mu,0.12),1.5),2.5);
        vec3 loss=extinction();
        vec3 segment=1-exp(-loss*stepSize);
        float fadeIn=entering?1.0:smoothstep(0.0,0.25,immersionDepth);
        for(int i=0;i<48;i++) {
            if(i>=count)break;
            float t=(float(i)+jitter)*stepSize;
            vec3 p=ray*(begin+t);
            float waterDepth=max(WaterMedium.x-WaterCamera.y-p.y,0.0);
            vec2 entry=(p+WaterCamera.xyz).xz+waterDepth*sunInWater.xz/max(sunInWater.y,0.2);
            vec2 entryDirection=ray.xz-ray.y*sunInWater.xz/max(sunInWater.y,0.2);
            float beam=beamFocus(entry,entryDirection*stepSize,t/WaterScreen.y);
            vec3 surfacePoint=vec3(entry-WaterCamera.xz,WaterMedium.x+0.05-WaterCamera.y).xzy;
            float openSky=sunlight(surfacePoint);
            vec3 solarTransmission=exp(-vec3(0.10,0.045,0.025)*waterDepth/max(sunInWater.y,0.2));
            shafts+=vec3(0.60,1.15,1.0)*solarTransmission*openSky*beam*
                exp(-loss*t)*segment*phase;
        }
        // Keep focused light above the diffuse component already represented by
        // waterScatter. Long view rays cross many ribbons; adding their mean a
        // second time turned the entire volume into a flat milky layer.
        vec3 baseline=vec3(0.60,1.15,1.0)*0.07*phase*(1-exp(-loss*distance));
        vec3 focused=max(shafts-baseline,vec3(0));
        focused=focused*focused/(focused+vec3(0.002))*1.8;
        color+=focused*WaterSun.w*WaterReflection.z*fadeIn;
    }
    fragColor=vec4(color,1);
}
