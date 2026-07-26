package org.mining.raytracing.core;

import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanGpuTexture;
import java.nio.file.Path;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.mining.raytracing.gpu.FrameData;
import org.mining.raytracing.gpu.RtBackend;
import org.mining.raytracing.gpu.StorageUpdates;
import org.mining.raytracing.gpu.gl.GlRtBackend;
import org.mining.raytracing.gpu.vk.VkRtBackend;
import org.mining.raytracing.accel.VoxelWorldStore;
import org.mining.raytracing.integration.EntityCapture;
import org.mining.raytracing.integration.Keybinds;
import org.mining.raytracing.materials.Materials;
import org.mining.raytracing.mixin.client.GpuDeviceAccessor;

/**
 * Orchestrator (docs/03): owns config, material registry, voxel store and the active
 * GPU backend; drives the per-frame sequence from the LevelRenderer TAIL hook.
 * Render thread only.
 */
public final class RaytracerCore {
    private static RaytracerCore instance;

    private final RtConfig config;
    private final Path configFile;
    private final Materials materials;
    private final VoxelWorldStore store;
    private final Keybinds keybinds = new Keybinds();
    private final EntityCapture entityCapture = new EntityCapture();
    private final FrameData frame = new FrameData();

    private RtBackend backend;
    private boolean backendInitTried;
    private boolean disabled;
    private ClientLevel trackedLevel;
    private int lastRtW, lastRtH, lastOutW, lastOutH;
    private float lastScale;
    private int frameIndex;

    // previous-frame camera (absolute coords; re-expressed per frame, docs/02 §5)
    private final Matrix4f prevProjRot = new Matrix4f();
    private final Vector3d prevCamAbs = new Vector3d();
    private boolean hasPrev;

    private RaytracerCore(Path configDir) {
        this.configFile = configDir.resolve("raytracing.json");
        this.config = RtConfig.load(configFile);
        this.materials = new Materials(configDir.resolve("raytracing").resolve("materials.json"));
        this.store = new VoxelWorldStore(config, materials);
    }

    public static void bootstrap(Path configDir) {
        if (instance == null) {
            instance = new RaytracerCore(configDir);
            RtLog.LOG.info("Raytracing initialized (backend: {}, enabled: {})",
                    instance.config.backend, instance.config.enabled);
        }
    }

    public static RaytracerCore get() {
        return instance;
    }

    /** Used by GlBackendMixin during window creation (before/without a full instance). */
    public static boolean shouldRaiseGlContext() {
        RaytracerCore c = instance;
        return c == null || c.config.raiseGlContext;
    }

    /** Autotest diagnostics. */
    public void debugAuditStore() {
        store.debugAudit();
    }

    // ------------------------------------------------------------------ mixin entry points

    /** Called at the TAIL of LevelRenderer.render — the world image is complete. */
    public static void onLevelRendered(CameraRenderState cam) {
        RaytracerCore c = instance;
        if (c == null || c.disabled) return;
        try {
            c.renderFrame(cam);
            if (org.mining.raytracing.integration.AutoTest.ENABLED) {
                org.mining.raytracing.integration.AutoTest.onFrameRendered(Minecraft.getInstance(), c.config);
            }
        } catch (Throwable t) {
            RtLog.LOG.error("Ray tracing frame failed — disabling (restart the game to retry)", t);
            c.disabled = true;
        }
    }

    public static void onBlockDirty(BlockPos pos) {
        RaytracerCore c = instance;
        if (c != null && !c.disabled) c.store.markBlockDirty(pos.getX(), pos.getY(), pos.getZ());
    }

    public static void onChunkLoaded(int cx, int cz) {
        RaytracerCore c = instance;
        if (c != null && !c.disabled) c.store.markColumnLoaded(cx, cz);
    }

    public static void onChunkUnloaded(int cx, int cz) {
        RaytracerCore c = instance;
        if (c != null && !c.disabled) c.store.markColumnUnloaded(cx, cz);
    }

    // ------------------------------------------------------------------ frame

    private void renderFrame(CameraRenderState cam) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || !cam.initialized) return;

        if (keybinds.poll(config)) config.save(configFile);
        if (!config.enabled || "off".equals(config.backend)) return;

        if (backend == null) {
            if (backendInitTried) return;
            backendInitTried = true;
            backend = createBackend(mc);
            if (backend == null || !backend.init(config)) {
                backend = null;
                disabled = true;
                RtLog.LOG.warn("No usable ray tracing backend — mod inactive");
                return;
            }
            RtLog.LOG.info("Ray tracing active: {}", backend.name());
        }

        Vec3 camPos = cam.pos;
        int camSecX = ((int) Math.floor(camPos.x)) >> 4;
        int camSecZ = ((int) Math.floor(camPos.z)) >> 4;

        if (level != trackedLevel || !store.ready()) {
            store.reset(level, camSecX, camSecZ);
            trackedLevel = level;
            hasPrev = false;
        }

        RenderTarget target = mc.gameRenderer.mainRenderTarget();
        int outW = Math.max(target.width, 1), outH = Math.max(target.height, 1);
        int rtW = Math.max(Math.round(outW * config.renderScale), 32);
        int rtH = Math.max(Math.round(outH * config.renderScale), 32);
        if (rtW != lastRtW || rtH != lastRtH || outW != lastOutW || outH != lastOutH
                || lastScale != config.renderScale) {
            backend.resize(rtW, rtH, outW, outH);
            lastRtW = rtW; lastRtH = rtH; lastOutW = outW; lastOutH = outH;
            lastScale = config.renderScale;
            hasPrev = false;
        }

        StorageUpdates updates = new StorageUpdates();
        store.tick(level, camSecX, camSecZ, updates);
        backend.uploadStorage(updates);

        buildFrameData(mc, level, cam, target, rtW, rtH, outW, outH);
        backend.renderFrame(frame);

        // stash this frame's camera for next frame's reprojection
        prevProjRot.set(new Matrix4f(cam.projectionMatrix).mul(cam.viewRotationMatrix));
        prevCamAbs.set(camPos.x, camPos.y, camPos.z);
        hasPrev = true;
        frameIndex++;
    }

    private RtBackend createBackend(Minecraft mc) {
        GpuTexture color = mc.gameRenderer.mainRenderTarget().getColorTexture();
        boolean wantGl = "auto".equals(config.backend) || "opengl".equals(config.backend);
        boolean wantVk = "auto".equals(config.backend) || "vulkan".equals(config.backend);
        if (wantGl && color instanceof GlTexture) {
            return new GlRtBackend();
        }
        if (wantVk && color instanceof VulkanGpuTexture) {
            Object be = ((GpuDeviceAccessor) (Object) RenderSystem.getDevice()).raytracing$getBackend();
            if (be instanceof VulkanDevice vd) return new VkRtBackend(vd);
        }
        RtLog.LOG.warn("Configured backend '{}' does not match the active Minecraft renderer ({})",
                config.backend, color.getClass().getSimpleName());
        return null;
    }

    private void buildFrameData(Minecraft mc, ClientLevel level, CameraRenderState cam,
                                RenderTarget target, int rtW, int rtH, int outW, int outH) {
        FrameData f = frame;
        f.rtWidth = rtW; f.rtHeight = rtH; f.outWidth = outW; f.outHeight = outH;

        double ox = store.originMetersX(), oy = store.originMetersY(), oz = store.originMetersZ();
        Vec3 camPos = cam.pos;
        f.camPos.set((float) (camPos.x - ox), (float) (camPos.y - oy), (float) (camPos.z - oz));

        Matrix4f projRot = new Matrix4f(cam.projectionMatrix).mul(cam.viewRotationMatrix);
        new Matrix4f(projRot)
                .translate(-f.camPos.x, -f.camPos.y, -f.camPos.z)
                .invert(f.invViewProj);

        if (hasPrev) {
            f.prevCamPos.set((float) (prevCamAbs.x - ox), (float) (prevCamAbs.y - oy), (float) (prevCamAbs.z - oz));
            new Matrix4f(prevProjRot)
                    .translate(-f.prevCamPos.x, -f.prevCamPos.y, -f.prevCamPos.z, f.prevViewProj);
            f.resetHistory = false;
        } else {
            f.prevCamPos.set(f.camPos);
            f.prevViewProj.identity();
            f.resetHistory = true;
        }

        new Matrix3f(cam.viewRotationMatrix).transpose().transform(0f, 0f, -1f, f.camForward);

        computeSun(level, f);

        f.gridSizeX = store.gridX(); f.gridSizeY = store.gridY(); f.gridSizeZ = store.gridZ();
        f.brickPoolSize = config.brickPoolSize;
        f.originSecX = (int) Math.floor(ox / 16.0);
        f.originSecZ = (int) Math.floor(oz / 16.0);

        f.frameIndex = frameIndex;
        f.bounces = config.bounces;
        f.debugView = config.debugView;
        f.maxHistory = config.maxHistory;
        f.temporalAlpha = config.temporalAlpha;
        f.radianceClamp = config.radianceClamp;
        f.exposure = config.exposure;
        f.sunAngularRadius = (float) Math.toRadians(config.sunAngularRadiusDeg);
        f.sunCosRadius = (float) Math.cos(Math.toRadians(config.sunAngularRadiusDeg));
        f.denoiseIterations = config.denoiseIterations;
        f.rainLevel = level.getRainLevel(1.0f);
        f.thunderLevel = level.getThunderLevel(1.0f);

        f.entityCount = entityCapture.capture(level, camPos, ox, oy, oz, config);
        f.entities = entityCapture.buffer();

        GpuTexture color = target.getColorTexture();
        f.targetGlTexture = color instanceof GlTexture gl ? gl.glId() : 0;
        f.targetVkImage = color instanceof VulkanGpuTexture vt ? vt.vkImage() : 0L;
    }

    /**
     * Sun direction/radiance from the 26.2 WorldClock, using vanilla's historical
     * day-fraction easing (docs/07). Tick 0 = sunrise, 6000 = noon.
     */
    private void computeSun(ClientLevel level, FrameData f) {
        long time = Math.floorMod(level.getDefaultClockTime(), 24000L);
        double frac = time / 24000.0;
        double d = frac - 0.25;
        if (d < 0) d += 1.0;
        double ease = 1.0 - (Math.cos(d * Math.PI) + 1.0) / 2.0;
        double a = d + (ease - d) / 3.0;
        double angle = a * 2.0 * Math.PI;

        Vector3f dir = new Vector3f((float) -Math.sin(angle), (float) Math.cos(angle), 0.15f).normalize();
        f.sunDir.set(dir);

        float elev = dir.y;
        float strength = Math.max(0f, Math.min(1f, elev * 5f + 0.15f));
        float warm = Math.max(0f, Math.min(1f, elev * 3f));
        float rain = level.getRainLevel(1.0f);
        float rx = lerp(1.00f, 1.00f, warm), gy = lerp(0.55f, 0.96f, warm), bz = lerp(0.25f, 0.92f, warm);
        float s = 60f * strength * (1f - 0.9f * rain);
        f.sunRadiance.set(rx * s, gy * s, bz * s);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }
}
