package org.mining.raytracing.gpu;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.joml.Matrix4f;

/**
 * Single source of truth for every GPU-visible layout shared by the two backends
 * and the GLSL kernels (see shader headers). All buffers are std430, little-endian.
 */
public final class RtLayout {
    private RtLayout() {}

    // SSBO binding points (VK: set 0, bindings 0..6; GL: indexed buffer bindings 0..6)
    public static final int SSBO_GLOBALS = 0;
    public static final int SSBO_SECTION_TABLE = 1;
    public static final int SSBO_BRICK_MATS = 2;
    public static final int SSBO_BRICK_OCC = 3;
    public static final int SSBO_MATERIALS = 4;
    public static final int SSBO_LIGHTS = 5;
    public static final int SSBO_ENTITIES = 6;
    public static final int SSBO_COUNT = 7;

    // brick geometry
    public static final int BRICK_MAT_UINTS = 16 * 16 * 16 / 2; // u16 ids, 2 per uint
    public static final int BRICK_MAT_BYTES = BRICK_MAT_UINTS * 4;      // 4096 B
    public static final int BRICK_OCC_UINTS = 64 * 2;                   // 64 cells × uvec2
    public static final int BRICK_OCC_BYTES = BRICK_OCC_UINTS * 4;      // 512 B

    // section table: {entry, lightCount} per slot
    public static final int TABLE_STRIDE_INTS = 2;
    public static final int TABLE_ENTRY_UNLOADED = -2;
    public static final int TABLE_ENTRY_AIR = -1;
    /** entry >= 0: brick index; entry with UNIFORM_BIT: uniform section of material (entry & 0xFFFF). */
    public static final int TABLE_UNIFORM_BIT = 0x4000_0000;

    public static final int MATERIAL_STRIDE_BYTES = 48; // 3 × vec4
    public static final int LIGHT_STRIDE_BYTES = 32;    // 2 × vec4 {pos,intensity} {color,unused}
    public static final int ENTITY_STRIDE_BYTES = 32;   // 2 × vec4 {min,colorPacked} {max,emissive}

    public static final int GLOBALS_BYTES = 304;

    /** Serialize FrameData into the Globals SSBO layout (offsets documented in common.glsl). */
    public static ByteBuffer packGlobals(FrameData f, int lightsPerSection, ByteBuffer reuse) {
        ByteBuffer b = (reuse != null && reuse.capacity() >= GLOBALS_BYTES)
                ? reuse.clear() : ByteBuffer.allocateDirect(GLOBALS_BYTES).order(ByteOrder.nativeOrder());
        putMat4(b, 0, f.invViewProj);
        putMat4(b, 64, f.prevViewProj);
        b.putFloat(128, f.camPos.x).putFloat(132, f.camPos.y).putFloat(136, f.camPos.z).putFloat(140, 0f);
        b.putFloat(144, f.prevCamPos.x).putFloat(148, f.prevCamPos.y).putFloat(152, f.prevCamPos.z).putFloat(156, 0f);
        b.putFloat(160, f.camForward.x).putFloat(164, f.camForward.y).putFloat(168, f.camForward.z).putFloat(172, 0f);
        b.putFloat(176, f.sunDir.x).putFloat(180, f.sunDir.y).putFloat(184, f.sunDir.z).putFloat(188, f.sunCosRadius);
        b.putFloat(192, f.sunRadiance.x).putFloat(196, f.sunRadiance.y).putFloat(200, f.sunRadiance.z).putFloat(204, f.rainLevel);
        b.putInt(208, f.gridSizeX).putInt(212, f.gridSizeY).putInt(216, f.gridSizeZ).putInt(220, f.brickPoolSize);
        b.putInt(224, f.rtWidth).putInt(228, f.rtHeight).putInt(232, f.outWidth).putInt(236, f.outHeight);
        b.putInt(240, f.frameIndex).putInt(244, f.bounces).putInt(248, f.debugView).putInt(252, f.maxHistory);
        b.putFloat(256, f.temporalAlpha).putFloat(260, f.radianceClamp).putFloat(264, f.exposure).putFloat(268, f.thunderLevel);
        b.putInt(272, lightsPerSection).putInt(276, f.entityCount).putInt(280, f.denoiseIterations)
         .putInt(284, f.resetHistory ? 1 : 0);
        b.putFloat(288, f.sunAngularRadius).putInt(292, f.originSecX).putInt(296, f.originSecZ).putFloat(300, 0f);
        b.position(0).limit(GLOBALS_BYTES);
        return b;
    }

    private static void putMat4(ByteBuffer b, int off, Matrix4f m) {
        // column-major, matching GLSL mat4
        for (int c = 0; c < 4; c++)
            for (int r = 0; r < 4; r++)
                b.putFloat(off + (c * 4 + r) * 4, m.get(c, r));
    }
}
