package org.mining.raytracing.mixin.client;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.mining.raytracing.core.RaytracerCore;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Frame hook: TAIL of LevelRenderer.render is after the frame graph executed (world
 * image complete in the main target) and before hand/HUD — the compositing point
 * (docs/01 §5).
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {

    @Inject(method = "render", at = @At("TAIL"))
    private void raytracing$afterLevelRender(GraphicsResourceAllocator allocator, DeltaTracker deltaTracker,
                                             boolean renderBlockOutline, CameraRenderState cameraState,
                                             Matrix4fc frustumMatrix, GpuBufferSlice fogBuffer,
                                             Vector4f clearColor, boolean isOutlineActive, CallbackInfo ci) {
        RaytracerCore.onLevelRendered(cameraState);
    }
}
