// sky.glsl — analytic sky (docs/05 §10). Linear HDR; sun peak ~60, zenith ~1.2.

vec3 skyRadiance(vec3 dir, bool withSunDisc) {
    vec3 sun = g.sunDirCos.xyz;
    float rain = g.sunRadRain.w;
    float elev = sun.y;                       // sun elevation, -1..1
    float day = smoothstep(-0.08, 0.15, elev); // 0 night, 1 day

    // day gradient
    float horiz = pow(1.0 - clamp(dir.y, 0.0, 1.0), 3.0);
    vec3 zenith = mix(vec3(0.07, 0.14, 0.32), vec3(0.18, 0.38, 0.85), clamp(elev * 2.0, 0.0, 1.0));
    vec3 horizon = mix(vec3(0.9, 0.45, 0.25), vec3(0.75, 0.82, 0.92), clamp(elev * 3.0, 0.0, 1.0));
    vec3 dayCol = mix(zenith, horizon, horiz) * 3.6;

    // Mie-ish forward glow around the sun
    float cosSun = dot(dir, sun);
    dayCol += vec3(1.0, 0.75, 0.45) * pow(clamp(cosSun * 0.5 + 0.5, 0.0, 1.0), 8.0) * 0.55;

    // night: deep blue + moon (opposite the sun) + stars
    vec3 moonDir = -sun;
    vec3 nightCol = vec3(0.004, 0.006, 0.012) + vec3(0.012, 0.014, 0.02) * pow(1.0 - clamp(dir.y, 0.0, 1.0), 2.0);
    float cosMoon = dot(dir, moonDir);
    nightCol += vec3(0.5, 0.55, 0.65) * smoothstep(0.9997, 0.99995, cosMoon) * 0.6; // moon disc
    nightCol += vec3(0.2, 0.22, 0.28) * pow(clamp(cosMoon, 0.0, 1.0), 32.0) * 0.02; // moon glow
    // hash-grid stars
    vec3 sd = dir * 260.0;
    uvec3 cell = uvec3(ivec3(floor(sd)) + 1000);
    float star = float(pcg3d(cell).x & 1023u) / 1023.0;
    if (star > 0.997 && dir.y > 0.0) {
        vec3 f = fract(sd) - 0.5;
        float twinkle = smoothstep(0.35, 0.0, dot(f, f));
        nightCol += vec3(0.6, 0.62, 0.7) * twinkle * (star - 0.997) * 250.0;
    }

    vec3 col = mix(nightCol, dayCol, day);

    if (withSunDisc && day > 0.0) {
        float disc = smoothstep(g.sunDirCos.w - 0.0006, g.sunDirCos.w, cosSun);
        col += g.sunRadRain.xyz * disc;
    }

    // weather dims and desaturates
    float dampen = 1.0 - 0.72 * rain;
    col = mix(vec3(luminance(col)), col, 1.0 - 0.5 * rain) * dampen;
    return col;
}
