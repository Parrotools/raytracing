package org.mining.raytracing.mixin.client;

import net.minecraft.client.Minecraft;
import org.mining.raytracing.integration.AutoTest;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Client tick hook — only used by the dev autotest harness. */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {

    @Inject(method = "tick", at = @At("TAIL"))
    private void raytracing$onClientTick(CallbackInfo ci) {
        if (AutoTest.ENABLED) {
            AutoTest.onClientTick((Minecraft) (Object) this);
        }
    }
}
