package org.mining.raytracing.gpu;

import java.nio.ByteBuffer;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Everything a backend needs to render one frame. All positions are grid-local
 * (relative to the voxel grid origin in metres, see docs/02 §5); previous-frame
 * data has already been re-expressed in the current grid frame by the core.
 */
public final class FrameData {
    // resolutions
    public int rtWidth, rtHeight;    // internal (render scale) resolution
    public int outWidth, outHeight;  // display resolution

    // camera (grid-local)
    public final Matrix4f invViewProj = new Matrix4f();
    public final Matrix4f prevViewProj = new Matrix4f();
    public final Vector3f camPos = new Vector3f();
    public final Vector3f prevCamPos = new Vector3f();
    public final Vector3f camForward = new Vector3f();

    // lighting environment
    public final Vector3f sunDir = new Vector3f(0, 1, 0);
    public final Vector3f sunRadiance = new Vector3f();
    public float sunCosRadius = 0.9999f;
    public float rainLevel, thunderLevel;

    // voxel grid
    public int gridSizeX, gridSizeY, gridSizeZ;
    public int brickPoolSize;
    /** world section coords of the grid origin (min corner) — needed for wrapped slot addressing */
    public int originSecX, originSecZ;

    // per-frame parameters
    public int frameIndex;
    public int bounces;
    public int debugView;
    public int maxHistory;
    public float temporalAlpha;
    public float radianceClamp;
    public float exposure;
    public float sunAngularRadius; // radians
    public int denoiseIterations;
    public boolean resetHistory;

    // dynamic scene: entity AABB proxies, 2 × vec4 each (see entities SSBO layout)
    public ByteBuffer entities; // little-endian, may be null when entityCount == 0
    public int entityCount;

    // output target handles (plain primitives so no engine types cross the HAL):
    public int targetGlTexture;   // GL backend: texture id of MC's main color attachment
    public long targetVkImage;    // VK backend: VkImage of MC's main color attachment
}
