package org.mining.raytracing.accel;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * Result of converting one 16³ chunk section to GPU form (produced on a worker
 * thread, consumed on the render thread).
 *
 * <p>Light positions are <b>section-relative</b> (0..16) so entries survive grid
 * origin shifts; the shader reconstructs world positions from the slot it queried
 * (docs/07 devlog).
 */
public record CapturedSection(
        int secX, int secY, int secZ,
        int generation,
        boolean uniform,
        char uniformMaterial,          // valid when uniform (0 = air)
        ByteBuffer materials,          // 8192 B packed u16 ids (2 per uint), null when uniform
        ByteBuffer occupancy,          // 512 B (64 × uvec2), null when uniform
        List<Light> lights) {

    /** One emissive voxel: section-relative centre + linear HDR radiance. */
    public record Light(float x, float y, float z, float r, float g, float b, float intensity) {}
}
