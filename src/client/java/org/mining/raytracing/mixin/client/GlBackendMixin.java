package org.mining.raytracing.mixin.client;

import com.mojang.blaze3d.opengl.GlBackend;
import org.lwjgl.glfw.GLFW;
import org.mining.raytracing.core.RaytracerCore;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla requests a GL 3.3 core context; compute shaders need ≥ 4.3. Re-hint to
 * 4.6 core (a strict superset of 3.3 core, so vanilla rendering is unaffected).
 * Skipped on macOS (caps at 4.1 — the Vulkan/MoltenVK backend is the mac path)
 * and when disabled via config {@code raiseGlContext:false}.
 */
@Mixin(GlBackend.class)
public abstract class GlBackendMixin {

    @Inject(method = "setWindowHints", at = @At("TAIL"))
    private void raytracing$raiseContextVersion(CallbackInfo ci) {
        if (!RaytracerCore.shouldRaiseGlContext()) return;
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("mac")) return;
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 4);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 6);
    }
}
