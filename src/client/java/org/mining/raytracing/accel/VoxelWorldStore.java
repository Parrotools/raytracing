package org.mining.raytracing.accel;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.mining.raytracing.core.RtConfig;
import org.mining.raytracing.core.RtLog;
import org.mining.raytracing.gpu.RtLayout;
import org.mining.raytracing.gpu.StorageUpdates;
import org.mining.raytracing.materials.Materials;

/**
 * Camera-anchored voxel acceleration structure (docs/02 §4).
 *
 * <p>Slots use wrapped (toroidal) addressing — {@code slot = floorMod(sec, gridSize)}
 * — so retained sections keep their slot and their GPU brick across grid origin
 * shifts; only vacated/entered slots are touched.
 *
 * <p>All public methods run on the render thread; conversion runs on a small worker
 * pool operating exclusively on section copies.
 */
public final class VoxelWorldStore {
    private static final int ST_UNLOADED = 0, ST_PENDING = 1, ST_READY = 2;

    private final RtConfig config;
    private final Materials materials;
    private final ExecutorService workers;

    // grid geometry (fixed until reset)
    private int gridX, gridY, gridZ;
    private int minSectionY;
    private int originSecX, originSecZ; // min corner of tracked XZ range
    private boolean hasGrid;

    // per-slot state
    private long[] slotKey;
    private byte[] slotStatus;
    private int[] slotBrick;      // >= 0 brick index, -1 none
    private int[] slotGeneration;

    private final ArrayDeque<Integer> freeBricks = new ArrayDeque<>();
    private final ArrayDeque<Long> captureQueue = new ArrayDeque<>();
    private final HashSet<Long> queued = new HashSet<>();
    private final HashSet<Long> dirtyFromEvents = new HashSet<>();
    private final ConcurrentLinkedQueue<CapturedSection> completed = new ConcurrentLinkedQueue<>();
    private int uploadedMaterialVersion = 0;
    private boolean warnedPoolFull;
    private boolean needFullTable;

    public VoxelWorldStore(RtConfig config, Materials materials) {
        this.config = config;
        this.materials = materials;
        this.workers = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "raytracing-capture");
            t.setDaemon(true);
            return t;
        });
    }

    public int gridX() { return gridX; }
    public int gridY() { return gridY; }
    public int gridZ() { return gridZ; }
    public int slotCount() { return gridX * gridY * gridZ; }
    public boolean ready() { return hasGrid; }

    /** Grid origin in metres (the point GPU coordinates are relative to). */
    public double originMetersX() { return originSecX * 16.0; }
    public double originMetersY() { return minSectionY * 16.0; }
    public double originMetersZ() { return originSecZ * 16.0; }

    /** Rebuild the grid for a (new) level. Requires a follow-up tick to start filling. */
    public void reset(ClientLevel level, int camSecX, int camSecZ) {
        int r = config.sectionRadius;
        gridX = gridZ = 2 * r + 1;
        gridY = level.getSectionsCount();
        minSectionY = level.getMinSectionY();
        originSecX = camSecX - r;
        originSecZ = camSecZ - r;
        int slots = slotCount();
        slotKey = new long[slots];
        slotStatus = new byte[slots];
        slotBrick = new int[slots];
        slotGeneration = new int[slots];
        java.util.Arrays.fill(slotBrick, -1);
        freeBricks.clear();
        for (int i = 0; i < config.brickPoolSize; i++) freeBricks.add(i);
        captureQueue.clear();
        queued.clear();
        dirtyFromEvents.clear();
        completed.clear();
        warnedPoolFull = false;
        needFullTable = true;
        hasGrid = true;
        enqueueAllInRange(camSecX, camSecZ);
        RtLog.LOG.info("Voxel grid reset: {}x{}x{} sections, origin ({}, {}), {} bricks",
                gridX, gridY, gridZ, originSecX, originSecZ, config.brickPoolSize);
    }

    /** Per-frame maintenance; appends this frame's uploads to {@code out}. */
    public void tick(ClientLevel level, int camSecX, int camSecZ, StorageUpdates out) {
        if (!hasGrid) return;
        shiftOriginIfNeeded(camSecX, camSecZ, out);

        for (Long key : dirtyFromEvents) enqueue(key);
        dirtyFromEvents.clear();

        if (needFullTable) {
            needFullTable = false;
            out.fullSectionTable = buildFullTable();
        }
        if (materials.version() != uploadedMaterialVersion) {
            uploadedMaterialVersion = materials.version();
            out.materialTable = materials.pack();
            out.materialCount = materials.count();
        }

        int budget = config.sectionCapturesPerFrame;
        while (budget-- > 0 && !captureQueue.isEmpty()) {
            long key = captureQueue.poll();
            queued.remove(key);
            captureOne(level, key);
        }

        CapturedSection cs;
        while ((cs = completed.poll()) != null) applyCompleted(cs, out);
    }

    /** Mark the section containing this block position (block update hook). */
    public void markBlockDirty(int bx, int by, int bz) {
        if (!hasGrid) return;
        long key = key(bx >> 4, by >> 4, bz >> 4);
        if (inRange(bx >> 4, by >> 4, bz >> 4)) dirtyFromEvents.add(key);
    }

    /** Chunk column arrived/replaced (chunk load hook). */
    public void markColumnLoaded(int cx, int cz) {
        if (!hasGrid || !inRangeXZ(cx, cz)) return;
        for (int sy = minSectionY; sy < minSectionY + gridY; sy++) dirtyFromEvents.add(key(cx, sy, cz));
    }

    /** Chunk column dropped (chunk unload hook) — handled next tick via re-capture (which sees null chunk). */
    public void markColumnUnloaded(int cx, int cz) {
        markColumnLoaded(cx, cz);
    }

    public void invalidate() {
        hasGrid = false;
    }

    public void close() {
        workers.shutdownNow();
        hasGrid = false;
    }

    /** Diagnostic dump: brick usage, duplicate assignments, non-air slot census. */
    public void debugAudit() {
        if (!hasGrid) return;
        java.util.HashMap<Integer, Long> brickOwners = new java.util.HashMap<>();
        int bricksUsed = 0, ready = 0, pending = 0;
        for (int i = 0; i < slotKey.length; i++) {
            if (slotBrick[i] >= 0) {
                bricksUsed++;
                Long prev = brickOwners.put(slotBrick[i], slotKey[i]);
                if (prev != null) {
                    RtLog.LOG.error("[audit] brick {} owned by BOTH ({},{},{}) and ({},{},{})",
                            slotBrick[i], keyX(prev), keyY(prev), keyZ(prev),
                            keyX(slotKey[i]), keyY(slotKey[i]), keyZ(slotKey[i]));
                }
                RtLog.LOG.info("[audit] slot {} sec ({},{},{}) brick {}",
                        i, keyX(slotKey[i]), keyY(slotKey[i]), keyZ(slotKey[i]), slotBrick[i]);
            }
            if (slotStatus[i] == ST_READY) ready++;
            if (slotStatus[i] == ST_PENDING) pending++;
        }
        RtLog.LOG.info("[audit] bricks used {}, free {}, slots ready {}, pending {}, origin ({}, {})",
                bricksUsed, freeBricks.size(), ready, pending, originSecX, originSecZ);
    }

    // ------------------------------------------------------------------ internals

    private void shiftOriginIfNeeded(int camSecX, int camSecZ, StorageUpdates out) {
        int r = config.sectionRadius;
        int wantX = camSecX - r, wantZ = camSecZ - r;
        if (wantX == originSecX && wantZ == originSecZ) return;
        int oldX = originSecX, oldZ = originSecZ;
        originSecX = wantX;
        originSecZ = wantZ;
        // vacate slots whose section left the range; enqueue sections that entered
        for (int i = 0; i < slotKey.length; i++) {
            if (slotStatus[i] == ST_UNLOADED && slotBrick[i] < 0) continue;
            long key = slotKey[i];
            int sx = keyX(key), sy = keyY(key), sz = keyZ(key);
            if (!inRange(sx, sy, sz)) {
                freeSlot(i, out);
            }
        }
        for (int sx = wantX; sx < wantX + gridX; sx++) {
            for (int sz = wantZ; sz < wantZ + gridZ; sz++) {
                if (sx >= oldX && sx < oldX + gridX && sz >= oldZ && sz < oldZ + gridZ) continue;
                for (int sy = minSectionY; sy < minSectionY + gridY; sy++) enqueue(key(sx, sy, sz));
            }
        }
    }

    private void enqueueAllInRange(int camSecX, int camSecZ) {
        List<long[]> cols = new ArrayList<>();
        for (int sx = originSecX; sx < originSecX + gridX; sx++)
            for (int sz = originSecZ; sz < originSecZ + gridZ; sz++)
                cols.add(new long[]{(long) (sx - camSecX) * (sx - camSecX) + (long) (sz - camSecZ) * (sz - camSecZ), sx, sz});
        cols.sort(java.util.Comparator.comparingLong(a -> a[0]));
        for (long[] c : cols)
            for (int sy = minSectionY; sy < minSectionY + gridY; sy++)
                enqueue(key((int) c[1], sy, (int) c[2]));
    }

    private void enqueue(long key) {
        if (queued.add(key)) captureQueue.add(key);
    }

    private void captureOne(ClientLevel level, long key) {
        int sx = keyX(key), sy = keyY(key), sz = keyZ(key);
        if (!inRange(sx, sy, sz)) return;
        int slot = slotIndex(sx, sy, sz);
        int gen = ++slotGeneration[slot];
        slotKey[slot] = key;

        LevelChunk chunk = level.getChunkSource().getChunk(sx, sz, ChunkStatus.FULL, false);
        if (chunk == null) {
            slotStatus[slot] = ST_UNLOADED;
            // brick (if any) stays allocated until a later successful capture or vacate;
            // mark unloaded so rays treat it as air
            completed.add(new CapturedSection(sx, sy, sz, gen, true, (char) 0xFFFF, null, null, List.of()));
            return;
        }
        int idx = sy - minSectionY;
        LevelChunkSection[] sections = chunk.getSections();
        if (idx < 0 || idx >= sections.length) return;
        LevelChunkSection section = sections[idx];
        if (section == null || section.hasOnlyAir()) {
            completed.add(new CapturedSection(sx, sy, sz, gen, true, Materials.AIR_ID, null, null, List.of()));
            slotStatus[slot] = ST_PENDING;
            return;
        }
        // snapshot on the render thread (same trick as vanilla meshing), resolve palette
        LevelChunkSection copy = section.copy();
        copy.getStates().forEachInPalette(materials::resolve);
        slotStatus[slot] = ST_PENDING;
        int maxLights = config.maxLightsPerSection;
        workers.submit(() -> {
            try {
                completed.add(SectionCapture.convert(sx, sy, sz, gen, copy.getStates(), materials, maxLights));
            } catch (Throwable t) {
                RtLog.LOG.error("Section capture failed for ({}, {}, {})", sx, sy, sz, t);
            }
        });
    }

    private void applyCompleted(CapturedSection cs, StorageUpdates out) {
        if (!inRange(cs.secX(), cs.secY(), cs.secZ())) return;
        int slot = slotIndex(cs.secX(), cs.secY(), cs.secZ());
        if (slotGeneration[slot] != cs.generation() || slotKey[slot] != key(cs.secX(), cs.secY(), cs.secZ()))
            return; // stale result

        int entry;
        if (cs.uniform() && cs.uniformMaterial() == (char) 0xFFFF) {
            entry = RtLayout.TABLE_ENTRY_UNLOADED;
            releaseBrick(slot);
            slotStatus[slot] = ST_UNLOADED;
        } else if (cs.uniform()) {
            entry = cs.uniformMaterial() == Materials.AIR_ID
                    ? RtLayout.TABLE_ENTRY_AIR
                    : (RtLayout.TABLE_UNIFORM_BIT | cs.uniformMaterial());
            releaseBrick(slot);
            slotStatus[slot] = ST_READY;
        } else {
            int brick = slotBrick[slot] >= 0 ? slotBrick[slot] : allocBrick();
            if (brick < 0) {
                entry = RtLayout.TABLE_ENTRY_AIR; // pool exhausted — degrade visibly but safely
            } else {
                slotBrick[slot] = brick;
                entry = brick;
                out.bricks.add(new StorageUpdates.BrickUpload(brick, cs.materials(), cs.occupancy()));
            }
            slotStatus[slot] = ST_READY;
        }
        out.patches.add(new StorageUpdates.SlotPatch(slot, entry, cs.lights().size(), packLights(cs.lights())));
    }

    private ByteBuffer packLights(List<CapturedSection.Light> lights) {
        if (lights.isEmpty()) return null;
        ByteBuffer b = ByteBuffer.allocateDirect(lights.size() * RtLayout.LIGHT_STRIDE_BYTES)
                .order(ByteOrder.nativeOrder());
        for (CapturedSection.Light l : lights) {
            b.putFloat(l.x()).putFloat(l.y()).putFloat(l.z()).putFloat(l.intensity());
            b.putFloat(l.r()).putFloat(l.g()).putFloat(l.b()).putFloat(0f);
        }
        b.flip();
        return b;
    }

    private ByteBuffer buildFullTable() {
        int slots = slotCount();
        ByteBuffer b = ByteBuffer.allocateDirect(slots * RtLayout.TABLE_STRIDE_INTS * 4)
                .order(ByteOrder.nativeOrder());
        for (int i = 0; i < slots; i++) {
            b.putInt(RtLayout.TABLE_ENTRY_UNLOADED).putInt(0);
        }
        b.flip();
        return b;
    }

    private int allocBrick() {
        Integer i = freeBricks.poll();
        if (i == null) {
            if (!warnedPoolFull) {
                warnedPoolFull = true;
                RtLog.LOG.warn("Brick pool exhausted ({} bricks) — raise brickPoolSize in config; "
                        + "distant sections will render as air", config.brickPoolSize);
            }
            return -1;
        }
        return i;
    }

    private void releaseBrick(int slot) {
        if (slotBrick[slot] >= 0) {
            freeBricks.add(slotBrick[slot]);
            slotBrick[slot] = -1;
        }
    }

    private void freeSlot(int slot, StorageUpdates out) {
        releaseBrick(slot);
        slotStatus[slot] = ST_UNLOADED;
        slotGeneration[slot]++;
        out.patches.add(new StorageUpdates.SlotPatch(slot, RtLayout.TABLE_ENTRY_UNLOADED, 0, null));
    }

    private boolean inRangeXZ(int sx, int sz) {
        return sx >= originSecX && sx < originSecX + gridX && sz >= originSecZ && sz < originSecZ + gridZ;
    }

    private boolean inRange(int sx, int sy, int sz) {
        return inRangeXZ(sx, sz) && sy >= minSectionY && sy < minSectionY + gridY;
    }

    private int slotIndex(int sx, int sy, int sz) {
        int lx = Math.floorMod(sx, gridX);
        int lz = Math.floorMod(sz, gridZ);
        int ly = sy - minSectionY;
        return (ly * gridZ + lz) * gridX + lx;
    }

    // 22/20/22-bit signed packing of section coords
    private static long key(int x, int y, int z) {
        return ((x & 0x3FFFFFL) << 42) | ((z & 0x3FFFFFL) << 20) | (y & 0xFFFFFL);
    }

    private static int keyX(long k) { return (int) (k << 0 >> 42); }
    private static int keyZ(long k) { return (int) (k << 22 >> 42); }
    private static int keyY(long k) { return (int) (k << 44 >> 44); }
}
