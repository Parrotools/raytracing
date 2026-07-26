package org.mining.raytracing.materials;

/**
 * One PBR material — GPU layout is 3 × vec4 (48 B, std430), see docs/06-materials.md.
 * Immutable; colors are linear sRGB, emission is linear HDR radiance.
 */
public record RtMaterial(
        float r, float g, float b, float roughness,
        float er, float eg, float eb, float metallic,
        float ior, float transmission, int flags, float sss) {

    /** Never voxelized (torches, flowers…); its emission still becomes a point light. */
    public static final int F_NON_OCCLUDING = 1;
    public static final int F_FLUID = 2;
    public static final int F_SSS = 4;

    public boolean emissive() {
        return er > 0f || eg > 0f || eb > 0f;
    }

    public float emissionLuma() {
        return 0.2126f * er + 0.7152f * eg + 0.0722f * eb;
    }

    public void pack(java.nio.ByteBuffer out) {
        out.putFloat(r).putFloat(g).putFloat(b).putFloat(roughness);
        out.putFloat(er).putFloat(eg).putFloat(eb).putFloat(metallic);
        out.putFloat(ior).putFloat(transmission).putFloat(Float.intBitsToFloat(flags)).putFloat(sss);
    }

    public static RtMaterial diffuse(float r, float g, float b, float rough) {
        return new RtMaterial(r, g, b, rough, 0, 0, 0, 0, 1.0f, 0, 0, 0);
    }
}
