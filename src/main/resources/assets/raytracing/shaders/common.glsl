// common.glsl — globals block + shared math. Included by every kernel.
// Layout must match RtLayout.packGlobals (Java) byte for byte.

SSBO(0) restrict readonly buffer GlobalsB {
    mat4  invViewProj;   // clip -> grid-local world (current frame)
    mat4  prevViewProj;  // grid-local world -> previous clip (re-expressed in current grid)
    vec4  camPos;        // xyz grid-local
    vec4  prevCamPos;    // xyz grid-local (current grid frame)
    vec4  camForward;    // xyz
    vec4  sunDirCos;     // xyz sun dir (to sun), w cos(sun angular radius)
    vec4  sunRadRain;    // xyz sun radiance, w rain level
    ivec4 gridSizePool;  // xyz grid size in sections, w brick pool size
    ivec4 rtOut;         // rtW, rtH, outW, outH
    ivec4 frameParams;   // frameIndex, bounces, debugView, maxHistory
    vec4  tuning;        // temporalAlpha, radianceClamp, exposure, thunder
    ivec4 counts;        // lightsPerSection, entityCount, denoiseIterations, flags (bit0 = resetHistory)
    vec4  misc;          // sunAngularRadius(rad), asint(originSecX), asint(originSecZ), 0
} g;

const float PI = 3.14159265358979;
const float INF = 1e30;
const float RAY_EPS = 1e-3;

float luminance(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }

// ---- ray generation: depth-convention agnostic (docs/07) -------------------
vec3 primaryRayDir(vec2 ndc) {
    vec4 a = g.invViewProj * vec4(ndc, 0.2, 1.0);
    vec4 b = g.invViewProj * vec4(ndc, 0.8, 1.0);
    vec3 d = normalize(b.xyz / b.w - a.xyz / a.w);
    // orient regardless of GL/VK/reversed-Z conventions
    return d * sign(dot(d, g.camForward.xyz));
}

// ---- random: pcg (Jarzynski & Olano 2020) ----------------------------------
uvec3 pcg3d(uvec3 v) {
    v = v * 1664525u + 1013904223u;
    v.x += v.y * v.z; v.y += v.z * v.x; v.z += v.x * v.y;
    v ^= v >> 16u;
    v.x += v.y * v.z; v.y += v.z * v.x; v.z += v.x * v.y;
    return v;
}

uint rngInit(uvec2 px, uint frame) {
    uvec3 h = pcg3d(uvec3(px, frame));
    return h.x ^ h.y ^ h.z;
}

float rnd(inout uint state) {
    state = state * 747796405u + 2891336453u;
    uint w = ((state >> ((state >> 28u) + 4u)) ^ state) * 277803737u;
    w = (w >> 22u) ^ w;
    return float(w) * (1.0 / 4294967296.0);
}

// ---- frame helpers ---------------------------------------------------------
mat3 buildBasis(vec3 n) {
    vec3 t = abs(n.y) < 0.99 ? normalize(cross(vec3(0, 1, 0), n)) : vec3(1, 0, 0);
    return mat3(t, cross(n, t), n);
}

vec3 cosineSample(vec3 n, inout uint rng) {
    float u1 = rnd(rng), u2 = rnd(rng);
    float r = sqrt(u1), phi = 2.0 * PI * u2;
    vec3 local = vec3(r * cos(phi), r * sin(phi), sqrt(max(0.0, 1.0 - u1)));
    return normalize(buildBasis(n) * local);
}

vec3 coneSample(vec3 dir, float cosMax, inout uint rng) {
    float u1 = rnd(rng), u2 = rnd(rng);
    float ct = 1.0 - u1 * (1.0 - cosMax);
    float st = sqrt(max(0.0, 1.0 - ct * ct));
    float phi = 2.0 * PI * u2;
    return normalize(buildBasis(dir) * vec3(st * cos(phi), st * sin(phi), ct));
}
