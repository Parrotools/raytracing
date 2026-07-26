package org.mining.raytracing.mixin.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.mining.raytracing.core.RaytracerCore;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Block updates → incremental re-voxelization of the containing section. */
@Mixin(ClientLevel.class)
public abstract class ClientLevelMixin {

    @Inject(method = "setBlocksDirty", at = @At("TAIL"))
    private void raytracing$onBlocksDirty(BlockPos pos, BlockState oldState, BlockState newState, CallbackInfo ci) {
        RaytracerCore.onBlockDirty(pos);
    }
}
