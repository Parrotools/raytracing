package org.mining.raytracing.gpu;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * A batch of acceleration-structure / material uploads produced by the voxel store
 * for one frame. Buffers are little-endian and owned by the producer until the
 * backend's {@code uploadStorage} returns.
 */
public final class StorageUpdates {

    /** One 16³ section brick: 2048 uints of packed material ids + 64 uvec2 occupancy. */
    public record BrickUpload(int brickIndex, ByteBuffer materials, ByteBuffer occupancy) {}

    /**
     * One section-table slot patch: the two table ints (entry, lightCount) plus the
     * slot's light-list region ({@code lights} may be null when lightCount == 0).
     */
    public record SlotPatch(int slotIndex, int tableEntry, int lightCount, ByteBuffer lights) {}

    /** Full material table re-upload (3 × vec4 per material), or null. */
    public ByteBuffer materialTable;
    public int materialCount;

    /** Full section table upload (grid reset), or null. Stride: 2 ints per slot. */
    public ByteBuffer fullSectionTable;

    public final List<SlotPatch> patches = new ArrayList<>();
    public final List<BrickUpload> bricks = new ArrayList<>();

    public boolean isEmpty() {
        return materialTable == null && fullSectionTable == null && patches.isEmpty() && bricks.isEmpty();
    }
}
