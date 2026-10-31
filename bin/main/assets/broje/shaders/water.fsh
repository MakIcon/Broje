#version 450
// WATER_COMMON
layout(binding=1) uniform Fog {
    vec4 FogColor;
    float FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, AlphaCutout;
};
layout(binding=4) uniform sampler2D Sampler0;
layout(location=0) in vec4 vertexColor;
layout(location=1) in vec2 texCoord0;
layout(location=2) in vec3 relativePosition;
layout(location=3) in float skyLight;
layout(location=4) in flat float fadeFactor;
layout(location=5) in vec3 biomeColor;
layout(location=6) in flat float waterMaterial;
layout(location=0) out vec4 fragColor;

float ramp(float d,float a,float b) { return clamp((d-a)/max(b-a,0.001),0.0,1.0); }
vec3 skyReflection(vec3 ray) {
    float up=sqrt(clamp(ray.y,0.0,1.0));
    vec3 horizon=mix(WaterAmbient.rgb,WaterSky.rgb,0.35);
    vec3 fallback=mix(horizon,WaterSky.rgb,up);
    vec2 uv;
    // Use the actual rendered sky when its direction is on screen. The analytic
    // dome covers directions the camera cannot see.
    if(projectPoint(ray*1024.0,uv)&&depthAt(uv)>0.99999) {
        vec2 edge=min(uv,1-uv);
        fallback=mix(fallback,texture(WaterColor,safeUV(uv)).rgb,smoothstep(0.0,0.06,min(edge.x,edge.y)));
    }
    // Neither has the volumetric clouds yet: they come from the cloud sky map, in
    // every direction, also while standing on the shore looking at the water.
    vec4 clouds=cloudReflection(ray);
    return fallback*clouds.a+clouds.rgb;
}

// Infer a receiver plane from adjacent depth texels. Precise mode chooses
// the neighbour on the same side of a silhouette instead of spanning the edge.
bool intersectScenePlane(vec3 origin,vec3 ray,vec2 uv,vec3 a,out vec3 intersection) {
    ivec2 size=textureSize(WaterDepth,0),pixel=clamp(ivec2(uv*size),ivec2(1),size-2);
    vec2 center=(vec2(pixel)+0.5)/vec2(size);
    vec3 b=positionAt(center+vec2(1.0/float(size.x),0),texelFetch(WaterDepth,pixel+ivec2(1,0),0).r);
    vec3 c=positionAt(center+vec2(0,1.0/float(size.y)),texelFetch(WaterDepth,pixel+ivec2(0,1),0).r);
    if(WaterPerformance.z>0.5) {
        vec3 left=positionAt(center-vec2(1.0/float(size.x),0),texelFetch(WaterDepth,pixel-ivec2(1,0),0).r);
        vec3 up=positionAt(center-vec2(0,1.0/float(size.y)),texelFetch(WaterDepth,pixel-ivec2(0,1),0).r);
        if(length(left-a)<length(b-a))b=left;
        if(length(up-a)<length(c-a))c=up;
    }
    if(length(b-a)>0.5+length(a)*0.02||length(c-a)>0.5+length(a)*0.02)return false;
    vec3 n=cross(b-a,c-a);
    float magnitude=length(n);if(magnitude<1e-10)return false;n/=magnitude;
    vec3 axis=abs(n);
    if(max(axis.x,max(axis.y,axis.z))>0.995)
        n=axis.x>axis.y&&axis.x>axis.z?vec3(sign(n.x),0,0):axis.y>axis.z?vec3(0,sign(n.y),0):vec3(0,0,sign(n.z));
    float denominator=dot(n,ray);if(abs(denominator)<0.002)return false;
    float t=dot(n,a-origin)/denominator;
    if(t<0.025||t>WaterPerformance.x)return false;
    intersection=origin+ray*t;
    vec2 target;if(!projectPoint(intersection,target))return false;
    ivec2 targetPixel=clamp(ivec2(target*size),ivec2(0),size-1);
    float targetZ=texelFetch(WaterDepth,targetPixel,0).r;
    if(targetZ>0.999999)return false;
    // Depth belongs to the texel centre. Reconstructing it on the continuous
    // target ray invents depth steps on sloping banks and rejects valid hits.
    vec2 targetCentre=(vec2(targetPixel)+0.5)/vec2(size);
    vec3 scene=positionAt(targetCentre,targetZ);
    vec3 cameraRay=normalize(positionAt(targetCentre,0.5));
    float cameraDenominator=dot(n,cameraRay);
    if(abs(cameraDenominator)<0.002)return false;
    vec3 expected=cameraRay*(dot(n,a)/cameraDenominator);
    return length(scene-expected)<0.035+length(scene)*0.0015;
}
float sceneCameraDepth(vec2 uv,float z) {
    vec4 clip=vec4(uv.x*2-1,1-uv.y*2,WaterScreen.z>0.5?z:z*2-1,1);
    float reciprocal=dot(vec4(WaterInverseVP[0][3],WaterInverseVP[1][3],WaterInverseVP[2][3],WaterInverseVP[3][3]),clip);
    return 1/max(reciprocal,0.0000001);
}
// Return UV, hit distance and confidence. March in order: a plane hypothesis
// ahead of the current interval must never hide a closer receiver.
vec4 traceReflection(vec3 origin,vec3 ray,vec3 geometricNormal) {
    float maxRange=WaterPerformance.x;
    vec4 beginClip=WaterVP*vec4(origin+ray*0.025,1),travelClip=WaterVP*vec4(ray,0);
    if(beginClip.w<=0.025)return vec4(0);
    if(travelClip.w<0)maxRange=min(maxRange,(0.025-beginClip.w)/travelClip.w);
    if(maxRange<=0.03)return vec4(0);
    vec3 begin=origin+ray*0.025,end=origin+ray*maxRange;
    vec4 endClip=WaterVP*vec4(end,1);
    vec2 a=vec2(beginClip.x,-beginClip.y)/beginClip.w*0.5+0.5;
    vec2 b=vec2(endClip.x,-endClip.y)/endClip.w*0.5+0.5,deltaUV=b-a;
    float screenEnd=1;
    for(int axis=0;axis<2;axis++) {
        if(deltaUV[axis]>0)screenEnd=min(screenEnd,(0.998-a[axis])/deltaUV[axis]);
        else if(deltaUV[axis]<0)screenEnd=min(screenEnd,(0.002-a[axis])/deltaUV[axis]);
    }
    screenEnd=clamp(screenEnd,0,1);
    float span=max(abs(deltaUV.x)*WaterScreen.x,abs(deltaUV.y)*WaterScreen.y)*screenEnd;
    int steps=min(int(WaterOptics.w),max(1,int(ceil(span))));
    vec3 q0=begin/beginClip.w,q1=end/endClip.w;
    float k0=1/beginClip.w,k1=1/endClip.w;
    float nearest=maxRange+1;
    vec4 result=vec4(0);
    ivec2 size=textureSize(WaterDepth,0);
    for(int i=0;i<64;i++) {
        if(i>=steps)break;
        float s=screenEnd*float(i+1)/float(steps),k=mix(k0,k1,s);
        vec2 uv=a+deltaUV*s;
        vec3 probe=mix(q0,q1,s)/k;
        float currentT=dot(probe-origin,ray);
        ivec2 pixel=clamp(ivec2(uv*size),ivec2(1),size-2);
        float z=texelFetch(WaterDepth,pixel,0).r;
        if(z<0.999999) {
            vec2 centre=(vec2(pixel)+0.5)/vec2(size);
            vec3 sourceScene=positionAt(centre,z);
            vec3 candidate;
            // Normal comes from neighbouring geometry, never from whichever
            // rounded world coordinate happens to be closest to an integer.
            if(intersectScenePlane(origin,ray,uv,sourceScene,candidate)&&dot(candidate-origin,geometricNormal)>-0.05) {
                float t=dot(candidate-origin,ray);
                if(t<nearest) {
                    vec2 hitUV;projectPoint(candidate,hitUV);
                    vec2 edge=min(hitUV,1-hitUV);
                    float confidence=smoothstep(0.0,0.055,min(edge.x,edge.y));
                    confidence*=1-smoothstep(WaterPerformance.x*0.7,WaterPerformance.x,t);
                    nearest=t;result=vec4(hitUV,t,confidence);
                }
            }
        }
        if(nearest<=currentT+0.025)return result;
    }
    return result;
}
vec3 reflectionColor(vec2 uv) {
    vec3 sum=texture(WaterColor,safeUV(uv)).rgb;
    float weight=1,centre=sceneCameraDepth(uv,depthAt(uv));
    vec2 pixel=1/vec2(textureSize(WaterDepth,0));
    const vec2 offsets[4]=vec2[4](vec2(0.75,0),vec2(-0.75,0),vec2(0,0.75),vec2(0,-0.75));
    int taps=int(WaterPerformance.y)-1;
    for(int i=0;i<4;i++) {
        if(i>=taps)break;
        vec2 q=safeUV(uv+offsets[i]*pixel);
        float distance=sceneCameraDepth(q,depthAt(q));
        float sameSurface=1-smoothstep(0.06,0.25,abs(distance-centre));
        sum+=texture(WaterColor,q).rgb*sameSurface;weight+=sameSurface;
    }
    return sum/weight;
}

// Trace the persistent top-down height field in WORLD space. Camera pitch never
// changes its coverage, unlike a reflected screen camera with a narrow frustum.
vec4 probeAt(vec2 uv) {
    ivec2 size=textureSize(WaterMirror,0);
    uv=clamp(uv,0.5/vec2(size),1-0.5/vec2(size));
    vec4 value=texture(WaterMirror,uv);
    // Height/validity must not interpolate with an empty texel: that invents a
    // receiver above the water at the edge of a bank and makes thin bright lines.
    value.a=texelFetch(WaterMirror,clamp(ivec2(uv*size),ivec2(0),size-1),0).a;
    return value;
}
vec4 seabedReflection(vec3 origin,vec3 direction) {
    if(WaterReflection.x<0.5||WaterProbe.w<=0)return vec4(0);
    vec3 ray=normalize(vec3(direction.x,-max(abs(direction.y),0.025),direction.z));
    // Continue the coarse seabed at the map boundary instead of dropping the
    // entire mirror to transmitted sky. Local detail remains height-field traced.
    vec2 seedUV=clamp((origin.xz-WaterProbe.xz)/(2*WaterProbe.w)+0.5,vec2(0.03),vec2(0.97));
    vec4 seed=probeAt(seedUV);
    if(seed.a<0.5)seed=probeAt(vec2(0.5));
    float floorY=seed.a>0.5?WaterProbe.y-(seed.a-1):WaterProbe.y-12;
    float fallbackT=max((floorY-origin.y-WaterCamera.y)/ray.y,0.0);
    vec2 fallbackUV=clamp((origin.xz+ray.xz*fallbackT-WaterProbe.xz)/(2*WaterProbe.w)+0.5,vec2(0.03),vec2(0.97));
    vec4 distant=probeAt(fallbackUV);
    vec3 distantColor=distant.a>0.5?distant.rgb:seed.a>0.5?seed.rgb:waterScatter(WaterMedium.yzw);
    vec3 fallbackLoss=exp(-extinction()*min(fallbackT,120.0));
    vec3 fallback=distantColor*fallbackLoss+waterScatter(WaterMedium.yzw)*(1-fallbackLoss);
    float previous=0.0,range=WaterProbe.w*2;
    int steps=int(WaterPerformance.w);
    for(int i=0;i<16;i++) {
        if(i>=steps)break;
        float f=float(i+1)/float(steps),t=range*f*f;
        vec3 q=origin+ray*t;
        vec2 uv=(q.xz-WaterProbe.xz)/(2*WaterProbe.w)+0.5;
        float edge=max(abs(uv.x-0.5),abs(uv.y-0.5))*2;
        if(edge>1.2)break;
        vec4 sampleColor=probeAt(uv);
        if(sampleColor.a>0.5) {
            float height=WaterProbe.y-(sampleColor.a-1);
            if(q.y+WaterCamera.y<=height) {
                float lo=previous,hi=t;
                for(int j=0;j<5;j++) {
                    float mid=(lo+hi)*0.5;vec3 p=origin+ray*mid;
                    vec2 probe=(p.xz-WaterProbe.xz)/(2*WaterProbe.w)+0.5;
                    vec4 hit=probeAt(probe);
                    if(hit.a>0.5&&p.y+WaterCamera.y<=WaterProbe.y-(hit.a-1))hi=mid;else lo=mid;
                }
                vec3 p=origin+ray*hi;
                vec2 probe=(p.xz-WaterProbe.xz)/(2*WaterProbe.w)+0.5;
                vec3 rgb=probeAt(probe).rgb;
                vec3 attenuation=exp(-extinction()*hi);
                float confidence=1-smoothstep(0.8,1.2,max(abs(probe.x-0.5),abs(probe.y-0.5))*2);
                return vec4(mix(fallback,rgb*attenuation+waterScatter(WaterMedium.yzw)*(1-attenuation),confidence),1);
            }
        }
        previous=t;
    }
    return vec4(fallback,1);
}
void main() {
    vec3 p=relativePosition;
    vec3 face=cross(dFdx(p),dFdy(p));
    vec3 geometric=dot(face,face)>1e-12?normalize(face):vec3(0,1,0);
    if(dot(geometric,p)>0)geometric=-geometric;
    bool submergedSurface=geometric.y< -0.5||(abs(geometric.y)<0.5&&WaterEffects.w>0.5);
    vec4 vanilla=texture(Sampler0,texCoord0)*vertexColor;
    float sphere=length(p),cylinder=max(length(p.xz),abs(p.y));
    float fog=max(ramp(sphere,FogEnvironmentalStart,FogEnvironmentalEnd),ramp(cylinder,FogRenderDistanceStart,FogRenderDistanceEnd));
    if(waterMaterial<0.5) {
        vanilla=mix(FogColor*vec4(1,1,1,vanilla.a),vanilla,fadeFactor);
        if(vanilla.a<AlphaCutout)discard;
        fragColor=vec4(mix(vanilla.rgb,FogColor.rgb,fog*FogColor.a),vanilla.a);return;
    }
    vec3 world=p+WaterCamera.xyz;
    vec3 w=wave(world.xz);
    float footprint=max(length(dFdx(world.xz)),length(dFdy(world.xz)));
    w.yz+=capillarySlope(world.xz)*exp(-footprint*3.0);
    float surface=abs(geometric.y);
    vec3 normal=surface>0.8?normalize(vec3(-w.y*2.0,sign(geometric.y),-w.z*2.0)):geometric;
    vec3 view=normalize(-p);
    if(dot(normal,view)<0)normal=geometric;
    vec2 uv=gl_FragCoord.xy/WaterScreen.xy;
    float opaqueZ=depthAt(uv);
    vec3 background=positionAt(uv,opaqueZ);
    float path=opaqueZ>0.999999?48.0:clamp(length(background)-length(p),0.0,80.0);

    vec2 bent=uv;
    vec2 candidate;
    // Project a displaced point so refraction follows camera orientation.
    if(projectPoint(p+vec3(normal.x,0,normal.z)*WaterOptics.y*min(path,3.0),candidate)) {
        vec3 q=positionAt(candidate,depthAt(candidate));
        // Reject foreground, dry banks and depth discontinuities before reading color.
        if(length(q)>length(p)+0.02&&(surface<0.5||q.y<p.y+0.12))bent=candidate;
    }
    if(any(notEqual(bent,uv))) {
        opaqueZ=depthAt(bent);background=positionAt(bent,opaqueZ);
        path=opaqueZ>0.999999?48.0:clamp(length(background)-length(p),0.0,80.0);
    }
    vec3 transmitted=texture(WaterColor,safeUV(bent)).rgb;
    if(submergedSurface) {
        // Readable underwater mode: keep the real shore in its original angular
        // position, with restrained wave distortion. No mandatory critical-angle
        // window or all-blue replacement when a refracted ray leaves the screen.
        vec2 through=uv,distortion;
        if(projectPoint(p+vec3(normal.x,0,normal.z)*WaterOptics.y*0.30,distortion)) {
            vec3 scene=positionAt(distortion,depthAt(distortion));
            if(depthAt(distortion)>0.999999||
                    (length(scene)>length(p)+0.02&&scene.y+WaterCamera.y>WaterMedium.x-0.15))through=distortion;
        }
        transmitted=texture(WaterColor,safeUV(through)).rgb;
    }
    float wetDepth=max(p.y-background.y,0.0);
    float light=sunlight(background+vec3(0,0.03,0))*WaterSun.w*smoothstep(0.35,0.95,skyLight);
    if(opaqueZ<0.999999&&surface>0.5&&!submergedSurface)
        transmitted*=1+(1-exp(-caustics(background+WaterCamera.xyz)*WaterEffects.y*0.65))*light*exp(-wetDepth*0.15)*0.55;
    vec3 transmittance=exp(-extinction()*path);
    if(!submergedSurface)transmitted=transmitted*transmittance+waterScatter(biomeColor)*(1-transmittance);

    float cosine=clamp(dot(normal,view),0.0,1.0);
    float fresnel=0.0204+0.9796*pow(1-cosine,5.0);
    if(submergedSurface) {
        fresnel=WaterReflection.y*(0.08+0.72*pow(1-cosine,3.0));
    }
    vec3 reflected=reflect(-view,normal);
    vec3 environment=skyReflection(reflected)*smoothstep(0.15,0.85,skyLight);
    vec4 mirror=vec4(0);
    if(submergedSurface) {
        mirror=seabedReflection(p,reflected);
        environment=mix(transmitted,mirror.rgb,mirror.a);
    }
    vec4 hit=WaterEffects.x>0.5&&!submergedSurface?traceReflection(p+geometric*0.025,reflected,geometric):vec4(0);
    if(hit.a>0)hit.rgb=reflectionColor(hit.xy);
    environment=mix(environment,hit.rgb,hit.a);
    vec3 halfVector=view+WaterSun.xyz;
    float highlight=dot(halfVector,halfVector)>1e-6?pow(max(dot(normal,normalize(halfVector)),0.0),420.0):0;
    float direct=submergedSurface?0.0:sunlight(p+geometric*0.08)*WaterSun.w*smoothstep(0.4,0.95,skyLight);
    // Fresnel splits light energy, so weigh it in linear light, not in display values:
    // there a bright cloud reflected at 10% over a sunlit shallow floor all but vanished,
    // and only dark deep water seen from high above showed the clouds at all.
    vec3 color=submergedSurface||WaterScreen.w==9?mix(transmitted,environment,fresnel)
            :pow(mix(pow(max(transmitted,0.0),vec3(2.2)),pow(max(environment,0.0),vec3(2.2)),fresnel),vec3(1.0/2.2));
    color+=vec3(1.0,0.88,0.65)*highlight*direct*0.8;
    // Contact tint fades continuously over the first few centimetres of water.
    color+=vec3(0.04,0.065,0.07)*exp(-path*7.0)*surface*WaterSun.w;
    if(!submergedSurface)color=mix(color,FogColor.rgb,fog*FogColor.a);
    color=mix(FogColor.rgb,color,fadeFactor);
    if(WaterScreen.w==1)color=vec3(1,0,1);
    if(WaterScreen.w==2)color=vec3(hit.a);
    if(WaterScreen.w==3)color=vec3(path/20.0);
    if(WaterScreen.w==4)color=mirror.a>0?mirror.rgb:vec3(1,0,1);
    if(WaterScreen.w==6)color=vec3(mirror.a);
    if(WaterScreen.w==7)color=environment;
    if(WaterScreen.w==8)color=vec3(fresnel);
    // Color already contains the refracted background: native SRC_ALPHA blending
    // must replace it once, rather than blending that same background twice.
    fragColor=vec4(color,1);
}
