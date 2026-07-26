package org.mining.raytracing.mixin.client;

import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import org.mining.raytracing.integration.AutoTest;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Autotest only: report the create-world screen so AutoTest can auto-confirm it. */
@Mixin(CreateWorldScreen.class)
public abstract class CreateWorldScreenMixin {

    @Inject(method = "init", at = @At("TAIL"))
    private void raytracing$onInit(CallbackInfo ci) {
        if (AutoTest.ENABLED) {
            AutoTest.createWorldScreenReady((CreateWorldScreen) (Object) this);
        }
    }
}
