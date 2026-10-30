#version 450
layout(binding=1) uniform Fog {
    vec4 FogColor;
    float FogEnvironmentalStart;
    float FogEnvironmentalEnd;
    float FogRenderDistanceStart;
    float FogRenderDistanceEnd;
    float AlphaCutout;
};
layout(std140,binding=3) uniform SunLight {
    mat4 BrojeShadowMatrix;
    mat4 BrojeShadowView;
    mat4 BrojeShadowInverse;
    vec4 BrojeSunLight;            // direction toward the sun, direct sunlight visibility
    vec4 BrojeShadowSettings; // direct share, softness, distance, sample count
    vec4 BrojeShadowMap;      // resolution, world width, depth span, undistorted texel world size
    vec4 BrojeFlags;          // debug view, map rendered, distortion strength, shadow filter
    vec4 BrojeLighting;       // bounce, foliage glow, light color, GI samples (0 = off)
    vec4 BrojeTint;           // contrast, mixing, brightness, saturation
    vec4 BrojeTintBlend;      // blend mode, overlap brightness, unused
};
layout(binding=4) uniform sampler2D Sampler0;
layout(binding=6) uniform sampler2D Sampler1; // sun depth map
layout(binding=7) uniform sampler2D Sampler3; // sunlight transmittance of glass in front of it

layout(location=0) in vec4 vertexColor;
layout(location=1) in vec2 texCoord0;
layout(location=2) in vec3 relativePosition;
layout(location=3) in float skyLight;
layout(location=4) in flat float fadeFactor;
layout(location=5) in float blockLight;
layout(location=0) out vec4 fragColor;

const float GOLDEN_ANGLE=2.39996323;

// Radial distortion of the light clip plane; the casters apply the same per vertex.
float distortionFactor(vec2 clip) {
    return length(clip)*BrojeFlags.z+(1.0-BrojeFlags.z);
}

vec3 toMap(vec3 position) {
    vec4 clip=BrojeShadowMatrix*vec4(position,1);
    vec2 xy=clip.xy/distortionFactor(clip.xy);
    return vec3(xy.x*0.5+0.5,0.5-xy.y*0.5,clip.z);
}

// World size of one map texel at this point, along the stretched radial direction.
float localTexel(vec3 position) {
    vec4 clip=BrojeShadowMatrix*vec4(position,1);
    float d=distortionFactor(clip.xy);
    return BrojeShadowMap.w*d*d/max(1.0-BrojeFlags.z,0.05);
}

bool insideMap(vec3 shadow) {
    return all(greaterThan(shadow,vec3(0.001)))&&all(lessThan(shadow,vec3(0.999)));
}

float compareTexel(ivec2 texel,float reference) {
    int resolution=int(BrojeShadowMap.x);
    if(any(lessThan(texel,ivec2(0)))||any(greaterThanEqual(texel,ivec2(resolution))))return 1;
    return step(reference,texelFetch(Sampler1,texel,0).r);
}

float bilinearPCF(vec2 uv,float reference) {
    vec2 p=uv*BrojeShadowMap.x-0.5;
    ivec2 base=ivec2(floor(p));vec2 f=fract(p);
    return mix(mix(compareTexel(base,reference),compareTexel(base+ivec2(1,0),reference),f.x),
               mix(compareTexel(base+ivec2(0,1),reference),compareTexel(base+ivec2(1,1),reference),f.x),f.y);
}

// Rotation of a sample pattern, fixed to the world. The map centre sits on
// a 2-block world grid, so the position relative to it is the world position modulo
// that grid; it is hashed on the 1/16-block texture grid, just inside the surface so a
// face exactly on a cell border cannot flicker between two cells.
vec3 mapCentre() {
    mat3 rotation=mat3(BrojeShadowView);
    return -transpose(rotation)*BrojeShadowView[3].xyz-BrojeSunLight.xyz*(BrojeShadowMap.z*0.5);
}

float patternRotation(vec3 position,vec3 normal) {
    uvec3 cell=uvec3(mod(floor((position-mapCentre()-normal*0.03)*16.0),32.0));
    uint h=cell.x*73856093u^cell.y*19349663u^cell.z*83492791u;
    h=(h^(h>>13))*1274126177u;h^=h>>16;
    return float(h&0xffffu)/65536.0*6.2831853;
}

// Color of the sunlight at a map point after the translucent blocks above it. The
// color target holds the product of all faces, its alpha FACE_ALPHA per face.
vec3 sunFilter(vec2 uv) {
    const float FACE_ALPHA=0.7;
    int resolution=int(BrojeShadowMap.x);
    vec4 stack=texelFetch(Sampler3,clamp(ivec2(uv*BrojeShadowMap.x),ivec2(0),ivec2(resolution-1)),0);
    bool additive=BrojeTintBlend.x>1.5;
    if(additive ? stack.a<0.01 : stack.a>0.99)return vec3(1);
    float layers;
    vec3 filterColor;
    if(additive) {
        layers=stack.a;
        vec3 average=clamp(stack.rgb/max(stack.a,0.01),0.0,1.0);
        filterColor=mix(vec3(max(average.r,max(average.g,average.b))),average,BrojeTint.y);
    } else {
    vec3 product=max(stack.rgb,vec3(1e-5));
    // Two faces per block: the mean filter of one block is the product's 2/faces power.
    // Full mixing keeps the product (real filters), none shows that mean color instead.
    float faces=log(max(stack.a,1e-5))/log(FACE_ALPHA);
    layers=faces*0.5;
    vec3 single=pow(product,vec3(2.0/max(faces,2.0)));
    filterColor=mix(single,product,BrojeTint.y);
    }
    // Contrast deepens or fades the tint while keeping its hue.
    filterColor=pow(max(filterColor,vec3(1e-5)),vec3(BrojeTint.x));
    if(BrojeTintBlend.x>0.5) {
        filterColor/=max(max(filterColor.r,max(filterColor.g,filterColor.b)),1e-5);
    }
    float grey=dot(filterColor,vec3(0.2126,0.7152,0.0722));
    filterColor=max(mix(vec3(grey),filterColor,BrojeTint.w),vec3(0));
    // Normalize after saturation so the brightness control is independent of hue.
    if(BrojeTintBlend.x>0.5) filterColor/=max(max(filterColor.r,max(filterColor.g,filterColor.b)),1e-5);
    float overlap=smoothstep(1.05,1.95,layers);
    float brightness=BrojeTint.z*mix(1.0,BrojeTintBlend.y,overlap);
    return clamp(filterColor*brightness,0.0,2.0);
}

// The receiver uses its exact face normal. Offsets scale with the local texel, so
// near the player (fine texels) shadows stay sharp and attached, far away no acne.
// Each filter sample carries the glass color at its own point, so colored shadow
// edges are as soft as the plain ones.
vec3 sunVisibility(vec3 position,vec3 normal,float NoL,bool plant,out vec3 shadow) {
    int mode=int(BrojeFlags.w+0.5);
    if(mode==0&&!plant) {
        // Sharp: every texture pixel (1/16 block) of a face takes one shadow decision,
        // so edges are made of the same squares as the block textures.
        vec3 local=position-mapCentre();
        vec3 snapped=(floor((local-normal*0.03)*16.0)+0.5)/16.0;
        position+=mix(snapped-local,vec3(0),step(0.5,abs(normal)));
    }
    float texel=localTexel(position);
    float reference;
    if(plant) {
        // A blade lies almost edge-on to the sun and its own texels sit on the same
        // map depth: step toward the sun past the blade so it cannot shadow itself
        // in bands, while other blades and blocks still do.
        float lift=texel*3.0+0.06;
        shadow=toMap(position+BrojeSunLight.xyz*lift);
        reference=shadow.z-0.002/BrojeShadowMap.z;
    } else {
        float slope=1.0-clamp(NoL,0.0,1.0);
        shadow=toMap(position+normal*texel*(0.6+1.0*slope));
        float tangent=clamp(sqrt(max(1.0-NoL*NoL,0.0))/max(NoL,0.05),0.0,3.0);
        reference=shadow.z-(texel*(0.5+1.5*tangent)+0.002)/BrojeShadowMap.z;
    }
    if(!insideMap(shadow))return vec3(1);
    float resolution=BrojeShadowMap.x;
    // Sharp: one map texel decides, no filtering across the pixel square.
    if(mode==0)return compareTexel(ivec2(floor(shadow.xy*resolution)),reference)*sunFilter(shadow.xy);
    // Crisp: one bilinear compare, an edge one texel wide without steps.
    if(mode==1)return bilinearPCF(shadow.xy,reference)*sunFilter(shadow.xy);
    float radius=BrojeShadowSettings.y;
    vec3 visibility=vec3(0);
    if(mode==3) {
        // Smooth: a 3x3 tent of bilinear compares on the texel grid. Grid taps overlap
        // evenly, so the edge is one gradient without the rings of a sample disk.
        float spacing=max(radius,0.5)/resolution;
        for(int y=-1;y<=1;y++)for(int x=-1;x<=1;x++) {
            vec2 uv=shadow.xy+vec2(x,y)*spacing;
            visibility+=float((2-abs(x))*(2-abs(y)))*bilinearPCF(uv,reference)*sunFilter(uv);
        }
        return visibility/16.0;
    }
    int samples=clamp(int(BrojeShadowSettings.w),8,24);
    float spin=0.0;
    if(mode==4) {
        // Contact hardening: the average depth of nearby blockers tells how far the
        // shadow travelled; the filter widens with that gap, as under a real sun disk.
        // The disk turns per texture pixel, so a wide penumbra is grain, not rings.
        spin=patternRotation(position,normal);
        float search=clamp(0.6/texel,4.0,32.0),blockers=0,blockerDepth=0;
        for(int i=0;i<8;i++) {
            float angle=(float(i)+0.5)*GOLDEN_ANGLE+spin;
            vec2 uv=shadow.xy+vec2(cos(angle),sin(angle))*sqrt((float(i)+0.5)/8.0)*search/resolution;
            float z=texelFetch(Sampler1,clamp(ivec2(uv*resolution),ivec2(0),ivec2(int(resolution)-1)),0).r;
            if(z<reference){blockers++;blockerDepth+=z;}
        }
        if(blockers==0)return sunFilter(shadow.xy);
        float gap=(reference-blockerDepth/blockers)*BrojeShadowMap.z; // blocks from blocker to receiver
        radius=clamp(gap*0.05/texel*radius,0.6,search);
    }
    // Soft: fixed spatial PCF in map texels, about one texel wide by default like Beryl.
    for(int i=0;i<24;i++) {
        if(i>=samples)break;
        float angle=float(i)*GOLDEN_ANGLE+spin;
        float r=sqrt((float(i)+0.5)/float(samples))*radius;
        vec2 uv=shadow.xy+vec2(cos(angle),sin(angle))*r/BrojeShadowMap.x;
        visibility+=bilinearPCF(uv,reference)*sunFilter(uv);
    }
    return visibility/float(samples);
}

vec3 mapPoint(ivec2 texel,out bool valid) {
    float z=texelFetch(Sampler1,texel,0).r;
    valid=z<0.9999;
    vec2 xy=vec2((float(texel.x)+0.5)/BrojeShadowMap.x*2-1,1-(float(texel.y)+0.5)/BrojeShadowMap.x*2);
    // Undo the radial distortion: r' = r/(rK+1-K)  =>  r = r'(1-K)/(1-r'K).
    float k=BrojeFlags.z,r=length(xy);
    xy*=(1.0-k)/max(1.0-r*k,1e-3);
    vec4 w=BrojeShadowInverse*vec4(xy,z,1);
    return w.xyz/w.w;
}

// One diffuse bounce of sunlight. Every texel of the sun's depth map is a sunlit
// surface; nearby texels act as small reflectors whose cosine-weighted light is
// summed at the receiver. The map is world-locked, so the result does not crawl.
float bounceLight(vec3 position,vec3 normal,vec2 center,int samples) {
    const float RADIUS=4.0, ALBEDO=0.4;
    int resolution=int(BrojeShadowMap.x);
    // The radius is in world blocks, so convert with the texel size at this point.
    float uvRadius=RADIUS/(localTexel(position)*BrojeShadowMap.x);
    // Each sample stands for this much reflecting area. A reflector closer than that
    // is not a point light: without the floor one sample hitting a player or a grass
    // blade next to the receiver outweighed all the others.
    float footprint=1.5707963*RADIUS*RADIUS/float(samples);
    // A fixed pattern paints every small reflector once per sample, as a row of
    // shifted bright copies on walls and ground. Turning it per texture pixel spreads
    // those copies into fine world-locked grain instead.
    float spin=patternRotation(position,normal);
    float sum=0;
    for(int i=0;i<24;i++) {
        if(i>=samples)break;
        float angle=(float(i)+0.5)*GOLDEN_ANGLE+spin;
        float r=sqrt((float(i)+0.5)/float(samples))*uvRadius;
        ivec2 t=clamp(ivec2((center+vec2(cos(angle),sin(angle))*r)*BrojeShadowMap.x),ivec2(0),ivec2(resolution-2));
        bool valid,validX,validY;
        vec3 q=mapPoint(t,valid);
        if(!valid)continue;
        vec3 ex=mapPoint(t+ivec2(1,0),validX)-q,ey=mapPoint(t+ivec2(0,1),validY)-q;
        // Silhouette edges have no reliable normal and would throw stray bright spots.
        if(!validX||!validY||dot(ex,ex)>0.25||dot(ey,ey)>0.25)continue;
        vec3 c=cross(ex,ey);
        if(dot(c,c)<1e-12)continue;
        vec3 reflector=normalize(c)*sign(dot(c,BrojeSunLight.xyz));
        // Blocks reflect with axis-aligned faces. Grass blades, flowers and turned mobs
        // have no broad face: they scatter light upward from where they stand, so they
        // never throw their silhouette sideways onto a wall.
        vec3 axis=abs(reflector);
        if(max(axis.x,max(axis.y,axis.z))<0.9)reflector=vec3(0,1,0);
        vec3 d=q-position;
        float dd=dot(d,d);
        if(dd<1e-4)continue;
        d*=inversesqrt(dd);
        sum+=max(dot(normal,d),0.0)*max(dot(reflector,-d),0.0)/max(dd,footprint);
    }
    return min(sum*ALBEDO*RADIUS*RADIUS/float(samples),1.0);
}

// Sky light arriving from the visible part of the sky dome: blue from the zenith,
// paler from the horizon, and warm light bounced off the sunlit ground from below.
vec3 skyIrradiance(vec3 normal) {
    const vec3 ZENITH=vec3(0.70,0.83,1.0), HORIZON=vec3(0.92,0.95,1.0), GROUND=vec3(1.0,0.86,0.70);
    return normal.y>=0.0?mix(HORIZON,ZENITH,normal.y):mix(HORIZON,GROUND,-normal.y);
}

float henyeyGreenstein(float cosine,float g) {
    float g2=g*g;
    return (1.0-g2)/(4.0*3.14159265*pow(max(1.0+g2-2.0*g*cosine,1e-3),1.5));
}

float fogRamp(float distance,float start,float end) {
    return distance<=start?0.0:distance>=end?1.0:(distance-start)/(end-start);
}

void main() {
    // Flat triangles: screen derivatives of the position give the exact face normal.
    vec3 face=cross(dFdx(relativePosition),dFdy(relativePosition));
    vec4 color=texture(Sampler0,texCoord0)*vertexColor;
    color=mix(FogColor*vec4(1,1,1,color.a),color,fadeFactor);
    if(color.a<AlphaCutout)discard;

    vec3 normal=dot(face,face)>1e-14?normalize(face):vec3(0,1,0);
    if(dot(normal,relativePosition)>0)normal=-normal;
    vec3 sun=BrojeSunLight.xyz;
    float NoL=dot(normal,sun);
    // Crossed plant quads: light them as thin two-sided foliage, as Beryl does.
    vec3 axis=abs(normal);
    bool plant=axis.y<0.35&&min(axis.x,axis.z)>0.3;
    if(plant&&NoL<0){normal=-normal;NoL=-NoL;}
    float lambert=plant?0.85:smoothstep(-0.15,0.35,NoL);

    int debug=int(BrojeFlags.x+0.5);
    float distance=length(relativePosition);
    bool covered=BrojeFlags.y>0.5&&distance<BrojeShadowSettings.z;
    vec3 shadow=toMap(relativePosition);
    vec3 raw=vec3(1);
    if(covered&&lambert>0)raw=sunVisibility(relativePosition,normal,NoL,plant,shadow);
    covered=covered&&insideMap(shadow);
    if(debug==2&&insideMap(shadow)){fragColor=vec4(vec3(texelFetch(Sampler1,ivec2(shadow.xy*BrojeShadowMap.x),0).r),1);return;}
    if(debug==3&&normal.y<0.98){fragColor=vec4(1,0,1,1);return;}
    if((debug==1||debug==3)&&covered&&NoL>0.02){fragColor=vec4(1-raw,1);return;}

    // Vanilla daylight is split into sky light and a direct sun share. Only surfaces
    // open to the sky (high sky light) receive the direct share, so caves, rooms and
    // overhangs keep their vanilla lighting instead of being darkened by the map.
    float fade=1-smoothstep(BrojeShadowSettings.z*0.7,BrojeShadowSettings.z,distance);
    vec3 visibility=mix(vec3(1),raw,fade);
    float direct=BrojeShadowSettings.x*BrojeSunLight.w*smoothstep(0.5,0.85,skyLight);
    float sky=1.0-direct;
    float tint=BrojeLighting.z*BrojeSunLight.w;
    vec3 sunColor=mix(vec3(1.0,0.58,0.34),vec3(1.0,0.96,0.9),smoothstep(0.03,0.45,sun.y));
    vec3 sunTint=mix(vec3(1),sunColor,tint);
    vec3 skyTint=mix(vec3(1),skyIrradiance(normal),tint);
    vec3 zenithTint=mix(vec3(1),skyIrradiance(vec3(0,1,0)),tint);

    float bounce=0,glow=0;
    int giSamples=int(BrojeLighting.w+0.5);
    if(giSamples>0&&direct>0&&covered) {
        if(BrojeLighting.x>0)bounce=bounceLight(relativePosition,normal,toMap(relativePosition).xy,giSamples)*BrojeLighting.x*fade;
#ifdef CUTOUT_LAYER
        // Plants and the sun-averted faces of leaves let light through (forward
        // scattering). Sunlit faces such as the ground never take this term: there the
        // test point would sit beside the surface and paint a shifted copy of its shadow.
        if(BrojeLighting.y>0&&(plant||NoL<0.0)) {
            // Test the light where it enters: just past a blade, or beyond a leaf block.
            vec3 through=toMap(relativePosition+sun*(plant?0.3:1.5));
            float lit=insideMap(through)?step(through.z-0.003/BrojeShadowMap.z,texelFetch(Sampler1,ivec2(through.xy*BrojeShadowMap.x),0).r):1.0;
            float forward=henyeyGreenstein(dot(normalize(relativePosition),sun),0.6)*4.0;
            glow=BrojeLighting.y*lit*min(forward,2.5)*fade;
        }
#endif
    }
    // A fully sunlit top face at midday keeps its vanilla color.
    vec3 reference=sky*zenithTint+direct;
    // Bounce only lifts shade toward full sunlight; it never brightens a lit surface.
    vec3 light=sky*skyTint+direct*sunTint*(min(visibility*lambert+bounce,max(vec3(1),visibility))+glow*0.35);
    vec3 factor=clamp(light/reference,vec3(0.15),max(vec3(1.35),visibility*1.35));
    // Torches and lava: block light dominates where sky light is weak; warm it up.
    float daylight=BrojeSunLight.w>0.0?skyLight:skyLight*0.3;
    float torch=clamp(blockLight-daylight,0.0,1.0)*BrojeLighting.z;
    factor*=mix(vec3(1),vec3(1.0,0.86,0.70),torch*0.7);
    color.rgb*=factor;

    float sphere=distance,cylinder=max(length(relativePosition.xz),abs(relativePosition.y));
    float fog=max(fogRamp(sphere,FogEnvironmentalStart,FogEnvironmentalEnd),fogRamp(cylinder,FogRenderDistanceStart,FogRenderDistanceEnd));
    fragColor=vec4(mix(color.rgb,FogColor.rgb,fog*FogColor.a),color.a);
}
