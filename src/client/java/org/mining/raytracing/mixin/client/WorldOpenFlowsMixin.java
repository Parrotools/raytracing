package org.mining.raytracing.mixin.client;

import com.mojang.serialization.Lifecycle;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import org.mining.raytracing.core.RtLog;
import org.mining.raytracing.integration.AutoTest;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Autotest only: the test-world preset is experimental, which normally pops a
 * confirmation dialog. Proceed directly so the automated run is headless-friendly.
 */
@Mixin(WorldOpenFlows.class)
public abstract class WorldOpenFlowsMixin {

    @Inject(method = "confirmWorldCreation", at = @At("HEAD"), cancellable = true)
    private static void raytracing$autoConfirm(Minecraft mc, CreateWorldScreen screen, Lifecycle lifecycle,
                                               Runnable onProceed, boolean skipWarnings, CallbackInfo ci) {
        if (AutoTest.ENABLED) {
            RtLog.LOG.info("[autotest] bypassing world-creation confirmation");
            onProceed.run();
            ci.cancel();
        }
    }
}
