#version 450

layout(std140, binding = 0) uniform BrojeCloudUniforms {
    mat4 BrojeInverseProjection;
    mat4 BrojeInverseView;
    vec4 BrojeCamera;       // world xyz wrapped to the noise period, zero-to-one projection flag
    vec4 BrojeCloudShape;   // layer base Y, layer thickness, coverage 0..1, extinction per block
    vec4 BrojeCloudWind;    // wind offset x/z, detail evolution in detail cells, rain 0..1
    vec4 BrojeCloudMarch;   // primary steps, max distance, opaque depth snapshot valid, reserved
    vec4 BrojeCloudLight;   // world direction to the sun or moon, its strength
    vec4 BrojeCloudSun;     // direct light color, unused
    vec4 BrojeCloudAmbient; // sky light color, unused
    vec4 BrojeCloudHorizon; // aerial haze color, haze strength
    vec4 BrojeCloudTarget;  // current render target width/height
    vec4 BrojeCloudLayers;  // height spread of single clouds, upper deck coverage, weather evolution, style
    vec4 BrojeCloudFrame;   // reserved, reserved, shadow map range, shadow map plane Y
    vec4 BrojeCloudExtra;   // lower deck coverage, reserved, weather map centre X/Z
    vec4 BrojeCloudMotion;  // reserved XYZ, weather map range
    mat4 BrojePreviousViewProjection; // reserved identity, kept for the uniform layout
};
layout(binding = 1) uniform sampler2D Sampler0; // world depth
layout(binding = 2) uniform sampler2D Sampler1; // world depth before translucent terrain
layout(binding = 3) uniform sampler2D Sampler2; // direct light, sky light, transmittance, apparent cloud distance
layout(binding = 4) uniform sampler2D Sampler3; // reserved white texture
layout(binding = 5) uniform sampler2D Sampler4; // immutable periodic 3D noise atlas
layout(binding = 6) uniform sampler2D Sampler5; // three cached weather columns per map position
layout(location = 0) out vec4 fragColor;

// Every lattice is periodic over 61440 blocks (2^12 * 15), the period the camera and
// wind are wrapped to; each scale below divides it into a whole number of cells.
// The hashes take cells already wrapped: a noise lookup wraps its cell and the next
// one once, instead of once per corner.
float hash2(vec2 cell) {
    vec3 h = fract(vec3(cell.xyx) * vec3(0.1031, 0.1030, 0.0973));
    h += dot(h, h.yzx + 33.33);
    return fract((h.x + h.y) * h.z);
}

float hash3(vec3 cell) {
    vec3 h = fract(cell * vec3(0.1031, 0.11369, 0.13787));
    h += dot(h, h.yxz + 19.19);
    return fract((h.x + h.y) * h.z);
}

float noise2(vec2 p, float period) {
    vec2 a = mod(floor(p), period);
    vec2 b = mod(a + 1.0, period);
    vec2 f = fract(p);
    f = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);
    return mix(mix(hash2(a), hash2(vec2(b.x, a.y)), f.x),
               mix(hash2(vec2(a.x, b.y)), hash2(b), f.x), f.y);
}

float noise3(vec3 p, float period) {
    vec3 a = mod(floor(p), period);
    vec3 b = mod(a + 1.0, period);
    vec3 f = fract(p);
    f = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);
    return mix(mix(mix(hash3(a), hash3(vec3(b.x, a.y, a.z)), f.x),
                   mix(hash3(vec3(a.x, b.y, a.z)), hash3(vec3(b.x, b.y, a.z)), f.x), f.y),
               mix(mix(hash3(vec3(a.x, a.y, b.z)), hash3(vec3(b.x, a.y, b.z)), f.x),
                   mix(hash3(vec3(a.x, b.y, b.z)), hash3(b), f.x), f.y), f.z);
}

float remap(float value, float low, float high) {
    return clamp((value - low) / max(high - low, 1e-4), 0.0, 1.0);
}

// Cloud styles: 0 Minecraft (volumetric blocks on the vanilla 12-block grid),
// 1 cumulus fields, 2 realistic (sparse single clouds, big and small, towering).
int cloudStyle() { return int(BrojeCloudLayers.w + 0.5); }

// Three overlapping populations, evaluated as ONE medium. The lower banks reach
// into the main cumulus, whose towers reach into the upper banks.
struct Layer {
    float base;       // nominal bottom Y
    float thickness;
    float scale;      // 1, 1.5 for the upper deck, 0.75 for realistic clouds
    float coverage;
    float spread;     // how far single clouds sit above or below the nominal bottom
    float extinction;
    vec3 seed;        // whole-cell offset: a different pattern per deck
    float ceiling;    // tallest tower, in deck thicknesses
    float hang;       // how far big clouds may sink below the deck, in blocks
    bool realistic;   // single clouds instead of a cumulus field
};

Layer layerAt(int index) {
    float rain = BrojeCloudWind.w;
    if (index == 0) {
        bool realistic = cloudStyle() == 2;
        // Realistic clouds are 4/3 the size: 0.75 keeps every period a whole number.
        return Layer(BrojeCloudShape.x, BrojeCloudShape.y, realistic ? 0.75 : 1.0, clamp(BrojeCloudShape.z + rain * 0.3, 0.0, 1.0),
                BrojeCloudLayers.x, BrojeCloudShape.w, vec3(0.0),
                realistic ? 2.2 : 1.0, realistic ? BrojeCloudShape.y * 0.3 : 0.0, realistic);
    }
    if (index == 2) {
        float thickness = BrojeCloudShape.y * 0.8;
        return Layer(BrojeCloudShape.x - BrojeCloudShape.y * 0.55, thickness, 1.0,
                clamp(BrojeCloudExtra.x + rain * 0.2, 0.0, 1.0), BrojeCloudLayers.x * 0.65,
                BrojeCloudShape.w * 0.65, vec3(19.0, 5.0, 23.0), 1.65, 0.0, false);
    }
    float thickness = BrojeCloudShape.y * 0.65;
    return Layer(BrojeCloudShape.x + BrojeCloudShape.y * 1.05, thickness, 1.5,
            clamp(BrojeCloudLayers.y + rain * 0.2, 0.0, 1.0), BrojeCloudLayers.x * 0.5, BrojeCloudShape.w * 0.6,
            vec3(7.0, 3.0, 13.0), 1.25, 0.0, false);
}

// Bottom and top of everything a deck may hold.
vec2 layerBounds(Layer layer) {
    return vec2(layer.base - layer.spread - layer.hang - layer.thickness * 0.30,
                layer.base + layer.thickness * layer.ceiling * 1.35 + layer.spread);
}

// Realistic weather: mostly clear sky. Clouds gather only where the evolving
// weather field peaks; the coverage setting moves that peak. Deep inside such a
// region the cover is full and clouds merge into big masses; toward its rim only
// the strongest swells of the shape noise break through, as small separate puffs.
// The very strongest peaks grow into towers up through the upper deck, their bases
// sinking below the main one.
vec3 realisticWeather(vec3 p, Layer layer) {
    vec2 q = p.xz + BrojeCloudWind.xy;
    float evolving = noise3(vec3(q.x / 2048.0, BrojeCloudLayers.z, q.y / 2048.0), 30.0);
    float local = noise2(q / 640.0 + vec2(17.0, 41.0), 96.0);
    float weather = evolving * 0.7 + local * 0.3;
    float gather = smoothstep(0.62 - layer.coverage * 0.35, 0.82 - layer.coverage * 0.25, weather);
    float tower = smoothstep(0.7, 0.9, weather);
    float bottom = layer.base + (local * 0.7 + (1.0 - evolving) * 0.3 - 0.5) * 2.0 * layer.spread - tower * layer.hang;
    return vec3(gather * 1.15, mix(0.6, layer.ceiling, tower), bottom);
}

// The weather of a column: cover, tower height and the bottom of the cloud there.
// Its large octave also runs through time, so clouds do not only drift: they grow
// out of clear air, swell, merge with a neighbour and thin away again. The bottom
// varies too, one cloud hanging lower, the next floating higher.
vec3 weatherAt(vec3 p, Layer layer, vec3 mainWeather) {
    if (layer.coverage <= 0.0) return vec3(0.0, 1.0, layer.base);
    if (layer.realistic) return mainWeather;
    float k = layer.scale;
    vec2 q = (p.xz + BrojeCloudWind.xy) * k;
    float evolving = noise3(vec3(q.x / 2048.0, BrojeCloudLayers.z * k, q.y / 2048.0) + layer.seed, 30.0 * k);
    float local = noise2(q / 640.0 + vec2(17.0, 41.0) + layer.seed.xz, 96.0 * k);
    float weather = evolving * 0.65 + local * 0.35;
    float bottom = layer.base + (local * 0.7 + (1.0 - evolving) * 0.3 - 0.5) * 2.0 * layer.spread;
    // Satellite banks share the broad weather of the main deck, but have their
    // own local pattern. Dense weather can therefore join all three populations.
    float cover = mix(layer.coverage * mix(0.85, 1.9, weather), mainWeather.x * layer.coverage * 2.4, 0.45);
    return vec3(cover, mix(0.65, layer.ceiling, smoothstep(0.3, 0.75, weather)), bottom);
}

vec3 columnAt(vec3 p, int index) {
    vec2 uv = (p.xz - BrojeCloudExtra.zw) / BrojeCloudMotion.w + 0.5;
    if (any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0))))
        return weatherAt(p, layerAt(index), realisticWeather(p, layerAt(0)));
    // Half-texel inset keeps bilinear samples inside this layer's atlas tile.
    vec2 mapSize = vec2(textureSize(Sampler5, 0)) / vec2(3.0, 1.0);
    uv = clamp(uv, 0.5 / mapSize, 1.0 - 0.5 / mapSize);
    return textureLod(Sampler5, vec2((uv.x + float(index)) / 3.0, uv.y), 0.0).xyz;
}

// Two hardware-filtered atlas taps replace a 27-cell Worley search and its
// hashes at every ray/light sample. Padded slices prevent tile-edge bleeding.
vec4 volumeNoise(vec3 lattice) {
    vec3 uvw = fract(lattice / 8.0);
    float slice = uvw.z * 64.0 - 0.5;
    float z0 = mod(floor(slice), 64.0), z1 = mod(z0 + 1.0, 64.0);
    vec2 xy = 1.0 + uvw.xy * 64.0;
    vec2 tile0 = vec2(mod(z0, 8.0), floor(z0 / 8.0));
    vec2 tile1 = vec2(mod(z1, 8.0), floor(z1 / 8.0));
    return mix(textureLod(Sampler4, (tile0 * 66.0 + xy) / 528.0, 0.0),
               textureLod(Sampler4, (tile1 * 66.0 + xy) / 528.0, 0.0), fract(slice));
}

// Value noise sits on an axis-aligned grid, which shows as blocky, voxel-like folds
// once billows are contrast-stretched. Each octave is therefore turned by the 3-4-5
// triangle (cos 0.6, sin 0.8), each in a different plane. Being rational, the turn
// keeps the noise periodic: a camera wrap of 61440 blocks moves a turned lattice by
// whole multiples of 61440 / 5 blocks, so the periods below are 61440 / (5 * cell).
vec3 turnXZ(vec3 p) { return vec3(0.6 * p.x - 0.8 * p.z, p.y, 0.8 * p.x + 0.6 * p.z); }
vec3 turnXY(vec3 p) { return vec3(0.6 * p.x - 0.8 * p.y, 0.8 * p.x + 0.6 * p.y, p.z); }
vec3 turnYZ(vec3 p) { return vec3(p.x, 0.6 * p.y - 0.8 * p.z, 0.8 * p.y + 0.6 * p.z); }

// How deep in a fold between billows the last fully detailed sample lay, 0..1.
// Broad weather shapes the formation; 3D cellular lobes shape its silhouette.
// Surface relief moves both inward and outward, rather than merely shaving the
// top off a smooth height-field dome. The core stays continuous when flown into.
// A single world-space field: camera distance and ray spacing never deform it.
float cloudDensity(vec3 p, vec3 column, Layer layer) {
    float h = (p.y - column.z) / layer.thickness;
    if (h <= -0.30 || h >= column.y * 1.35 || column.x <= 0.0) return 0.0;
    float threshold = 1.0 - column.x;
    float k = layer.scale;
    vec3 s = vec3(p.x + BrojeCloudWind.x, p.y, p.z + BrojeCloudWind.y) * k + layer.seed * 192.0;
    // Preserve the large formation layout; only its small lobes use the atlas.
    float base = noise3(turnXZ(s) / 192.0, 64.0 * k) * 0.6;
    if (base + 0.4 <= threshold) return 0.0;
    vec4 shape = volumeNoise(turnYZ(s) / 64.0 + vec3(5.0, 9.0, 3.0));
    float lobes = smoothstep(0.1, 0.85, 1.0 - shape.g);
    float core = remap(base + mix(shape.r, lobes, 0.55) * 0.4, threshold, threshold + 0.3);
    if (core <= 0.0) return 0.0;
    float top = column.y * sqrt(core) * mix(0.52, 1.0, lobes);
    // Broad billows remain. Small displaced fluff was unstable while moving.
    float bottom = ((shape.r - 0.55) * 0.16 - (0.6 - shape.g) * 0.08) * sqrt(core);
    float edge = max(0.06, 2.0 / layer.thickness);
    float shell = min(top - h, h - bottom);
    return smoothstep(0.0, edge, shell) * mix(0.85, 1.0, shape.r);
}

vec3 sampleColumns[3];
bool columnReady[3];

// Extinction, height and fold occlusion. Max-union prevents doubled optical mass
// at a join; lighting uses the same combined medium as visibility and shadows.
vec3 volumeAt(vec3 p) {
    float extinction = 0.0, weight = 0.0, height = 0.0;
    for (int index = 0; index < 3; ++index) {
        columnReady[index] = false;
        Layer layer = layerAt(index);
        vec2 bounds = layerBounds(layer);
        if (layer.coverage <= 0.0 || p.y <= bounds.x || p.y >= bounds.y) continue;
        vec3 column = columnAt(p, index);
        sampleColumns[index] = column; columnReady[index] = true;
        float density = cloudDensity(p, column, layer) * layer.extinction;
        extinction = max(extinction, density);
        height += clamp((p.y - column.z) / (layer.thickness * column.y), 0.0, 1.0) * density;
        weight += density;
    }
    return vec3(extinction, height / max(weight, 1e-5), 0.0);
}

vec2 volumeBounds() {
    vec2 bounds = layerBounds(layerAt(0));
    for (int i = 1; i < 3; ++i) {
        Layer layer = layerAt(i);
        if (layer.coverage <= 0.0) continue;
        vec2 other = layerBounds(layer);
        bounds = vec2(min(bounds.x, other.x), max(bounds.y, other.y));
    }
    return bounds;
}

float phaseHG(float cosine, float g) {
    float g2 = g * g;
    return (1.0 - g2) / pow(max(1.0 + g2 - 2.0 * g * cosine, 1e-3), 1.5);
}

// Weather varies over hundreds of blocks; reuse the primary sample's columns
// for the short light march, including banks entered by the light above/below it.
float lightOpticalDepth(vec3 p, vec3 light, out float near) {
    vec3 columns[3];
    for (int index = 0; index < 3; ++index) {
        Layer layer = layerAt(index);
        columns[index] = columnReady[index] ? sampleColumns[index] : columnAt(p, index);
    }
    float stepLength = BrojeCloudShape.y * 0.045;
    float depth = 0.0, travelled = 0.0;
    near = 0.0;
    int steps = BrojeCloudMarch.x <= 32.0 ? 3 : 4;
    for (int i = 0; i < 4; ++i) {
        if (i >= steps) break;
        vec3 at = p + light * (travelled + stepLength * 0.5);
        float extinction = 0.0;
        for (int index = 0; index < 3; ++index) {
            Layer layer = layerAt(index);
            if (layer.coverage <= 0.0) continue;
            extinction = max(extinction, cloudDensity(at, columns[index], layer) * layer.extinction);
        }
        depth += extinction * stepLength;
        if (i == 0) near = depth;
        travelled += stepLength;
        stepLength *= 3.0;
    }
    return depth;
}

vec4 viewAt(vec2 ndc, float depth) {
    float clipDepth = BrojeCamera.w > 0.5 ? depth : depth * 2.0 - 1.0;
    return BrojeInverseProjection * vec4(ndc, clipDepth, 1.0);
}

// Distance range of the ray inside the slab [bottom, top].
vec2 slabRange(vec3 origin, vec3 direction, float bottom, float top) {
    if (abs(direction.y) < 1e-5) {
        return origin.y > bottom && origin.y < top ? vec2(0.0, 1e9) : vec2(-1.0);
    }
    float a = (bottom - origin.y) / direction.y;
    float b = (top - origin.y) / direction.y;
    return vec2(max(min(a, b), 0.0), max(a, b));
}

// Shared by the whole volume of one ray.
vec3 rayDirection, sunDirection;
float phase0, phase1, phase2, forwardness;
float transmittance = 1.0, direct = 0.0, ambient = 0.0;
float distanceSum = 0.0;

// Minecraft style: blocks of 12 x 4 x 12 like the vanilla layer, from a blobby
// pattern of the same character. The grid repeats every 5120 cells (61440 blocks).
const float BLOCK = 12.0;
const float BLOCK_HEIGHT = 4.0;

bool blockFilled(vec2 cell) {
    vec2 id = mod(cell, 5120.0);
    float pattern = noise2(id / 16.0, 320.0) * 0.5 + noise2(id / 4.0 + vec2(7.0, 3.0), 1280.0) * 0.35 + hash2(id) * 0.15;
    return pattern > 1.0 - clamp(BrojeCloudShape.z + BrojeCloudWind.w * 0.3, 0.0, 1.0) * 0.95;
}

float blockExtinction() { return BrojeCloudShape.w / 0.15 * 0.35; }

// Walks the ray cell by cell through the block layer (an exact grid traversal, so no
// block is ever skipped or sliced). Each filled cell adds the light of the face the
// ray entered through: tops bright, sides by the sun, undersides darker.
void marchBlocks(float rayEnd) {
    float bottom = BrojeCloudShape.x;
    vec2 range = slabRange(BrojeCamera.xyz, rayDirection, bottom, bottom + BLOCK_HEIGHT);
    float maxDistance = BrojeCloudMarch.y;
    float t = range.x;
    float end = min(min(range.y, rayEnd), maxDistance);
    if (range.y <= 0.0 || end <= t) return;
    vec2 direction = rayDirection.xz;
    vec2 start = BrojeCamera.xz + BrojeCloudWind.xy + direction * t;
    vec2 cell = floor(start / BLOCK);
    vec2 stepDir = sign(direction);
    vec2 inverse = 1.0 / max(abs(direction), vec2(1e-6));
    vec2 delta = BLOCK * inverse;
    vec2 edge = (cell + max(stepDir, 0.0)) * BLOCK;
    vec2 next = vec2(stepDir.x != 0.0 ? t + abs(edge.x - start.x) * inverse.x : 1e9,
                     stepDir.y != 0.0 ? t + abs(edge.y - start.y) * inverse.y : 1e9);
    vec3 normal = range.x > 0.0 ? vec3(0.0, -sign(rayDirection.y), 0.0) : vec3(0.0, 1.0, 0.0);
    float extinction = blockExtinction();
    bool previous = false;
    for (int i = 0; i < 256; ++i) {
        float leave = min(min(next.x, next.y), end);
        bool filled = blockFilled(cell);
        if (filled) {
            float fade = 1.0 - smoothstep(maxDistance * 0.6, maxDistance, t);
            float stepTransmittance = exp(-extinction * (leave - t) * fade);
            float sun = 0.25 + 0.75 * max(dot(normal, sunDirection), 0.0);
            float sky = normal.y > 0.5 ? 1.0 : normal.y < -0.5 ? 0.55 : 0.8;
            float weight = transmittance * (1.0 - stepTransmittance);
            distanceSum += weight * (t + leave) * 0.5;
            direct += weight * sun;
            ambient += weight * sky;
            transmittance *= stepTransmittance;
            if (transmittance < 0.02) break;
        }
        previous = filled;
        if (leave >= end) break;
        // Only a face between clear air and a block is a face; inside a run of filled
        // cells the face the ray came in through stays.
        if (next.x < next.y) {
            t = next.x; next.x += delta.x; cell.x += stepDir.x;
            if (!previous) normal = vec3(-stepDir.x, 0.0, 0.0);
        } else {
            t = next.y; next.y += delta.y; cell.y += stepDir.y;
            if (!previous) normal = vec3(0.0, 0.0, -stepDir.y);
        }
    }
}

// Cloud shadows: sunlight that gets through every deck above one point of the map
// plane, a horizontal plane below all clouds. Terrain projects itself onto that
// plane along the same light direction and reads the transmittance here.
vec3 shadowLight() {
    vec3 light = normalize(BrojeCloudLight.xyz);
    return normalize(vec3(light.x, max(light.y, 0.2), light.z));
}

float shadowTransmittance(vec3 start) {
    vec3 light = shadowLight();
    if (cloudStyle() == 0) {
        float rise = (BrojeCloudShape.x + BLOCK_HEIGHT * 0.5 - start.y) / light.y;
        vec2 at = start.xz + light.xz * rise + BrojeCloudWind.xy;
        return blockFilled(floor(at / BLOCK)) ? exp(-blockExtinction() * BLOCK_HEIGHT / light.y) : 1.0;
    }
    vec2 bounds = volumeBounds();
    float begin = max((bounds.x - start.y) / light.y, 0.0);
    float end = (bounds.y - start.y) / light.y;
    float stepLength = max(end - begin, 0.0) / 12.0;
    float depth = 0.0;
    for (int i = 0; i < 12; ++i) {
        vec3 p = start + light * (begin + (float(i) + 0.5) * stepLength);
        depth += volumeAt(p).x * stepLength;
    }
    return exp(-min(depth, 60.0));
}

// One front-to-back march, including overlapping banks. No ordering switch at a
// layer boundary and no far bank composited in front of nearby cloud interiors.
void marchVolume(float rayEnd, float configured) {
    vec2 bounds = volumeBounds();
    vec2 range = slabRange(BrojeCamera.xyz, rayDirection, bounds.x, bounds.y);
    float begin = range.x, end = min(range.y, rayEnd);
    if (range.y <= 0.0 || end <= begin) return;
    float fineStep = clamp(BrojeCloudShape.y * 1.2 / configured, 2.0, 6.0);
    float coarseStep = max(fineStep, BrojeCloudShape.y * 0.2);
    float maxDistance = BrojeCloudMarch.y;
    float t = begin, previousClear = begin, refineUntil = begin;
    bool inside = false;
    int clear = 0;
    float toLight = 0.0, near = 0.0;
    for (int i = 0; i < 256; ++i) {
        if (t >= end || transmittance < 0.01) break;
        float stepLength = min(inside ? fineStep : coarseStep, end - t);
        float sampleT = t + stepLength * (inside ? 0.25 : 0.5);
        vec3 p = BrojeCamera.xyz + rayDirection * sampleT;
        vec3 medium = volumeAt(p);
        // Fixed quadrature replaces screen-space sampling noise.
        if (inside && transmittance > 0.5) {
            float secondT = t + stepLength * 0.75;
            medium = (medium + volumeAt(BrojeCamera.xyz + rayDirection * secondT)) * 0.5;
            sampleT = t + stepLength * 0.5;
            p = BrojeCamera.xyz + rayDirection * sampleT;
        }
        if (medium.x <= 0.0001) {
            if (!inside) previousClear = t;
            // Finish the segment that found cloud before returning to coarse
            // steps; otherwise backtracking can repeatedly revisit clear air.
            if (inside && ++clear >= 3 && t + stepLength >= refineUntil) inside = false;
            t += stepLength;
            continue;
        }
        if (!inside) {
            inside = true;
            clear = 0;
            // The last coarse clear sample could have skipped the front edge.
            // Revisit it finely so that the edge cannot pop in while moving.
            refineUntil = t + stepLength;
            t = max(begin, previousClear);
            continue;
        }
        clear = 0;
        float extinction = medium.x * (1.0 - smoothstep(maxDistance * 0.6, maxDistance, sampleT));
        float stepTransmittance = exp(-min(extinction * stepLength, 60.0));
        toLight = lightOpticalDepth(p, sunDirection, near);
        float powder = mix(1.0 - exp(-extinction * BrojeCloudShape.y * 0.4), 1.0, forwardness * forwardness);
        float sun = exp(-toLight) * phase0
                + 0.6 * exp(-toLight * 0.3) * phase1
                + 0.35 * exp(-toLight * 0.1) * phase2;
        // A diffuse floor approximates light scattered in from the rest of the
        // cloud. A clear-day interior must not turn into an unlit storm cavity.
        sun += 0.12 * (1.0 - exp(-toLight * 0.2));
        float sky = mix(0.78, 1.0, exp(-extinction * BrojeCloudShape.y * (1.0 - medium.y) * 0.25))
                * (0.85 + 0.15 * medium.y);
        sky *= mix(0.85, 1.0, exp(-near));
        float weight = transmittance * (1.0 - stepTransmittance);
        distanceSum += weight * sampleT;
        direct += weight * sun * powder * 0.5;
        ambient += weight * sky;
        transmittance *= stepTransmittance;
        t += stepLength;
    }
}

// Depth guide for reconstruction. It is sampled at the exact reduced ray centre,
// rather than reusing the cloud distance (which lies in front of the surface).
float sceneEnd(vec2 uv) {
    ivec2 size = textureSize(Sampler0, 0);
    ivec2 pixel = clamp(ivec2(uv * vec2(size)), ivec2(0), size - 1);
    float depth = texelFetch(Sampler0, pixel, 0).r;
    if (BrojeCloudMarch.z > 0.5) depth = max(depth, texelFetch(Sampler1, pixel, 0).r);
    vec4 view = viewAt(vec2(uv.x * 2.0 - 1.0, 1.0 - uv.y * 2.0), depth);
    return view.w > 1e-7 ? min(length(view.xyz / view.w), BrojeCloudMarch.y) : BrojeCloudMarch.y;
}

void main() {
#ifdef CLOUDS_WEATHER
    int index = int(gl_FragCoord.x) / 192;
    vec2 uv = vec2(mod(gl_FragCoord.x, 192.0) / 192.0, gl_FragCoord.y / 192.0);
    vec3 at = vec3(BrojeCloudExtra.z + (uv.x - 0.5) * BrojeCloudMotion.w, 0.0,
                   BrojeCloudExtra.w + (uv.y - 0.5) * BrojeCloudMotion.w);
    fragColor = vec4(weatherAt(at, layerAt(index), realisticWeather(at, layerAt(0))), 1.0);
    return;
#endif
#ifdef CLOUDS_SHADOW
    // One texel of the cloud shadow map, centred on the camera.
    vec2 mapUV = gl_FragCoord.xy / BrojeCloudTarget.xy;
    vec3 start = vec3(BrojeCamera.x + (mapUV.x - 0.5) * BrojeCloudFrame.z, BrojeCloudFrame.w,
                      BrojeCamera.z + (mapUV.y - 0.5) * BrojeCloudFrame.z);
    fragColor = vec4(shadowTransmittance(start), 0.0, 0.0, 1.0);
    return;
#endif
    ivec2 size = textureSize(Sampler0, 0);
    ivec2 pixel = clamp(ivec2(gl_FragCoord.xy / BrojeCloudTarget.xy * vec2(size)), ivec2(0), size - 1);
    float depth = texelFetch(Sampler0, pixel, 0).r;
    // Glass, ice and water hide nothing behind them; the solid world does.
    if (BrojeCloudMarch.z > 0.5) depth = max(depth, texelFetch(Sampler1, pixel, 0).r);
    // The ray goes through this pixel's exact centre, in whatever resolution is being
    // drawn; only the depth comes from the nearest full-resolution texel. Taking the
    // ray from that texel's centre instead put reduced pixels a quarter pixel off, and
    // the history crept by that quarter every frame: smeared, stepped stripes.
    vec2 screenUV = gl_FragCoord.xy / BrojeCloudTarget.xy;
    vec2 ndc = vec2(screenUV.x * 2.0 - 1.0, 1.0 - screenUV.y * 2.0);
    vec4 view = viewAt(ndc, depth);
    // The direction joins two finite points of the pixel's ray (infinite-far depth
    // gives w=0). Never one point seen from the origin: view bobbing shifts the eye
    // inside the projection by about as much as that point's own tiny distance, and
    // the clouds then shook and smeared with every step.
    vec4 rayNear = viewAt(ndc, 0.0);
    vec4 rayMiddle = viewAt(ndc, 0.5);
    vec3 direction = normalize(mat3(BrojeInverseView) * normalize(rayMiddle.xyz / rayMiddle.w - rayNear.xyz / rayNear.w));
    float maxDistance = BrojeCloudMarch.y;
    float surface = view.w > 1e-7 ? length(view.xyz / view.w) : maxDistance;
    if (isnan(surface) || isinf(surface)) surface = maxDistance;
    float rayEnd = min(surface, maxDistance);

#if defined(CLOUDS_FILTER)
    // Current-frame spatial filtering only. A single cloud depth cannot reproject
    // a volume containing several depths without swimming during camera motion.
    ivec2 own = ivec2(gl_FragCoord.xy);
    ivec2 cloudSize = textureSize(Sampler2, 0);
    vec4 current = texelFetch(Sampler2, own, 0);
    float tolerance = max(8.0, current.w * 0.15);
    vec3 filtered = vec3(0.0);
    float filterWeight = 0.0;
    float surfaceTolerance = max(1.0, rayEnd * 0.02);
    for (int y = -1; y <= 1; ++y) {
        for (int x = -1; x <= 1; ++x) {
            ivec2 tap = clamp(own + ivec2(x, y), ivec2(0), cloudSize - 1);
            vec4 around = texelFetch(Sampler2, tap, 0);
            // Clear sky has no cloud distance; retain it at cloud silhouettes.
            if (abs(around.w - current.w) > tolerance && current.z < 0.5 && around.z < 0.5) continue;
            // A compact filter in reduced resolution removes the fixed spatial
            // sampling grain. Its one-texel radius retains the 3D billow relief.
            float spatial = float((x == 0 ? 2 : 1) * (y == 0 ? 2 : 1));
            // Cloud distance cannot distinguish clear sky from a foreground
            // leaf. Use actual scene depth so the filter never fills silhouettes
            // with neighbouring cloud light.
            float surfaceGap = abs(sceneEnd((vec2(tap) + 0.5) / vec2(cloudSize)) - rayEnd);
            spatial *= max(0.0, 1.0 - surfaceGap / surfaceTolerance);
            filtered += around.xyz * spatial;
            filterWeight += spatial;
        }
    }
    current.xyz = filtered / max(filterWeight, 1.0);
    fragColor = current;
#elif defined(CLOUDS_RESOLVE)
    // Depth-aware upsampling: only reduced pixels that ended where this one ends
    // (sky with sky, mountain with mountain) contribute. A 2x2 reconstruction keeps
    // billows crisp and avoids 16 cloud + 32 depth reads per full-screen pixel.
    ivec2 cloudSize = textureSize(Sampler2, 0);
    vec2 cloudPixel = screenUV * vec2(cloudSize) - 0.5;
    ivec2 base = ivec2(floor(cloudPixel));
    vec3 sum = vec3(0.0);
    float weightSum = 0.0;
    vec3 closest = vec3(0.0, 0.0, 1.0);
    float closestGap = 1e9;
    float tolerance = max(1.0, rayEnd * 0.02);
    for (int y = 0; y < 2; ++y) {
        for (int x = 0; x < 2; ++x) {
            ivec2 tap = base + ivec2(x, y);
            ivec2 validTap = clamp(tap, ivec2(0), cloudSize - 1);
            vec4 cloud = texelFetch(Sampler2, validTap, 0);
            vec2 weightXY = max(vec2(0.0), 1.0 - abs(vec2(tap) - cloudPixel));
            float gap = abs(sceneEnd((vec2(validTap) + 0.5) / vec2(cloudSize)) - rayEnd);
            float weight = weightXY.x * weightXY.y * max(0.0, 1.0 - gap / tolerance);
            sum += cloud.xyz * weight;
            weightSum += weight;
            if (gap < closestGap) { closestGap = gap; closest = cloud.xyz; }
        }
    }
    // Thin silhouettes without a matching neighbour take the closest ray end.
    vec3 integrated = weightSum > 1e-5 ? sum / weightSum : closest;
    float transmittance = clamp(integrated.z, 0.0, 1.0);
    vec3 color = BrojeCloudSun.rgb * integrated.x + BrojeCloudAmbient.rgb * integrated.y;
    // Low clouds near the horizon sink into the same haze as the sky behind them.
    vec2 bounds = cloudStyle() == 0 ? vec2(BrojeCloudShape.x, BrojeCloudShape.x + BLOCK_HEIGHT) : volumeBounds();
    vec2 range = slabRange(BrojeCamera.xyz, direction, bounds.x, bounds.y);
    float approach = max(range.x, 0.0);
    // Haze belongs to distant air. Within the volume it must never paint an
    // angular horizon stripe through the nearby cloud.
    float haze = BrojeCloudHorizon.w * (1.0 - exp(-approach * 0.0004))
            * (1.0 - smoothstep(0.0, 0.22, abs(direction.y)));
    color = mix(color, BrojeCloudHorizon.rgb * (1.0 - transmittance), haze);
    // Soft shoulder instead of clipping the silver lining to flat white.
    vec3 over = max(color - 0.75, 0.0);
    color = min(color, 0.75 + over / (1.0 + 4.0 * over));
    fragColor = vec4(max(color, 0.0), transmittance);
#else
    if (BrojeCloudShape.z <= 0.0 && BrojeCloudLayers.y <= 0.0 && BrojeCloudExtra.x <= 0.0 && BrojeCloudWind.w <= 0.0) {
        fragColor = vec4(0.0, 0.0, 1.0, rayEnd);
        return;
    }
    // Spatial decorrelation, with no animated offset. The geometry and lighting
    // must be invariant when wind, weather and the camera are stationary.
    rayDirection = direction;
    sunDirection = normalize(BrojeCloudLight.xyz);
    float cosine = dot(direction, sunDirection);
    // Three scattering orders: each deeper order is dimmer, sees less extinction and
    // a broader phase. This replaces the dark, flat look of single scattering.
    phase0 = mix(phaseHG(cosine, -0.2), phaseHG(cosine, 0.6), 0.6);
    phase1 = mix(phaseHG(cosine, -0.15), phaseHG(cosine, 0.35), 0.6);
    phase2 = mix(phaseHG(cosine, -0.1), phaseHG(cosine, 0.2), 0.6);
    forwardness = cosine * 0.5 + 0.5;
    if (cloudStyle() == 0) {
        marchBlocks(rayEnd);
    } else {
        marchVolume(rayEnd, BrojeCloudMarch.x);
    }
    // Preserve continuous Beer-Lambert opacity, including thin wisps.
    transmittance = clamp(transmittance, 0.0, 1.0);
    float apparentDistance = 1.0 - transmittance > 0.001 ? distanceSum / max(1.0 - transmittance, 1e-5) : rayEnd;
    fragColor = vec4(direct * BrojeCloudLight.w, ambient, transmittance, apparentDistance);
#endif
}
