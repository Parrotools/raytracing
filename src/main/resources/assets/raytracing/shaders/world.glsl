// world.glsl — voxel scene access: section table, brick pool, materials,
// 3-level DDA (docs/05 §4), shadow rays, light selection, entity proxies.
// Included by pathtrace.comp only.

SSBO(1) restrict readonly buffer SectionTableB { ivec2 sectionTable[]; }; // {entry, lightCount}
SSBO(2) restrict readonly buffer BrickMatsB   { uint brickMats[]; };      // 2048 uints / brick
SSBO(3) restrict readonly buffer BrickOccB    { uvec2 brickOcc[]; };      // 64 uvec2 / brick
SSBO(4) restrict readonly buffer MaterialsB   { vec4 matData[]; };        // 3 vec4 / material
SSBO(5) restrict readonly buffer LightsB      { vec4 lightData[]; };      // 2 vec4 / light
SSBO(6) restrict readonly buffer EntitiesB    { vec4 entityData[]; };     // 2 vec4 / entity

const int  ENTRY_AIR = -1;
const int  ENTRY_UNLOADED = -2;
const int  UNIFORM_BIT = 0x40000000;
const uint MF_NON_OCCLUDING = 1u, MF_FLUID = 2u, MF_SSS = 4u;

struct Mat {
    vec3 albedo; float rough;
    vec3 emission; float metal;
    float ior; float trans; uint flags; float sss;
};

Mat loadMat(uint id) {
    vec4 a = matData[id * 3u], b = matData[id * 3u + 1u], c = matData[id * 3u + 2u];
    Mat m;
    m.albedo = a.rgb; m.rough = a.w;
    m.emission = b.rgb; m.metal = b.w;
    m.ior = c.x; m.trans = c.y; m.flags = floatBitsToUint(c.z); m.sss = c.w;
    return m;
}

// wrapped slot addressing — must match VoxelWorldStore.slotIndex
int slotOf(ivec3 secLocal) {
    int ox = floatBitsToInt(g.misc.y), oz = floatBitsToInt(g.misc.z);
    ivec3 gs = g.gridSizePool.xyz;
    int lx = ((secLocal.x + ox) % gs.x + gs.x) % gs.x;
    int lz = ((secLocal.z + oz) % gs.z + gs.z) % gs.z;
    return (secLocal.y * gs.z + lz) * gs.x + lx;
}

struct HitInfo { float t; vec3 n; uint mat; };

// ---- level 2: voxel DDA inside one occupied 4³ cell ------------------------
bool traceCell(int brick, ivec3 sec, ivec3 cell, uvec2 mask,
               vec3 ro, vec3 rd, vec3 invD, float tIn, float tOut, uint skipMat,
               inout vec3 inNormal, out HitInfo hit) {
    vec3 base = vec3(sec) * 16.0 + vec3(cell) * 4.0;
    vec3 p = ro + (tIn + 1e-4) * rd;
    ivec3 v = clamp(ivec3(floor(p - base)), ivec3(0), ivec3(3));
    ivec3 stepv = ivec3(sign(rd));
    vec3 tDelta = abs(invD);
    vec3 tNext = (base + vec3(v) + max(vec3(stepv), 0.0) - ro) * invD;
    float t = tIn;
    for (int i = 0; i < 12; i++) {
        int bit = ((v.z * 4) + v.y) * 4 + v.x;
        bool occ = bit < 32 ? (mask.x & (1u << bit)) != 0u : (mask.y & (1u << (bit - 32))) != 0u;
        if (occ) {
            ivec3 lv = cell * 4 + v;
            int linear = ((lv.z * 16) + lv.y) * 16 + lv.x;
            uint word = brickMats[brick * 2048 + (linear >> 1)];
            uint m = (linear & 1) == 0 ? (word & 0xFFFFu) : (word >> 16);
            if (m != 0u && m != skipMat) {
                hit.t = t; hit.n = inNormal; hit.mat = m;
                return true;
            }
        }
        int axis = tNext.x < tNext.y ? (tNext.x < tNext.z ? 0 : 2) : (tNext.y < tNext.z ? 1 : 2);
        t = tNext[axis];
        if (t > tOut) return false;
        v[axis] += stepv[axis];
        if (v[axis] < 0 || v[axis] > 3) return false;
        inNormal = vec3(0.0);
        inNormal[axis] = -float(stepv[axis]);
        tNext[axis] += tDelta[axis];
    }
    return false;
}

// ---- level 1: 4 m cell DDA inside one 16³ brick -----------------------------
bool traceBrick(int brick, ivec3 sec,
                vec3 ro, vec3 rd, vec3 invD, float tIn, float tOut, uint skipMat,
                inout vec3 inNormal, out HitInfo hit) {
    vec3 base = vec3(sec) * 16.0;
    vec3 p = ro + (tIn + 1e-4) * rd;
    ivec3 c = clamp(ivec3(floor((p - base) / 4.0)), ivec3(0), ivec3(3));
    ivec3 stepv = ivec3(sign(rd));
    vec3 tDelta = 4.0 * abs(invD);
    vec3 tNext = (base + (vec3(c) + max(vec3(stepv), 0.0)) * 4.0 - ro) * invD;
    float t = tIn;
    for (int i = 0; i < 12; i++) {
        uvec2 mask = brickOcc[brick * 64 + ((c.z * 4) + c.y) * 4 + c.x];
        if ((mask.x | mask.y) != 0u) {
            float cellExit = min(min(tNext.x, tNext.y), tNext.z);
            if (traceCell(brick, sec, c, mask, ro, rd, invD, t, min(cellExit, tOut), skipMat, inNormal, hit))
                return true;
        }
        int axis = tNext.x < tNext.y ? (tNext.x < tNext.z ? 0 : 2) : (tNext.y < tNext.z ? 1 : 2);
        t = tNext[axis];
        if (t > tOut) return false;
        c[axis] += stepv[axis];
        if (c[axis] < 0 || c[axis] > 3) return false;
        inNormal = vec3(0.0);
        inNormal[axis] = -float(stepv[axis]);
        tNext[axis] += tDelta[axis];
    }
    return false;
}

// ---- level 0: section DDA over the whole grid -------------------------------
bool traceScene(vec3 ro, vec3 rd, float tMax, uint skipMat, out HitInfo hit) {
    // avoid division hazards on axis-aligned rays
    rd.x = abs(rd.x) < 1e-6 ? 1e-6 : rd.x;
    rd.y = abs(rd.y) < 1e-6 ? 1e-6 : rd.y;
    rd.z = abs(rd.z) < 1e-6 ? 1e-6 : rd.z;
    vec3 invD = 1.0 / rd;

    vec3 gridMax = vec3(g.gridSizePool.xyz) * 16.0;
    vec3 t0v = (vec3(0.0) - ro) * invD;
    vec3 t1v = (gridMax - ro) * invD;
    vec3 tminv = min(t0v, t1v), tmaxv = max(t0v, t1v);
    float tEnter = max(max(tminv.x, tminv.y), max(tminv.z, 0.0));
    float tExit = min(min(tmaxv.x, tmaxv.y), min(tmaxv.z, tMax));
    if (tEnter > tExit) return false;

    vec3 inNormal;
    if (tEnter > 0.0) {
        inNormal = vec3(0.0);
        if (tminv.x >= tminv.y && tminv.x >= tminv.z) inNormal.x = -sign(rd.x);
        else if (tminv.y >= tminv.z) inNormal.y = -sign(rd.y);
        else inNormal.z = -sign(rd.z);
    } else {
        inNormal = -rd; // started inside a cell; degenerate but consistent
    }

    vec3 p = ro + (tEnter + 1e-4) * rd;
    ivec3 sec = clamp(ivec3(floor(p / 16.0)), ivec3(0), g.gridSizePool.xyz - 1);
    ivec3 stepv = ivec3(sign(rd));
    vec3 tDelta = 16.0 * abs(invD);
    vec3 tNext = ((vec3(sec) + max(vec3(stepv), 0.0)) * 16.0 - ro) * invD;
    float t = tEnter;

    for (int i = 0; i < 160; i++) {
        ivec2 e2 = sectionTable[slotOf(sec)];
        int e = e2.x;
        if (e != ENTRY_AIR && e != ENTRY_UNLOADED) {
            if ((e & UNIFORM_BIT) != 0) {
                uint m = uint(e & 0xFFFF);
                if (m != skipMat) {
                    hit.t = t; hit.n = inNormal; hit.mat = m;
                    return true;
                }
            } else {
                float secExit = min(min(tNext.x, tNext.y), tNext.z);
                if (traceBrick(e, sec, ro, rd, invD, t, min(secExit, tExit), skipMat, inNormal, hit))
                    return true;
            }
        }
        int axis = tNext.x < tNext.y ? (tNext.x < tNext.z ? 0 : 2) : (tNext.y < tNext.z ? 1 : 2);
        t = tNext[axis];
        if (t > tExit) return false;
        sec[axis] += stepv[axis];
        if (sec[axis] < 0 || sec[axis] >= g.gridSizePool[axis]) return false;
        inNormal = vec3(0.0);
        inNormal[axis] = -float(stepv[axis]);
        tNext[axis] += tDelta[axis];
    }
    return false;
}

// ---- entity AABB proxies ----------------------------------------------------
// Returns hit index (or -1); shrinks tBest.
int intersectEntities(vec3 ro, vec3 rd, inout float tBest, out vec3 n, out vec3 color) {
    int best = -1;
    n = vec3(0, 1, 0);
    color = vec3(0.5);
    vec3 invD = 1.0 / mix(rd, vec3(1e-6), lessThan(abs(rd), vec3(1e-6)));
    for (int i = 0; i < g.counts.y; i++) {
        vec4 a = entityData[i * 2], b = entityData[i * 2 + 1];
        vec3 t0v = (a.xyz - ro) * invD, t1v = (b.xyz - ro) * invD;
        vec3 tminv = min(t0v, t1v), tmaxv = max(t0v, t1v);
        float tN = max(max(tminv.x, tminv.y), tminv.z);
        float tF = min(min(tmaxv.x, tmaxv.y), tmaxv.z);
        if (tN <= tF && tN > RAY_EPS && tN < tBest) {
            best = i;
            tBest = tN;
            n = vec3(0.0);
            if (tminv.x >= tminv.y && tminv.x >= tminv.z) n.x = -sign(rd.x);
            else if (tminv.y >= tminv.z) n.y = -sign(rd.y);
            else n.z = -sign(rd.z);
            color = unpackUnorm4x8(floatBitsToUint(a.w)).rgb;
        }
    }
    return best;
}

bool entityBlocks(vec3 ro, vec3 rd, float maxDist) {
    float t = maxDist;
    vec3 n, c;
    return intersectEntities(ro, rd, t, n, c) >= 0;
}

// ---- shadow ray with colored transmission (docs/07) -------------------------
vec3 traceShadow(vec3 ro, vec3 rd, float maxDist) {
    vec3 atten = vec3(1.0);
    vec3 o = ro;
    float remaining = maxDist;
    uint skip = 0u;
    for (int i = 0; i < 4; i++) {
        HitInfo h;
        if (!traceScene(o, rd, remaining, skip, h)) {
            if (entityBlocks(o, rd, min(remaining, 48.0))) atten *= 0.25;
            return atten;
        }
        Mat m = loadMat(h.mat);
        if (m.trans <= 0.01) return vec3(0.0);
        atten *= m.albedo * m.trans;
        skip = h.mat; // single-run-per-medium approximation
        float adv = h.t + RAY_EPS;
        o += rd * adv;
        remaining -= adv;
        if (remaining <= 0.0) return atten;
    }
    return vec3(0.0);
}

// ---- emissive light selection: weighted reservoir over 3³ sections ----------
bool pickLight(vec3 P, inout uint rng, out vec3 lpos, out vec3 lrad, out float invPdf) {
    int lps = g.counts.x;
    if (lps == 0) return false;
    float wsum = 0.0, selW = 0.0;
    vec3 selPos = vec3(0.0), selCol = vec3(0.0);
    ivec3 secC = ivec3(floor(P / 16.0));
    for (int dz = -1; dz <= 1; dz++)
    for (int dy = -1; dy <= 1; dy++)
    for (int dx = -1; dx <= 1; dx++) {
        ivec3 s = secC + ivec3(dx, dy, dz);
        if (any(lessThan(s, ivec3(0))) || any(greaterThanEqual(s, g.gridSizePool.xyz))) continue;
        int slot = slotOf(s);
        int cnt = min(sectionTable[slot].y, lps);
        int baseIdx = slot * lps;
        for (int i = 0; i < cnt; i++) {
            vec4 pI = lightData[(baseIdx + i) * 2];
            vec3 wp = vec3(s) * 16.0 + pI.xyz; // section-relative reconstruction
            float d2 = max(dot(wp - P, wp - P), 0.25);
            float w = pI.w / d2;
            wsum += w;
            if (rnd(rng) * wsum < w) {
                selPos = wp;
                selCol = lightData[(baseIdx + i) * 2 + 1].rgb;
                selW = w;
            }
        }
    }
    if (selW <= 0.0) return false;
    invPdf = wsum / selW;
    lpos = selPos;
    lrad = selCol;
    return true;
}
