#version 450

// Volumetric clouds of Quasar 26.1.2 (cloud_settings, cloud_density, cloud_lighting,
// cloud_raymarch and cloud_ground_shadow), inside Broje's own passes: a reduced
// resolution march, a current-frame filter, a depth-aware composite and the terrain
// cloud shadow map, plus a sky hemisphere of clouds for water reflections. The natural
// cloud map and the fast height-volume sampling come from MakeUp Ultra Fast 9.5b by
// Javier Garduno (GNU LGPL v3.0).
//
// Positions are camera-relative world space: the camera is at the origin, +Y is up.

layout(std140, binding = 0) uniform BrojeCloudUniforms {
    mat4 BrojeInverseProjection;
    mat4 BrojeInverseView;
    vec4 BrojeCamera;       // cloud map origin x/z (camera plus wind, wrapped), camera Y, zero-to-one projection flag
    vec4 BrojeCloudShape;   // layer base Y, layer thickness, coverage 0..1, size (1 = 100%)
    vec4 BrojeCloudTime;    // formation clock 0..4, rain 0..1, thunder 0..1, opaque depth snapshot valid
    vec4 BrojeCloudMarch;   // primary steps at the horizon, at the zenith, light steps, reserved
    vec4 BrojeCloudSun;     // world direction to the sun (below the horizon at night), night 0..1
    vec4 BrojeCloudSky;     // sky radiance, sunlight visibility through the weather
    vec4 BrojeCloudHorizon; // aerial haze color, haze strength
    vec4 BrojeCloudTarget;  // current render target width/height
    vec4 BrojeCloudShadowLight;  // shadow light direction, unused
    vec4 BrojeCloudShadowArea; // reserved, reserved, shadow map range, shadow map plane Y
    vec4 BrojeCloudLook;    // density (1 = 100%), airiness 0..1, relief 0..4, reserved
};
layout(binding = 1) uniform sampler2D Sampler0; // world depth
layout(binding = 2) uniform sampler2D Sampler1; // world depth before translucent terrain
layout(binding = 3) uniform sampler2D Sampler2; // previous pass: premultiplied cloud light, transmittance
layout(binding = 4) uniform sampler2D Sampler3; // natural repeating cloud map with linear-density mipmaps
layout(location = 0) out vec4 fragColor;

#define CloudNoiseTex Sampler3

// ---- Settings (cloud_settings.glsl) ---------------------------------
// Coverage, size, height, wind, density, airiness, relief and the step budget are
// settings; the rest keeps Quasar's own defaults.
const float CLOUD_CLUSTERING = 0.50;
const float CLOUD_DETAIL = 1.0;
const float CLOUD_SMOOTHNESS = 0.78;
#define CLOUD_DENSITY BrojeCloudLook.x
#define CLOUD_AIRINESS BrojeCloudLook.y
#define CLOUD_RELIEF BrojeCloudLook.z   // Flat(0), Low(1), Medium(2), High(3), Realistic(4)

const float CLOUDS_MAX_DIST = 6000.0;
const float CLOUDS_COVERAGE_FREQ = 0.00065;
const float CLOUDS_EXTINCTION = 0.045;
const float CLOUDS_SUN_STRENGTH = 0.72;
const float CLOUDS_AMBIENT_STRENGTH = 0.72;
const float CLOUDS_PHASE_G = 0.72;
const float CLOUDS_MAX_OPTICAL_DEPTH = 3.912023005;

// Quasar's CameraPos.xz plus its wind offset, kept small and wrapped on the CPU.
#define CloudMapOrigin BrojeCamera.xz

float cloudRainAmount() { return clamp(BrojeCloudTime.y, 0.0, 1.0); }
float cloudThunderAmount() { return clamp(BrojeCloudTime.z, 0.0, 1.0); }

const mat2 CN_ROT2 = mat2(0.80, 0.60, -0.60, 0.80);
const mat2 CLOUD_PATTERN_ROT_A = mat2(0.819, 0.574, -0.574, 0.819);
const mat2 CLOUD_PATTERN_ROT_B = mat2(0.906, -0.423, 0.423, 0.906);

float g_cloudMapLod = 1.5;

float sampleCloudMap(sampler2D cloudTexture, vec2 uv) {
    return textureLod(cloudTexture, uv, g_cloudMapLod).r;
}

float cloudMapLodForStep(float stepLength, float frequency) {
    float texelSpan = stepLength * frequency * float(textureSize(CloudNoiseTex, 0).x);
    return clamp(log2(max(texelSpan * 0.45, 1.0)), 1.5, 3.5);
}

// Four spatial layouts cross-fade: masses dissolve while new ones condense elsewhere.
// The clock is advanced (and wrapped to its four-layout cycle) on the CPU.
float cloudPatternClock() { return BrojeCloudTime.x; }

int cloudPatternIndex() { return int(mod(floor(cloudPatternClock()), 4.0)); }

float cloudPatternBlend() { return smoothstep(0.48, 0.98, fract(cloudPatternClock())); }

vec2 cloudPatternCoord(vec2 uv, int pattern) {
    if (pattern == 1) return CLOUD_PATTERN_ROT_A * (uv * 0.78) + vec2(0.173, -0.291);
    if (pattern == 2) return CLOUD_PATTERN_ROT_B * (uv * 1.27) + vec2(-0.347, 0.119);
    if (pattern == 3) return CLOUD_PATTERN_ROT_A * (uv * 0.53) + vec2(0.411, 0.367);
    return uv;
}

float sampleCloudPattern(sampler2D cloudTexture, vec2 uv) {
    int currentPattern = cloudPatternIndex();
    int nextPattern = (currentPattern + 1) % 4;
    float currentField = sampleCloudMap(cloudTexture, cloudPatternCoord(uv, currentPattern));
    float nextField = sampleCloudMap(cloudTexture, cloudPatternCoord(uv, nextPattern));
    return mix(currentField, nextField, cloudPatternBlend());
}

float cloudPatternThresholdOffsetFor(int pattern) {
    if (pattern == 0) return 0.025;
    if (pattern == 1) return 0.000;
    if (pattern == 2) return -0.025;
    return -0.045;
}

float cloudAmountThresholdOffset() {
    int currentPattern = cloudPatternIndex();
    int nextPattern = (currentPattern + 1) % 4;
    return mix(cloudPatternThresholdOffsetFor(currentPattern),
               cloudPatternThresholdOffsetFor(nextPattern), cloudPatternBlend());
}

float cloudLayerThickness() { return clamp(BrojeCloudShape.y, 10.0, 160.0); }
float cloudCoverageSetting() { return clamp(BrojeCloudShape.z, 0.05, 0.95); }
float cloudClusteringSetting() { return CLOUD_CLUSTERING; }
float cloudSizeSetting() { return clamp(BrojeCloudShape.w, 0.35, 8.0); }
// Sublinear scaling preserves useful nearby shapes for very big clouds.
float cloudCoverageFrequency() { return CLOUDS_COVERAGE_FREQ / sqrt(cloudSizeSetting()); }
float cloudDensitySetting() { return clamp(CLOUD_DENSITY * 0.75, 0.12, 1.6); }
float cloudDetailSetting() { return CLOUD_DETAIL; }
float cloudAirinessSetting() { return clamp(CLOUD_AIRINESS, 0.0, 1.0); }
float cloudSmoothnessSetting() { return CLOUD_SMOOTHNESS; }
float cloudReliefSetting() { return clamp(floor(CLOUD_RELIEF + 0.5), 0.0, 4.0) * 0.25; }
float cloudReliefBumpScale() { return step(1.0, floor(CLOUD_RELIEF + 0.5)); }

// A broad field moves whole cloud bodies up or down; unrelated islands get
// different offsets. It travels with the clouds without vertical swimming.
float cloudBankHeightOffset(sampler2D cloudTexture, vec2 coverageUv) {
    const mat2 scatterRotation = mat2(0.384, 0.923, -0.923, 0.384);
    vec2 scatterUv = scatterRotation * (coverageUv * 0.47) + vec2(0.317, -0.581);
    float field = textureLod(cloudTexture, scatterUv, 2.0).r;
    float scatter = smoothstep(0.23, 0.77, field) * 2.0 - 1.0;
    return scatter * mix(0.70, 1.10, cloudReliefSetting());
}

// Conservative bounds include displacement, independent lobes and edge filters.
float cloudEnvelopeMinAltitude() { return -3.8; }
float cloudEnvelopeMaxAltitude() { return 5.0; }

// ---- Density (cloud_density.glsl) ------------------------------------
float g_cloudBaseRel = 0.0;

vec2 cloudCoverageCoord(vec2 relativeXZ) {
    return (CloudMapOrigin + relativeXZ) * cloudCoverageFrequency();
}

float sampleNaturalCloudMap(vec2 uv) { return sampleCloudMap(CloudNoiseTex, uv); }
float sampleEvolvingCloudPattern(vec2 uv) { return sampleCloudPattern(CloudNoiseTex, uv); }

// The underside and the top need independent structure, or dense banks converge
// to two flat planes.
float cloudLowerSurfaceFieldFromUv(vec2 uv) {
    return sampleNaturalCloudMap(CN_ROT2 * (uv * mix(1.65, 1.28, cloudReliefSetting())) + vec2(-0.23, 0.61));
}

float cloudUpperSurfaceFieldFromUv(vec2 uv) {
    return sampleNaturalCloudMap(CN_ROT2 * (uv * mix(1.30, 1.00, cloudReliefSetting())) + vec2(0.49, 0.17));
}

float cloudLowerBankFieldFromUv(vec2 uv) { return sampleNaturalCloudMap(CN_ROT2 * (uv * 0.92) + vec2(-0.67, 0.31)); }
float cloudUpperBankFieldFromUv(vec2 uv) { return sampleNaturalCloudMap(CN_ROT2 * (uv * 0.78) + vec2(0.19, -0.73)); }

float cloudShapeDetailAmount() {
    float detailBlend = (1.0 - cloudSmoothnessSetting()) * clamp(cloudDetailSetting() * 0.70, 0.0, 1.0);
    return max((detailBlend - 0.18) * (1.0 / 0.82), 0.0);
}

vec2 cloudShapeFieldFromUv(vec2 uv, float effectiveDetail) {
    float baseShape = sampleEvolvingCloudPattern(uv);
    float secondary = baseShape;
    if (effectiveDetail > 0.0) secondary = sampleNaturalCloudMap(CN_ROT2 * (uv * 2.15) + vec2(0.37, -0.19));
    float field = mix(baseShape, baseShape * 0.72 + secondary * 0.28, effectiveDetail);
    return vec2(field, secondary);
}

float cloudCoverageThreshold() {
    float threshold = mix(0.66, 0.40, cloudCoverageSetting());
    // Clustering expands or contracts existing islands: the gaps between clouds.
    threshold += mix(0.08, -0.14, cloudClusteringSetting());
    threshold += cloudAmountThresholdOffset();
    // A thunderstorm closes the banks into a continuous dark ceiling.
    threshold -= cloudThunderAmount() * 0.34;
    return threshold;
}

float cloudCoverageTransition() { return mix(0.070, 0.21, cloudAirinessSetting()); }

float cloudCoverageFromFieldPrepared(float field, float threshold, float transition) {
    return smoothstep(threshold, threshold + transition, field);
}

float cloudCoverageFromField(float field) {
    return cloudCoverageFromFieldPrepared(field, cloudCoverageThreshold(), cloudCoverageTransition());
}

// The footprint term is analytic anti-aliasing of the discrete ray steps: coarse far
// or grazing samples would otherwise show the slab as hard horizontal plates.
float cloudHeightEdgeWidth(float sampleFootprint) {
    float relief = cloudReliefSetting();
    return mix(0.025, 0.10, cloudAirinessSetting()) + sampleFootprint * mix(0.65, 0.95, relief) + relief * 0.025;
}

// Height field turned into a soft volume: low-map regions form a wispy shelf while
// strong regions grow upward into broad cumulus humps, towers and hanging lobes.
float cloudHeightProfilePrepared(float altF, float localCoverage, float lowerShape, float upperShape,
                                 float edgeWidth, out float heightFraction) {
    float lowerBillow = lowerShape - 0.5;
    float upperBillow = upperShape - 0.5;
    float relief = cloudReliefSetting();
    float reliefPresence = smoothstep(mix(0.10, 0.015, relief), mix(0.68, 0.42, relief), localCoverage);

    float lobeThreshold = mix(0.50, 0.38, relief);
    float towerThreshold = mix(0.48, 0.36, relief);
    float tailWidth = mix(0.28, 0.46, relief);
    float lobeRound = mix(1.20, 0.88, relief);
    float towerRound = mix(1.18, 0.85, relief);
    float hangingLobe = pow(smoothstep(lobeThreshold, lobeThreshold + tailWidth, lowerShape), lobeRound) * reliefPresence;
    float lowerScoop = pow(smoothstep(0.52, 0.80, 1.0 - lowerShape), 1.18) * reliefPresence;
    float risingTower = pow(smoothstep(towerThreshold, towerThreshold + tailWidth, upperShape), towerRound) * reliefPresence;
    float upperSaddle = pow(smoothstep(0.52, 0.80, 1.0 - upperShape), 1.15) * reliefPresence;

    float bumpScale = cloudReliefBumpScale();
    float body = sqrt(clamp(localCoverage, 0.0, 1.0));
    float lowerSurface = mix(0.06, -0.55, body)
        + bumpScale * (lowerBillow * mix(0.26, 0.40, relief)
                       - hangingLobe * mix(1.10, 1.40, relief)
                       + lowerScoop * mix(0.34, 0.46, relief));
    float upperSurface = mix(0.85, 1.85, body)
        + bumpScale * (upperBillow * mix(0.34, 0.50, relief)
                       + risingTower * mix(1.35, 1.65, relief)
                       - upperSaddle * mix(0.38, 0.50, relief));
    // Keep wisps volumetric even where the two deformed surfaces approach.
    upperSurface = max(upperSurface, lowerSurface + mix(0.60, 1.10, body));

    float lower = smoothstep(lowerSurface - edgeWidth, lowerSurface + edgeWidth, altF);
    float upper = 1.0 - smoothstep(upperSurface - edgeWidth * mix(1.55, 2.20, relief), upperSurface + edgeWidth * 0.35, altF);
    heightFraction = clamp((altF - lowerSurface) / max(upperSurface - lowerSurface, 0.1), 0.0, 1.0);
    float core = smoothstep(0.0, 0.28, heightFraction) * (1.0 - smoothstep(0.65, 1.0, heightFraction));
    return lower * upper * mix(0.25, 1.0, core);
}

float cloudRareBankCoverage(float field) {
    float threshold = mix(0.78, 0.64, cloudCoverageSetting());
    threshold += mix(0.015, -0.025, cloudClusteringSetting());
    return smoothstep(threshold, threshold + 0.11, field);
}

float cloudRareBankProfile(float localAltF, float localCoverage, float shape, float edgeWidth, out float heightFraction) {
    float billow = shape - 0.5;
    float lowerSurface = mix(0.08, -0.14, sqrt(localCoverage)) - billow * 0.24;
    float upperSurface = mix(0.58, 1.12, sqrt(localCoverage)) + billow * 0.40;
    upperSurface = max(upperSurface, lowerSurface + 0.50);
    float lower = smoothstep(lowerSurface - edgeWidth, lowerSurface + edgeWidth, localAltF);
    float upper = 1.0 - smoothstep(upperSurface - edgeWidth * 1.45, upperSurface + edgeWidth * 0.35, localAltF);
    heightFraction = clamp((localAltF - lowerSurface) / max(upperSurface - lowerSurface, 0.1), 0.0, 1.0);
    float core = smoothstep(0.0, 0.30, heightFraction) * (1.0 - smoothstep(0.62, 1.0, heightFraction));
    return lower * upper * mix(0.25, 1.0, core);
}

// Detached banks below and above the main layer break the single-sheet read.
float cloudCombinedHeightDensity(vec2 coverageUv, float altF, float mainDensity, float edgeWidth, inout float heightFraction) {
    float satelliteEdge = min(edgeWidth / 0.95, 0.30);

    float lowerDensity = 0.0;
    float lowerHeight = 0.5;
    if (altF > -2.70 && altF < 0.10) {
        float lowerField = cloudLowerBankFieldFromUv(coverageUv);
        float lowerCoverage = cloudRareBankCoverage(lowerField);
        lowerDensity = lowerCoverage * cloudRareBankProfile((altF + 1.30 + lowerField * 0.70) / 0.95,
                lowerCoverage, lowerField, satelliteEdge, lowerHeight);
    }

    float upperDensity = 0.0;
    float upperHeight = 0.5;
    if (altF > 0.90 && altF < 3.90) {
        float upperField = cloudUpperBankFieldFromUv(coverageUv);
        float upperCoverage = cloudRareBankCoverage(upperField);
        upperDensity = upperCoverage * cloudRareBankProfile((altF - 1.35 - upperField * 0.85) / 1.10,
                upperCoverage, upperField, satelliteEdge, upperHeight);
    }

    lowerDensity *= 0.78;
    upperDensity *= 0.78;
    float total = mainDensity + lowerDensity + upperDensity;
    heightFraction = (heightFraction * mainDensity + lowerHeight * lowerDensity + upperHeight * upperDensity) / max(total, 1.0e-5);
    return max(mainDensity, max(lowerDensity, upperDensity));
}

float cloudOpacityScale() {
    return mix(1.0, 0.42, cloudAirinessSetting()) * cloudDensitySetting() * (1.0 + cloudThunderAmount() * 0.65);
}

float cloudSurfaceDetailStrength() { return (1.0 - cloudSmoothnessSetting()) * cloudDetailSetting(); }

// Coverage-only density for the light march.
float cloudDensityCheap(vec3 pos, float sampleLength) {
    g_cloudMapLod = cloudMapLodForStep(sampleLength, cloudCoverageFrequency());
    float thickness = cloudLayerThickness();
    float altF = (pos.y - g_cloudBaseRel) / thickness;
    if (altF < cloudEnvelopeMinAltitude() || altF > cloudEnvelopeMaxAltitude()) return 0.0;

    vec2 coverageUv = cloudCoverageCoord(pos.xz);
    float localAltF = altF - cloudBankHeightOffset(CloudNoiseTex, coverageUv);
    float field = sampleEvolvingCloudPattern(coverageUv);
    float coverage = cloudCoverageFromField(field);
    float footprint = clamp(sampleLength / thickness * 0.20, 0.0, 0.20);
    float edgeWidth = cloudHeightEdgeWidth(footprint);
    float mainDensity = 0.0;
    float heightFraction = 0.5;
    if (coverage > 0.0) {
        float lowerShape = cloudLowerSurfaceFieldFromUv(coverageUv);
        float upperShape = cloudUpperSurfaceFieldFromUv(coverageUv);
        mainDensity = coverage * cloudHeightProfilePrepared(localAltF, coverage, lowerShape, upperShape, edgeWidth, heightFraction);
    }
    return cloudCombinedHeightDensity(coverageUv, localAltF, mainDensity, edgeWidth, heightFraction) * cloudOpacityScale();
}

// Primary-ray variant: everything invariant for the ray is computed once by the caller.
float cloudDensityRaySample(vec2 coverageUv, float altF, float opacityScale, float effectiveDetail,
                            float coverageThreshold, float coverageTransition, float heightEdgeWidth,
                            float surfaceDetailStrength, out float heightFraction) {
    float localAltF = altF - cloudBankHeightOffset(CloudNoiseTex, coverageUv);
    heightFraction = 0.5;
    vec2 shape = cloudShapeFieldFromUv(coverageUv, effectiveDetail);
    float coverage = cloudCoverageFromFieldPrepared(shape.x, coverageThreshold, coverageTransition);
    float mainDensity = 0.0;
    if (coverage > 0.0) {
        float lowerShape = cloudLowerSurfaceFieldFromUv(coverageUv);
        float upperShape = cloudUpperSurfaceFieldFromUv(coverageUv);
        mainDensity = coverage * cloudHeightProfilePrepared(localAltF, coverage, lowerShape, upperShape, heightEdgeWidth, heightFraction);
    }
    float density = cloudCombinedHeightDensity(coverageUv, localAltF, mainDensity, heightEdgeWidth, heightFraction);
    float surfaceDetail = mix(0.90, 1.05, shape.y);
    surfaceDetail = mix(1.0, surfaceDetail, surfaceDetailStrength);
    return clamp(density * surfaceDetail, 0.0, 1.0) * opacityScale;
}

// ---- Lighting (cloud_lighting.glsl) ----------------------------------
#define CLOUDS_4PI 12.56637061

float phaseHG(float cosTheta, float g) {
    float g2 = g * g;
    float denom = max(1.0 + g2 - 2.0 * g * cosTheta, 1.0e-4);
    return (1.0 - g2) / (CLOUDS_4PI * denom * sqrt(denom));
}

// Strong forward glow near the sun, a tight near-sun glow and a soft back lobe.
float cloudPhase(float cosTheta) {
    float forward = phaseHG(cosTheta, CLOUDS_PHASE_G);
    float forwardTight = phaseHG(cosTheta, 0.92);
    float back = phaseHG(cosTheta, -0.25);
    return 0.65 * max(forward, forwardTight) + 0.30 * forward + 0.20 * back;
}

float powderEffect(float density, float cosTheta) {
    float powder = density / (density + 0.15);
    return mix(powder, 1.0, 0.7 * (cosTheta * 0.5 + 0.5));
}

// Directional self-shadow: a few exponentially growing taps over fixed local distances.
float cloudSunOpticalDepth(vec3 pos, vec3 lightDir) {
    float thickness = cloudLayerThickness();
    float travel = thickness * 2.8;
    int steps = int(BrojeCloudMarch.z + 0.5);
    float od = 0.0;
    for (int i = 0; i < 5; i++) {
        if (i >= steps) break;
        float a = float(i) / float(steps);
        float b = float(i + 1) / float(steps);
        float stepLen = travel * (b * b - a * a);
        vec3 p = pos + lightDir * (travel * (a * a + b * b) * 0.5);
        od += cloudDensityCheap(p, stepLen) * min(stepLen, thickness);
    }
    return od;
}

vec3 cloudDirectLighting(vec3 pos, vec3 rayDir, vec3 lightDir, vec3 radiance, float density, float height) {
    float cosTheta = dot(rayDir, lightDir);
    float lightOD = cloudSunOpticalDepth(pos, lightDir);
    // The broad second term mimics multiple scattering without washing out the core.
    float beerDirect = exp(-lightOD * CLOUDS_EXTINCTION);
    float beerMultiple = 0.28 * exp(-lightOD * CLOUDS_EXTINCTION * 0.22);
    float beer = max(beerDirect, beerMultiple);
    float phase = clamp(cloudPhase(cosTheta) * CLOUDS_4PI, 0.18, 7.0);
    float powder = powderEffect(density, cosTheta);
    float grad = 0.48 + 0.52 * smoothstep(0.0, 0.85, height);
    return radiance * (beer * phase * powder * grad) * CLOUDS_SUN_STRENGTH;
}

vec3 cloudLighting(vec3 pos, vec3 rayDir, float density, float altF) {
    float height = clamp(altF, 0.0, 1.0);
    vec3 sunDir = normalize(BrojeCloudSun.xyz);
    // Both sources fade by elevation; the phase and self-shadow direction never flip.
    float daylight = smoothstep(-0.20, 0.12, sunDir.y);
    float moonlight = smoothstep(0.0, 0.25, -sunDir.y) * (1.0 - daylight);
    float warm = 1.0 - smoothstep(0.02, 0.55, sunDir.y);
    warm *= 1.0 - cloudRainAmount();
    float weather = BrojeCloudSky.w * (1.0 - 0.5 * cloudThunderAmount());
    vec3 solarRadiance = mix(vec3(1.0, 0.90, 0.80), vec3(0.75, 0.27, 0.15), warm) * (8.0 * daylight * weather);
    vec3 lunarRadiance = vec3(0.17, 0.19, 0.23) * (moonlight * weather);
    vec3 direct = vec3(0.0);
    if (daylight > 0.0) direct += cloudDirectLighting(pos, rayDir, sunDir, solarRadiance, density, height);
    if (moonlight > 0.0) direct += cloudDirectLighting(pos, rayDir, -sunDir, lunarRadiance, density, height);

    // Approximate sky occlusion inside the cloud: dense undersides darken.
    float ambientOD = max(density, 0.0) * cloudLayerThickness() * (1.0 - 0.55 * height);
    float ambientVisibility = mix(0.48, 1.0, exp(-ambientOD * CLOUDS_EXTINCTION * 0.18));
    vec3 ambient = BrojeCloudSky.rgb * ((0.35 + 0.65 * smoothstep(0.0, 0.9, height)) * ambientVisibility * CLOUDS_AMBIENT_STRENGTH);

    vec3 color = direct + ambient;
    float storm = cloudThunderAmount();
    if (storm > 0.0) color *= mix(1.0, mix(0.16, 0.48, smoothstep(0.0, 1.0, altF)), storm);
    return color;
}

// ---- Ray march (cloud_raymarch.glsl) ---------------------------------
// rgb = in-scattered cloud light, a = opacity. The ray stops at the scene surface.
vec4 renderVolumetricClouds(vec3 rayDir, float cloudBaseRel, float dither, float rayEnd) {
    g_cloudBaseRel = cloudBaseRel;

    float nominalBase = cloudBaseRel;
    float thickness = cloudLayerThickness();
    float envelopeBase = nominalBase + thickness * cloudEnvelopeMinAltitude();
    float envelopeTop = nominalBase + thickness * cloudEnvelopeMaxAltitude();
    bool cameraInsideEnvelope = envelopeBase <= 0.0 && envelopeTop >= 0.0;

    float rayY = rayDir.y;
    if (abs(rayY) < 1.0e-4) {
        if (!cameraInsideEnvelope) return vec4(0.0);
        rayY = rayY < 0.0 ? -1.0e-4 : 1.0e-4;
    }

    float t0 = envelopeBase / rayY;
    float t1 = envelopeTop / rayY;
    float tEnter = cameraInsideEnvelope ? 0.0 : max(min(t0, t1), 0.0);
    float tExit = cameraInsideEnvelope && abs(rayDir.y) < 1.0e-4 ? CLOUDS_MAX_DIST : max(t0, t1);
    if (tExit <= tEnter) return vec4(0.0);
    tExit = min(tExit, CLOUDS_MAX_DIST);
    if (tEnter >= tExit) return vec4(0.0);

    float absRayY = abs(rayDir.y);
    // Inside the envelope a grazing ray would cover the full slab; bound it.
    float insideHorizon = cameraInsideEnvelope ? 1.0 - smoothstep(0.055, 0.26, absRayY) : 0.0;
    float insideMaxDistance = max(thickness * 34.0, 1600.0);
    tExit = min(tExit, mix(CLOUDS_MAX_DIST, insideMaxDistance, insideHorizon));
    if (tEnter >= tExit) return vec4(0.0);

    // More primary steps at the horizon, fewer at the zenith.
    int steps = int(mix(BrojeCloudMarch.x, BrojeCloudMarch.y, clamp(absRayY, 0.0, 1.0)) + 0.5);
    steps = int(float(steps) * mix(1.0, 1.18, cloudReliefSetting()) + 0.5);
    float invStepCount = 1.0 / float(steps);

    float opacityScale = cloudOpacityScale();
    float coverageThreshold = cloudCoverageThreshold();
    float coverageTransition = cloudCoverageTransition();
    float surfaceDetailStrength = cloudSurfaceDetailStrength();
    float coverageFrequency = cloudCoverageFrequency();
    float shapeDetail = cloudShapeDetailAmount();

    // Only distant samples fade at the horizon; no angular band is cut away.
    float horizonAmount = 1.0 - smoothstep(0.025, 0.12, absRayY);
    float localHorizonDistance = max(thickness * 24.0, 1100.0);
    float sampleFadeDistance = mix(CLOUDS_MAX_DIST, localHorizonDistance, horizonAmount);
    float insideFadeDistance = max(thickness * 30.0, 1450.0);
    sampleFadeDistance = min(sampleFadeDistance, mix(CLOUDS_MAX_DIST, insideFadeDistance, insideHorizon));
    tExit = min(min(tExit, sampleFadeDistance), rayEnd);
    if (tEnter >= tExit) return vec4(0.0);
    float span = tExit - tEnter;

    // Optical depth plus a visibility-weighted lighting position: self-shadowing is
    // evaluated once per ray, and hidden distant banks cannot move it.
    float opticalDepth = 0.0;
    float firstHitT = CLOUDS_MAX_DIST;
    float lightingWeight = 0.0;
    float lightingDensitySum = 0.0;
    float lightingAltSum = 0.0;
    vec3 lightingPosSum = vec3(0.0);
    float stepLen = span * invStepCount;
    g_cloudMapLod = cloudMapLodForStep(stepLen, coverageFrequency);
    float sampleFootprint = min(stepLen * absRayY / thickness * 0.55, 0.28);
    float heightEdgeWidth = cloudHeightEdgeWidth(sampleFootprint);

    for (int i = 0; i < steps; i++) {
        if (opticalDepth > CLOUDS_MAX_OPTICAL_DEPTH) break;
        float cellStart = float(i) * invStepCount;
        float cellEnd = float(i + 1) * invStepCount;
        // Stratified distances with a fixed per-pixel offset: no frame-time noise.
        float jitter = mix(0.30, 0.70, fract(dither + float(i) * 0.618033989));
        float t = tEnter + span * mix(cellStart, cellEnd, jitter);
        vec3 p = rayDir * t;
        float altF = (p.y - nominalBase) / thickness;
        vec2 densityUv = (CloudMapOrigin + p.xz) * coverageFrequency;
        float heightFraction;
        float density = cloudDensityRaySample(densityUv, altF, opacityScale, shapeDetail, coverageThreshold,
                coverageTransition, heightEdgeWidth, surfaceDetailStrength, heightFraction);
        density *= 1.0 - smoothstep(sampleFadeDistance * 0.70, sampleFadeDistance, t);

        if (density > 0.0) {
            firstHitT = min(firstHitT, t);
            float sampleOD = density * CLOUDS_EXTINCTION * stepLen;
            float sampleWeight = exp(-opticalDepth) * (1.0 - exp(-sampleOD));
            lightingWeight += sampleWeight;
            lightingDensitySum += density * sampleWeight;
            lightingAltSum += heightFraction * sampleWeight;
            lightingPosSum += p * sampleWeight;
            opticalDepth += sampleOD;
        }
    }

    float alpha = 1.0 - exp(-opticalDepth);
    if (lightingWeight <= 1.0e-5) return vec4(0.0);
    vec3 cloudColor = cloudLighting(lightingPosSum / lightingWeight, rayDir,
            lightingDensitySum / lightingWeight, lightingAltSum / lightingWeight);
    alpha *= 1.0 - smoothstep(CLOUDS_MAX_DIST * 0.40, CLOUDS_MAX_DIST * 0.70, firstHitT);
    return vec4(cloudColor, alpha);
}

// ---- Ground shadow (cloud_ground_shadow.glsl) ------------------------
float cloudGroundVerticalDensity(float altF, float coverage, float field) {
    float billow = field - 0.5;
    float body = sqrt(clamp(coverage, 0.0, 1.0));
    float lowerSurface = mix(0.06, -0.55, body) + billow * 0.20;
    float upperSurface = mix(0.85, 1.85, body) + billow * 0.30;
    upperSurface = max(upperSurface, lowerSurface + mix(0.16, 0.30, coverage));
    float edge = mix(0.025, 0.10, cloudAirinessSetting());
    float lower = smoothstep(lowerSurface - edge, lowerSurface + edge, altF);
    float upper = 1.0 - smoothstep(upperSurface - edge * 1.55, upperSurface + edge * 0.35, altF);
    return coverage * lower * upper;
}

// Sunlight let through the clouds above one point of the shadow map plane.
float cloudGroundShadow(vec3 surfacePos, vec3 lightDir) {
    float visibility = BrojeCloudSky.w * (1.0 - BrojeCloudSun.w);
    float sunY = normalize(BrojeCloudSun.xyz).y;
    if (visibility <= 0.001 || sunY <= 0.06) return 1.0;

    float thickness = cloudLayerThickness();
    float layerMinY = g_cloudBaseRel - thickness * 1.75;
    float layerMaxY = g_cloudBaseRel + thickness * 3.05;
    if (surfacePos.y >= layerMaxY) return 1.0;
    float rayStart = max((layerMinY - surfacePos.y) / lightDir.y, 0.0);
    float rayEnd = max((layerMaxY - surfacePos.y) / lightDir.y, 0.0);
    if (rayEnd <= rayStart) return 1.0;

    const int SHADOW_STEPS = 6;
    float stepLength = (rayEnd - rayStart) / float(SHADOW_STEPS);
    float opticalDepth = 0.0;
    float frequency = cloudCoverageFrequency();
    g_cloudMapLod = cloudMapLodForStep(stepLength, frequency);
    float threshold = cloudCoverageThreshold();
    float transition = cloudCoverageTransition();
    for (int i = 0; i < SHADOW_STEPS; i++) {
        vec3 samplePos = surfacePos + lightDir * (rayStart + (float(i) + 0.5) * stepLength);
        vec2 uv = (CloudMapOrigin + samplePos.xz) * frequency;
        float field = sampleCloudPattern(CloudNoiseTex, uv);
        float coverage = smoothstep(threshold, threshold + transition, field);
        float altF = (samplePos.y - g_cloudBaseRel) / thickness - cloudBankHeightOffset(CloudNoiseTex, uv);
        opticalDepth += cloudGroundVerticalDensity(altF, coverage, field) * stepLength;
    }

    float transmittance = exp(-opticalDepth * cloudOpacityScale() * CLOUDS_EXTINCTION);
    // Only direct light is affected; the floor keeps dense banks from going black.
    transmittance = clamp(transmittance, 0.28, 1.0);
    return mix(1.0, transmittance, visibility * smoothstep(0.06, 0.18, sunY));
}

// ---- Composite ---------------------------------------------------------
// Quasar's modified ACES curve and display gamma: the march is in linear HDR.
vec3 aces(vec3 x) {
    const float a = 2.1, b = 0.15, c = 2.03, d = 0.8, e = 0.25;
    return clamp((x * (a * x + b)) / (x * (c * x + d) + e), 0.0, 1.0);
}

vec4 viewAt(vec2 ndc, float depth) {
    float clipDepth = BrojeCamera.w > 0.5 ? depth : depth * 2.0 - 1.0;
    return BrojeInverseProjection * vec4(ndc, clipDepth, 1.0);
}

// Distance range of a ray from the camera inside the slab [bottom, top] (camera-relative).
vec2 slabRange(vec3 direction, float bottom, float top) {
    if (abs(direction.y) < 1e-5) return bottom < 0.0 && top > 0.0 ? vec2(0.0, 1e9) : vec2(-1.0);
    float a = bottom / direction.y;
    float b = top / direction.y;
    return vec2(max(min(a, b), 0.0), max(a, b));
}

// Depth guide for reconstruction, sampled at the exact reduced ray centre.
float sceneEnd(vec2 uv) {
    ivec2 size = textureSize(Sampler0, 0);
    ivec2 pixel = clamp(ivec2(uv * vec2(size)), ivec2(0), size - 1);
    float depth = texelFetch(Sampler0, pixel, 0).r;
    if (BrojeCloudTime.w > 0.5) depth = max(depth, texelFetch(Sampler1, pixel, 0).r);
    vec4 view = viewAt(vec2(uv.x * 2.0 - 1.0, 1.0 - uv.y * 2.0), depth);
    return view.w > 1e-7 ? min(length(view.xyz / view.w), CLOUDS_MAX_DIST) : CLOUDS_MAX_DIST;
}

// Linear premultiplied cloud light and transmittance to display values, with the
// horizon haze: premultiplied color to add over the scene, and the scene's weight.
vec4 displayClouds(vec4 integrated, vec3 direction, float cloudBaseRel) {
    float transmittance = clamp(integrated.w, 0.0, 1.0);
    float opacity = 1.0 - transmittance;
    vec3 radiance = opacity > 1e-4 ? max(integrated.rgb, 0.0) / opacity : vec3(0.0);
    vec3 color = pow(aces(radiance), vec3(1.0 / 2.2)) * opacity;
    // Low clouds near the horizon sink into the same haze as the sky behind them.
    float thickness = cloudLayerThickness();
    vec2 range = slabRange(direction, cloudBaseRel - thickness * 1.75, cloudBaseRel + thickness * 3.05);
    float approach = max(range.x, 0.0);
    float haze = BrojeCloudHorizon.w * (1.0 - exp(-approach * 0.0004)) * (1.0 - smoothstep(0.0, 0.22, abs(direction.y)));
    color = mix(color, BrojeCloudHorizon.rgb * opacity, haze);
    return vec4(max(color, 0.0), transmittance);
}

void main() {
    float cloudBaseRel = BrojeCloudShape.x - BrojeCamera.y;
#ifdef CLOUDS_SKY
    // The upper sky hemisphere around the camera, stereographically projected onto
    // the unit disk (as water_common.glsl cloudReflection() reads it). Water reflects
    // clouds from here wherever its reflected ray leaves the screen.
    vec2 disk = gl_FragCoord.xy / BrojeCloudTarget.xy * 2.0 - 1.0;
    float r2 = dot(disk, disk);
    if (r2 > 1.0) { disk *= inversesqrt(r2); r2 = 1.0; }
    vec3 skyDirection = normalize(vec3(2.0 * disk.x, 1.0 - r2, 2.0 * disk.y));
    float skyDither = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
    vec4 skyClouds = renderVolumetricClouds(skyDirection, cloudBaseRel, skyDither, CLOUDS_MAX_DIST);
    fragColor = displayClouds(vec4(skyClouds.rgb * skyClouds.a, 1.0 - skyClouds.a), skyDirection, cloudBaseRel);
    return;
#endif
#ifdef CLOUDS_SHADOW
    // One texel of the cloud shadow map, centred on the camera.
    g_cloudBaseRel = cloudBaseRel;
    vec2 mapUV = gl_FragCoord.xy / BrojeCloudTarget.xy;
    vec3 start = vec3((mapUV.x - 0.5) * BrojeCloudShadowArea.z, BrojeCloudShadowArea.w - BrojeCamera.y,
                      (mapUV.y - 0.5) * BrojeCloudShadowArea.z);
    fragColor = vec4(cloudGroundShadow(start, normalize(BrojeCloudShadowLight.xyz)), 0.0, 0.0, 1.0);
    return;
#endif
    ivec2 size = textureSize(Sampler0, 0);
    ivec2 pixel = clamp(ivec2(gl_FragCoord.xy / BrojeCloudTarget.xy * vec2(size)), ivec2(0), size - 1);
    float depth = texelFetch(Sampler0, pixel, 0).r;
    // Glass, ice and water hide nothing behind them; the solid world does.
    if (BrojeCloudTime.w > 0.5) depth = max(depth, texelFetch(Sampler1, pixel, 0).r);
    // The ray goes through this pixel's exact centre in whatever resolution is drawn;
    // only the depth comes from the nearest full-resolution texel.
    vec2 screenUV = gl_FragCoord.xy / BrojeCloudTarget.xy;
    vec2 ndc = vec2(screenUV.x * 2.0 - 1.0, 1.0 - screenUV.y * 2.0);
    vec4 view = viewAt(ndc, depth);
    // The direction joins two finite points of the pixel's ray, never one point seen
    // from the origin: view bobbing would otherwise shake the clouds.
    vec4 rayNear = viewAt(ndc, 0.0);
    vec4 rayMiddle = viewAt(ndc, 0.5);
    vec3 direction = normalize(mat3(BrojeInverseView) * normalize(rayMiddle.xyz / rayMiddle.w - rayNear.xyz / rayNear.w));
    float surface = view.w > 1e-7 ? length(view.xyz / view.w) : CLOUDS_MAX_DIST;
    if (isnan(surface) || isinf(surface)) surface = CLOUDS_MAX_DIST;
    float rayEnd = min(surface, CLOUDS_MAX_DIST);

#if defined(CLOUDS_FILTER)
    // Current-frame spatial filtering removes the fixed sampling grain. Scene depth
    // keeps it from filling silhouettes with neighbouring cloud light.
    ivec2 own = ivec2(gl_FragCoord.xy);
    ivec2 cloudSize = textureSize(Sampler2, 0);
    vec4 filtered = vec4(0.0);
    float filterWeight = 0.0;
    float surfaceTolerance = max(1.0, rayEnd * 0.02);
    for (int y = -1; y <= 1; ++y) {
        for (int x = -1; x <= 1; ++x) {
            ivec2 tap = clamp(own + ivec2(x, y), ivec2(0), cloudSize - 1);
            float spatial = float((x == 0 ? 2 : 1) * (y == 0 ? 2 : 1));
            float surfaceGap = abs(sceneEnd((vec2(tap) + 0.5) / vec2(cloudSize)) - rayEnd);
            spatial *= max(0.0, 1.0 - surfaceGap / surfaceTolerance);
            filtered += texelFetch(Sampler2, tap, 0) * spatial;
            filterWeight += spatial;
        }
    }
    fragColor = filterWeight > 1e-5 ? filtered / filterWeight : texelFetch(Sampler2, own, 0);
#elif defined(CLOUDS_RESOLVE)
    // Depth-aware upsampling: only reduced pixels that ended where this one ends
    // (sky with sky, mountain with mountain) contribute.
    ivec2 cloudSize = textureSize(Sampler2, 0);
    vec2 cloudPixel = screenUV * vec2(cloudSize) - 0.5;
    ivec2 base = ivec2(floor(cloudPixel));
    vec4 sum = vec4(0.0);
    float weightSum = 0.0;
    vec4 closest = vec4(0.0, 0.0, 0.0, 1.0);
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
            sum += cloud * weight;
            weightSum += weight;
            if (gap < closestGap) { closestGap = gap; closest = cloud; }
        }
    }
    // Thin silhouettes without a matching neighbour take the closest ray end.
    fragColor = displayClouds(weightSum > 1e-5 ? sum / weightSum : closest, direction, cloudBaseRel);
#else
    // Fixed interleaved noise spreads the integration error without shimmer.
    float dither = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
    vec4 clouds = renderVolumetricClouds(direction, cloudBaseRel, dither, rayEnd);
    // Premultiplied light and transmittance, so filtering and upsampling stay linear.
    fragColor = vec4(clouds.rgb * clouds.a, 1.0 - clouds.a);
#endif
}
