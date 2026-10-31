#version 450

layout(std140, binding = 0) uniform BrojeFogUniforms {
    mat4 BrojeInverseProjection;
    mat4 BrojeInverseView;
    vec4 BrojeCamera;       // wrapped world xyz, zero-to-one projection flag
    vec4 BrojeFog;          // extinction / block, clear distance, layer Y, height falloff
    vec4 BrojeVolume;       // heterogeneity, wind displacement, volume flag, max steps
    vec4 BrojeAtmosphere;   // biome/time-dependent scattering color, integration limit
    vec4 BrojeSun;          // world sun direction, daylight strength
    vec4 BrojeShaft;        // light shafts on, sun lobe strength, map distortion, opaque depth snapshot valid
    vec4 BrojeShaftRange;   // shadow distance in blocks, unused
    vec4 BrojeFogTarget;    // current render target width/height
    mat4 BrojeShadowMatrix; // camera-relative position -> sun map clip space
};
layout(binding = 1) uniform sampler2D Sampler0;
layout(binding = 2) uniform sampler2D Sampler1; // sun depth map
layout(binding = 3) uniform sampler2D Sampler2; // world depth before translucent terrain
layout(binding = 4) uniform sampler2D Sampler3; // reduced fog: transmittance, sunlight weight, ray end
layout(location = 0) out vec4 fragColor;

// Periodic, smooth 3D value noise, anchored in world space, never in screen space.
float hash3(vec3 cell) {
    cell = mod(cell, 64.0);
    vec3 h = fract(cell * vec3(0.1031, 0.11369, 0.13787));
    h += dot(h, h.yxz + 19.19);
    return fract((h.x + h.y) * h.z);
}

float noise3(vec3 p) {
    vec3 cell = floor(p);
    vec3 f = fract(p);
    // Quintic interpolation avoids visible derivative seams between cells.
    f = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);
    return mix(mix(mix(hash3(cell), hash3(cell + vec3(1,0,0)), f.x),
                   mix(hash3(cell + vec3(0,1,0)), hash3(cell + vec3(1,1,0)), f.x), f.y),
               mix(mix(hash3(cell + vec3(0,0,1)), hash3(cell + vec3(1,0,1)), f.x),
                   mix(hash3(cell + vec3(0,1,1)), hash3(cell + vec3(1,1,1)), f.x), f.y), f.z);
}

float heightDensity(float worldY) {
    return exp(-clamp((worldY - BrojeFog.z) / max(BrojeFog.w, 1.0), 0.0, 80.0));
}

float densityAt(vec3 worldPosition) {
    if (BrojeVolume.x <= 0.0) return heightDensity(worldPosition.y);
    vec3 wind = vec3(BrojeVolume.y, 0.0, BrojeVolume.y * 0.5);
    vec3 p = worldPosition + wind;
    float banks = noise3(p / 64.0) * 0.72 + noise3(p / 32.0 + vec3(13,7,23)) * 0.28;
    float variation = mix(1.0, 0.16 + 1.9 * smoothstep(0.18, 0.82, banks), BrojeVolume.x);
    return heightDensity(worldPosition.y) * variation;
}

// 1 where the sun reaches this camera-relative point, 0 behind a caster.
float sunReaches(vec3 relative) {
    vec4 clip = BrojeShadowMatrix * vec4(relative, 1.0);
    vec2 xy = clip.xy / (length(clip.xy) * BrojeShaft.z + (1.0 - BrojeShaft.z));
    vec2 uv = vec2(xy.x * 0.5 + 0.5, 0.5 - xy.y * 0.5);
    if (any(lessThanEqual(uv, vec2(0.0))) || any(greaterThanEqual(uv, vec2(1.0))) || clip.z < 0.0 || clip.z > 1.0) return 1.0;
    int resolution = textureSize(Sampler1, 0).x;
    ivec2 texel = clamp(ivec2(uv * float(resolution)), ivec2(0), ivec2(resolution - 1));
    float lit = step(clip.z - 0.0001, texelFetch(Sampler1, texel, 0).r);
    // Match terrain's range fade instead of jumping from shadow to full sun at the map edge.
    float fade = 1.0 - smoothstep(BrojeShaftRange.x * 0.7, BrojeShaftRange.x, length(relative));
    return mix(1.0, lit, fade);
}

// Exact exponential height integral for the inexpensive non-volumetric mode.
float heightIntegral(vec3 origin, vec3 direction, float begin, float end) {
    float length = max(end - begin, 0.0);
    float y0 = origin.y + direction.y * begin;
    float y1 = origin.y + direction.y * end;
    if (abs(direction.y) < 0.001) return length * heightDensity((y0 + y1) * 0.5);
    if (max(y0, y1) <= BrojeFog.z) return length;
    float crossing = clamp((BrojeFog.z - origin.y) / direction.y, begin, end);
    float upperBegin = direction.y > 0.0 ? crossing : begin;
    float upperEnd = direction.y > 0.0 ? end : crossing;
    float lowerLength = length - (upperEnd - upperBegin);
    float a = heightDensity(origin.y + direction.y * upperBegin);
    float b = heightDensity(origin.y + direction.y * upperEnd);
    return max(0.0, lowerLength + (a - b) * BrojeFog.w / direction.y);
}

vec4 viewAt(vec2 ndc, float depth) {
    float clipDepth = BrojeCamera.w > 0.5 ? depth : depth * 2.0 - 1.0;
    return BrojeInverseProjection * vec4(ndc, clipDepth, 1.0);
}

void main() {
    if (BrojeSun.w < -1.5) { fragColor = vec4(1, 0.15, 0.05, 1); return; }
    ivec2 size = textureSize(Sampler0, 0);
    ivec2 pixel = clamp(ivec2(gl_FragCoord.xy / BrojeFogTarget.xy * vec2(size)), ivec2(0), size - 1);
    // Exact nearest depth preserves fences, leaves and silhouettes; no depth filtering.
    float depth = texelFetch(Sampler0, pixel, 0).r;
    vec2 screenUV = (vec2(pixel) + 0.5) / vec2(size);
    vec2 ndc = vec2(screenUV.x * 2.0 - 1.0, 1.0 - screenUV.y * 2.0);
    vec4 view = viewAt(ndc, depth);
    // World-space face normal of the nearest surface, while control flow is uniform.
    vec3 nearest = view.xyz / (abs(view.w) < 1e-7 ? 1.0 : view.w);
    vec3 surfaceNormal = mat3(BrojeInverseView) * cross(dFdx(nearest), dFdy(nearest));
    if (BrojeShaft.w > 0.5) {
        float opaque = texelFetch(Sampler2, pixel, 0).r;
        // A translucent surface in front of the opaque world. Glass, ice and slime let
        // the fog behind them show through: the ray runs on to the opaque surface.
        // Water keeps its own surface as the end, or lakes would fill with haze; it is
        // the flat face that sits at 8/9 of a block, the height of still water.
        if (opaque > depth + 1e-7) {
            vec3 rayWorld = mat3(BrojeInverseView) * nearest;
            float surfaceY = fract(BrojeCamera.y + rayWorld.y);
            bool level = dot(surfaceNormal, surfaceNormal) > 1e-12 && abs(normalize(surfaceNormal).y) > 0.95;
            bool water = level && surfaceY > 0.80 && surfaceY < 0.96;
            if (!water) { depth = opaque; view = viewAt(ndc, depth); }
        }
    }
    // An infinite-far projection has w=0 at clear depth. At distant clouds/terrain,
    // depth quantization can round neighbouring samples onto that plane. They must
    // integrate the same air as their neighbours, never alternate with no fog.
    // Two finite points of the ray, not one point seen from the origin: view bobbing
    // shifts the eye inside the projection by about that point's own distance.
    vec4 rayNear = viewAt(ndc, 0.0);
    vec4 rayMiddle = viewAt(ndc, 0.5);
    vec3 viewRay = normalize(rayMiddle.xyz / rayMiddle.w - rayNear.xyz / rayNear.w);
    float surfaceDistance = view.w > 1e-7 ? length(view.xyz / view.w) : BrojeAtmosphere.w;
    if (BrojeSun.w < -0.5) { fragColor = vec4(depth, clamp(surfaceDistance / 128.0, 0.0, 1.0), clamp(BrojeInverseProjection[3][3] / 20.0, 0.0, 1.0), 1); return; }
    if (surfaceDistance < 1e-5 || isnan(surfaceDistance) || isinf(surfaceDistance)) {
        fragColor = vec4(0); return;
    }
    vec3 rayDirection = normalize(mat3(BrojeInverseView) * viewRay);
    // Every sample stays strictly in front of the nearest visible opaque/cutout surface.
    float rayEnd = min(max(surfaceDistance - 0.02, 0.0), BrojeAtmosphere.w);
    float rayBegin = min(max(BrojeFog.y, 0.0), rayEnd);
    if (rayEnd <= rayBegin || BrojeFog.x <= 0.0) {
#ifdef FOG_REDUCED
        fragColor = vec4(1.0, 0.0, rayEnd, 1.0);
#else
        fragColor = vec4(0);
#endif
        return;
    }

    float transmittance = 1.0;
    float sunlit = 0.0; // in-scattering weight of the samples the sun actually reaches
    bool resolved = false;
#ifdef FOG_RESOLVE
    // Bilateral reconstruction of the two integrated scalars. Phase and scattering
    // color are still evaluated at full resolution, keeping the solar glow sharp.
    ivec2 fogSize = textureSize(Sampler3, 0);
    vec2 fogPixel = screenUV * vec2(fogSize) - 0.5;
    ivec2 base = ivec2(floor(fogPixel));
    vec2 fraction = fract(fogPixel);
    vec2 integrated = vec2(0.0);
    float weightSum = 0.0;
    float tolerance = max(0.25, rayEnd * 0.015);
    for (int y = 0; y < 2; ++y) {
        for (int x = 0; x < 2; ++x) {
            vec3 sampleFog = texelFetch(Sampler3, clamp(base + ivec2(x,y), ivec2(0), fogSize - 1), 0).rgb;
            vec2 weightXY = mix(1.0 - fraction, fraction, vec2(x,y));
            float weight = weightXY.x * weightXY.y * max(0.0, 1.0 - abs(sampleFog.z - rayEnd) / tolerance);
            integrated += sampleFog.xy * weight;
            weightSum += weight;
        }
    }
    // Thin fences/leaves may have no matching reduced pixel: integrate those rays
    // at their exact depth instead of smearing the background fog over the edge.
    resolved = weightSum > 1e-5;
    if (resolved) {
        transmittance = clamp(integrated.x / weightSum, 0.0, 1.0);
        sunlit = clamp(integrated.y / weightSum, 0.0, 1.0 - transmittance);
    }
#endif
    if (!resolved && BrojeVolume.z > 0.5 && (BrojeVolume.x > 0.0 || BrojeShaft.x > 0.5)) {
        int steps = clamp(int(BrojeVolume.w), 16, 64);
        // Short rays need fewer steps; the configured count is a strict upper bound.
        steps = min(steps, max(4, int(ceil((rayEnd - rayBegin) / 3.0))));
#ifdef FOG_RESOLVE
        // Sparse edge lanes must not force an entire GPU wave through 32/64 steps.
        // Four segments retain the exact surface limit without a full second march.
        steps = min(steps, 4);
#endif
        float stepLength = (rayEnd - rayBegin) / float(steps);
        // Stable spatial jitter reduces sample bands without animated grain/history ghosts.
        float jitter = fract(52.9829189 * fract(dot(vec2(pixel), vec2(0.06711056, 0.00583715))));
        for (int i = 0; i < 64; ++i) {
            if (i >= steps || transmittance < 0.004) break;
            float distance = rayBegin + (float(i) + 0.35 + 0.3 * jitter) * stepLength;
            vec3 worldPosition = BrojeCamera.xyz + rayDirection * distance;
            float extinction = max(BrojeFog.x * densityAt(worldPosition), 0.0);
            // Beer-Lambert over a finite segment: stable at every sample count.
            float segment = exp(-min(extinction * stepLength, 80.0));
            float lit = BrojeShaft.x > 0.5 ? sunReaches(rayDirection * distance) : 1.0;
            sunlit += transmittance * (1.0 - segment) * lit;
            transmittance *= segment;
        }
    } else if (!resolved) {
        float opticalDepth = BrojeFog.x * heightIntegral(BrojeCamera.xyz, rayDirection, rayBegin, rayEnd);
        transmittance = exp(-clamp(opticalDepth, 0.0, 80.0));
        sunlit = 1.0 - transmittance;
    }

#ifdef FOG_REDUCED
    fragColor = vec4(transmittance, sunlit, rayEnd, 1.0);
    return;
#endif
    float cosine = clamp(dot(rayDirection, normalize(BrojeSun.xyz)), -1.0, 1.0);
    // Two Henyey-Greenstein lobes: a broad haze and a tight glow around the sun.
    float broad = (1.0 - 0.45 * 0.45) / pow(max(1.0 + 0.45 * 0.45 - 0.9 * cosine, 0.05), 1.5);
    float tight = (1.0 - 0.8 * 0.8) / pow(max(1.0 + 0.8 * 0.8 - 1.6 * cosine, 0.02), 1.5);
    float phase = min(broad, 3.0) * 0.6 + min(tight, 12.0) * 0.08;
    float opacity = clamp(1.0 - transmittance, 0.0, 0.996);
    vec3 atmosphere = clamp(BrojeAtmosphere.rgb, vec3(0.0), vec3(1.0));
    float sunHeight = normalize(BrojeSun.xyz).y;
    vec3 sunColor = mix(vec3(1.0, 0.62, 0.38), vec3(1.0, 0.95, 0.86), smoothstep(0.05, 0.5, sunHeight));
    // Ambient sky scattering everywhere, plus a sun lobe only where the sun reaches:
    // with light shafts on, terrain and tree shadows carve visible beams into the fog.
    float lobe = (BrojeShaft.x > 0.5 ? BrojeShaft.y : 0.12) * BrojeSun.w;
    // The source is bounded by the atmosphere color. Adding a second scattering
    // color to an already bright fog made distant banks clip to luminous white.
    float illumination = clamp(phase * lobe, 0.0, 1.0) * min(sunlit / max(opacity, 1e-5), 1.0);
    vec3 source = mix(atmosphere * 0.88, atmosphere * sunColor, illumination);
    vec3 scattering = source * opacity;
    fragColor = vec4(scattering, opacity);
}
