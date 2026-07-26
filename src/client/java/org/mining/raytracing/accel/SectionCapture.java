package org.mining.raytracing.accel;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.mining.raytracing.materials.Materials;
import org.mining.raytracing.materials.RtMaterial;

/**
 * Converts a copied {@link PalettedContainer} into brick bytes + occupancy masks +
 * light entries. Runs on a worker thread; the render thread has already resolved
 * every palette entry to a material id, so lookups here are lock-free cache hits.
 */
final class SectionCapture {

    static CapturedSection convert(int secX, int secY, int secZ, int generation,
                                   PalettedContainer<BlockState> states,
                                   Materials materials, int maxLights) {
        char[] ids = new char[4096];
        long[] occBits = new long[64];
        List<CapturedSection.Light> lights = new ArrayList<>();
        char first = Character.MAX_VALUE;
        boolean uniform = true;

        for (int z = 0; z < 16; z++) {
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    char id = materials.idFor(states.get(x, y, z));
                    RtMaterial m = materials.get(id);
                    boolean occludes = id != Materials.AIR_ID && (m.flags() & RtMaterial.F_NON_OCCLUDING) == 0;
                    // the voxel grid holds only occluding matter; sub-voxel decorations
                    // are skipped but keep their light entry below
                    char effective = occludes ? id : Materials.AIR_ID;

                    ids[((z * 16) + y) * 16 + x] = effective;
                    if (first == Character.MAX_VALUE) first = effective;
                    else if (effective != first) uniform = false;

                    if (occludes) {
                        int cell = ((z >> 2) * 4 + (y >> 2)) * 4 + (x >> 2);
                        int bit = ((z & 3) * 4 + (y & 3)) * 4 + (x & 3);
                        occBits[cell] |= 1L << bit;
                    }
                    if (m.emissive() && (m.flags() & RtMaterial.F_FLUID) == 0) {
                        // fluids light via bounce pickup only (docs/07); others get NEE entries
                        lights.add(new CapturedSection.Light(x + 0.5f, y + 0.5f, z + 0.5f,
                                m.er(), m.eg(), m.eb(), m.emissionLuma()));
                    }
                }
            }
        }

        if (lights.size() > maxLights) {
            lights.sort(Comparator.comparingDouble((CapturedSection.Light l) -> l.intensity()).reversed());
            lights = new ArrayList<>(lights.subList(0, maxLights));
        }

        if (uniform) {
            return new CapturedSection(secX, secY, secZ, generation, true, first, null, null,
                    first == Materials.AIR_ID ? List.copyOf(lights) : lights);
        }

        ByteBuffer mats = ByteBuffer.allocateDirect(org.mining.raytracing.gpu.RtLayout.BRICK_MAT_BYTES)
                .order(ByteOrder.nativeOrder());
        for (int i = 0; i < 4096; i += 2) {
            mats.putInt((ids[i] & 0xFFFF) | ((ids[i + 1] & 0xFFFF) << 16));
        }
        mats.flip();
        ByteBuffer occ = ByteBuffer.allocateDirect(org.mining.raytracing.gpu.RtLayout.BRICK_OCC_BYTES)
                .order(ByteOrder.nativeOrder());
        for (int i = 0; i < 64; i++) {
            occ.putInt((int) occBits[i]);
            occ.putInt((int) (occBits[i] >>> 32));
        }
        occ.flip();
        return new CapturedSection(secX, secY, secZ, generation, false, (char) 0, mats, occ, lights);
    }

    private SectionCapture() {}
}
