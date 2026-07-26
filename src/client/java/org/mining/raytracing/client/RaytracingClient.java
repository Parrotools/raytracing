package org.mining.raytracing.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.mining.raytracing.core.RaytracerCore;

public class RaytracingClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        RaytracerCore.bootstrap(FabricLoader.getInstance().getConfigDir());
    }
}
