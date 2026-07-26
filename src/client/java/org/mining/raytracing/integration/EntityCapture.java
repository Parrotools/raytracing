package org.mining.raytracing.integration;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.mining.raytracing.core.RtConfig;

/**
 * Per-frame capture of nearby entities as AABB proxies (docs/02 §4). Positions are
 * converted to grid-local floats; each entity type gets a stable pastel tint.
 */
public final class EntityCapture {
    private ByteBuffer buffer;
    private int count;

    private record Candidate(AABB box, double dist2, int color) {}

    /** @return number of captured entities; buffer() holds 2×vec4 each. */
    public int capture(ClientLevel level, Vec3 camPos, double ox, double oy, double oz, RtConfig cfg) {
        count = 0;
        if (cfg.maxEntities <= 0) return 0;
        double range2 = (double) cfg.entityRange * cfg.entityRange;
        List<Candidate> found = new ArrayList<>();
        for (Entity e : level.entitiesForRendering()) {
            AABB box = e.getBoundingBox();
            if (box.contains(camPos)) continue; // the camera's own body (first person)
            double dist2 = e.position().distanceToSqr(camPos);
            if (dist2 > range2) continue;
            found.add(new Candidate(box, dist2, tint(e)));
        }
        found.sort(Comparator.comparingDouble(Candidate::dist2));
        int n = Math.min(found.size(), cfg.maxEntities);
        int needed = cfg.maxEntities * 32;
        if (buffer == null || buffer.capacity() < needed) {
            buffer = ByteBuffer.allocateDirect(needed).order(ByteOrder.nativeOrder());
        }
        buffer.clear();
        for (int i = 0; i < n; i++) {
            Candidate c = found.get(i);
            buffer.putFloat((float) (c.box.minX - ox)).putFloat((float) (c.box.minY - oy))
                    .putFloat((float) (c.box.minZ - oz)).putFloat(Float.intBitsToFloat(c.color));
            buffer.putFloat((float) (c.box.maxX - ox)).putFloat((float) (c.box.maxY - oy))
                    .putFloat((float) (c.box.maxZ - oz)).putFloat(0f);
        }
        buffer.flip();
        count = n;
        return n;
    }

    public ByteBuffer buffer() {
        return buffer;
    }

    public int count() {
        return count;
    }

    /** Stable muted tint per entity type, packed for GLSL unpackUnorm4x8 (r = low byte). */
    private static int tint(Entity e) {
        int h = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString().hashCode();
        float hue = (h & 0xFFFF) / 65535f;
        float sat = 0.35f, val = 0.62f;
        float c = val * sat, x = c * (1 - Math.abs((hue * 6f) % 2f - 1f)), m = val - c;
        float r, g, b;
        switch ((int) (hue * 6f) % 6) {
            case 0 -> { r = c; g = x; b = 0; }
            case 1 -> { r = x; g = c; b = 0; }
            case 2 -> { r = 0; g = c; b = x; }
            case 3 -> { r = 0; g = x; b = c; }
            case 4 -> { r = x; g = 0; b = c; }
            default -> { r = c; g = 0; b = x; }
        }
        int ri = (int) ((r + m) * 255), gi = (int) ((g + m) * 255), bi = (int) ((b + m) * 255);
        return ri | (gi << 8) | (bi << 16) | 0xFF000000;
    }
}
