package dev.broje.render;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Camera-relative sun projection centred on a 2-block world grid. Inside one grid cell
 * every world point keeps the same map position while the camera moves; the shaders
 * then apply a radial distortion that concentrates texels around that centre.
 */
public final class ShadowProjection {
    public static final float DEPTH_SPAN = 512;
    public static final double GRID = 2.0;
    public final Matrix4f view = new Matrix4f();
    public final Matrix4f matrix = new Matrix4f();
    /** Map centre relative to the camera, in blocks. */
    public final Vector3f centre = new Vector3f();
    private final Vector3f eye = new Vector3f();
    private final Vector3f zero = new Vector3f();
    private final Vector3f up = new Vector3f(0,0,1);

    public void update(double cameraX, double cameraY, double cameraZ, Vector3f sun, float extent) {
        // Offsets are formed in double so far-world coordinates do not lose the cell phase.
        centre.set((float)(Math.floor(cameraX/GRID)*GRID-cameraX),
                (float)(Math.floor(cameraY/GRID)*GRID-cameraY),
                (float)(Math.floor(cameraZ/GRID)*GRID-cameraZ));
        view.identity().lookAt(eye.set(sun).mul(DEPTH_SPAN*0.5f),zero,up).translate(-centre.x,-centre.y,-centre.z);
        matrix.setOrtho(-extent,extent,-extent,extent,0.1f,DEPTH_SPAN,true).mul(view);
    }

    /** Beryl-style strength: stronger concentration for longer shadow ranges. */
    public static float distortion(int distanceBlocks) {
        return Math.max(0.4f, Math.min(0.9f, 1f - 1.2f / (distanceBlocks / 16f)));
    }
}
