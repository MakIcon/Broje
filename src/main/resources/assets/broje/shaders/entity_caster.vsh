#version 450
layout(binding=0) uniform Transform { mat4 MVP; vec4 BrojeFlags; };
#ifdef TEXTURED
layout(location=0) out vec2 texCoord0;
#endif
// Position (and UV0 when textured) are declared above at the locations of the
// vanilla vertex format this caster stands in for.
void main() {
    vec4 clip = MVP * vec4(Position, 1.0);
    // Same radial distortion as the terrain casters and receivers.
    clip.xy /= length(clip.xy) * BrojeFlags.z + (1.0 - BrojeFlags.z);
    clip.z = clamp(clip.z, 0.0, 1.0);
    gl_Position = clip;
#ifdef TEXTURED
    texCoord0 = UV0;
#endif
}
