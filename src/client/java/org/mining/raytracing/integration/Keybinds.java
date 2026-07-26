package org.mining.raytracing.integration;

import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import org.mining.raytracing.core.RtConfig;
import org.mining.raytracing.core.RtLog;

/**
 * GLFW-polled controls (no Fabric API dependency, docs/01 §5):
 * F6 toggle · F7 debug view · F8 render scale.
 */
public final class Keybinds {
    private static final float[] SCALES = {1.0f, 0.75f, 0.5f, 0.33f};
    private static final String[] DEBUG_NAMES = {"off", "albedo", "normals", "depth", "irradiance"};

    private final boolean[] wasDown = new boolean[3];

    /** @return true if any setting changed (caller persists the config). */
    public boolean poll(RtConfig cfg) {
        long window = Minecraft.getInstance().getWindow().handle();
        boolean changed = false;
        if (pressed(window, GLFW.GLFW_KEY_F6, 0)) {
            cfg.enabled = !cfg.enabled;
            RtLog.LOG.info("Ray tracing {}", cfg.enabled ? "enabled" : "disabled");
            changed = true;
        }
        if (pressed(window, GLFW.GLFW_KEY_F7, 1)) {
            cfg.debugView = (cfg.debugView + 1) % DEBUG_NAMES.length;
            RtLog.LOG.info("Debug view: {}", DEBUG_NAMES[cfg.debugView]);
            changed = true;
        }
        if (pressed(window, GLFW.GLFW_KEY_F8, 2)) {
            int i = 0;
            for (int k = 0; k < SCALES.length; k++) if (Math.abs(cfg.renderScale - SCALES[k]) < 0.01f) i = k;
            cfg.renderScale = SCALES[(i + 1) % SCALES.length];
            RtLog.LOG.info("Render scale: {}", cfg.renderScale);
            changed = true;
        }
        return changed;
    }

    private boolean pressed(long window, int key, int idx) {
        boolean down = GLFW.glfwGetKey(window, key) == GLFW.GLFW_PRESS;
        boolean edge = down && !wasDown[idx];
        wasDown[idx] = down;
        return edge;
    }
}
