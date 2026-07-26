package org.mining.raytracing.materials;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.mining.raytracing.core.RtLog;

/**
 * BlockState → material-id registry (docs/06-materials.md).
 *
 * <p>Resolution priority: exact block id from JSON → wildcard rule → synthesized
 * from MapColor + light emission. Ids are stable for the lifetime of the registry.
 *
 * <p>Thread model: {@link #resolve} must be called on the render thread (it may
 * append to the table); {@link #idFor} is a lock-free lookup safe from worker
 * threads once the render thread has resolved the state (ConcurrentHashMap and
 * BlockState identity semantics guarantee safe publication).
 */
public final class Materials {
    public static final char AIR_ID = 0;

    // CopyOnWrite: workers read concurrently while the render thread interns new materials
    private final List<RtMaterial> table = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<BlockState, Character> stateCache = new ConcurrentHashMap<>();
    private final Map<String, Character> byBlockId = new LinkedHashMap<>();
    private final List<Rule> rules = new ArrayList<>();
    private final Map<Long, Character> synthesized = new LinkedHashMap<>();
    private volatile int version = 1; // bumped on every table append → triggers re-upload

    private record Rule(Pattern pattern, RtMaterial material) {}

    public Materials(Path userOverride) {
        table.add(new RtMaterial(0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0)); // id 0: air
        try (InputStream in = Materials.class.getResourceAsStream("/assets/raytracing/materials.json")) {
            if (in == null) throw new IllegalStateException("materials.json missing from mod resources");
            loadJson(new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class));
        } catch (Exception e) {
            RtLog.LOG.error("Failed to load built-in materials.json", e);
        }
        if (userOverride != null && Files.exists(userOverride)) {
            try {
                loadJson(new Gson().fromJson(Files.readString(userOverride), JsonObject.class));
                RtLog.LOG.info("Applied material overrides from {}", userOverride);
            } catch (Exception e) {
                RtLog.LOG.warn("Ignoring bad material override file {}", userOverride, e);
            }
        }
    }

    private void loadJson(JsonObject root) {
        if (root.has("blocks")) {
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("blocks").entrySet()) {
                char id = intern(parseMaterial(e.getValue().getAsJsonObject()));
                byBlockId.put(e.getKey().contains(":") ? e.getKey() : "minecraft:" + e.getKey(), id);
            }
        }
        if (root.has("rules")) {
            for (JsonElement e : root.getAsJsonArray("rules")) {
                JsonObject o = e.getAsJsonObject();
                String glob = o.get("match").getAsString();
                Pattern p = Pattern.compile(("\\Q" + glob.replace("*", "\\E.*\\Q") + "\\E").replace("\\Q\\E", ""));
                rules.add(new Rule(p, parseMaterial(o)));
            }
        }
    }

    private RtMaterial parseMaterial(JsonObject o) {
        float[] albedo = o.has("albedo") ? srgbHexToLinear(o.get("albedo").getAsString()) : new float[]{0.5f, 0.5f, 0.5f};
        float rough = o.has("roughness") ? o.get("roughness").getAsFloat() : 0.85f;
        float metal = o.has("metallic") ? o.get("metallic").getAsFloat() : 0f;
        float[] em = new float[3];
        if (o.has("emission")) {
            JsonArray a = o.getAsJsonArray("emission");
            em = new float[]{a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat()};
        }
        float ior = o.has("ior") ? o.get("ior").getAsFloat() : 1.0f;
        float trans = o.has("transmission") ? o.get("transmission").getAsFloat() : 0f;
        float sss = o.has("sss") ? o.get("sss").getAsFloat() : 0f;
        int flags = 0;
        if (o.has("flags")) {
            for (JsonElement f : o.getAsJsonArray("flags")) {
                flags |= switch (f.getAsString()) {
                    case "nonOccluding" -> RtMaterial.F_NON_OCCLUDING;
                    case "fluid" -> RtMaterial.F_FLUID;
                    case "sss" -> RtMaterial.F_SSS;
                    default -> 0;
                };
            }
        }
        if (sss > 0) flags |= RtMaterial.F_SSS;
        return new RtMaterial(albedo[0], albedo[1], albedo[2], rough, em[0], em[1], em[2], metal, ior, trans, flags, sss);
    }

    /** Render thread only: get-or-create the material id for a state. */
    public char resolve(BlockState state) {
        Character cached = stateCache.get(state);
        if (cached != null) return cached;
        char id = state.isAir() ? AIR_ID : resolveUncached(state);
        stateCache.put(state, id);
        return id;
    }

    /** Worker-thread safe lookup; returns AIR for never-resolved states (callers pre-resolve palettes). */
    public char idFor(BlockState state) {
        Character c = stateCache.get(state);
        return c != null ? c : AIR_ID;
    }

    private char resolveUncached(BlockState state) {
        String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        Character exact = byBlockId.get(blockId);
        if (exact != null) return exact;
        for (Rule r : rules) {
            if (r.pattern.matcher(blockId).matches()) return intern(withStateEmission(r.material, state));
        }
        return synthesize(state);
    }

    /** Rules carry no per-block emission; inherit it from the state's light level. */
    private RtMaterial withStateEmission(RtMaterial m, BlockState state) {
        int light = state.getLightEmission();
        if (light <= 0 || m.emissive()) return m;
        float s = (light / 15f) * 6f;
        return new RtMaterial(m.r(), m.g(), m.b(), m.roughness(),
                s, s * 0.82f, s * 0.55f, m.metallic(), m.ior(), m.transmission(), m.flags(), m.sss());
    }

    private char synthesize(BlockState state) {
        int argb;
        try {
            argb = state.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).col;
        } catch (Exception e) {
            argb = 0x7F7F7F;
        }
        int light = state.getLightEmission();
        long key = ((long) argb << 8) | light;
        Character dedup = synthesized.get(key);
        if (dedup != null) return dedup;

        float r = srgbToLinear(((argb >> 16) & 0xFF) / 255f);
        float g = srgbToLinear(((argb >> 8) & 0xFF) / 255f);
        float b = srgbToLinear((argb & 0xFF) / 255f);
        float s = (light / 15f) * 6f;
        char id = intern(new RtMaterial(r, g, b, 0.85f, s * Math.max(r, 0.5f), s * Math.max(g, 0.4f),
                s * Math.max(b, 0.25f), 0, 1, 0, 0, 0));
        synthesized.put(key, id);
        return id;
    }

    private char intern(RtMaterial m) {
        table.add(m);
        version++;
        return (char) (table.size() - 1);
    }

    public RtMaterial get(char id) {
        return table.get(id);
    }

    public int version() {
        return version;
    }

    public int count() {
        return table.size();
    }

    /** Pack the whole table for upload (render thread). */
    public ByteBuffer pack() {
        ByteBuffer buf = ByteBuffer.allocateDirect(table.size() * 48).order(ByteOrder.nativeOrder());
        for (RtMaterial m : table) m.pack(buf);
        buf.flip();
        return buf;
    }

    private static float[] srgbHexToLinear(String hex) {
        int v = Integer.parseInt(hex.replace("#", ""), 16);
        return new float[]{
                srgbToLinear(((v >> 16) & 0xFF) / 255f),
                srgbToLinear(((v >> 8) & 0xFF) / 255f),
                srgbToLinear((v & 0xFF) / 255f)};
    }

    private static float srgbToLinear(float c) {
        return c <= 0.04045f ? c / 12.92f : (float) Math.pow((c + 0.055f) / 1.055f, 2.4);
    }
}
