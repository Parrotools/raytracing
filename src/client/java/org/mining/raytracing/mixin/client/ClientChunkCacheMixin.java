package org.mining.raytracing.mixin.client;

import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.mining.raytracing.core.RaytracerCore;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Chunk streaming → voxel store load/unload (docs/01 §2). */
@Mixin(ClientChunkCache.class)
public abstract class ClientChunkCacheMixin {

    @Inject(method = "replaceWithPacketData", at = @At("RETURN"))
    private void raytracing$onChunkLoaded(int x, int z, FriendlyByteBuf buffer, Map<?, ?> heightmaps,
                                          Consumer<?> blockEntityOutput, CallbackInfoReturnable<LevelChunk> cir) {
        RaytracerCore.onChunkLoaded(x, z);
    }

    @Inject(method = "drop", at = @At("HEAD"))
    private void raytracing$onChunkDropped(ChunkPos pos, CallbackInfo ci) {
        RaytracerCore.onChunkUnloaded(pos.x(), pos.z());
    }
}
