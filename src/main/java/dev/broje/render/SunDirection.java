package dev.broje.render;

import org.joml.Vector3f;

/** Shared world-space solar orbit for the visible sun, terrain and fog. */
public final class SunDirection {
    private SunDirection() {}

    public static Vector3f at(float angle, int tiltDegrees, Vector3f result) {
        double tilt = Math.toRadians(tiltDegrees);
        float height = (float) Math.cos(angle);
        return result.set(-(float) Math.sin(angle), height * (float) Math.cos(tilt),
                height * (float) Math.sin(tilt)).normalize();
    }
}
