package org.mining.raytracing.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * User configuration, persisted to {@code config/raytracing.json}.
 * All fields are hot-mutable; resolution-affecting changes are picked up by the
 * core's lazy resize check each frame.
 */
public final class RtConfig {
    /** auto | opengl | vulkan | off — "auto" uses whichever backend Minecraft runs. */
    public String backend = "auto";
    public boolean enabled = true;

    // image
    public float renderScale = 0.75f;
    public float exposure = 0.14f; // calibrated: noon sunlit diffuse ≈ 0.8 post-ACES (docs/05 §10)

    // path tracing
    public int bounces = 2;
    public float sunAngularRadiusDeg = 1.5f;
    public float radianceClamp = 30f;

    // denoiser
    public int denoiseIterations = 3;
    public float temporalAlpha = 0.05f;
    public int maxHistory = 32;

    // acceleration structure
    public int sectionRadius = 8;          // horizontal radius in 16 m sections
    public int brickPoolSize = 6144;       // ≈ 28 MB of brick data
    public int sectionCapturesPerFrame = 128;
    public int maxLightsPerSection = 16;

    // entities
    public int maxEntities = 64;
    public float entityRange = 64f;

    // vulkan quirks
    public boolean vulkanFlipY = false;

    /** Re-hint the GL context to 4.6 core (vanilla asks for 3.3; compute needs ≥ 4.3). */
    public boolean raiseGlContext = true;

    public int debugView = 0; // 0 off, 1 albedo, 2 normal, 3 depth, 4 denoised irradiance

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static RtConfig load(Path file) {
        try {
            if (Files.exists(file)) {
                RtConfig cfg = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), RtConfig.class);
                if (cfg != null) { cfg.sanitize(); return cfg; }
            }
        } catch (Exception e) {
            RtLog.LOG.warn("Could not read {} — using defaults", file, e);
        }
        RtConfig cfg = new RtConfig();
        cfg.save(file);
        return cfg;
    }

    public void save(Path file) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(this), StandardCharsets.UTF_8);
        } catch (IOException e) {
            RtLog.LOG.warn("Could not save {}", file, e);
        }
    }

    public void sanitize() {
        renderScale = Math.clamp(renderScale, 0.25f, 1.0f);
        bounces = Math.clamp(bounces, 1, 4);
        denoiseIterations = Math.clamp(denoiseIterations, 0, 5);
        sectionRadius = Math.clamp(sectionRadius, 2, 16);
        brickPoolSize = Math.clamp(brickPoolSize, 512, 32768);
        maxLightsPerSection = Math.clamp(maxLightsPerSection, 0, 32);
        maxEntities = Math.clamp(maxEntities, 0, 256);
        temporalAlpha = Math.clamp(temporalAlpha, 0.01f, 1f);
        maxHistory = Math.clamp(maxHistory, 1, 256);
    }
}
