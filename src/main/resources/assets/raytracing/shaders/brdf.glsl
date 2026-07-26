// brdf.glsl — Cook-Torrance GGX + Lambert, metalness workflow (docs/05 §3).

vec3 fresnelSchlick(vec3 f0, float cosTheta) {
    float m = clamp(1.0 - cosTheta, 0.0, 1.0);
    float m2 = m * m;
    return f0 + (1.0 - f0) * m2 * m2 * m;
}

float ggxD(float noh, float alpha) {
    float a2 = alpha * alpha;
    float d = noh * noh * (a2 - 1.0) + 1.0;
    return a2 / max(PI * d * d, 1e-7);
}

// Smith height-correlated visibility V = G / (4 NoL NoV)
float smithV(float nov, float nol, float alpha) {
    float a2 = alpha * alpha;
    float gv = nol * sqrt(nov * nov * (1.0 - a2) + a2);
    float gl = nov * sqrt(nol * nol * (1.0 - a2) + a2);
    return 0.5 / max(gv + gl, 1e-7);
}

float smithG1(float nov, float alpha) {
    float a2 = alpha * alpha;
    return 2.0 * nov / max(nov + sqrt(a2 + (1.0 - a2) * nov * nov), 1e-7);
}

// Full BRDF eval (both lobes), for NEE. wo/wi in world space, both pointing away from surface.
vec3 brdfEval(vec3 albedo, float rough, float metal, vec3 n, vec3 wo, vec3 wi) {
    float nol = dot(n, wi), nov = dot(n, wo);
    if (nol <= 0.0 || nov <= 0.0) return vec3(0.0);
    vec3 h = normalize(wo + wi);
    float noh = clamp(dot(n, h), 0.0, 1.0);
    float voh = clamp(dot(wo, h), 0.0, 1.0);
    float alpha = max(rough * rough, 1e-3);
    vec3 f0 = mix(vec3(0.04), albedo, metal);
    vec3 F = fresnelSchlick(f0, voh);
    vec3 spec = F * (ggxD(noh, alpha) * smithV(nov, nol, alpha));
    vec3 diff = (1.0 - F) * (1.0 - metal) * albedo / PI;
    return diff + spec;
}

// GGX VNDF sampling (Heitz 2018). Returns half-vector in world space.
vec3 sampleVNDF(vec3 n, vec3 wo, float alpha, inout uint rng) {
    mat3 tbn = buildBasis(n);
    vec3 v = normalize(transpose(tbn) * wo); // to tangent space, z = normal
    vec3 vh = normalize(vec3(alpha * v.x, alpha * v.y, v.z));
    float lensq = vh.x * vh.x + vh.y * vh.y;
    vec3 T1 = lensq > 0.0 ? vec3(-vh.y, vh.x, 0.0) * inversesqrt(lensq) : vec3(1, 0, 0);
    vec3 T2 = cross(vh, T1);
    float u1 = rnd(rng), u2 = rnd(rng);
    float r = sqrt(u1), phi = 2.0 * PI * u2;
    float t1 = r * cos(phi);
    float t2 = r * sin(phi);
    float s = 0.5 * (1.0 + vh.z);
    t2 = (1.0 - s) * sqrt(max(0.0, 1.0 - t1 * t1)) + s * t2;
    vec3 nh = t1 * T1 + t2 * T2 + sqrt(max(0.0, 1.0 - t1 * t1 - t2 * t2)) * vh;
    vec3 h = normalize(vec3(alpha * nh.x, alpha * nh.y, max(0.0, nh.z)));
    return normalize(tbn * h);
}

// Sample specular continuation; returns direction, sets throughput weight = F * G2/G1.
vec3 sampleSpecular(vec3 n, vec3 wo, vec3 albedo, float rough, float metal,
                    out vec3 weight, inout uint rng) {
    float alpha = max(rough * rough, 1e-3);
    vec3 h = sampleVNDF(n, wo, alpha, rng);
    vec3 wi = reflect(-wo, h);
    float nol = dot(n, wi), nov = max(dot(n, wo), 1e-4);
    if (nol <= 0.0) { weight = vec3(0.0); return wi; }
    vec3 f0 = mix(vec3(0.04), albedo, metal);
    vec3 F = fresnelSchlick(f0, clamp(dot(wo, h), 0.0, 1.0));
    // VNDF estimator: f * cos / pdf = F * G2/G1
    float g1 = smithG1(nov, alpha);
    float g2 = 4.0 * nol * nov * smithV(nov, nol, alpha); // height-correlated G2
    weight = F * (g2 / max(g1, 1e-7));
    return wi;
}
