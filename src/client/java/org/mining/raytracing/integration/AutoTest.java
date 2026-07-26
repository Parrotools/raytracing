package org.mining.raytracing.integration;

import java.io.File;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import org.mining.raytracing.core.RtConfig;
import org.mining.raytracing.core.RtLog;

/**
 * Dev-only validation harness, active only with {@code -Draytracing.autotest=true}
 * (wired to {@code gradlew runClient -PrtAutotest}).
 *
 * <p>Flow: boot → create vanilla's IDE test world → let the path tracer fill its
 * voxel grid and accumulate → screenshot the beauty pass and each debug view →
 * disable RT and screenshot the vanilla frame for comparison → quit. Screenshots
 * are taken inside the frame hook, i.e. before the HUD draws, so images are clean.
 */
public final class AutoTest {
    public static final boolean ENABLED = Boolean.getBoolean("raytracing.autotest");

    private static int clientTicks;
    private static int levelFrames;
    private static boolean worldRequested;
    private static boolean worldConfirmed;
    private static boolean done;
    private static CreateWorldScreen pendingCreateScreen;

    /** Client tick (any state): create the test world once the title screen is up. */
    public static void onClientTick(Minecraft mc) {
        if (!ENABLED || done) return;
        if (pendingCreateScreen != null && !worldConfirmed) {
            // one tick after the screen initialized: press Create programmatically
            worldConfirmed = true;
            CreateWorldScreen screen = pendingCreateScreen;
            pendingCreateScreen = null;
            RtLog.LOG.info("[autotest] auto-confirming world creation");
            ((org.mining.raytracing.mixin.client.CreateWorldScreenInvoker) (Object) screen)
                    .raytracing$invokeOnCreate();
            return;
        }
        if (worldRequested || mc.level != null) return;
        if (++clientTicks == 200) {
            worldRequested = true;
            RtLog.LOG.info("[autotest] opening test world screen");
            CreateWorldScreen.testWorld(mc, () -> {});
        }
    }

    /** Called from CreateWorldScreenMixin when the screen finished init. */
    public static void createWorldScreenReady(CreateWorldScreen screen) {
        if (ENABLED && worldRequested && !worldConfirmed) {
            pendingCreateScreen = screen;
        }
    }

    /** Called after every level-render hook (even when RT is disabled). */
    public static void onFrameRendered(Minecraft mc, RtConfig cfg) {
        if (!ENABLED || done || mc.level == null) return;
        levelFrames++;
        if (levelFrames == 1) RtLog.LOG.info("[autotest] first level frame rendered");
        switch (levelFrames) {
            case 40 -> {
                cfg.maxEntities = 0; // isolate voxel geometry from entity proxies
                setupScene(mc);
            }
            case 250 -> org.mining.raytracing.core.RaytracerCore.get().debugAuditStore();
            case 300 -> shoot(mc, "rt-final");
            case 310 -> cfg.debugView = 1;
            case 325 -> shoot(mc, "rt-albedo");
            case 330 -> cfg.debugView = 2;
            case 345 -> shoot(mc, "rt-normals");
            case 350 -> cfg.debugView = 3;
            case 365 -> shoot(mc, "rt-depth");
            case 370 -> {
                cfg.debugView = 0;
                cfg.enabled = false;
            }
            case 385 -> shoot(mc, "vanilla");
            case 400 -> {
                done = true;
                RtLog.LOG.info("[autotest] complete — stopping client");
                mc.stop();
            }
            default -> { }
        }
    }

    /**
     * Builds a deterministic material-test scene in the flat world via commands:
     * stone wall + log pillar (shadow casters), gold block (metal), glowstone and
     * lava (emissives), glass (refraction), water pool — then frames the camera.
     */
    private static void setupScene(Minecraft mc) {
        if (mc.player == null) return;
        RtLog.LOG.info("[autotest] building test scene");
        // all coordinates relative to the player's spawn position (= on the surface),
        // so the scene works regardless of the preset's ground height
        String[] cmds = {
                "time set noon",
                "weather clear",
                "fill ~4 ~ ~2 ~14 ~7 ~3 minecraft:stone",
                "fill ~5 ~ ~5 ~5 ~5 ~5 minecraft:oak_log",
                "fill ~7 ~ ~7 ~9 ~2 ~9 minecraft:gold_block",
                "setblock ~11 ~ ~7 minecraft:glowstone",
                "setblock ~11 ~1 ~7 minecraft:glowstone",
                "fill ~10 ~ ~10 ~12 ~4 ~10 minecraft:glass",
                "fill ~3 ~-1 ~8 ~5 ~-1 ~12 minecraft:water",
                "fill ~13 ~-1 ~12 ~14 ~-1 ~13 minecraft:lava",
                "fill ~8 ~ ~12 ~9 ~3 ~13 minecraft:iron_block",
                "gamemode spectator @s",
                "tp @s ~-4 ~3 ~-4 facing ~8 ~ ~8"
        };
        for (String c : cmds) mc.player.connection.sendCommand(c);
    }

    private static void shoot(Minecraft mc, String name) {
        RtLog.LOG.info("[autotest] screenshot {} (frame {})", name, levelFrames);
        Screenshot.grab(mc.gameDirectory, name + ".png",
                mc.gameRenderer.mainRenderTarget(), 1,
                component -> RtLog.LOG.info("[autotest] saved: {}", component.getString()));
    }

    private AutoTest() {}
}
